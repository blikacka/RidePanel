package cz.blikacka.ridepanel

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean

/**
 * GLES2 pipeline that **letterboxes** a variable-aspect source (phone display)
 * into a fixed-aspect destination (head-unit's encoder surface).
 *
 * Without this, `VirtualDisplay` at `800×480` receives the phone's
 * `2410×1080` content stretched (no aspect preservation), producing
 * visibly distorted output on the head-unit. With this pipeline,
 * the source is sampled at its native dimensions and rendered into a
 * centred sub-rectangle of the encoder surface, with black bars filling
 * the rest.
 *
 * Architecture:
 * ```
 *   VirtualDisplay (logical size = phone display, e.g. 2410×1080)
 *        v
 *   SurfaceTexture wrapped in a Surface (this pipeline's [inputSurface])
 *        v   onFrameAvailable
 *   GL thread renders into encoderSurface using
 *        OES external texture + letterboxed glViewport
 *        v
 *   encoder receives 800×480 frames with aspect-correct content + bars
 * ```
 *
 * Threading: all GL calls happen on a dedicated [HandlerThread] so the
 * EGL context (which is thread-bound) stays consistent. The constructor's
 * `encoderSurface` may only be used from that GL thread once `start()`
 * returns; the caller must not draw to it itself.
 *
 * Lifecycle: `start()` must complete before the VirtualDisplay is wired
 * to [inputSurface]. `stop()` must run before the encoder (which owns
 * `encoderSurface`) is released, otherwise GL calls land on a freed
 * buffer queue.
 */
