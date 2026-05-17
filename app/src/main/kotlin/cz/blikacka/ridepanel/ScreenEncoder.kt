package cz.blikacka.ridepanel

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * H.264 surface-input encoder. **Does NOT manage MediaProjection /
 * VirtualDisplay** — those live in [MirrorService] because Android 14+
 * forbids calling `MediaProjection.createVirtualDisplay()` more than once
 * per token. The Encoder only owns its [MediaCodec] + input [Surface]; the
 * caller wires that surface into the existing VirtualDisplay via
 * `virtualDisplay.setSurface(encoder.inputSurface)`.
 *
 * Encoder configuration (mirror of `net/easyconn/carman/m.java::F`):
 * H.264, surface input, 30 fps, 3 s I-frame interval, VBR.
 */
class ScreenEncoder(
    private val width: Int,
    private val height: Int,
    private val frameRate: Int = 30,
    private val bitRate: Int = 4_000_000,
    private val onFrame: (buffer: ByteBuffer, info: MediaCodec.BufferInfo) -> Unit,
) {

    private var codec: MediaCodec? = null
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private val running = AtomicBoolean(false)

    /** Set after [start] — give this to the VirtualDisplay via setSurface(). */
    @Volatile var inputSurface: Surface? = null
        private set

    fun start() {
        if (running.getAndSet(true)) return

        // Mirror Carbit's MediaCodec setup verbatim (m.java::F): only set
        // colour-format, bitrate, frame-rate, i-frame-interval. Do NOT pin
        // profile/level/bitrate-mode and do NOT use prepend-sps-pps-to-idr —
        // the head-unit decoder wants SPS+PPS as its own frame ahead of the
        // first IDR (CODEC_CONFIG buffer below), and inline-prepending them
        // into the IDR produced a stream the decoder silently refused
        // (5-second teardown). The default profile picked by the codec
        // (typically baseline-ish on Google's software encoder) is what
        // Carbit ships in production.
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
            // I-frame interval 2 s — was bumped to 5 s for static-dashboard
            // efficiency, but Wi-Fi P2P drops packets often enough that on
            // rapidly-changing content (Maps scroll, map panning) the
            // head-unit's decoder loses a P-frame, gets a broken reference
            // and shows fragmented/torn output until the next IDR. At a 5 s
            // interval that means up to 5 seconds of corrupted display.
            // 2 s caps that recovery window and is the value the original
            // Carbit Ride app ships with.
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            // Pin profile to Baseline — c2.google.avc.encoder defaults to
            // High profile (profile=8) which the Carbit head-unit's decoder
            // does NOT understand. Empty stream parses, decoder fails, and
            // the head-unit times out after ~5 s. Baseline (1) is the only
            // profile we've actually seen accepted, and it matches the
            // 0x42 profile_idc in the prior working build's SPS.
            setInteger(MediaFormat.KEY_PROFILE,
                MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
            // Level 4.0 — Baseline @ Level 4 supports up to 25 Mbps, so the
            // 6 Mbps cap in MirrorService.reconfigureEncoder fits comfortably.
            // Level 3.1 only allows 14 Mbps which is also fine, but bumping
            // gives the codec headroom to pick higher per-frame sizes when
            // motion spikes (panning the map, scrolling) without auto-
            // downgrading mid-stream like the old 10 fps @ 400 kbps test did.
            setInteger(MediaFormat.KEY_LEVEL,
                MediaCodecInfo.CodecProfileLevel.AVCLevel4)
            // CBR (Constant Bitrate) — VBR let the encoder emit IDR/P-frames
            // many times larger than the bitrate target on complex content,
            // which saturated the Wi-Fi P2P link. CBR clamps frame sizes.
            setInteger(MediaFormat.KEY_BITRATE_MODE,
                MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
            // Keep the pipeline alive when the source screen goes static.
            setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 100_000L)
        }

        val c = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        c.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        inputSurface = c.createInputSurface()
        c.start()
        codec = c

        val ht = HandlerThread("RidePanel-Encoder").also { it.start() }
        thread = ht
        handler = Handler(ht.looper).also { it.post(::drainLoop) }
        AppLog.i(TAG, "encoder started ${width}x$height @${frameRate}fps ${bitRate}bps")
    }

    private fun drainLoop() {
        val info = MediaCodec.BufferInfo()
        var consecutiveErrors = 0
        while (running.get()) {
            val c = codec ?: break
            val idx = try {
                c.dequeueOutputBuffer(info, 10_000L)
            } catch (t: IllegalStateException) {
                consecutiveErrors++
                AppLog.w(TAG, "dequeueOutputBuffer ISE #$consecutiveErrors", t)
                if (consecutiveErrors >= 5) { AppLog.e(TAG, "too many codec errors, bailing"); break }
                Thread.sleep(50); continue
            } catch (t: Throwable) {
                AppLog.e(TAG, "dequeueOutputBuffer fatal", t); break
            }
            consecutiveErrors = 0
            when {
                idx == MediaCodec.INFO_TRY_AGAIN_LATER -> { /* spin */ }
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> { /* SPS/PPS are inlined with every IDR — nothing to extract here. */ }
                idx >= 0 -> {
                    val buf = try { c.getOutputBuffer(idx) } catch (t: Throwable) {
                        AppLog.w(TAG, "getOutputBuffer($idx) failed", t); null
                    }
                    if (buf != null && info.size > 0) {
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        runCatching { onFrame(buf, info) }
                            .onFailure { AppLog.w(TAG, "onFrame consumer threw", it) }
                    }
                    runCatching { c.releaseOutputBuffer(idx, false) }
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
        }
        AppLog.i(TAG, "drainLoop exited (running=${running.get()})")
    }

    fun requestKeyFrame() {
        try {
            val params = android.os.Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            }
            codec?.setParameters(params)
        } catch (t: Throwable) {
            AppLog.w(TAG, "requestKeyFrame failed", t)
        }
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        try { codec?.signalEndOfInputStream() } catch (_: Throwable) {}
        try { codec?.stop() } catch (_: Throwable) {}
        try { codec?.release() } catch (_: Throwable) {}
        try { inputSurface?.release() } catch (_: Throwable) {}
        try { thread?.quitSafely() } catch (_: Throwable) {}
        codec = null
        inputSurface = null
        thread = null
        handler = null
    }

    companion object { private const val TAG = "RidePanel.Encoder" }
}
