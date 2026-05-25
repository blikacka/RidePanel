package cz.blikacka.ridepanel

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.UUID
import org.json.JSONObject

/**
 * Drives the Carbit / EasyConn BLE pairing flow against a head-unit acting as
 * GATT peripheral. The exchange is, simplified:
 *
 *   1. Phone scans, connects, discovers services, locates write+notify chars.
 *   2. Phone enables notifications and (optionally) negotiates a larger MTU.
 *   3. Phone writes cmd 48 (EC_BTP_CLIENT_INFO) with its phoneID.
 *   4. Head-unit notifies cmd 88 or 89 (handshake response).
 *   5. Phone replies cmd 96 (VerifyResult, license=0) — we skip the
 *      api.carbit.com.cn round-trip and grant unconditional pass.
 *   6. Phone writes cmd 80 (EC_BTP_REQUEST_BUILD_NET) with empty payload.
 *   7. Head-unit notifies cmd 80 with [BuildNetStatusInfo] JSON describing
 *      whether it wants to use phone's hotspot, or has internet already.
 *   8. If the head-unit needs phone-AP credentials, phone writes cmd 82
 *      (EC_BTP_NOTIFY_AP_INFO) with [PhoneApInfo] JSON.
 *   9. Head-unit joins the Wi-Fi → dials TCP 10922 on the phone → mirror.
 *
 * GATT service / characteristic UUIDs are taken from `ne/a.java`. Three GATT
 * layouts exist:
 *
 *   v1: write & notify share a characteristic from [V1_CHARS]
 *   v2: notify uses [V2_NOTIFY_CHARS], write uses [V2_WRITE_CHARS]
 *   v3: a single characteristic from [V3_CHARS] does both (preferred)
 *
 * All three live under one of [HUD_SERVICES].
 */
