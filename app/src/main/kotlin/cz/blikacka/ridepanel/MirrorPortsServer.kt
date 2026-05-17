package cz.blikacka.ridepanel

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Implements the **mirror data path** the original Carbit phone exposes via
 * `MediaProjectService.H0()` (= `m0.I0`) and `K0()` in the decompiled APK.
 *
 * Phone listens on **four** TCP sockets simultaneously, head-unit dials all
 * four after CLIENT_INFO succeeds:
 *
 *   10921  control NEW   — `m0$b` thread → `MediaCtrlExecute` (`e0.java`)
 *   10920  data    NEW   — `m0$c` thread → `ServerDataExecute` (`v.java extends j0`)
 *   10711  control OLD   — `Protocol.RV_CTRL_PORT`
 *   10710  data    OLD   — `Protocol.RV_DATA_PORT`
 *
 * Both control sockets speak the same 8-byte `ReqBase` framing as the data
 * socket, but the cmdType set is different (see [Protocol] constants below).
 *
 * Wire format (Phone ↔ Car, both directions):
 *   8 B header:  short cmdType | short cmdLength | int cmdToken   (LE)
 *   N B body:    cmdLength bytes
 *
 * Frame data sent on the data sockets in response to `REQ_RV_DATA_NEXT`
 * (cmdType 0x72 / 114) does **not** use the ReqBase format — it's a raw
 * `int32 LE size | <NAL> | int64 LE timestamp` blob, per `j0.java::458`.
 */
