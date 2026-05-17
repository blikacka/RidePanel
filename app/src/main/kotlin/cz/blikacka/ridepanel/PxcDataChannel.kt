package cz.blikacka.ridepanel

import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Data channel between phone (this side) and the head-unit. Implements both
 * modes that `net.easyconn.carman.common.base.j0` (`ServerDataExecute`)
 * supports in the decompiled APK:
 *
 *   - **Pull mode** (`reqConfigCapture.getWantFps() == 0`, `j0.java::139`)
 *     phone reads an 8-byte `Protocol.ReqBase`, dispatches by `cmdType`
 *     (only 0x72 = REQ_CAPTURE produces a frame) and writes one frame back.
 *
 *   - **Push mode** (`getWantFps() != 0`, `j0.java::185`) phone never reads
 *     from this socket; it just pumps encoded frames as fast as the encoder
 *     produces them.
 *
 * We don't know which mode the head-unit wants because we never receive its
 * `ReqConfigCapture` (that arrives on the e0 control channel only opened
 * by some head-units). So we run **both concurrently** — a reader that
 * answers any incoming REQ_CAPTURE, and a separate writer that pushes
 * frames as they arrive. The head-unit will use whichever stream it
 * understands.
 *
 * Wire format on the phone→car side (`j0.java::458-468`):
 *
 *   int32 LE  (size + 8)        - size of NAL bytes plus the 8-byte trailing timestamp
 *   bytes     <H264 NALU>
 *   int64 LE  SystemClock.elapsedRealtime
 */
class PxcDataChannel(
    private val input: InputStream,
    private val output: OutputStream,
    private val onClosed: () -> Unit = {},
) {
    private val running = AtomicBoolean(true)
    /** Most-recent encoded frame; replaced on each [submitFrame] call. */
    private val pending = LinkedBlockingDeque<ByteArray>(/* capacity = */ 4)

    private var readerThread: Thread? = null
    private var writerThread: Thread? = null

    /** Blocks the calling thread until the data channel ends. */
    fun run() {
        // Reader handles pull-mode REQ_CAPTURE requests (if head-unit ever sends them).
        readerThread = Thread({ runReadLoop() }, "PxcData-Reader").apply {
            isDaemon = true; start()
        }
        // Writer pushes whatever's in the queue continuously. Head-unit ignores
        // it if it expects pull-mode; otherwise it draws.
        writerThread = Thread({ runWriteLoop() }, "PxcData-Writer").apply {
            isDaemon = true; start()
        }
        // Block until both threads exit.
        try { readerThread?.join() } catch (_: InterruptedException) {}
        try { writerThread?.join() } catch (_: InterruptedException) {}
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        runCatching { output.close() }
        runCatching { input.close() }
        try { readerThread?.interrupt() } catch (_: Throwable) {}
        try { writerThread?.interrupt() } catch (_: Throwable) {}
    }

    /** Push the latest H264 NAL into the queue; older pending frames are dropped. */
    fun submitFrame(nalu: ByteArray) {
        pending.clear()
        pending.offer(nalu)
    }

    private fun runReadLoop() {
        val header = ByteArray(REQ_BASE_SIZE)
        try {
            while (running.get()) {
                if (!readFully(input, header)) {
                    AppLog.i(TAG, "head-unit closed the read side")
                    break
                }
                val bb = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
                val cmdType = bb.short
                val cmdLength = bb.short.toInt() and 0xffff
                val cmdToken = bb.int
                val extData = if (cmdLength > 0) ByteArray(cmdLength).also {
                    if (!readFully(input, it)) {
                        AppLog.w(TAG, "extData truncated for cmdType=$cmdType")
                        return
                    }
                } else ByteArray(0)
                AppLog.d(TAG, "ReqBase cmdType=0x${(cmdType.toInt() and 0xffff).toString(16)} " +
                    "len=$cmdLength token=$cmdToken extData=${extData.size}B")

                if (cmdType.toInt() == REQ_CAPTURE) {
                    writeOneFrame(triggeredByReq = true)
                }
                // For any other cmdType we silently ignore; original sends
                // RLY_UN_SUPPORT but head-unit on j0 just continues.
            }
        } catch (t: Throwable) {
            if (running.get()) AppLog.w(TAG, "read loop failed", t)
        } finally {
            running.set(false)
            onClosed()
        }
    }

    private fun runWriteLoop() {
        // Push-mode: continuously pump frames whether head-unit asks or not.
        // We rely on the encoder's own pacing (frame-rate set on MediaCodec)
        // to throttle; pending queue is bounded.
        var pushedCount = 0L
        try {
            while (running.get()) {
                val frame = pending.poll(200, TimeUnit.MILLISECONDS) ?: continue
                writeFrame(frame)
                pushedCount++
                if (pushedCount % 30 == 0L) {
                    AppLog.d(TAG, "pushed $pushedCount frames so far")
                }
            }
        } catch (t: Throwable) {
            if (running.get()) AppLog.w(TAG, "write loop failed", t)
        }
    }

    private fun writeOneFrame(triggeredByReq: Boolean) {
        val frame = pending.poll(50, TimeUnit.MILLISECONDS)
        if (frame == null) {
            // No frame ready — write the empty marker (Protocol.INT_ZERO).
            synchronized(output) {
                output.write(INT_ZERO)
                output.flush()
            }
            return
        }
        writeFrame(frame)
        if (triggeredByReq) AppLog.d(TAG, "REQ_CAPTURE answered with ${frame.size} B frame")
    }

    private fun writeFrame(frame: ByteArray) {
        synchronized(output) {
            // size = NAL bytes (no timestamp) — j0.java::462 form, the
            // branch the original phone takes when m0.M() is false. M() is
            // toggled by REQ_CONFIGCAPTUREREXTEND's supportFunction bit 0;
            // the head-unit in this build sends supportExtendProtocol=0
            // and never issues REQ_CONFIGCAPTUREREXTEND, so M() stays
            // false. Matches MirrorPortsServer's wire format on the
            // dedicated data port (:10920) — head-unit gets consistent
            // size-only framing on BOTH data paths.
            output.write(intLE(frame.size))
            output.write(frame)
            output.flush()
        }
    }

    private fun intLE(v: Int): ByteArray =
        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array()

    private fun longLE(v: Long): ByteArray =
        ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(v).array()

    private fun readFully(input: InputStream, buf: ByteArray): Boolean {
        var read = 0
        while (read < buf.size) {
            val n = input.read(buf, read, buf.size - read)
            if (n <= 0) return false
            read += n
        }
        return true
    }

    /**
     * Write one encoded frame using the alternate framing (no timestamp).
     * Some head-unit firmwares disable timestamps; the test button uses
     * this to fan out frames in multiple formats.
     */
    fun writeFrameNoTimestamp(frame: ByteArray) {
        synchronized(output) {
            output.write(intLE(frame.size))
            output.write(frame)
            output.flush()
        }
    }

    /** For diagnostic test send: write a raw byte array straight to the socket. */
    fun writeRaw(bytes: ByteArray) {
        synchronized(output) {
            output.write(bytes)
            output.flush()
        }
    }

    companion object {
        private const val TAG = "RidePanel.PxcData"
        private const val REQ_BASE_SIZE = 8
        /** `Protocol.java::REQ_CAPTURE` — see j0.java::172 (cmdType == 114). */
        private const val REQ_CAPTURE = 0x72  // 114
        private val INT_ZERO = ByteArray(4)
    }
}