class BlePxcPairer(
    private val context: Context,
    private val listener: Listener,
    /** Optional deterministic identifier for the head-unit we want to pair
     *  with. Used by time-server mode to match the BLE peripheral instead
     *  of falling back to a "qj-*" heuristic — the last 4 hex chars of the
     *  Wi-Fi MAC are the same across the head-unit's Wi-Fi, BT-Classic and
     *  BLE radios in this ecosystem (e.g. Wi-Fi `54:44:4A:03:80:BF` ↔
     *  BT-Classic `53:95:4A:03:80:BF` ↔ BLE name `qj-80bf`). */
    private val targetWifiMac: String? = null,
    /** Optional head-unit name from PXC CLIENT_INFO (e.g. `SSDQ01-0120`).
     *  Some peripherals advertise this string directly — matches it
     *  case-insensitively as a secondary fallback. */
    private val targetHuName: String? = null,
) {

    interface Listener {
        fun onScanResult(devices: List<BluetoothDevice>) {}
        fun onState(state: State, message: String? = null)
        /**
         * Called when the head-unit has reported its build-net status. Return
         * a [PhoneApInfo] if the phone wants to push hotspot credentials
         * (status 1 / 2), or null to leave the choice to the head-unit.
         */
        fun onBuildNetStatus(status: Int, info: BuildNetStatusInfo): PhoneApInfo? = null
        /** Pairing complete — head-unit has accepted us; mirror is expected next. */
        fun onPaired() {}
    }

    enum class State {
        IDLE, SCANNING, CONNECTING, NEGOTIATING, HANDSHAKING, BUILDING_NET, READY, ERROR
    }

    data class BuildNetStatusInfo(
        val status: Int,
        val rawJson: String,
    )

    data class PhoneApInfo(
        val ssid: String,
        val pwd: String,
        val auth: String = "WPA2",
        val mac: String = "",
        val ip: String? = null,
    ) {
        fun toJson(): String = JSONObject().apply {
            put("ssid", ssid)
            put("pwd", pwd)
            put("auth", auth)
            put("mac", mac)
            ip?.let { put("ip", it) }
        }.toString()
    }

    private val adapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val phoneId: String = run {
        val sp = context.getSharedPreferences("ridepanel", Context.MODE_PRIVATE)
        sp.getString("phoneId", null) ?: UUID.randomUUID().toString().also {
            sp.edit().putString("phoneId", it).apply()
        }
    }

    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var writeChar: BluetoothGattCharacteristic? = null
    @Volatile private var notifyChar: BluetoothGattCharacteristic? = null
    @Volatile private var v3Combined: Boolean = false
    @Volatile private var mtu: Int = 23
    private val reassembler = BleFrame.Reassembler()
    private val scanned = LinkedHashMap<String, BluetoothDevice>()

    /** When true, on the first scan match the pairer auto-connects without
     *  showing a device picker, and after services are discovered it skips
     *  the pairing handshake (CLIENT_INFO send + BUILD_NET). The GATT
     *  connection stays alive to relay async commands such as
     *  COMMAND_SYNC_TIME (0x01) / QUERY_TIME (0x55). Used by the post-Wi-Fi
     *  time-sync flow — the head-unit's BLE GATT is the only path that sets
     *  the dashboard wall-clock; PXC QUERY_TIME is SDK-internal only. */
    @Volatile private var timeServerMode: Boolean = false

    fun hasBluetoothPermissions(): Boolean {
        val perms = if (Build.VERSION.SDK_INT >= 31)
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        return perms.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
    }

    /** Entry point for the BLE time-sync flow used after Wi-Fi P2P is up.
     *  Skips pairing UI: tries already-bonded devices first (handles the
     *  common case where the user has paired the head-unit through Android
     *  Bluetooth settings for HFP/A2DP calls), then falls back to BLE scan.
     *  Once connected, sits on the GATT characteristic waiting for the
     *  head-unit to push cmd 0x01 / 0x55. */
    @SuppressLint("MissingPermission")
    fun startTimeServer() {
        if (!hasBluetoothPermissions()) {
            AppLog.w(TAG, "startTimeServer: missing Bluetooth permissions, skipping")
            return
        }
        timeServerMode = true
        val a = adapter
        if (a == null) {
            AppLog.w(TAG, "startTimeServer: no Bluetooth adapter on device")
            return
        }
        // Try bonded devices first — head-units in this ecosystem are
        // typically paired through Android BT settings for HFP. Going via
        // the bonded address skips the slower BLE scan and works even when
        // the head-unit stops advertising once an HFP/A2DP link is up.
        val bonded = try {
            a.bondedDevices?.toList().orEmpty()
        } catch (t: Throwable) {
            AppLog.w(TAG, "bondedDevices read failed", t); emptyList()
        }
        AppLog.i(TAG, "BLE time-sync: ${bonded.size} bonded device(s) total")
        AppLog.i(TAG, "BLE time-sync: targetWifiMac=$targetWifiMac targetHuName=$targetHuName " +
            "(suffix=${macSuffix(targetWifiMac)})")
        val candidate = bonded.firstOrNull { dev ->
            val name = try { dev.name } catch (_: Throwable) { null } ?: ""
            looksLikeHud(name, dev.address).also {
                AppLog.d(TAG, "  bonded: name='$name' mac=${dev.address} hudMatch=$it")
            }
        }
        if (candidate != null) {
            AppLog.i(TAG, "time-sync: connecting to bonded ${candidate.address} ('${candidate.name}')")
            connect(candidate)
            return
        }
        AppLog.i(TAG, "no bonded head-unit found, starting BLE scan")
        startScan()
    }

    /** Last 4 hex chars of a MAC (e.g. `54:44:4A:03:80:BF` → `80bf`),
     *  used as a deterministic device discriminator. */
    private fun macSuffix(mac: String?): String? =
        mac?.replace(":", "")?.takeLast(4)?.lowercase()?.takeIf { it.length == 4 }

    /** Match a discovered BLE device against the known head-unit identity.
     *  Strategy (most-specific first):
     *    1. MAC suffix of [targetWifiMac] matches the device's own MAC, OR
     *       appears anywhere in the device's advertised name.
     *    2. The device name equals [targetHuName] (case-insensitive).
     *    3. Generic fallback: name pattern looks like a Carbit-ecosystem HUD. */
    private fun looksLikeHud(name: String, mac: String?): Boolean {
        val n = name.lowercase()
        val suffix = macSuffix(targetWifiMac)
        if (suffix != null) {
            val devSuffix = macSuffix(mac)
            if (devSuffix != null && devSuffix == suffix) return true
            if (n.contains(suffix)) return true
        }
        targetHuName?.let { hu ->
            if (n.isNotEmpty() && n == hu.lowercase()) return true
            if (n.isNotEmpty() && n.contains(hu.lowercase())) return true
        }
        return n.startsWith("qj-") || n.contains("ssdq") || n.contains("easyconn") ||
            n.contains("hud") || n.contains("carbit")
    }

    @SuppressLint("MissingPermission")
    fun startScan() {
        val scanner = adapter?.bluetoothLeScanner ?: run {
            listener.onState(State.ERROR, "Bluetooth unavailable")
            return
        }
        scanned.clear()
        listener.onState(State.SCANNING)
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        if (timeServerMode) {
            // Time-server mode scans UNFILTERED: head-units in this ecosystem
            // often don't advertise the HUD service UUID in the connectable
            // advertising packet (it's only exposed after GATT connect), so
            // a UUID filter would miss them entirely. We match candidates by
            // local name pattern inside [scanCallback] instead.
            AppLog.i(TAG, "time-sync: starting unfiltered BLE scan (matching by name)")
            try {
                scanner.startScan(null, settings, scanCallback)
            } catch (t: Throwable) {
                AppLog.w(TAG, "unfiltered scan failed", t)
            }
            return
        }
        val filters = HUD_SERVICES.map {
            ScanFilter.Builder().setServiceUuid(ParcelUuid(it)).build()
        }
        try {
            scanner.startScan(filters, settings, scanCallback)
        } catch (t: Throwable) {
            // Some devices reject the filtered scan with a 128-bit UUID; retry unfiltered.
            AppLog.w(TAG, "filtered scan rejected, scanning all", t)
            scanner.startScan(scanCallback)
        }
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        try { adapter?.bluetoothLeScanner?.stopScan(scanCallback) } catch (_: Throwable) {}
    }

    @SuppressLint("MissingPermission")
    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val dev = result.device ?: return
            val key = dev.address ?: return
            val firstSeen = scanned.put(key, dev) == null
            if (timeServerMode) {
                if (gatt != null) return
                // Prefer the local-name from the advertising record (no
                // permission needed); fall back to cached device name.
                val advName = result.scanRecord?.deviceName
                val devName = try { dev.name } catch (_: Throwable) { null }
                val name = advName ?: devName ?: ""
                val hudMatch = looksLikeHud(name, dev.address)
                if (firstSeen) {
                    AppLog.d(TAG, "scan: name='$name' mac=${dev.address} " +
                        "rssi=${result.rssi} hudMatch=$hudMatch")
                }
                if (hudMatch) {
                    AppLog.i(TAG, "time-sync: scan found HUD '$name' (${dev.address}), connecting")
                    connect(dev)
                }
            } else if (firstSeen) {
                listener.onScanResult(scanned.values.toList())
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice) {
        stopScan()
        listener.onState(State.CONNECTING, device.address)
        gatt = device.connectGatt(context, /* autoConnect = */ false, gattCallback,
            BluetoothDevice.TRANSPORT_LE)
        if (timeServerMode) scheduleConnectTimeout()
    }

    @Volatile private var connectTimeoutRunnable: Runnable? = null

    /** When the bonded address is in fact only the device's Bluetooth Classic
     *  endpoint (HFP/A2DP) and the BLE peripheral lives on a different MAC,
     *  `connectGatt(TRANSPORT_LE)` accepts the call but the connection never
     *  resolves — STATE_CONNECTED never fires and STATE_DISCONNECTED takes
     *  Android's default ~30 s to give up. Time-server mode can't wait that
     *  long, so we bail at 8 s and switch to BLE scan, which finds the
     *  advertising BLE peripheral regardless of Classic-bond addressing. */
    @SuppressLint("MissingPermission")
    private fun scheduleConnectTimeout() {
        cancelConnectTimeout()
        val r = Runnable {
            AppLog.w(TAG, "time-sync: GATT connect timeout (8 s) — falling back to BLE scan")
            try { gatt?.disconnect() } catch (_: Throwable) {}
            try { gatt?.close() } catch (_: Throwable) {}
            gatt = null
            startScan()
        }
        connectTimeoutRunnable = r
        mainHandler.postDelayed(r, 8000L)
    }

    private fun cancelConnectTimeout() {
        connectTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        connectTimeoutRunnable = null
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        try { gatt?.disconnect() } catch (_: Throwable) {}
        try { gatt?.close() } catch (_: Throwable) {}
        gatt = null; writeChar = null; notifyChar = null
        listener.onState(State.IDLE)
    }

    @SuppressLint("MissingPermission")
    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                AppLog.i(TAG, "GATT connected (status=$status)")
                cancelConnectTimeout()
                g.requestMtu(247)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                AppLog.i(TAG, "GATT disconnected (status=$status)")
                try { g.close() } catch (_: Throwable) {}
                gatt = null
                if (timeServerMode && status != 0) {
                    // Bonded-device GATT connect rejected (e.g. Classic-only
                    // profile, or peripheral not advertising). Try BLE scan
                    // as the next strategy.
                    AppLog.w(TAG, "time-sync: GATT connect failed (status=$status); falling back to scan")
                    startScan()
                } else {
                    listener.onState(State.IDLE, "disconnected")
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, newMtu: Int, status: Int) {
            mtu = newMtu
            AppLog.i(TAG, "MTU = $newMtu")
            g.discoverServices()
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            dumpGatt(g)
            val candidateServices = buildList {
                HUD_SERVICES.forEach { uuid -> g.getService(uuid)?.let { add(it) } }
                // Fallback: any service that exposes at least one notify+write
                // characteristic, in case the head-unit uses an OEM service UUID.
                if (isEmpty()) addAll(g.services)
            }
            val matched = candidateServices.firstOrNull { locateChars(it) }
            if (matched == null) {
                if (timeServerMode) {
                    // Bonded device likely exposes only Classic BT profiles
                    // (HFP/A2DP) and no HUD GATT service. Drop this GATT and
                    // fall back to BLE scan to find the head-unit's BLE
                    // peripheral, which may live on a different MAC.
                    AppLog.w(TAG, "time-sync: no HUD service on bonded device — falling back to BLE scan")
                    try { g.disconnect() } catch (_: Throwable) {}
                    try { g.close() } catch (_: Throwable) {}
                    gatt = null
                    startScan()
                } else {
                    listener.onState(State.ERROR, "no usable notify+write characteristic")
                }
                return
            }
            AppLog.i(TAG, "using service ${matched.uuid}, " +
                "notify=${notifyChar?.uuid} write=${writeChar?.uuid} v3=$v3Combined")
            listener.onState(State.NEGOTIATING)
            val nc = notifyChar!!
            g.setCharacteristicNotification(nc, true)
            // Standard Client Characteristic Configuration descriptor.
            val cccd = nc.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))
            if (cccd != null) {
                val enable = if ((nc.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0)
                    BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                else
                    BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                if (Build.VERSION.SDK_INT >= 33) {
                    g.writeDescriptor(cccd, enable)
                } else {
                    @Suppress("DEPRECATION")
                    cccd.value = enable
                    @Suppress("DEPRECATION")
                    g.writeDescriptor(cccd)
                }
            } else {
                // No CCCD — send the client info immediately. We *always*
                // run the full BLE handshake (CLIENT_INFO → HANDSHAKE
                // response → VERIFY_RESULT → REQUEST_BUILD_NET) — even in
                // time-server mode — because the head-unit only enters the
                // state where it will push cmd 0x01/0x55 (time-sync
                // requests) AFTER the handshake completes. Skipping it
                // means the head-unit never asks for time and the
                // dashboard clock stays at 00:00.
                listener.onState(State.HANDSHAKING)
                sendClientInfo()
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            listener.onState(State.HANDSHAKING)
            sendClientInfo()
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray
        ) = onNotify(value)

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            // Pre-API 33 path.
            ch.value?.let { onNotify(it) }
        }
    }

    private fun locateChars(svc: BluetoothGattService): Boolean {
        writeChar = null; notifyChar = null; v3Combined = false
        // Try v3 (combined notify+write) first.
        for (uuid in V3_CHARS) {
            val c = svc.getCharacteristic(uuid) ?: continue
            val p = c.properties
            if (p.hasNotifyOrIndicate() && p.hasAnyWrite()) {
                writeChar = c; notifyChar = c; v3Combined = true
                return true
            }
        }
        // v2: separate notify / write characteristics.
        for (uuid in V2_NOTIFY_CHARS) {
            val c = svc.getCharacteristic(uuid) ?: continue
            if (c.properties.hasNotifyOrIndicate()) { notifyChar = c; break }
        }
        for (uuid in V2_WRITE_CHARS) {
            val c = svc.getCharacteristic(uuid) ?: continue
            if (c.properties.hasAnyWrite()) { writeChar = c; break }
        }
        if (notifyChar != null && writeChar != null) return true
        // v1: notify + write share UUID list, distinguished by properties.
        for (uuid in V1_CHARS) {
            val c = svc.getCharacteristic(uuid) ?: continue
            val p = c.properties
            if (p.hasNotifyOrIndicate() && notifyChar == null) notifyChar = c
            if (p.hasAnyWrite() && writeChar == null) writeChar = c
        }
        if (notifyChar != null && writeChar != null) return true
        // Final fallback: any characteristic in this service with both
        // notify and write properties (possibly two different ones).
        var combined: BluetoothGattCharacteristic? = null
        var anyNotify: BluetoothGattCharacteristic? = null
        var anyWrite: BluetoothGattCharacteristic? = null
        for (c in svc.characteristics) {
            val p = c.properties
            if (p.hasNotifyOrIndicate() && p.hasAnyWrite() && combined == null) combined = c
            if (p.hasNotifyOrIndicate() && anyNotify == null) anyNotify = c
            if (p.hasAnyWrite() && anyWrite == null) anyWrite = c
        }
        if (combined != null) {
            writeChar = combined; notifyChar = combined; v3Combined = true
            return true
        }
        if (anyNotify != null && anyWrite != null) {
            notifyChar = anyNotify; writeChar = anyWrite
            return true
        }
        return false
    }

    private fun Int.hasNotifyOrIndicate() =
        (this and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or
                   BluetoothGattCharacteristic.PROPERTY_INDICATE)) != 0
    private fun Int.hasAnyWrite() =
        (this and (BluetoothGattCharacteristic.PROPERTY_WRITE or
                   BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)) != 0

    private fun dumpGatt(g: BluetoothGatt) {
        AppLog.i(TAG, "── discovered ${g.services.size} services ──")
        for (svc in g.services) {
            AppLog.i(TAG, "  svc ${svc.uuid} (${svc.characteristics.size} chars)")
            for (c in svc.characteristics) {
                AppLog.i(TAG, "    char ${c.uuid} props=${propsToString(c.properties)}")
            }
        }
    }

    private fun propsToString(p: Int): String {
        val parts = mutableListOf<String>()
        if (p and BluetoothGattCharacteristic.PROPERTY_READ != 0) parts += "READ"
        if (p and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) parts += "WRITE"
        if (p and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) parts += "WRITE_NR"
        if (p and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) parts += "NOTIFY"
        if (p and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) parts += "INDICATE"
        if (p and BluetoothGattCharacteristic.PROPERTY_BROADCAST != 0) parts += "BROADCAST"
        return parts.joinToString("|").ifEmpty { "0x${p.toString(16)}" }
    }

    private fun onNotify(chunk: ByteArray) {
        for (frame in reassembler.feed(chunk)) handleFrame(frame)
    }

    private fun handleFrame(frame: BleFrame.Frame) {
        AppLog.d(TAG, "RX cmd=0x${"%02x".format(frame.cmd.toInt() and 0xff)} (${frame.payload.size} B)")
        when (frame.cmd) {
            BleFrame.CMD_SYNC_TIME -> respondSyncTime()
            BleFrame.CMD_QUERY_TIME -> respondQueryTime()
            BleFrame.CMD_HANDSHAKE_OUTER -> {
                // Skip the upstream HTTP license check — reply pass.
                sendVerifyResult(license = 0, msg = null)
                // Now request build-net so the head-unit joins our Wi-Fi.
                mainHandler.postDelayed({
                    listener.onState(State.BUILDING_NET)
                    writeFrame(BleFrame.encode(BleFrame.CMD_EC_BTP_REQUEST_BUILD_NET, ByteArray(0)))
                }, 250)
            }
            BleFrame.CMD_HANDSHAKE_INNER -> {
                // Same as OUTER — VERIFY_RESULT first — but additionally
                // mirror `qg/p.java::d.a()` post-verify branch: if the
                // INNER response advertises blesdk!=1 && wpn==1, the
                // head-unit expects the phone to follow up with
                // EXTEND_CLIENT_INFO (cmd 0x54) carrying phoneName +
                // network-interface IPs. Without it, modern head-units
                // (incl. QJMOTO-class) treat the BLE session as
                // incomplete and never push cmd 0x01 / 0x55 time-sync.
                val blesdk = if (frame.payload.size > 72) frame.payload[72].toInt() and 0xff else 0
                val wpn = if (frame.payload.size > 73) frame.payload[73].toInt() and 0xff else 0
                AppLog.i(TAG, "INNER handshake: blesdk=$blesdk wpn=$wpn (payload=${frame.payload.size}B)")
                sendVerifyResult(license = 0, msg = null)
                if (blesdk != 1 && wpn == 1) {
                    mainHandler.postDelayed({ sendExtendClientInfo() }, 100)
                    mainHandler.postDelayed({
                        listener.onState(State.BUILDING_NET)
                        writeFrame(BleFrame.encode(BleFrame.CMD_EC_BTP_REQUEST_BUILD_NET, ByteArray(0)))
                    }, 350)
                } else {
                    mainHandler.postDelayed({
                        listener.onState(State.BUILDING_NET)
                        writeFrame(BleFrame.encode(BleFrame.CMD_EC_BTP_REQUEST_BUILD_NET, ByteArray(0)))
                    }, 250)
                }
            }
            BleFrame.CMD_EC_BTP_REQUEST_BUILD_NET -> {
                val json = String(frame.payload, StandardCharsets.UTF_8)
                val status = try { JSONObject(json).optInt("status", -3) } catch (_: Throwable) { -3 }
                val info = BuildNetStatusInfo(status, json)
                AppLog.i(TAG, "BUILD_NET status=$status json=$json")
                val ap = listener.onBuildNetStatus(status, info)
                if (ap != null && (status == 1 || status == 2)) {
                    val payload = ap.toJson().toByteArray(StandardCharsets.UTF_8)
                    writeFrame(BleFrame.encode(BleFrame.CMD_EC_BTP_NOTIFY_AP_INFO, payload))
                } else if (status == 0) {
                    listener.onState(State.READY)
                    listener.onPaired()
                } else if (timeServerMode) {
                    // Time-server mode: Wi-Fi is already up via the known-
                    // device reconnect path, so a non-zero BUILD_NET status
                    // (head-unit asking us to bring up hotspot creds it
                    // already had) is harmless — we just finalize the
                    // handshake locally and wait for cmd 0x01/0x55.
                    AppLog.i(TAG, "time-sync: BUILD_NET status=$status, ignoring (Wi-Fi already up)")
                    listener.onState(State.READY)
                }
            }
            BleFrame.CMD_EC_BTP_NOTIFY_BUILD_NET_FINISH -> {
                listener.onState(State.READY)
                listener.onPaired()
            }
        }
    }

    /** COMMAND_SYNC_TIME (cmd 0x01) — head-unit's legacy clock-set request.
     *  Reply with the wall-clock in milliseconds since the Unix epoch shifted
     *  by the phone's raw timezone offset, encoded little-endian as 8 bytes.
     *  This is the call that actually sets the head-unit's OS-level wall
     *  clock (`qg/p.java::T()` + `qg/b.c(Long)` + `ug.e.e(long)` in the
     *  decompiled APK). Without it the dashboard clock stays at 00:00. */
    private fun respondSyncTime() {
        val wallMillis = System.currentTimeMillis() +
            java.util.TimeZone.getDefault().rawOffset
        val payload = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            .putLong(wallMillis)
            .array()
        AppLog.i(TAG, "→ SYNC_TIME (cmd=0x01) wallMillis=$wallMillis " +
            "(${java.util.TimeZone.getDefault().id})")
        writeFrame(BleFrame.encode(BleFrame.CMD_SYNC_TIME, payload))
    }

    /** QUERY_TIME (cmd 0x55) — head-unit's modern clock-set request. Reply
     *  with a UTF-8 string formatted "dd.MM.yyyy HH:mm:ss:zzz" (default
     *  locale), truncated to 120 bytes. Mirrors `net/easyconn/carman/h.t()`
     *  + `qg/d.i(String)` + `ug.e.f(str, 120)`. */
    private fun respondQueryTime() {
        val now = java.util.Date()
        val str = java.text.SimpleDateFormat(
            "dd.MM.yyyy HH:mm:ss:zzz",
            java.util.Locale.getDefault(),
        ).format(now)
        val bytes = str.toByteArray(StandardCharsets.UTF_8)
        val payload = if (bytes.size <= 120) bytes else bytes.copyOf(120)
        AppLog.i(TAG, "→ QUERY_TIME (cmd=0x55) str='$str' (${payload.size} B)")
        writeFrame(BleFrame.encode(BleFrame.CMD_QUERY_TIME, payload))
    }

    private fun sendClientInfo() {
        val json = JSONObject()
            .put("phoneType", 0)
            .put("phoneID", phoneId)
            .toString().toByteArray(StandardCharsets.UTF_8)
        writeFrame(BleFrame.encode(BleFrame.CMD_EC_BTP_CLIENT_INFO, json))
    }

    /** Phone → car cmd 0x54 EC_BTP_P2C_EXTEND_CLIENT_INFO. Mirrors
     *  `qg/d.d()` + `ug.a.c()` (phone name) + `ug.h.b()` (network interfaces).
     *  Payload: 32B phoneName (zero-padded UTF-8) + 1B count + N × (4B IPv4
     *  + 4B mask). The phoneName is the local Bluetooth adapter name (not
     *  Build.MODEL — head-unit may have stored it during pairing). */
    @SuppressLint("MissingPermission")
    private fun sendExtendClientInfo() {
        val phoneName = try {
            val bm = context.getSystemService(Context.BLUETOOTH_SERVICE)
                as? android.bluetooth.BluetoothManager
            bm?.adapter?.name ?: ""
        } catch (_: Throwable) { "" }
        val netInfos = collectNetworkInterfaces()
        val payload = ByteArray(33 + netInfos.size * 8)
        val nameBytes = phoneName.toByteArray(StandardCharsets.UTF_8)
        val nameLen = minOf(nameBytes.size, 32)
        System.arraycopy(nameBytes, 0, payload, 0, nameLen)
        payload[32] = netInfos.size.toByte()
        for ((i, info) in netInfos.withIndex()) {
            val ip = parseIpV4(info.first)
            val mask = parseIpV4(info.second)
            System.arraycopy(ip, 0, payload, 33 + i * 8, 4)
            System.arraycopy(mask, 0, payload, 33 + i * 8 + 4, 4)
        }
        AppLog.i(TAG, "→ EXTEND_CLIENT_INFO (cmd=0x54) phoneName='$phoneName' " +
            "netInfos=$netInfos payload=${payload.size}B")
        writeFrame(BleFrame.encode(BleFrame.CMD_EXTEND_CLIENT_INFO, payload))
    }

    /** Mirror of `ug/h.java::b()` — enumerate IPv4 interfaces, skipping
     *  loopback / virtual / cellular ones the protocol explicitly excludes. */
    private fun collectNetworkInterfaces(): List<Pair<String, String>> {
        val skipPrefixes = listOf("oem", "nm_", "tun", "qcom_", "dummy",
            "lo", "ifb", "sit", "usb", "tunl", "bond", "ccmni")
        val out = mutableListOf<Pair<String, String>>()
        try {
            val ifaces = java.net.NetworkInterface.getNetworkInterfaces() ?: return emptyList()
            for (iface in java.util.Collections.list(ifaces)) {
                val name = iface.displayName?.lowercase(java.util.Locale.US) ?: continue
                if (skipPrefixes.any { name.startsWith(it) }) continue
                if (name.contains("rmnet")) continue
                for (ia in iface.interfaceAddresses) {
                    val addr = ia.address ?: continue
                    if (addr.isLoopbackAddress || addr.isAnyLocalAddress) continue
                    if (addr !is java.net.Inet4Address) continue
                    val ipStr = addr.hostAddress ?: continue
                    val prefix = ia.networkPrefixLength.toInt().coerceIn(0, 32)
                    val maskInt = if (prefix == 0) 0 else (-1 shl (32 - prefix))
                    val maskStr =
                        "${(maskInt ushr 24) and 0xff}." +
                        "${(maskInt ushr 16) and 0xff}." +
                        "${(maskInt ushr 8) and 0xff}." +
                        "${maskInt and 0xff}"
                    out += ipStr to maskStr
                }
            }
        } catch (t: Throwable) {
            AppLog.w(TAG, "collectNetworkInterfaces failed", t)
        }
        return out
    }

    /** Mirror of `qg/d.java::o(String)` — parse "x.x.x.x" into 4 bytes
     *  (network-byte-order: byte 0 = MSB of address). */
    private fun parseIpV4(s: String): ByteArray {
        val out = ByteArray(4)
        try {
            val parts = s.split(".")
            if (parts.size == 4) {
                for (i in 0..3) out[i] = (parts[i].toInt() and 0xff).toByte()
            }
        } catch (_: Throwable) { /* leave zeros */ }
        return out
    }

    private fun sendVerifyResult(license: Int, msg: String?) {
        // VerifyResult is `byte license + ASCII msg padded to 120 bytes`
        // but the parser treats msg as utf8 with explicit length, so we send a
        // null-terminated short message.
        val msgBytes = (msg ?: "").take(120).toByteArray(StandardCharsets.UTF_8)
        val payload = ByteBuffer.allocate(1 + msgBytes.size).order(ByteOrder.LITTLE_ENDIAN)
            .put(license.toByte())
            .put(msgBytes)
            .array()
        writeFrame(BleFrame.encode(BleFrame.CMD_VERIFY_RESULT, payload))
    }

    @SuppressLint("MissingPermission")
    private fun writeFrame(frame: ByteArray) {
        val g = gatt ?: return
        val ch = writeChar ?: return
        // Split into MTU-sized chunks the head-unit's reassembler will accept.
        val maxChunk = (mtu - 3).coerceAtLeast(20)
        var off = 0
        while (off < frame.size) {
            val len = minOf(maxChunk, frame.size - off)
            val chunk = frame.copyOfRange(off, off + len)
            val type = if ((ch.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0)
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            else
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            if (Build.VERSION.SDK_INT >= 33) {
                g.writeCharacteristic(ch, chunk, type)
            } else {
                @Suppress("DEPRECATION")
                ch.value = chunk
                @Suppress("DEPRECATION")
                ch.writeType = type
                @Suppress("DEPRECATION")
                g.writeCharacteristic(ch)
            }
            off += len
        }
    }

    companion object {
        private const val TAG = "RidePanel.Ble"

        // Service UUIDs the HUD may use (any one of these).
        private val HUD_SERVICES = listOf(
            UUID.fromString("0000b360-d6d8-c7ec-bdf0-eab1bfc6bcbc"),
            UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb"),
        )

        // v3: single characteristic does both notify and write.
        private val V3_CHARS = listOf(
            UUID.fromString("0000b364-d6d8-c7ec-bdf0-eab1bfc6bcbc"),
        )
        // v2: split characteristics.
        private val V2_NOTIFY_CHARS = listOf(
            UUID.fromString("0000b364-d6d8-c7ec-bdf0-eab1bfc6bcbc"),
        )
        private val V2_WRITE_CHARS = listOf(
            UUID.fromString("0000b363-d6d8-c7ec-bdf0-eab1bfc6bcbc"),
        )
        // v1: legacy — notify + write share these UUIDs, properties decide which is which.
        private val V1_CHARS = listOf(
            UUID.fromString("0000b362-d6d8-c7ec-bdf0-eab1bfc6bcbc"),
            UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb"),
            UUID.fromString("00006967-0000-1000-8000-00805f9b34fb"),
        )
    }
}