class LetterboxingPipeline(
    private val encoderSurface: Surface,
    private val outputW: Int,
    private val outputH: Int,
    initialSourceW: Int,
    initialSourceH: Int,
) {

    @Volatile private var srcW: Int = initialSourceW
    @Volatile private var srcH: Int = initialSourceH
    private val running = AtomicBoolean(false)

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var textureId: Int = 0
    private var surfaceTexture: SurfaceTexture? = null
    private var program: Int = 0
    private var aPositionLoc: Int = -1
    private var aTexCoordLoc: Int = -1
    private var uSTMatrixLoc: Int = -1
    private var vertexBuffer: FloatBuffer? = null
    private var texCoordBuffer: FloatBuffer? = null
    private val stMatrix = FloatArray(16)

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var framesRendered: Long = 0L

    /** Surface to feed into the VirtualDisplay. Valid only after [start] returns true. */
    @Volatile var inputSurface: Surface? = null
        private set

    /**
     * Notify the pipeline of a new source size, e.g. when the phone rotates
     * and `MediaProjection.onCapturedContentResize` fires. Updates the
     * SurfaceTexture's default buffer size so producer-side resizing
     * happens cleanly, and recalculates the letterbox rect at next draw.
     */
    fun setSourceSize(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        if (w == srcW && h == srcH) return
        srcW = w
        srcH = h
        handler?.post {
            try {
                surfaceTexture?.setDefaultBufferSize(w, h)
                AppLog.i(TAG, "source size → ${w}x${h}")
            } catch (t: Throwable) {
                AppLog.w(TAG, "setDefaultBufferSize($w,$h) failed", t)
            }
        }
    }

    /**
     * Set up EGL, compile shaders, create the SurfaceTexture. Blocks until
     * setup completes (or fails) on the GL thread so callers can immediately
     * wire [inputSurface] to a VirtualDisplay.
     */
    fun start(): Boolean {
        if (running.getAndSet(true)) return inputSurface != null
        val ht = HandlerThread("RidePanel-LetterboxGL").apply { start() }
        thread = ht
        handler = Handler(ht.looper)
        val done = CompletableFuture<Boolean>()
        handler!!.post {
            try {
                setupGL()
                done.complete(true)
            } catch (t: Throwable) {
                AppLog.e(TAG, "GL setup failed", t)
                done.complete(false)
            }
        }
        val ok = try { done.get() } catch (_: Throwable) { false }
        if (!ok) {
            running.set(false)
            handler = null
            thread?.quitSafely()
            thread = null
        }
        return ok
    }

    private fun setupGL() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(eglDisplay != EGL14.EGL_NO_DISPLAY) { "eglGetDisplay returned NO_DISPLAY" }
        val version = IntArray(2)
        check(EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) { "eglInitialize failed" }

        val configAttribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 0,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGLExt.EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        check(
            EGL14.eglChooseConfig(eglDisplay, configAttribs, 0, configs, 0, 1, numConfigs, 0) &&
                numConfigs[0] > 0
        ) { "eglChooseConfig found no recordable RGBA config" }

        val contextAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
        eglContext = EGL14.eglCreateContext(
            eglDisplay, configs[0], EGL14.EGL_NO_CONTEXT, contextAttribs, 0,
        )
        check(eglContext != EGL14.EGL_NO_CONTEXT) { "eglCreateContext failed" }

        val surfaceAttribs = intArrayOf(EGL14.EGL_NONE)
        eglSurface = EGL14.eglCreateWindowSurface(
            eglDisplay, configs[0], encoderSurface, surfaceAttribs, 0,
        )
        check(eglSurface != EGL14.EGL_NO_SURFACE) { "eglCreateWindowSurface failed" }

        check(EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            "eglMakeCurrent failed"
        }

        // OES external texture for SurfaceTexture sampling.
        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        textureId = tex[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        surfaceTexture = SurfaceTexture(textureId).apply {
            setDefaultBufferSize(srcW, srcH)
            setOnFrameAvailableListener { handler?.post(::renderFrame) }
        }
        inputSurface = Surface(surfaceTexture)

        program = buildProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        aPositionLoc = GLES20.glGetAttribLocation(program, "aPosition")
        aTexCoordLoc = GLES20.glGetAttribLocation(program, "aTexCoord")
        uSTMatrixLoc = GLES20.glGetUniformLocation(program, "uSTMatrix")
        check(aPositionLoc >= 0 && aTexCoordLoc >= 0 && uSTMatrixLoc >= 0) {
            "shader attribute/uniform lookup failed " +
                "(aPos=$aPositionLoc aTex=$aTexCoordLoc uST=$uSTMatrixLoc)"
        }

        vertexBuffer = ByteBuffer.allocateDirect(QUAD_VERTICES.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply { put(QUAD_VERTICES); position(0) }
        texCoordBuffer = ByteBuffer.allocateDirect(QUAD_TEX_COORDS.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply { put(QUAD_TEX_COORDS); position(0) }

        AppLog.i(TAG, "GL pipeline ready: source ${srcW}x${srcH} → encoder ${outputW}x${outputH}")
    }

    private fun renderFrame() {
        if (!running.get()) return
        val st = surfaceTexture ?: return
        try {
            EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
            st.updateTexImage()
            st.getTransformMatrix(stMatrix)

            // Letterbox math: fit source aspect into output rectangle.
            val srcAspect = srcW.toFloat() / srcH.toFloat()
            val dstAspect = outputW.toFloat() / outputH.toFloat()
            val (rectW, rectH, rectX, rectY) = if (srcAspect > dstAspect) {
                val rw = outputW
                val rh = (outputW / srcAspect).toInt()
                Quad(rw, rh, 0, (outputH - rh) / 2)
            } else {
                val rh = outputH
                val rw = (outputH * srcAspect).toInt()
                Quad(rw, rh, (outputW - rw) / 2, 0)
            }

            // Clear the whole encoder surface to black (= letterbox bars).
            GLES20.glViewport(0, 0, outputW, outputH)
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

            // Draw the source texture into the letterboxed sub-rect.
            GLES20.glViewport(rectX, rectY, rectW, rectH)
            GLES20.glUseProgram(program)

            vertexBuffer!!.position(0)
            GLES20.glVertexAttribPointer(aPositionLoc, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)
            GLES20.glEnableVertexAttribArray(aPositionLoc)

            texCoordBuffer!!.position(0)
            GLES20.glVertexAttribPointer(aTexCoordLoc, 2, GLES20.GL_FLOAT, false, 0, texCoordBuffer)
            GLES20.glEnableVertexAttribArray(aTexCoordLoc)

            GLES20.glUniformMatrix4fv(uSTMatrixLoc, 1, false, stMatrix, 0)

            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)

            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

            GLES20.glDisableVertexAttribArray(aPositionLoc)
            GLES20.glDisableVertexAttribArray(aTexCoordLoc)

            // PresentationTimeANDROID — without this the encoder's input
            // timestamps would all be 0 and the codec's bitrate controller
            // would treat every frame as instantaneous. Use the SurfaceTexture's
            // own timestamp (set by SurfaceFlinger when it queued the buffer).
            EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, st.timestamp)
            EGL14.eglSwapBuffers(eglDisplay, eglSurface)

            framesRendered++
            if (framesRendered % 300L == 1L) {
                AppLog.d(TAG, "rendered #$framesRendered " +
                    "src=${srcW}x${srcH} dst=${outputW}x${outputH} " +
                    "rect=${rectW}x${rectH}@($rectX,$rectY)")
            }
        } catch (t: Throwable) {
            if (running.get()) AppLog.w(TAG, "renderFrame failed", t)
        }
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        val h = handler
        val t = thread
        handler = null
        thread = null
        h?.post {
            try {
                surfaceTexture?.setOnFrameAvailableListener(null)
                inputSurface?.release()
                surfaceTexture?.release()
                if (textureId != 0) {
                    GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
                    textureId = 0
                }
                if (program != 0) {
                    GLES20.glDeleteProgram(program)
                    program = 0
                }
                if (eglSurface != EGL14.EGL_NO_SURFACE) {
                    EGL14.eglMakeCurrent(
                        eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT,
                    )
                    EGL14.eglDestroySurface(eglDisplay, eglSurface)
                    eglSurface = EGL14.EGL_NO_SURFACE
                }
                if (eglContext != EGL14.EGL_NO_CONTEXT) {
                    EGL14.eglDestroyContext(eglDisplay, eglContext)
                    eglContext = EGL14.EGL_NO_CONTEXT
                }
                if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                    EGL14.eglTerminate(eglDisplay)
                    eglDisplay = EGL14.EGL_NO_DISPLAY
                }
            } catch (e: Throwable) {
                AppLog.w(TAG, "stop cleanup threw", e)
            }
            inputSurface = null
        }
        t?.quitSafely()
    }

    private data class Quad(val w: Int, val h: Int, val x: Int, val y: Int)

    private fun buildProgram(vertexSrc: String, fragmentSrc: String): Int {
        val vs = compileShader(GLES20.GL_VERTEX_SHADER, vertexSrc)
        val fs = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSrc)
        val prog = GLES20.glCreateProgram()
        GLES20.glAttachShader(prog, vs)
        GLES20.glAttachShader(prog, fs)
        GLES20.glLinkProgram(prog)
        val linked = IntArray(1)
        GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, linked, 0)
        if (linked[0] == 0) {
            val log = GLES20.glGetProgramInfoLog(prog)
            GLES20.glDeleteProgram(prog)
            error("program link failed: $log")
        }
        GLES20.glDeleteShader(vs)
        GLES20.glDeleteShader(fs)
        return prog
    }

    private fun compileShader(type: Int, source: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, source)
        GLES20.glCompileShader(s)
        val compiled = IntArray(1)
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(s)
            GLES20.glDeleteShader(s)
            error("shader compile failed: $log\nSource:\n$source")
        }
        return s
    }

    companion object {
        private const val TAG = "RidePanel.Letterbox"

        // Full-NDC quad as TRIANGLE_STRIP.
        private val QUAD_VERTICES = floatArrayOf(
            -1f, -1f,
             1f, -1f,
            -1f,  1f,
             1f,  1f,
        )
        // Matching texture coordinates; transformed by SurfaceTexture's
        // STMatrix at draw time so the OES sampler reads the right region
        // regardless of the producer's orientation/crop.
        private val QUAD_TEX_COORDS = floatArrayOf(
            0f, 0f,
            1f, 0f,
            0f, 1f,
            1f, 1f,
        )

        private const val VERTEX_SHADER = """
            attribute vec4 aPosition;
            attribute vec4 aTexCoord;
            uniform mat4 uSTMatrix;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vTexCoord = (uSTMatrix * aTexCoord).xy;
            }
        """

        private const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES uTexture;
            varying vec2 vTexCoord;
            void main() {
                gl_FragColor = texture2D(uTexture, vTexCoord);
            }
        """
    }
}