class MirrorPortsServer(
    private val onClientConnected: () -> Unit = {},
    private val onClientDisconnected: () -> Unit = {},
    /** Fired the first time the head-unit sends REQ_RV_CONFIG_CAPTURE so the
     *  encoder can be reconfigured to the actual display the head-unit
     *  expects (e.g. 800×480 instead of the phone's full screen). */
    private val onConfigCaptureRequest: (ReqConfigCaptureView) -> Unit = {},
    /** Fired on every REQ_RV_DATA_NEXT — service uses it to force an
     *  I-frame on the very first request so the head-unit's decoder has a
     *  proper SPS/PPS/IDR sequence to start from. */
    private val onCaptureRequest: () -> Unit = {},
) {

    /** Mirror of `net.easyconn.carman.utils.Protocol` cmdType / RLY values. */
    object Protocol {
        const val PORT_CTRL_NEW = 10921
        const val PORT_DATA_NEW = 10920
        const val PORT_CTRL_OLD = 10711
        const val PORT_DATA_OLD = 10710

        // REQ (head-unit → phone)
        const val REQ_RV_CONFIG_CAPTURE: Short = 16
        const val REQ_SCREEN_EVENT: Short = 32
        const val REQ_GET_VERSION: Short = 48
        const val REQ_HEARTBEAT: Short = 64
        const val REQ_OPERATE_SCREEN: Short = 80
        const val REQ_CONFIGCAPTUREREXTEND: Short = 96
        const val REQ_RV_DATA_START: Short = 112
        const val REQ_RV_DATA_NEXT: Short = 114
        const val REQ_EXECUTE_SHELL: Short = 272

        // RLY (phone → head-unit)
        const val RLY_RV_CONFIG_CAPTURE: Short = 17
        const val RLY_GET_VERSION: Short = 49
        const val RLY_HEARTBEAT: Short = 65
        const val RLY_OPERATE_SCREEN: Short = 81
        const val RLY_CONFIGCAPTUREREXTEND: Short = 97
        const val RLY_RV_DATA_START: Short = 113
        const val RLY_EXECUTE_SHELL: Short = 273
        const val RLY_UN_SUPPORT: Short = 32514
        const val RLY_ERROR_CMD: Short = 32513
    }

    private val running = AtomicBoolean(false)
    private val serverSockets = mutableListOf<ServerSocket>()
    private val pool = Executors.newCachedThreadPool { r ->
        Thread(r, "MirrorPorts-Worker").apply { isDaemon = true }
    }
    private val dataChannels = CopyOnWriteArrayList<DataChannel>()

    /** Latest encoded H264 frame waiting to be served. */
    @Volatile private var pendingFrame: ByteArray? = null

    /** Whether [pendingFrame] is an IDR (with inline SPS/PPS). The head-unit
     *  decoder needs an IDR as the *first* frame on a fresh data socket —
     *  if we ship a P-frame first it produces garbage and tears the socket
     *  down after ~5 s. Re-checked when answering REQ_RV_DATA_NEXT #1. */
    @Volatile private var pendingIsKey: Boolean = false

    /** Standalone SPS+PPS bytes emitted by the encoder as a CODEC_CONFIG
     *  buffer at start. Cached forever and prepended (as its own frame) to
     *  the very first response on each data channel — the head-unit decoder
     *  needs SPS+PPS before any IDR can be parsed. Without this, clients
     *  that connect AFTER the codec's one-shot CODEC_CONFIG buffer never
     *  learn the parameter sets and the head-unit gives up after ~5 s. */
    @Volatile private var codecConfig: ByteArray? = null

    fun setCodecConfig(spsPps: ByteArray) {
        codecConfig = spsPps
        AppLog.i(TAG, "codec config cached (${spsPps.size} B SPS+PPS)")
    }

    /**
     * `wantEncoder` value from the head-unit's last REQ_RV_CONFIG_CAPTURE.
     * Only when this is `4` (= FFmpeg software path) does the head-unit
     * expect the trailing 8-byte timestamp on each frame — see
     * `j0.java::457-460` which guards the timestamp via `m0.M()`, set by
     * `m0.E0(true)` only when `wantEncoder == 4`.
     */
    @Volatile private var includeTimestamp: Boolean = false

    fun start() {
        if (running.getAndSet(true)) return
        listOf(
            Protocol.PORT_CTRL_NEW to ::handleCtrl,
            Protocol.PORT_DATA_NEW to ::handleData,
            Protocol.PORT_CTRL_OLD to ::handleCtrl,
            Protocol.PORT_DATA_OLD to ::handleData,
        ).forEach { (port, handler) -> openListener(port, handler) }
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        serverSockets.forEach { runCatching { it.close() } }
        serverSockets.clear()
        dataChannels.forEach { it.stop() }
        dataChannels.clear()
        pool.shutdownNow()
    }

    fun submitVideoFrame(nalu: ByteArray, isKeyFrame: Boolean) {
        pendingFrame = nalu
        pendingIsKey = isKeyFrame
        if (isKeyFrame) {
            AppLog.d(TAG, "submitVideoFrame: IDR ${nalu.size} B, clients=${dataChannels.size}")
        }
        for (ch in dataChannels) ch.notifyFrameAvailable()
    }

    /**
     * Drop any cached frame from a previous encoder generation. Called by
     * [MirrorService.reconfigureEncoder] so the data path doesn't briefly
     * ship a stale P-frame (referencing the *old* IDR the head-unit no
     * longer has) right after we swap the encoder.
     */
    fun resetPendingFrame() {
        pendingFrame = null
        pendingIsKey = false
        for (ch in dataChannels) ch.resetForNewEncoder()
    }

    fun hasDataClient(): Boolean = dataChannels.isNotEmpty()
    fun dataClientCount(): Int = dataChannels.size

    private fun openListener(port: Int, handler: (Socket) -> Unit) {
        thread(name = "MirrorPorts-$port", isDaemon = true) {
            try {
                val ss = ServerSocket(port, 4, InetAddress.getByName("0.0.0.0"))
                serverSockets += ss
                AppLog.i(TAG, "listening on 0.0.0.0:$port")
                while (running.get()) {
                    val sock = try { ss.accept() } catch (t: Throwable) {
                        if (running.get()) AppLog.w(TAG, ":$port accept failed", t)
                        break
                    }
                    pool.submit {
                        try {
                            sock.tcpNoDelay = true
                            AppLog.i(TAG, "client ${sock.remoteSocketAddress} connected to :$port")
                            handler(sock)
                        } catch (t: Throwable) {
                            AppLog.w(TAG, ":$port handler errored", t)
                        } finally {
                            runCatching { sock.close() }
                            AppLog.i(TAG, "client ${sock.remoteSocketAddress} on :$port closed")
                        }
                    }
                }
            } catch (t: Throwable) {
                AppLog.e(TAG, ":$port listener failed", t)
            }
        }
    }

    // ─── Control socket handler ─────────────────────────────────────────────

    private fun handleCtrl(sock: Socket) {
        val input = sock.getInputStream()
        val output = sock.getOutputStream()
        while (running.get() && !sock.isClosed) {
            val tRead = System.currentTimeMillis()
            val req = readReqBase(input) ?: break
            val readGap = System.currentTimeMillis() - tRead
            handleCtrlRequest(req, output, readGap)
        }
    }

    private fun handleCtrlRequest(req: ReqBase, output: OutputStream, readGapMs: Long) {
        val bodyPreview = req.body.take(24).joinToString("") { "%02x".format(it) }
        AppLog.i(TAG, "ctrl ← cmdType=0x${"%04x".format(req.cmdType.toInt() and 0xffff)} " +
            "len=${req.body.size} tok=${req.cmdToken} sinceLastRead=${readGapMs}ms" +
            if (bodyPreview.isNotEmpty()) " [$bodyPreview${if (req.body.size > 24) "…" else ""}]" else "")
        when (req.cmdType) {
            Protocol.REQ_RV_CONFIG_CAPTURE -> {
                // Echo back a minimal RlyConfigCapture: encoder + dimensions
                // copied from the request, no extend flag.
                val rly = buildRlyConfigCapture(req.body)
                writeReqBase(output, Protocol.RLY_RV_CONFIG_CAPTURE, rly, req.cmdToken)
            }
            Protocol.REQ_SCREEN_EVENT -> {
                writeReqBase(output, Protocol.RLY_RV_CONFIG_CAPTURE, ByteArray(0), req.cmdToken)
            }
            Protocol.REQ_GET_VERSION -> {
                // RlyGetVersion: int32 LE ERROR_CODE_AUDIO_TRACK_WRITE_FAILED + int32 LE 1
                // (e0.java:478-480) — version 1 of the protocol.
                val body = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
                    .putInt(2003)   // versionLo
                    .putInt(1)      // versionHi
                    .array()
                writeReqBase(output, Protocol.RLY_GET_VERSION, body, req.cmdToken)
            }
            Protocol.REQ_HEARTBEAT -> {
                writeReqBase(output, Protocol.RLY_HEARTBEAT, ByteArray(0), req.cmdToken)
            }
            Protocol.REQ_OPERATE_SCREEN -> {
                writeReqBase(output, Protocol.RLY_OPERATE_SCREEN, ByteArray(0), req.cmdToken)
            }
            Protocol.REQ_CONFIGCAPTUREREXTEND -> {
                // Mirror e0.java::l() in the decompiled APK: parse the
                // JSON body (viewAreaConfig + supportFunction), reply with
                // `{"viewAreaConfig":{"state":0},"supportFunction":<echo>}`
                // as UTF-8 bytes. The head-unit gates a "phone is ready
                // to render extended-protocol mirror" state on this — a
                // 4-byte int reply (what we were sending) is malformed
                // for parsers expecting JSON.
                val reqJson = if (req.body.isNotEmpty())
                    String(req.body, java.nio.charset.StandardCharsets.UTF_8) else ""
                AppLog.i(TAG, "REQ_CONFIGCAPTUREREXTEND body=$reqJson")
                val sf = try {
                    org.json.JSONObject(reqJson).optInt("supportFunction", 0)
                } catch (_: Throwable) { 0 }
                val replyJson = org.json.JSONObject().apply {
                    put("viewAreaConfig", org.json.JSONObject().apply { put("state", 0) })
                    put("supportFunction", sf)
                }
                val body = replyJson.toString().toByteArray(java.nio.charset.StandardCharsets.UTF_8)
                writeReqBase(output, Protocol.RLY_CONFIGCAPTUREREXTEND, body, req.cmdToken)
                AppLog.i(TAG, "RLY_CONFIGCAPTUREREXTEND reply=${body.size}B json=${replyJson}")
            }
            Protocol.REQ_RV_DATA_START -> {
                // Phone now starts streaming on the data channel. Empty body
                // RLY_RV_DATA_START is the "go" signal per e0.java:534.
                AppLog.i(TAG, "ctrl: REQ_RV_DATA_START — head-unit ready to receive frames")
                writeReqBase(output, Protocol.RLY_RV_DATA_START, ByteArray(0), req.cmdToken)
            }
            else -> {
                AppLog.w(TAG, "ctrl: unsupported cmdType=0x${"%04x".format(req.cmdType.toInt() and 0xffff)}")
                writeReqBase(output, Protocol.RLY_UN_SUPPORT, ByteArray(0), req.cmdToken)
            }
        }
    }

    private fun buildRlyConfigCapture(reqBody: ByteArray): ByteArray {
        val want = ReqConfigCaptureView.fromBytes(reqBody)
        AppLog.i(TAG, "REQ_RV_CONFIG_CAPTURE deviceW=${want.deviceWidth} " +
            "deviceH=${want.deviceHeight} wantFps=${want.wantFps} " +
            "wantEncoder=${want.wantEncoder} bitRate=${want.bitRate} " +
            "supportExtendProtocol=${want.supportExtendProtocol}")
        // Match phone-equivalent of useSoftEncode==true in j0.java:457-464:
        // we're a software encoder (c2.google.avc.encoder), so ship frames
        // as `int32(size+8) | NAL | int64(timestamp)`. Advertise encoder=4
        // (FFmpeg/soft path) in the reply so the head-unit parser picks
        // that layout.
        includeTimestamp = false
        val advertisedEncoder = if (want.wantEncoder in 1..4) want.wantEncoder else 1
        AppLog.i(TAG, "frame format: ${if (includeTimestamp) "size+NAL+timestamp" else "size+NAL"} (encoder=$advertisedEncoder, head-unit asked for ${want.wantEncoder})")
        onConfigCaptureRequest(want)
        val captureW = (want.deviceWidth and 0xfff0).toShort()
        val captureH = (want.deviceHeight and 0xfff0).toShort()
        // Mirror Protocol.RlyConfigCapture.toByteArray() line 627-640:
        // ship 9 bytes when supportExtendProtocol != 0, else 8. Echoing
        // the byte tells the head-unit "phone speaks extended protocol";
        // without it head-units that *only* support extended (flavor 51 /
        // SSDQ01-0120 motorcycle is one) may hang waiting for the 9th
        // byte and eventually tear the mirror down.
        val ext = want.supportExtendProtocol
        val size = if (ext != 0.toByte()) 9 else 8
        val out = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
        out.putInt(advertisedEncoder)
        out.putShort(captureW)
        out.putShort(captureH)
        if (ext != 0.toByte()) out.put(ext)
        AppLog.i(TAG, "RLY_RV_CONFIG_CAPTURE reply=${size}B encoder=$advertisedEncoder ${captureW}x$captureH" +
            if (ext != 0.toByte()) " supportExtendProtocol=$ext" else "")
        return out.array()
    }

    // ─── Data socket handler ────────────────────────────────────────────────

    private fun handleData(sock: Socket) {
        val ch = DataChannel(sock.getInputStream(), sock.getOutputStream())
        dataChannels += ch
        onClientConnected()
        try { ch.run() } finally {
            dataChannels -= ch
            onClientDisconnected()
        }
    }

    // ─── Helpers ────────────────────────────────────────────────────────────

    private fun readReqBase(input: InputStream): ReqBase? {
        val header = ByteArray(8)
        if (!readFully(input, header)) return null
        val bb = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        val cmdType = bb.short
        val cmdLength = bb.short.toInt() and 0xffff
        val cmdToken = bb.int
        val body = if (cmdLength > 0) ByteArray(cmdLength).also {
            if (!readFully(input, it)) return null
        } else ByteArray(0)
        return ReqBase(cmdType, cmdToken, body)
    }

    private fun writeReqBase(out: OutputStream, cmdType: Short, body: ByteArray, token: Int) {
        val t0 = System.currentTimeMillis()
        synchronized(out) {
            val header = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
                .putShort(cmdType)
                .putShort(body.size.toShort())
                .putInt(token)
                .array()
            try {
                out.write(header)
                if (body.isNotEmpty()) out.write(body)
                out.flush()
            } catch (t: IOException) {
                AppLog.e(TAG, "ctrl write FAILED cmdType=0x${"%04x".format(cmdType.toInt() and 0xffff)} body=${body.size}B", t)
                throw t
            }
        }
        val cost = System.currentTimeMillis() - t0
        if (cost > 50) {
            AppLog.w(TAG, "ctrl → cmdType=0x${"%04x".format(cmdType.toInt() and 0xffff)} body=${body.size}B SLOW writeMs=$cost")
        } else {
            AppLog.d(TAG, "ctrl → cmdType=0x${"%04x".format(cmdType.toInt() and 0xffff)} body=${body.size}B writeMs=$cost")
        }
    }

    private fun readFully(input: InputStream, buf: ByteArray): Boolean {
        var read = 0
        while (read < buf.size) {
            val n = input.read(buf, read, buf.size - read)
            if (n <= 0) return false
            read += n
        }
        return true
    }

    data class ReqBase(val cmdType: Short, val cmdToken: Int, val body: ByteArray) {
        override fun equals(other: Any?): Boolean =
            other is ReqBase && cmdType == other.cmdType && cmdToken == other.cmdToken &&
                body.contentEquals(other.body)
        override fun hashCode(): Int = 31 * cmdType.toInt() + body.contentHashCode()
    }

    /**
     * Parses `Protocol.ReqConfigCapture` body so we can configure the
     * encoder for the head-unit's actual display. Field order is taken
     * verbatim from `Protocol.java::byteArrayToFields` (line 354+):
     *
     *   short  realDeviceWidth        - head-unit display W (px)
     *   short  realDeviceHeight       - head-unit display H (px)
     *   int    wantFps                - desired frame rate (0 = push-mode)
     *   int    wantEncoder            - codec hint (1=hw1, 2=hw2, 4=ffmpeg)
     *   int    supportCodec           - codec capability bitset
     *   short  minQuality, maxQuality
     *   int    bitRate                - requested bitrate (0 → 3 Mbps)
     *   --- optional extended fields, only if body has ≥ 32 bytes ---
     *   byte   capScreenMode
     *   byte   touchMode
     *   byte   orientation
     *   byte   displayId
     *   byte   videoType
     *   byte   supportExtendProtocol  - non-zero means head-unit wants the
     *                                    9-byte RlyConfigCapture and may
     *                                    follow up with REQ_CONFIGCAPTUREREXTEND
     *   2B reserved
     */
    data class ReqConfigCaptureView(
        val deviceWidth: Int,
        val deviceHeight: Int,
        val wantFps: Int,
        val wantEncoder: Int,
        val bitRate: Int,
        val supportExtendProtocol: Byte,
    ) {
        companion object {
            fun fromBytes(b: ByteArray): ReqConfigCaptureView {
                if (b.size < 24) return ReqConfigCaptureView(800, 480, 30, 1, 3_000_000, 0)
                val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
                val w = bb.short.toInt() and 0xffff
                val h = bb.short.toInt() and 0xffff
                val fps = bb.int
                val enc = bb.int.let { if (it == 0) 1 else it }
                /* supportCodec */ bb.int
                /* minQuality   */ bb.short
                /* maxQuality   */ bb.short
                val rate = bb.int.let { if (it == 0) 3_000_000 else it }
                // Optional ext block at offset 24..31. supportExtendProtocol
                // is byte at offset 29 (24 + capScreenMode + touchMode +
                // orientation + displayId + videoType = 24 + 5).
                val ext: Byte = if (b.size >= 30) b[29] else 0
                return ReqConfigCaptureView(w, h, fps, enc, rate, ext)
            }
        }
    }

    /** Per-data-channel pump that writes frames in the j0.java format. */
    inner class DataChannel(
        private val input: InputStream,
        private val output: OutputStream,
    ) {
        private val active = AtomicBoolean(true)
        private val frameSignal = Object()
        /** Set to true the first time we see REQ_RV_DATA_NEXT — head-unit is
         *  in **pull** mode, the writer thread should stop double-pushing. */
        private val pullModeActive = AtomicBoolean(false)
        /** Last frame the reader actually shipped — guards against sending
         *  the same NAL twice in a row (head-unit's decoder treats a repeat
         *  as a duplicate IDR and may stall) and lets us wait on a *new*
         *  frame instead of immediately answering REQ_NEXT with INT_ZERO. */
        @Volatile private var lastSentFrame: ByteArray? = null

        /** True after we've shipped at least one IDR on this channel.
         *  Until then we MUST wait for a key-frame, even if it means
         *  answering REQ_NEXTs with INT_ZERO for a while — shipping a
         *  P-frame first hands the head-unit decoder a frame it can't
         *  resolve (no reference IDR) and triggers the 5-second teardown. */
        @Volatile private var firstKeySent: Boolean = false

        /** Cleared by [resetForNewEncoder] when the encoder is rebuilt
         *  mid-session so we re-enter "needs IDR" mode. */
        fun resetForNewEncoder() {
            firstKeySent = false
            lastSentFrame = null
        }

        fun stop() {
            active.set(false)
            synchronized(frameSignal) { frameSignal.notifyAll() }
            runCatching { input.close() }
            runCatching { output.close() }
        }

        fun notifyFrameAvailable() {
            synchronized(frameSignal) { frameSignal.notifyAll() }
        }

        /**
         * Block up to [timeoutMs] waiting for a frame that we haven't shipped
         * yet on this channel. Mirrors `j0.x()` in the decompiled APK, which
         * polls its queue every 100 ms for up to 10 s before falling back to
         * INT_ZERO. Spamming INT_ZERO at the head-unit's request rate (~45/s
         * vs our 20 fps encoder) starves its decoder and triggers the
         * 5-second teardown we saw in logs.
         *
         * When [requireKeyFrame] is true the wait additionally insists on
         * the *current* frame being a fresh IDR — needed for the very first
         * REQ_NEXT on a data socket, where shipping any inter-frame ahead of
         * an IDR yields undecodable garbage and the same teardown.
         */
        private fun waitForNewFrame(timeoutMs: Long, requireKeyFrame: Boolean = false): ByteArray? {
            val deadline = System.currentTimeMillis() + timeoutMs
            synchronized(frameSignal) {
                while (active.get()) {
                    val current = pendingFrame
                    val isKey = pendingIsKey
                    val fresh = current != null && current !== lastSentFrame
                    if (fresh && (!requireKeyFrame || isKey)) return current
                    val remaining = deadline - System.currentTimeMillis()
                    if (remaining <= 0) return null
                    try { frameSignal.wait(remaining) } catch (_: InterruptedException) { return null }
                }
            }
            return null
        }

        fun run() {
            // Reader thread for incoming REQ_RV_DATA_NEXT (pull mode).
            val reader = Thread({ runReader() }, "MirrorData-Reader").apply {
                isDaemon = true; start()
            }
            // Writer thread continually pushes frames (push mode). Both modes
            // run together — the head-unit picks whichever it understands.
            runWriter()
            try { reader.join() } catch (_: InterruptedException) {}
        }

        private fun runReader() {
            var reqCount = 0L
            var sentCount = 0L
            var emptyCount = 0L
            var lastReqAt = System.currentTimeMillis()
            try {
                while (active.get()) {
                    val req = readReqBase(input) ?: break
                    val now = System.currentTimeMillis()
                    val gap = now - lastReqAt
                    lastReqAt = now
                    if (gap > 500) {
                        AppLog.w(TAG, "data ← REQ gap=${gap}ms (head-unit slow polling? cmdType=0x${"%04x".format(req.cmdType.toInt() and 0xffff)})")
                    }
                    if (req.cmdType == Protocol.REQ_RV_DATA_NEXT) {
                        if (pullModeActive.compareAndSet(false, true)) {
                            AppLog.i(TAG, "data: pull mode detected — disabling push writer")
                        }
                        reqCount++
                        if (reqCount == 1L) {
                            onCaptureRequest()
                            // FIRST REQ_NEXT on this channel — ship the
                            // cached SPS+PPS *as its own frame* before
                            // anything else. The encoder emits CODEC_CONFIG
                            // exactly once at start; clients that connect
                            // later (e.g. after head-unit's REQ_DATA_START
                            // round-trip, ~1 s after encoder boot) would
                            // otherwise never see the parameter sets and
                            // their decoder would silently drop every IDR.
                            val cfg = codecConfig
                            if (cfg != null) {
                                writeFrame(cfg)
                                AppLog.i(TAG, "data: REQ_NEXT #1 → CFG ${cfg.size} B (SPS+PPS handshake)")
                                continue
                            } else {
                                AppLog.w(TAG, "data: REQ_NEXT #1 but no cached SPS+PPS yet — head-unit may not init decoder")
                            }
                        }
                        // Until we've shipped one IDR on this channel, every
                        // request must wait for a key-frame — even if it
                        // means answering with INT_ZERO. Once a key-frame
                        // goes out, subsequent requests just wait briefly
                        // for the next frame at the encoder's pace.
                        val frame = if (!firstKeySent) {
                            waitForNewFrame(500L, requireKeyFrame = true)
                        } else {
                            waitForNewFrame(100L)
                        }
                        if (frame != null) {
                            val wasKey = pendingIsKey
                            writeFrame(frame)
                            lastSentFrame = frame
                            // pendingIsKey snapshot at the moment the frame
                            // came out of waitForNewFrame is what made it
                            // pass the gate; once shipped we can relax.
                            if (!firstKeySent && wasKey) {
                                firstKeySent = true
                                AppLog.i(TAG, "data: first IDR shipped on REQ_NEXT #$reqCount (${frame.size} B)")
                            }
                            sentCount++
                            if (reqCount <= 3L || (reqCount <= 10L && wasKey)) {
                                AppLog.i(TAG, "data: REQ_NEXT #$reqCount → ${if (wasKey) "IDR" else "P  "} ${frame.size} B")
                            }
                        } else {
                            writeIntZero(); emptyCount++
                            if (reqCount <= 5L || emptyCount % 10 == 0L) {
                                AppLog.w(TAG, "data: REQ_NEXT #$reqCount → INT_ZERO " +
                                    "(firstKeySent=$firstKeySent, pendingFrame=${pendingFrame?.size}, pendingIsKey=$pendingIsKey)")
                            }
                        }
                        if (reqCount % 30 == 0L) {
                            AppLog.d(TAG, "data: REQ_NEXT #$reqCount (sent=$sentCount, empty=$emptyCount, firstKeySent=$firstKeySent)")
                        }
                    } else {
                        AppLog.d(TAG, "data: ignoring cmdType=0x${"%04x".format(req.cmdType.toInt() and 0xffff)}")
                        writeIntZero()
                    }
                }
                val sinceLast = System.currentTimeMillis() - lastReqAt
                AppLog.i(TAG, "data reader EXITED after $reqCount REQ_NEXTs (sent=$sentCount, empty=$emptyCount). " +
                    "Last req was ${sinceLast}ms ago, last sent frame ${lastSentFrame?.size ?: -1}B. " +
                    "Exit reason: ${if (active.get()) "head-unit closed write side (EOF)" else "active=false externally"}")
            } catch (t: Throwable) {
                AppLog.e(TAG, "data reader THREW after $reqCount REQ_NEXTs (sent=$sentCount, empty=$emptyCount)", t)
            } finally {
                active.set(false)
            }
        }

        private fun runWriter() {
            var lastFrame: ByteArray? = null
            var pushed = 0L
            var sentFirstKey = false
            try {
                while (active.get()) {
                    if (!pullModeActive.get()) {
                        val frame = pendingFrame
                        val isKey = pendingIsKey
                        // In push mode the head-unit also needs an IDR as
                        // the first frame it ever sees on this socket; skip
                        // anything before the first key frame.
                        val gateOpen = sentFirstKey || isKey
                        if (gateOpen && frame != null && frame !== lastFrame) {
                            lastFrame = frame
                            writeFrame(frame)
                            sentFirstKey = sentFirstKey || isKey
                            pushed++
                            if (pushed % 30 == 0L) AppLog.d(TAG, "data: pushed $pushed frames (push-mode)")
                        }
                    }
                    synchronized(frameSignal) {
                        try { frameSignal.wait(33) } catch (_: InterruptedException) {}
                    }
                }
            } catch (t: Throwable) {
                if (active.get()) AppLog.w(TAG, "data writer failed", t)
            }
        }

        private fun writeFrame(frame: ByteArray) {
            val t0 = System.currentTimeMillis()
            synchronized(output) {
                try {
                    if (includeTimestamp) {
                        output.write(intLE(frame.size + 8))
                        output.write(frame)
                        output.write(longLE(android.os.SystemClock.elapsedRealtime()))
                    } else {
                        output.write(intLE(frame.size))
                        output.write(frame)
                    }
                    output.flush()
                } catch (t: Throwable) {
                    AppLog.e(TAG, "data writeFrame FAILED size=${frame.size}B after ${System.currentTimeMillis() - t0}ms", t)
                    throw t
                }
            }
            val cost = System.currentTimeMillis() - t0
            // 100 ms write = TCP backpressure. Worth flagging because the
            // head-unit's 5 s SO_TIMEOUT (j0.java:115) means a few slow
            // writes back to back can stall a frame past the deadline.
            if (cost > 100) {
                AppLog.w(TAG, "data ← BACKPRESSURE frame ${frame.size}B writeMs=$cost (head-unit input buffer full?)")
            }
        }

        private fun writeIntZero() {
            val t0 = System.currentTimeMillis()
            synchronized(output) {
                try {
                    output.write(byteArrayOf(0, 0, 0, 0))
                    output.flush()
                } catch (t: Throwable) {
                    AppLog.e(TAG, "data writeIntZero FAILED after ${System.currentTimeMillis() - t0}ms", t)
                    throw t
                }
            }
        }
    }

    private fun intLE(v: Int): ByteArray =
        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array()

    private fun longLE(v: Long): ByteArray =
        ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(v).array()

    companion object {
        private const val TAG = "RidePanel.Mirror"
    }
}
