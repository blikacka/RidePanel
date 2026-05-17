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

    fun hasBluetoothPermissions(): Boolean {
        val perms = if (Build.VERSION.SDK_INT >= 31)
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        return perms.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
    }

    @SuppressLint("MissingPermission")
    fun startScan() {
        val scanner = adapter?.bluetoothLeScanner ?: run {
            listener.onState(State.ERROR, "Bluetooth unavailable")
            return
        }
        scanned.clear()
        listener.onState(State.SCANNING)
        val filters = HUD_SERVICES.map {
            ScanFilter.Builder().setServiceUuid(ParcelUuid(it)).build()
        }
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
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

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val dev = result.device ?: return
            val key = dev.address ?: return
            if (scanned.put(key, dev) == null) {
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
                g.requestMtu(247)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                AppLog.i(TAG, "GATT disconnected (status=$status)")
                listener.onState(State.IDLE, "disconnected")
                try { g.close() } catch (_: Throwable) {}
                gatt = null
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
                listener.onState(State.ERROR, "no usable notify+write characteristic")
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
                // No CCCD — send the client info immediately.
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
            BleFrame.CMD_HANDSHAKE_OUTER, BleFrame.CMD_HANDSHAKE_INNER -> {
                // Skip the upstream HTTP license check — reply pass.
                sendVerifyResult(license = 0, msg = null)
                // Now request build-net so the head-unit joins our Wi-Fi.
                mainHandler.postDelayed({
                    listener.onState(State.BUILDING_NET)
                    writeFrame(BleFrame.encode(BleFrame.CMD_EC_BTP_REQUEST_BUILD_NET, ByteArray(0)))
                }, 250)
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
                }
            }
            BleFrame.CMD_EC_BTP_NOTIFY_BUILD_NET_FINISH -> {
                listener.onState(State.READY)
                listener.onPaired()
            }
        }
    }

    private fun sendClientInfo() {
        val json = JSONObject()
            .put("phoneType", 0)
            .put("phoneID", phoneId)
            .toString().toByteArray(StandardCharsets.UTF_8)
        writeFrame(BleFrame.encode(BleFrame.CMD_EC_BTP_CLIENT_INFO, json))
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
