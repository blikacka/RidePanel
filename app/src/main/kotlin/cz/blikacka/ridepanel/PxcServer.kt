package cz.blikacka.ridepanel

import android.content.Context
import android.os.Build
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import org.json.JSONObject

/**
 * Mini PXC server. Listens on TCP 10922. Each accepted socket sends a 16-byte
 * channel-open frame; we ack it and then either:
 *
 *  - on a *control* socket, loop reading ECP_C2P_* requests and writing
 *    cmd+1 responses (auto-response per `t0.d()` in the original SDK);
 *  - on a *data* socket, hand it to the video broadcaster.
 *
 * The control loop implements just enough of the protocol to keep the
 * head-unit happy:
 *
 *   - **ECP_C2P_CLIENT_INFO** (0x10010) — head-unit's full capability JSON.
 *     We reply with our phone-side JSON (`pxcVersion`, `phoneUUID`, etc.,
 *     mirroring `ECP_C2P_CLIENT_INFO.f0()` in the original APK).
 *   - **ECP_C2P_CHECK_SN / DOWNLOAD_CAR_UUID_LICENSE / REGISTER_CAR_UUID**
 *     (0x10320 / 0x10380 / 0x10390) — license-server round-trips the original
 *     phone app would forward to api.carbit.com.cn. We don't run a license
 *     server; we ack with an empty payload so the head-unit stops asking.
 *   - **ECP_C2P_HARDWARE_AUTH_STATE** (0x10770) — informational, ignored.
 *   - Everything else — empty cmd+1 ack.
 */
