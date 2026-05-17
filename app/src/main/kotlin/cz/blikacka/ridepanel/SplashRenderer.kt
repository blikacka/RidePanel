package cz.blikacka.ridepanel

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.Rect
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Draws the RidePanel "connected" splash bitmap onto the encoder's input
 * Surface using the Canvas/lockHardwareCanvas API — no GLES, no
 * SurfaceTexture pipeline, no VirtualDisplay. Used during the *connected
 * but not yet sharing screen* phase: the head-unit needs a live H.264
 * feed to render anything, so we synthesize one out of a single PNG
 * pushed at a low frame rate.
 *
 * Lifecycle:
 *  • [start] grabs the encoder Surface, schedules redraws at ~5 fps
 *    onto its own [HandlerThread] so the encoder's bitrate controller
 *    keeps timestamps moving and the head-unit decoder doesn't flag
 *    the stream as stalled.
 *  • [stop] cancels redraws and releases the surface claim so a
 *    [LetterboxingPipeline] can take over via its own EGL window
 *    surface when the user accepts the MediaProjection dialog.
 *
 * Thread safety: all `lockHardwareCanvas` / `unlockCanvasAndPost`
 * calls happen on [handler]; callers may invoke [start] / [stop]
 * from any thread.
 */
class SplashRenderer(
    context: Context,
    private val encoderSurface: Surface,
    private val outputW: Int,
    private val outputH: Int,
    private val splashResId: Int,
) {

    private val running = AtomicBoolean(false)
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var bitmap: Bitmap? = null
    private val ctx = context.applicationContext

    fun start() {
        if (running.getAndSet(true)) return
        bitmap = decodeBitmap()
        val ht = HandlerThread("RidePanel-Splash").apply { start() }
        thread = ht
        handler = Handler(ht.looper)
        handler?.post(redrawRunnable)
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        val h = handler
        val t = thread
        handler = null
        thread = null
        h?.removeCallbacks(redrawRunnable)
        h?.post {
            try { bitmap?.recycle() } catch (_: Throwable) {}
            bitmap = null
        }
        t?.quitSafely()
    }

    private val redrawRunnable = object : Runnable {
        override fun run() {
            if (!running.get()) return
            drawOneFrame()
            handler?.postDelayed(this, FRAME_INTERVAL_MS)
        }
    }

    private fun drawOneFrame() {
        val bmp = bitmap ?: return
        // lockHardwareCanvas works on MediaCodec input surfaces on API 23+
        // and is GPU-backed (zero-copy upload). Falls back to lockCanvas
        // (software) on devices where the hardware path is refused.
        val canvas: Canvas = try {
            encoderSurface.lockHardwareCanvas()
        } catch (_: Throwable) {
            try { encoderSurface.lockCanvas(null) } catch (_: Throwable) { null }
        } ?: return
        try {
            // Black background fills any pillar/letterbox area if the bitmap
            // doesn't exactly match the encoder's output size (it should —
            // the resource is pre-resized to 800×480 — but resilience costs
            // nothing).
            canvas.drawColor(Color.BLACK, PorterDuff.Mode.SRC)
            val src = Rect(0, 0, bmp.width, bmp.height)
            // Aspect-fit destination so the splash never gets stretched if
            // the encoder happens to be at a non-5:3 size.
            val srcAspect = bmp.width.toFloat() / bmp.height
            val dstAspect = outputW.toFloat() / outputH
            val dst = if (srcAspect > dstAspect) {
                val h = (outputW / srcAspect).toInt()
                Rect(0, (outputH - h) / 2, outputW, (outputH + h) / 2)
            } else {
                val w = (outputH * srcAspect).toInt()
                Rect((outputW - w) / 2, 0, (outputW + w) / 2, outputH)
            }
            canvas.drawBitmap(bmp, src, dst, paint)
        } finally {
            try { encoderSurface.unlockCanvasAndPost(canvas) } catch (_: Throwable) {}
        }
    }

    private fun decodeBitmap(): Bitmap? = try {
        val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
        BitmapFactory.decodeResource(ctx.resources, splashResId, opts)
    } catch (t: Throwable) {
        AppLog.e(TAG, "splash bitmap decode failed", t)
        null
    }

    companion object {
        private const val TAG = "RidePanel.Splash"
        /** ~5 fps. Encoder needs a steady frame cadence to keep the
         *  head-unit decoder happy, but the content is static so any
         *  faster is wasted bandwidth. */
        private const val FRAME_INTERVAL_MS: Long = 200L
        private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    }
}