class PxcServer(
    private val context: Context,
    private val onClientConnected: () -> Unit = {},
    private val onClientDisconnected: () -> Unit = {},
) {

    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    /** Active data channels, keyed by remote address — fed encoded H.264. */
    private val dataChannels = CopyOnWriteArrayList<PxcDataChannel>()
    private val ioPool = Executors.newCachedThreadPool { r ->
        Thread(r, "PxcServer-Worker").apply { isDaemon = true }
    }
    /** Output stream of the primary control channel (0x10000). Held so we
     *  can push async P2C commands (e.g. OTA FTP status 0x20280) that don't
     *  fit the standard request/response pattern. */
    @Volatile private var primaryCtrlOutput: OutputStream? = null

    /** Cached from the head-unit's CLIENT_INFO. When true, the head-unit
     *  uses our `dateTime` string from QUERY_TIME replies to drive its own
     *  wall-clock display. Without `dateTime` the head-unit's clock stays
     *  at 00:00 (`ih/l0.java::g()` in the decompiled APK). */
    @Volatile private var carSupportSyncCorrectTime: Boolean = false
    /** Cached "channel" string from the head-unit's CLIENT_INFO. Selects
     *  the `dateTime` format: motorcycle flavors ("21312"/"21313") want
     *  colon-separated date components, everyone else wants dot-separated. */
    @Volatile private var carChannel: String = ""

    private val phoneUuid: String = run {
        val sp = context.getSharedPreferences("ridepanel", Context.MODE_PRIVATE)
        sp.getString("phoneUuid", null) ?: UUID.randomUUID().toString().also {
            sp.edit().putString("phoneUuid", it).apply()
        }
    }

    fun start() {
        if (running.getAndSet(true)) return
        thread(name = "PxcServer-Accept", isDaemon = true) {
            try {
                val ss = ServerSocket(PxcFrame.PORT, 4, InetAddress.getByName("0.0.0.0"))
                serverSocket = ss
                AppLog.i(TAG, "Listening on 0.0.0.0:${PxcFrame.PORT}")
                while (running.get()) {
                    val sock = try { ss.accept() } catch (t: Throwable) {
                        if (running.get()) AppLog.w(TAG, "accept failed", t)
                        break
                    }
                    ioPool.submit { handle(sock) }
                }
            } catch (t: Throwable) {
                AppLog.e(TAG, "server failed", t)
            }
        }
    }

    private fun handle(sock: Socket) {
        try {
            sock.tcpNoDelay = true
            val input = sock.getInputStream()
            val output = sock.getOutputStream()
            val open = PxcFrame.read(input) ?: return
            AppLog.i(TAG, "client ${sock.remoteSocketAddress} opens channel cmd=0x${open.cmd.toString(16)}")
            // Acknowledge the channel-open with cmd+1 and an empty body.
            PxcFrame.write(output, open.cmd + 1, ByteArray(0))

            // Is this a data channel or a control channel?
            val isData = open.cmd in DATA_CHANNELS
            if (isData) {
                val channel = PxcDataChannel(input, output) {
                    onClientDisconnected()
                }
                dataChannels.add(channel)
                onClientConnected()
                try {
                    channel.run()  // blocks on this worker thread until closed
                } finally {
                    channel.stop()
                    dataChannels.remove(channel)
                }
            } else {
                if (open.cmd == PxcFrame.CH_MAIN_CTRL) primaryCtrlOutput = output
                try { serveControl(input, output) } finally {
                    if (open.cmd == PxcFrame.CH_MAIN_CTRL) primaryCtrlOutput = null
                }
            }
        } catch (t: Throwable) {
            AppLog.w(TAG, "client errored", t)
        } finally {
            try { sock.close() } catch (_: Throwable) {}
        }
    }

    private fun serveControl(input: java.io.InputStream, output: OutputStream) {
        var seq = 0L
        // After CLIENT_INFO handshake completes (seq == 1), start the
        // navigation-active heartbeat thread. The Carbit Ride flavor 51
        // motorcycle head-unit gates the mirror data stream on a "phone has
        // navigation running" indicator: ECP_P2C_NAVI_STATUS (0x20200) once
        // with {status:true}, followed by ECP_P2C_PHONE_HUDINFO (0x20150)
        // every ~1 s. Without these, the head-unit decides the phone
        // session is incomplete and tears the mirror sockets down ~5.5 s
        // after REQ_RV_DATA_START — even with valid video frames flowing.
        // See kh/w.java, kh/b0.java, utils/MapboxDataHelper.java in the
        // decompiled APK.
        val naviActive = AtomicBoolean(false)
        val naviThread = Thread({
            try {
                // Wait until CLIENT_INFO handshake echoes back.
                while (running.get() && !naviActive.get()) Thread.sleep(50)
                if (!running.get()) return@Thread
                sendNaviStatus(output, active = true)
                while (running.get()) {
                    sendHudInfo(output)
                    Thread.sleep(1_000)
                }
            } catch (_: InterruptedException) {
            } catch (t: Throwable) {
                AppLog.d(TAG, "navi sender ended: ${t.javaClass.simpleName}: ${t.message}")
            }
        }, "PxcServer-Navi").apply { isDaemon = true; start() }
        try {
            while (running.get()) {
                val tRead = System.currentTimeMillis()
                val req = try { PxcFrame.read(input) } catch (t: Throwable) {
                    AppLog.w(TAG, "ctrl read failed", t); null
                } ?: break
                seq++
                val preview = req.payload.take(32).joinToString("") { "%02x".format(it) }
                AppLog.i(TAG, "← #$seq cmd=0x${"%08x".format(req.cmd)} body=${req.payload.size}B ${if (preview.isNotEmpty()) "[$preview${if (req.payload.size > 32) "…" else ""}]" else ""}")
                val responsePayload = handleRequest(req)
                val tWrite = System.currentTimeMillis()
                try {
                    PxcFrame.write(output, req.cmd + 1, responsePayload)
                } catch (t: Throwable) {
                    AppLog.e(TAG, "← #$seq write failed for cmd+1=0x${"%08x".format(req.cmd + 1)}", t)
                    throw t
                }
                val writeCost = System.currentTimeMillis() - tWrite
                AppLog.i(TAG, "→ #$seq cmd=0x${"%08x".format(req.cmd + 1)} body=${responsePayload.size}B writeMs=$writeCost totalMs=${System.currentTimeMillis() - tRead}")
                // Trigger the navigation heartbeat once CLIENT_INFO reply
                // is acknowledged (right after we ship cmd 0x10011).
                if (req.cmd == PxcFrame.ECP_C2P_CLIENT_INFO) naviActive.set(true)
            }
        } finally {
            naviThread.interrupt()
        }
    }

    /** kh/w.java — ECP_P2C_NAVI_STATUS (0x20200, decimal 131552).
     *  Body: {"status": true|false}. Tells the head-unit phone is currently
     *  running a navigation session. Head-unit motorcycle flavors gate
     *  mirror data acceptance on this. */
    private fun sendNaviStatus(out: OutputStream, active: Boolean) {
        val body = JSONObject().apply { put("status", active) }
            .toString().toByteArray(StandardCharsets.UTF_8)
        try {
            PxcFrame.write(out, ECP_P2C_NAVI_STATUS, body)
            AppLog.i(TAG, "→ async cmd=0x${"%08x".format(ECP_P2C_NAVI_STATUS)} body=${body.size}B (NAVI_STATUS active=$active)")
        } catch (t: Throwable) {
            AppLog.w(TAG, "NAVI_STATUS write failed", t)
        }
    }

    /** kh/b0.java — ECP_P2C_PHONE_HUDINFO (0x20110, decimal 131344).
     *  Continuous heartbeat carrying current navigation HUD info. Carbit
     *  Ride sends this every ~1 s while the user is in a navigation
     *  fragment.
     *
     *  We don't have real navigation, so emit a stable but **non-empty**
     *  payload. The head-unit's mirror gate uses `kh/b0.a.o()` which only
     *  forwards the message when at least one of `currentRoad`,
     *  `carDirection`, `cameraType…cameraDistance`, `naviIcon`, `nextRoad`,
     *  `roadRemainingDistance…destinationRemainingTime` is non-zero/non-
     *  empty. All-zero payloads would be silently dropped, the head-unit
     *  would never see the HUDINFO tick, decide the phone is broken
     *  ~5–6 s after REQ_RV_DATA_START, and tear the mirror sockets down. */
    private fun sendHudInfo(out: OutputStream) {
        val body = JSONObject().apply {
            put("status", 0)
            put("currentRoad", "Mirror")
            put("carDirection", 0)
            put("cameraType", 0)
            put("cameraSpeed", 0)
            put("cameraDistance", 0)
            put("naviIcon", 53)
            put("nextRoad", "")
            put("roadRemainingDistance", 1000)
            put("roadRemainingTime", 60)
            put("destinationRemainingDistance", 1000)
            put("destinationRemainingTime", 60)
            put("arriveTimeZone", java.util.TimeZone.getDefault().id)
            put("arriveTime", System.currentTimeMillis() + 60_000)
            put("signalIntensity", 1)
            put("naviDestination", "Mirror")
        }.toString().toByteArray(StandardCharsets.UTF_8)
        try {
            PxcFrame.write(out, ECP_P2C_PHONE_HUDINFO, body)
            AppLog.d(TAG, "→ async cmd=0x${"%08x".format(ECP_P2C_PHONE_HUDINFO)} body=${body.size}B (HUDINFO tick)")
        } catch (t: Throwable) {
            AppLog.w(TAG, "HUDINFO write failed", t)
        }
    }

    private fun handleRequest(req: PxcFrame.Frame): ByteArray {
        return when (req.cmd) {
            PxcFrame.ECP_C2P_CLIENT_INFO -> handleClientInfo(req.payload)
            PxcFrame.ECP_C2P_CHECK_SN,
            PxcFrame.ECP_C2P_DOWNLOAD_CAR_UUID_LICENSE,
            PxcFrame.ECP_C2P_REGISTER_CAR_UUID -> handleLicenseStub(req)
            PxcFrame.ECP_C2P_HARDWARE_AUTH_STATE -> ByteArray(0)
            // Periodic queries the head-unit fires every ~2 s. Original phone
            // returns proper JSON; the head-unit may gate further state on these.
            ECP_C2P_QUERY_TIME -> handleQueryTime()
            ECP_C2P_QUERY_GPS -> handleQueryGps()
            ECP_C2P_QUERY_WEATHER -> ByteArray(0)
            ECP_C2P_ENABLE_DOWNLOAD_PHONE_HUD -> ByteArray(0)
            ECP_C2P_PHONE_FTP_SPEED -> ByteArray(0)
            ECP_C2P_OTA_DOWNLOAD_REQUEST -> {
                handleOtaRequest(req.payload)
                ByteArray(0)
            }
            else -> {
                AppLog.d(TAG, "unhandled ECP cmd=0x${req.cmd.toString(16)} (${req.payload.size} B)")
                ByteArray(0)
            }
        }
    }

    /**
     * Head-unit asks us to spin up a FTP server for OTA download and reply
     * via P2C cmd 0x20280 with {isOk, dataPort, ctrlPort, errCode, errMsg}.
     * Mirrors `kh/g0.java` + `net/easyconn/carman/common/ftp/d0.java` —
     * Carbit's OTA flow.
     *
     * Without this reply the head-unit waits ~5–6 s for the OTA handshake
     * to complete, decides the phone is broken, and tears down both mirror
     * sockets. We don't actually support OTA download (we'd need the phone
     * Carbit cloud account); reply isOk=false with a sensible errMsg so the
     * head-unit moves on instead of hanging.
     */
    private fun handleOtaRequest(payload: ByteArray) {
        val body = if (payload.isNotEmpty()) String(payload, StandardCharsets.UTF_8) else ""
        AppLog.i(TAG, "OTA request received (0x104a0) body=$body — replying via 0x20280 isOk=false")
        val pxcOutput = primaryCtrlOutput ?: run {
            AppLog.w(TAG, "OTA reply: no primary ctrl output available")
            return
        }
        val reply = JSONObject().apply {
            put("isOk", false)
            put("dataPort", 0)
            put("ctrlPort", 0)
            put("errCode", -1)
            put("errMsg", "phone-side OTA not supported")
        }
        val bytes = reply.toString().toByteArray(StandardCharsets.UTF_8)
        try {
            PxcFrame.write(pxcOutput, ECP_P2C_OTA_FTP_STATUS, bytes)
            AppLog.i(TAG, "→ async cmd=0x${"%08x".format(ECP_P2C_OTA_FTP_STATUS)} body=${bytes.size}B (OTA status)")
        } catch (t: Throwable) {
            AppLog.e(TAG, "OTA reply write failed", t)
        }
    }

    /** Based on `ih/l0.java::g()` plus our own observation that QJMOTO-class
     *  firmware needs the `dateTime` string even when CLIENT_INFO omits the
     *  `supportSyncCorrectTime` flag — without it the on-screen clock stays
     *  at 00:00. The original app sent `dateTime` only conditionally, but
     *  unconditionally sending it is forward-compatible: head-units that
     *  ignore the field stay unaffected. */
    private fun handleQueryTime(): ByteArray {
        val tz = java.util.TimeZone.getDefault()
        val now = System.currentTimeMillis()
        val gmtNow = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("GMT")).timeInMillis
        val localNow = java.util.Calendar.getInstance(tz).timeInMillis + tz.getOffset(now)
        // Motorcycle flavors (Carbit Ride channels "21312"/"21313") parse
        // colon-separated date components; other head-units use dot-separated.
        // Matches `ih/l0.java::g()`'s pattern selection exactly.
        val pattern = if (carChannel == "21312" || carChannel == "21313") {
            "dd:MM:yyyy HH:mm:ss:SSS"
        } else {
            "dd.MM.yyyy HH:mm:ss:SSS"
        }
        val dateTime = SimpleDateFormat(pattern, Locale.getDefault()).format(Date(now))
        val reply = JSONObject().apply {
            put("time", gmtNow)
            put("currentTime", localNow)
            put("currentTimeZone", tz.id)
            put("dateTime", dateTime)
        }
        AppLog.i(TAG, "QUERY_TIME reply: channel='$carChannel' " +
            "supportSyncCorrectTime=$carSupportSyncCorrectTime dateTime='$dateTime' reply=$reply")
        return reply.toString().toByteArray(StandardCharsets.UTF_8)
    }

    /** Mirror of `ih/k0.java::g()` — `{status: false}` when phone has no GPS fix. */
    private fun handleQueryGps(): ByteArray =
        """{"status":false}""".toByteArray(StandardCharsets.UTF_8)

    /**
     * Build the phone-side capability JSON the head-unit expects in answer
     * to ECP_C2P_CLIENT_INFO. Mirrors the field set populated by
     * `ECP_C2P_CLIENT_INFO.f0()` in the decompiled APK; fields that require
     * an RSA key pair or a real Carbit license token are left empty / zero,
     * which is acceptable on head-units that do not enforce
     * `enableSockServerAuth`.
     */
    private fun handleClientInfo(payload: ByteArray): ByteArray {
        val carJson = if (payload.isNotEmpty()) String(payload, StandardCharsets.UTF_8) else ""
        AppLog.i(TAG, "CLIENT_INFO from car: $carJson")
        val pkg = context.packageName
        val versionCode = try {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(pkg, 0).longVersionCode
        } catch (_: Throwable) { 1L }

        // Parse the head-unit's CLIENT_INFO so we can echo back the fields
        // it actually checks (`supportFunction`, `speechEngineType`, …).
        // Mirrors what `ECP_C2P_CLIENT_INFO.f0()` does in the decompiled APK
        // (line 758 echoes `this.f67292b0`, line 469 in `p$a.M()` adds
        // `speechEngineType` via the override map).
        val car = try { JSONObject(carJson) } catch (_: Throwable) { JSONObject() }
        val carHuid = car.optString("HUID", "")
        val carSupportFunction = car.optInt("supportFunction", 0)
        val carSpeechEngineType = car.optInt("supportSpeechEngine", 0)
        val carPxcVersion = car.optString("pxcVersion", "1.0.2")
        carSupportSyncCorrectTime = car.optBoolean("supportSyncCorrectTime", false)
        carChannel = car.optString("channel", "")

        // Best-effort: get the phone's Bluetooth adapter name. The original
        // Carbit code uses BluetoothUtil.getBlueToothAdapterName() — a non-
        // empty value here matters for head-units that gate the mirror
        // session on phone "identity" (BT adapter name is a stable per-
        // device label). If we have no BT permission or no adapter, fall
        // back to a stable name derived from Build.MODEL.
        val btName = try {
            val bm = context.getSystemService(Context.BLUETOOTH_SERVICE)
                as? android.bluetooth.BluetoothManager
            val adapter = bm?.adapter
            @Suppress("MissingPermission")
            adapter?.name ?: (Build.MODEL ?: "RidePanel")
        } catch (_: Throwable) {
            Build.MODEL ?: "RidePanel"
        }
        val reply = JSONObject().apply {
            put("pxcVersion", carPxcVersion)
            put("phoneUUID", phoneUuid)
            put("phoneBrand", Build.BRAND ?: "")
            put("phoneModel", Build.MODEL ?: "")
            put("phoneOsVersion", Build.VERSION.SDK_INT.toString())
            put("phoneOs", "Android")
            put("package", pkg)
            put("versionCode", versionCode.toInt())
            put("token", 0)
            put("pubkey", RsaSigner.publicKeyBase64(context))
            put("encryptedHUID", RsaSigner.encryptHuid(context, carHuid))
            put("bluetoothName", btName)
            put("supportH264IFrame", true)
            // Echo head-unit's supportFunction so it sees the phone as
            // capable of the same features the head-unit advertised.
            // Sending 0 (default) tells the head-unit the phone is empty,
            // which gates fullscreen video reception.
            put("supportFunction", carSupportFunction)
            put("speechEngineType", carSpeechEngineType)
            put("appVersionFingerPrint", "")
            put("haveSysScreenInSetting", true)
        }
        AppLog.i(TAG, "CLIENT_INFO reply: pubkey=${reply.optString("pubkey").length} chars, " +
            "encryptedHUID=${reply.optString("encryptedHUID").length} chars, " +
            "supportFunction=$carSupportFunction (echoed), speechEngineType=$carSpeechEngineType")
        return reply.toString().toByteArray(StandardCharsets.UTF_8)
    }

    /**
     * The original phone app forwards SAE-license requests to api.carbit.com.cn
     * and answers the head-unit with the resulting blob. We simply ack with
     * an empty payload — most head-units treat that as "no license available,
     * proceed without one".
     */
    private fun handleLicenseStub(req: PxcFrame.Frame): ByteArray {
        val asJson = try { JSONObject(String(req.payload, StandardCharsets.UTF_8)) } catch (_: Throwable) { null }
        AppLog.i(TAG, "license cmd=0x${req.cmd.toString(16)} ${asJson ?: ""}")
        return ByteArray(0)
    }

    /**
     * Submit one encoded H.264 frame to every active data channel — they
     * each hand it back the next time the head-unit asks via REQ_CAPTURE.
     */
    fun submitVideoFrame(nalu: ByteArray) {
        // Defensive copy because the buffer ownership otherwise depends on
        // the encoder's MediaCodec output buffer lifecycle.
        val payload = if (nalu.size == nalu.size) nalu else nalu.copyOf()
        for (ch in dataChannels) {
            ch.submitFrame(payload)
        }
    }

    fun hasClient(): Boolean = dataChannels.isNotEmpty()

    fun clientCount(): Int = dataChannels.size

    /**
     * Diagnostic: send the same frame to every connected data channel using
     * three different framings so we can tell visually (via head-unit
     * display) which one — if any — the firmware understands.
     */
    fun testSendFrame(frame: ByteArray): String {
        if (dataChannels.isEmpty()) return "no data channels open"
        var result = ""
        for (ch in dataChannels) {
            try {
                // Framing 1: size + bytes + timestamp (the canonical j0.java form)
                ch.submitFrame(frame)
                AppLog.i(TAG, "test#1: queued frame for size+bytes+ts framing (${frame.size} B)")
                Thread.sleep(150)
                // Framing 2: size + bytes (no timestamp)
                ch.writeFrameNoTimestamp(frame)
                AppLog.i(TAG, "test#2: pushed size+bytes (no timestamp) (${frame.size} B)")
                Thread.sleep(150)
                // Framing 3: raw NAL bytes
                ch.writeRaw(frame)
                AppLog.i(TAG, "test#3: pushed raw NAL bytes (${frame.size} B)")
                result = "queued 3 framings to channel"
            } catch (t: Throwable) {
                AppLog.e(TAG, "test send failed", t)
                result = "test send failed: ${t.message}"
            }
        }
        return result
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        try { serverSocket?.close() } catch (_: Throwable) {}
        dataChannels.forEach { runCatching { it.stop() } }
        dataChannels.clear()
        ioPool.shutdownNow()
    }

    companion object {
        private const val TAG = "RidePanel.Pxc"

        // ECP_C2P_* periodic-query cmd ids (head-unit polls every ~2 s).
        // Sourced from `ih/k0.java`, `ih/l0.java`, `ih/n0.java`, etc.
        private const val ECP_C2P_QUERY_GPS: Int = 0x10430        // 66608
        private const val ECP_C2P_QUERY_TIME: Int = 0x10450       // 66640
        private const val ECP_C2P_QUERY_WEATHER: Int = 0x10630    // 67120
        private const val ECP_C2P_ENABLE_DOWNLOAD_PHONE_HUD: Int = 0x10040  // 65600
        private const val ECP_C2P_PHONE_FTP_SPEED: Int = 0x10690  // 67216
        /** Head-unit asks phone to host a FTP server for OTA package download.
         *  Defined in `ih/w0.java` (return 66720). Body carries
         *  userName/pwd/dataPort/ctrlPort in JSON. We don't implement OTA, but
         *  we MUST reply via [ECP_P2C_OTA_FTP_STATUS] within ~5 s or the
         *  head-unit assumes the phone died and tears the mirror down. */
        private const val ECP_C2P_OTA_DOWNLOAD_REQUEST: Int = 0x104a0  // 66720
        /** Phone → head-unit. Sent in response to [ECP_C2P_OTA_DOWNLOAD_REQUEST]
         *  carrying `{isOk, dataPort, ctrlPort, errCode, errMsg}`. Mirrors
         *  `kh/g0.java` (cmd 131712 = 0x20280). */
        private const val ECP_P2C_OTA_FTP_STATUS: Int = 0x20280  // 131712
        /** Phone → head-unit. ECP_P2C_PHONE_HUDINFO — periodic navigation
         *  HUD info from phone. `kh/b0.java` cmd 131344 = **0x20110**.
         *  (Earlier builds used 0x20150 which is 131408 — that hex was a
         *  conversion mistake and produced an unknown opcode the head-unit
         *  silently dropped, so the HUDINFO gate hypothesis was never
         *  actually tested.) */
        private const val ECP_P2C_PHONE_HUDINFO: Int = 0x20110  // 131344
        /** Phone → head-unit. ECP_P2C_NAVI_STATUS — navigation active/inactive
         *  flag. `kh/w.java` cmd 131552 = 0x20220. */
        private const val ECP_P2C_NAVI_STATUS: Int = 0x20220  // 131552

        private val DATA_CHANNELS = setOf(
            PxcFrame.CH_MAIN_DATA,
            0x40000,
            0x34000,
            0x70000,
        )
    }
}
