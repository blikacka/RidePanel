package cz.blikacka.ridepanel

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Display
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import java.nio.ByteBuffer

class MirrorService : Service() {

    private var projection: MediaProjection? = null
    private var encoder: ScreenEncoder? = null
    /** Created ONCE per session — Android 14+ forbids createVirtualDisplay
     *  twice on the same projection. The VD is sized to the **phone's
     *  native display** (e.g. 2410×1080); a [LetterboxingPipeline] sits
     *  between it and the encoder, downscaling + letterboxing into the
     *  head-unit's requested encoder dimensions (800×480). */
    private var virtualDisplay: VirtualDisplay? = null
    private var sessionDpi: Int = 320
    private var sessionSourceW: Int = 0
    private var sessionSourceH: Int = 0
    /** GLES letterbox stage between VD and encoder. Re-created on every
     *  encoder reconfigure because its EGL window surface is bound to
     *  the specific [ScreenEncoder.inputSurface]. */
    private var letterbox: LetterboxingPipeline? = null

    /** Drives the static "RidePanel connected" splash bitmap into the
     *  encoder before the user has accepted the MediaProjection dialog
     *  and a real VirtualDisplay-backed pipeline exists. Mutually
     *  exclusive with [letterbox] — only one source can write to the
     *  encoder's input surface at a time. */
    private var splash: SplashRenderer? = null

    /** True while the service has started without a MediaProjection
     *  token (splash-on-HU phase). Distinguished from
     *  [isActivelyMirroring] which is reserved for the post-projection
     *  full capture state. Mirrors into the companion's
     *  [isHeadUnitConnected] so UI code can check "are we paired with
     *  a HU?" regardless of which phase (splash vs full capture)
     *  the session is in. */
    @Volatile private var isSplashing: Boolean = false
        set(value) {
            field = value
            isHeadUnitConnected = value || isActivelyMirroring
        }

    /** Keeps the phone display "on" while mirror runs so MediaProjection
     *  doesn't go black when the user pockets the phone. The screen still
     *  goes to minimum brightness via MainActivity's window flag. */
    private var wakeLock: android.os.PowerManager.WakeLock? = null

    /** Most recent key-frame (with inline SPS/PPS), exposed for the test-send button. */
    @Volatile
    private var lastKeyFrame: ByteArray? = null
    private val pxc by lazy {
        PxcServer(
            context = applicationContext,
            onClientConnected = { encoder?.requestKeyFrame() },
        )
    }

    /** Listens on TCP 10920/10921/10710/10711 — the actual mirror data path
     *  the head-unit dials in to once CLIENT_INFO succeeds. See
     *  `m0.java::I0` and `m0.java::K0` in the decompiled APK.
     *
     *  `onClientConnected` already requests a sync-frame for every new data
     *  socket, so `onCaptureRequest` (first REQ_NEXT) is a no-op — earlier
     *  builds requested two key-frames ~13 ms apart at startup, burning ~50 KB
     *  twice on Baseline for no decoder benefit. */
    private val mirrorPorts by lazy {
        MirrorPortsServer(
            onClientConnected = { encoder?.requestKeyFrame() },
            onConfigCaptureRequest = { cfg -> reconfigureEncoder(cfg) },
            onCaptureRequest = { /* deduped — onClientConnected already syncs */ },
        )
    }

    /** Cached config request — used when MediaProjection hasn't finished
     *  setting up yet but head-unit already sent its dimensions. */
    @Volatile private var pendingConfig: MirrorPortsServer.ReqConfigCaptureView? = null

    /**
     * Called when the head-unit sends `REQ_RV_CONFIG_CAPTURE` describing the
     * resolution / fps / bitrate it can decode. **Lazy encoder model:**
     * encoder is built here, not in [startMirror]. The VirtualDisplay was
     * created up-front with no surface so we can hand a freshly-built
     * encoder its surface on demand. Skipping reconfig when params already
     * match avoids the MediaCodec stop/start race that earlier builds hit
     * (`releaseOutputBuffer() is valid only at Executing states`) and
     * eliminates the SPS/PPS mid-stream change the head-unit decoder used
     * to see when we downgraded bitrate.
     *
     * Android 14+ allows `createVirtualDisplay` only once per token —
     * subsequent param changes go through `vd.surface = newSurface` and
     * `vd.resize(...)`. Mirrors `MediaProjectionKeeper.d()`.
     */
    @Synchronized
    private fun reconfigureEncoder(cfg: MirrorPortsServer.ReqConfigCaptureView) {
        val w = cfg.deviceWidth and 0xfff0     // 16-aligned for hw codecs
        val h = cfg.deviceHeight and 0xfff0
        val fps = if (cfg.wantFps == 0) 30 else cfg.wantFps
        // Use the head-unit's requested bitrate verbatim. A previous build
        // floored this at 6 Mbps for "quality headroom", but Wi-Fi P2P
        // packet loss at sustained 6 Mbps on the motorcycle SSDQ01 link
        // produced visible fragmentation (broken P-frame chains) on
        // rapidly-changing content. Honouring the HU's advertised 4 Mbps
        // ceiling — combined with the shorter 2 s I-frame interval below —
        // gives a stream that recovers from drops within ~2 s instead
        // of smearing for the full 5 s GOP.
        val bitRate = if (cfg.bitRate == 0) 4_000_000 else cfg.bitRate
        pendingConfig = cfg

        val existing = encoder
        if (existing != null && currentEncW == w && currentEncH == h &&
            currentEncFps == fps && currentEncBitRate == bitRate) {
            AppLog.i(TAG, "reconfigureEncoder: params unchanged " +
                "(${w}x${h} @${fps}fps ${bitRate}bps) — skipping rebuild, " +
                "requesting key-frame instead")
            existing.requestKeyFrame()
            return
        }

        AppLog.i(TAG, "reconfigureEncoder ${w}x${h} @${fps}fps ${bitRate / 1000}kbps " +
            "(was ${currentEncW}x${currentEncH} @${currentEncFps}fps ${currentEncBitRate / 1000}kbps), " +
            "mode=${if (virtualDisplay != null) "mirror" else "splash"}")

        // Tear down old source first (pipeline / splash), then old encoder.
        // Releasing the encoder before its consumer can leave the consumer
        // writing to a freed buffer queue.
        virtualDisplay?.let {
            try { it.surface = null } catch (t: Throwable) { AppLog.w(TAG, "vd.setSurface(null) threw", t) }
        }
        letterbox?.stop()
        letterbox = null
        splash?.stop()
        splash = null
        encoder?.stop()
        encoder = null
        mirrorPorts.resetPendingFrame()
        framesEncoded.set(0L)
        AppLog.d(TAG, "encoder reset — frame counter cleared, pending frame dropped")

        val newEnc = ScreenEncoder(
            width = w,
            height = h,
            frameRate = fps,
            bitRate = bitRate,
            onFrame = ::onEncodedFrame,
        )
        newEnc.start()
        val encSurface = newEnc.inputSurface ?: run {
            AppLog.e(TAG, "encoder has no input surface after start()")
            newEnc.stop()
            return
        }

        val vd = virtualDisplay
        if (vd != null) {
            // Mirror mode: GLES letterbox between VD and encoder.
            val pipe = LetterboxingPipeline(
                encoderSurface = encSurface,
                outputW = w,
                outputH = h,
                initialSourceW = sessionSourceW.takeIf { it > 0 } ?: w,
                initialSourceH = sessionSourceH.takeIf { it > 0 } ?: h,
            )
            if (!pipe.start()) {
                AppLog.e(TAG, "letterbox pipeline failed to start — falling back to direct VD→encoder")
                try { vd.surface = encSurface } catch (t: Throwable) {
                    AppLog.e(TAG, "fallback vd.setSurface threw", t)
                }
            } else {
                try { vd.surface = pipe.inputSurface } catch (t: Throwable) {
                    AppLog.e(TAG, "vd.setSurface(letterbox.inputSurface) threw", t)
                }
                letterbox = pipe
            }
        } else {
            // Splash mode: no VirtualDisplay, no GLES. Just push the
            // pre-rendered bitmap to the encoder's input surface at a
            // slow cadence — enough to keep the head-unit's decoder
            // from declaring the stream stalled while the user reads
            // the dashboard before tapping Start/Maps.
            val sr = SplashRenderer(
                context = applicationContext,
                encoderSurface = encSurface,
                outputW = w,
                outputH = h,
                splashResId = R.drawable.splash_connected,
            )
            sr.start()
            splash = sr
            AppLog.i(TAG, "splash renderer started for ${w}x${h}")
        }
        encoder = newEnc
        currentEncW = w
        currentEncH = h
        currentEncFps = fps
        currentEncBitRate = bitRate
        AppLog.i(TAG, "encoder built: ${w}x$h @${fps}fps ${bitRate}bps " +
            "(letterbox=${letterbox != null}, splash=${splash != null}, " +
            "source=${sessionSourceW}x${sessionSourceH})")
    }

    /** Tracking for [reconfigureEncoder]'s "skip if unchanged" check. */
    @Volatile private var currentEncW: Int = 0
    @Volatile private var currentEncH: Int = 0
    @Volatile private var currentEncFps: Int = 0
    @Volatile private var currentEncBitRate: Int = 0

    /** Bridges DisplayManager's [DisplayManager.DisplayListener] callback
     *  (which fires from the main thread) into the same display-size
     *  pipeline that handles MediaProjection's onCapturedContentResize. */
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    /**
     * Backup watcher for default-display rotation. `MediaProjection`'s
     * `onCapturedContentResize` is the canonical trigger — it fires once
     * SurfaceFlinger has reconfigured the projection target — but its
     * timing varies by OEM/Android version and on some devices it lags
     * a frame or two behind the actual rotation, producing a brief
     * "wrong-aspect" buffer that ends up encoded and shipped to the HU.
     * DisplayListener observes the system Display layer directly and
     * normally fires *first*, so we can call [applyDisplaySize] before
     * the lagged buffer reaches our pipeline.
     *
     * Listener is registered in [startMirror], unregistered in [stopMirror].
     */
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) { /* no-op */ }
        override fun onDisplayRemoved(displayId: Int) { /* no-op */ }
        override fun onDisplayChanged(displayId: Int) {
            if (displayId != Display.DEFAULT_DISPLAY) return
            val (w, h, _) = displaySpec()
            val rot = currentDisplayRotation()
            AppLog.d(TAG, "DisplayListener.onDisplayChanged " +
                "default display=${w}x${h} rotation=${rot.name}")
            applyDisplaySize(w, h, source = "DisplayListener")
        }
    }

    /**
     * Central handler for "the source display might have a new size."
     * Both [MediaProjection.Callback.onCapturedContentResize] and the
     * [displayListener] funnel here.
     *
     * Two-stage rule:
     *  • Same width+height as current → no-op (the common dedupe case).
     *  • Different size → DEBOUNCE for 250 ms before propagating.
     *
     * The debounce matters because rapid rotations during app-switching
     * (e.g. Maps → Home portrait → Maps landscape happening within
     * ~300 ms) used to fire 3–4 vd.resize calls back-to-back. Each
     * resize tears down the SurfaceTexture buffer queue mid-frame,
     * the GL pipeline draws a stale buffer at the new viewport, and
     * the encoded output shows the tile-grid fragmentation we saw on
     * the HU. Coalescing into a single resize once the user has
     * stopped rotating eliminates that race.
     *
     * The `source` arg only matters for logging — letting us tell
     * which callback fired first when reading logs.
     */
    @Synchronized
    private fun applyDisplaySize(width: Int, height: Int, source: String) {
        if (width <= 0 || height <= 0) return
        if (width == sessionSourceW && height == sessionSourceH) return
        pendingDisplaySize = Pair(width, height)
        pendingDisplaySizeSource = source
        mainHandler.removeCallbacks(displayResizeRunnable)
        mainHandler.postDelayed(displayResizeRunnable, 250L)
    }

    @Volatile private var pendingDisplaySize: Pair<Int, Int>? = null
    @Volatile private var pendingDisplaySizeSource: String = "?"
    private val displayResizeRunnable = Runnable {
        val (w, h) = pendingDisplaySize ?: return@Runnable
        pendingDisplaySize = null
        val src = pendingDisplaySizeSource
        if (w == sessionSourceW && h == sessionSourceH) return@Runnable
        AppLog.i(TAG, "display size change ${sessionSourceW}x${sessionSourceH} → " +
            "${w}x${h} (debounced from $src, rotation=${currentDisplayRotation().name})")
        sessionSourceW = w
        sessionSourceH = h
        try {
            virtualDisplay?.resize(w, h, sessionDpi)
        } catch (t: Throwable) {
            AppLog.w(TAG, "vd.resize($w,$h) failed", t)
        }
        letterbox?.setSourceSize(w, h)
        encoder?.requestKeyFrame()
    }

    /** Returns a human-readable name for the default display's current rotation. */
    private fun currentDisplayRotation(): DisplayRotation {
        val rot = try {
            val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            @Suppress("DEPRECATION") wm.defaultDisplay.rotation
        } catch (_: Throwable) { -1 }
        return when (rot) {
            android.view.Surface.ROTATION_0 -> DisplayRotation.PORTRAIT_0
            android.view.Surface.ROTATION_90 -> DisplayRotation.LANDSCAPE_90
            android.view.Surface.ROTATION_180 -> DisplayRotation.PORTRAIT_180
            android.view.Surface.ROTATION_270 -> DisplayRotation.LANDSCAPE_270
            else -> DisplayRotation.UNKNOWN
        }
    }

    private enum class DisplayRotation { PORTRAIT_0, LANDSCAPE_90, PORTRAIT_180, LANDSCAPE_270, UNKNOWN }

    /**
     * Browses for the head-unit's `_EasyConn._tcp.` advertisement; on
     * resolution we fire [PxcProbe] which tells the head-unit "phone is
     * alive — please dial back". Dialled connection lands in [pxc].
     */
    private val mdns by lazy {
        MdnsDiscoverer(applicationContext) { found -> onMdnsResolved(found) }
    }
    private val probeExecutor =
        java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "PxcProbe").apply { isDaemon = true }
        }

    private fun onMdnsResolved(found: MdnsDiscoverer.Found) {
        AppLog.i("Service",
            "head-unit @ ${found.host.hostAddress}:${found.port} (huid=${found.huid}, channel=${found.channel}) — probing")
        probeExecutor.submit {
            val res = PxcProbe.probe(found.host, found.port, applicationContext.packageName)
            AppLog.i("Service", "probe result: ${res.message}")
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        startInForeground(withMediaProjectionType = false)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val data: Intent? = intent.getParcelableExtra(EXTRA_DATA)
                if (resultCode != 0 && data != null) startMirror(resultCode, data)
            }
            ACTION_START_SPLASH -> startSplashOnly()
            ACTION_STOP -> stopMirror()
        }
        return START_NOT_STICKY
    }

    /**
     * Start the service in **splash-only** mode: PXC + Mirror sockets
     * listen, encoder is built once the head-unit sends
     * REQ_RV_CONFIG_CAPTURE, and the encoder is fed a static "connected"
     * bitmap by [SplashRenderer]. No [MediaProjection] is requested —
     * the user hasn't tapped Start/Maps yet, so we cannot legally
     * capture the screen.
     *
     * This is what makes the splash visible on the head-unit between
     * `Wi-Fi Direct connected` and the user actually choosing to share.
     * Upgrading to a real mirror is a separate ACTION_START call with
     * the projection token — see [startMirror].
     *
     * Note: `isActivelyMirroring` stays **false** in splash mode. The
     * UI uses that flag to decide whether to skip the projection
     * request when the user taps Maps; in splash we DO still want the
     * request (otherwise we'd never upgrade to real capture).
     * [isSplashing] is the separate "are we running with no projection
     * token" flag.
     */
    private fun startSplashOnly() {
        if (isSplashing) {
            AppLog.d(TAG, "startSplashOnly: already running, ignoring")
            return
        }
        AppLog.i(TAG, "starting in splash-only mode (no projection)")
        isSplashing = true
        pxc.start()
        mirrorPorts.start()
        mdns.start()
        acquireWakeLock()
        // Encoder + splash renderer get wired in reconfigureEncoder()
        // once the head-unit sends REQ_RV_CONFIG_CAPTURE.
        val (phoneW, phoneH, dpi) = displaySpec()
        sessionDpi = dpi
        sessionSourceW = phoneW
        sessionSourceH = phoneH
    }

    private fun startMirror(resultCode: Int, data: Intent) {
        // Android 14+ requires the FGS to ALREADY be of type
        // FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION before getMediaProjection()
        // is allowed to materialise the token. We came up as dataSync in
        // onCreate (no token yet), so re-promote here BEFORE the call.
        startInForeground(withMediaProjectionType = true)
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val proj = mpm.getMediaProjection(resultCode, data) ?: run {
            AppLog.e(TAG, "MediaProjection unavailable")
            isActivelyMirroring = false
            stopSelf()
            return
        }
        // Upgrade from splash mode → real mirror mode. Stop the bitmap
        // pump so reconfigureEncoder can rebuild the encoder + plug a
        // LetterboxingPipeline in its place. Sockets, mDNS, wake-lock,
        // PXC and Mirror servers keep running — only the *source*
        // feeding the encoder is swapped.
        if (isSplashing) {
            AppLog.i(TAG, "upgrading from splash mode to full mirror")
            splash?.stop()
            splash = null
            encoder?.stop()
            encoder = null
            currentEncW = 0; currentEncH = 0
            currentEncFps = 0; currentEncBitRate = 0
            isSplashing = false
        }
        isActivelyMirroring = true
        proj.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                AppLog.w(TAG, "MediaProjection.onStop() — projection revoked. " +
                    "Likely cause: head-unit closed both sockets ⇒ stopMirror() ⇒ projection.stop(). " +
                    "If it fired BEFORE stopMirror(), the system itself revoked us (user, config change, timeout).")
                stopMirror()
            }
            override fun onCapturedContentVisibilityChanged(isVisible: Boolean) {
                AppLog.d(TAG, "MediaProjection visibility=$isVisible")
            }
            override fun onCapturedContentResize(width: Int, height: Int) {
                applyDisplaySize(width, height, source = "MediaProjection")
            }
        }, null)
        projection = proj

        val (phoneW, phoneH, dpi) = displaySpec()
        sessionDpi = dpi
        sessionSourceW = phoneW
        sessionSourceH = phoneH
        val initialRotation = currentDisplayRotation()
        AppLog.i(TAG, "startMirror — initial display ${phoneW}x${phoneH} " +
            "@${dpi}dpi, rotation=${initialRotation.name}")

        // Subscribe to default-display config changes as a defensive
        // backup for MediaProjection.onCapturedContentResize. Posted on
        // the main thread so the callback runs on the same Looper that
        // owns MainActivity's window/orientation observers — keeps
        // ordering predictable wrt activity lifecycle events.
        val dm = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        dm.registerDisplayListener(displayListener, mainHandler)

        pxc.start()
        mirrorPorts.start()
        mdns.start()
        acquireWakeLock()

        // VirtualDisplay sized to the phone's NATIVE display, not the
        // head-unit's 800×480. The encoder still emits 800×480 — but
        // a [LetterboxingPipeline] GLES stage sits in between, sampling
        // the high-res VD output through a SurfaceTexture and drawing
        // an aspect-fit (letterboxed) version into the encoder's input
        // surface. This avoids SurfaceFlinger's default fill-stretch
        // mode that was squeezing the phone's 2410×1080 (≈2.23:1) into
        // 800×480 (≈1.67:1) and producing visible vertical compression.
        //
        // Encoder construction is still deferred to REQ_RV_CONFIG_CAPTURE
        // so the codec is built with the head-unit's exact requested
        // dimensions/bitrate.
        virtualDisplay = proj.createVirtualDisplay(
            "RidePanel",
            phoneW,
            phoneH,
            dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            null,            // surface = null until encoder + pipeline are built
            null,
            null,
        )
        AppLog.i(TAG, "Mirror started — VirtualDisplay ${phoneW}x$phoneH " +
            "@${dpi}dpi (phone native, surface=null), encoder + letterbox " +
            "deferred to REQ_RV_CONFIG_CAPTURE")

        // If the head-unit's config arrived before we got the projection token
        // (race condition), apply it now.
        pendingConfig?.let { reconfigureEncoder(it) }
    }

    private fun onEncodedFrame(buf: ByteBuffer, info: MediaCodec.BufferInfo) {
        val isKey = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
        val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
        val payload = ByteArray(info.size).also { buf.get(it) }
        if (isKey) lastKeyFrame = payload
        val n = framesEncoded.incrementAndGet()
        val kind = when {
            isConfig -> "CFG"
            isKey -> "IDR"
            else -> "P  "
        }
        if (n <= 3 || n % 30 == 0L || isConfig) {
            AppLog.i(TAG, "encoder: frame #$n $kind size=${info.size}B " +
                "pts=${info.presentationTimeUs}us hasClient=${mirrorPorts.hasDataClient()}")
        }
        // CODEC_CONFIG (SPS+PPS) is emitted exactly ONCE by the codec, at
        // start. Cache it forever — head-unit data clients that connect AFTER
        // this point still need it as their decoder-init frame, otherwise
        // every subsequent IDR is undecodable (no parameter sets known) and
        // the head-unit tears the socket down ~5 s later.
        if (isConfig) {
            mirrorPorts.setCodecConfig(payload)
            // Don't ship CFG as a normal frame — submitVideoFrame's IDR-gate
            // already handles "send CFG before the first frame" per channel.
            return
        }
        // Video frames go to BOTH paths:
        //   1. dedicated mirror data ports (Protocol.PORT_DATA_NEW = 10920)
        //   2. PXC data channel (cmd=0x20000 on TCP 10922)
        // Earlier builds only fed (1) and the head-unit always opened (2)
        // too — sitting on a starved data channel may be one reason the
        // head-unit decides the phone session is broken after ~5 s.
        if (mirrorPorts.hasDataClient()) {
            mirrorPorts.submitVideoFrame(payload, isKey)
        }
        if (pxc.hasClient()) {
            pxc.submitVideoFrame(payload)
        }
    }

    /** Sequence counter for [onEncodedFrame] — used only to throttle the per-frame log. */
    private val framesEncoded = java.util.concurrent.atomic.AtomicLong(0L)

    /** Triggered by Activity's "Test send" button via ACTION_TEST_SEND. */
    private fun handleTestSend() {
        if (!pxc.hasClient()) {
            AppLog.w(TAG, "test-send: no head-unit connected")
            return
        }
        encoder?.requestKeyFrame()
        AppLog.i(TAG, "test-send: requested key-frame; ${pxc.clientCount()} channel(s)")
        // Give the encoder a moment to produce a fresh key-frame, then ship.
        Thread {
            Thread.sleep(300)
            val frame = lastKeyFrame
            if (frame == null) {
                AppLog.w(TAG, "test-send: no key-frame cached yet")
                return@Thread
            }
            val msg = pxc.testSendFrame(frame)
            AppLog.i(TAG, "test-send: $msg")
        }.start()
    }

    private fun displaySpec(): Triple<Int, Int, Int> {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(metrics)
        // Round down to even; some encoders dislike odd dimensions.
        val w = metrics.widthPixels and 1.inv()
        val h = metrics.heightPixels and 1.inv()
        return Triple(w, h, metrics.densityDpi)
    }

    private fun stopMirror() {
        // Clear all live-session flags immediately. Subsequent UI taps
        // (e.g. user pressing "Open Maps" right after head-unit dropped)
        // see "not running" and correctly request a fresh projection
        // token instead of opening Maps over a dead mirror.
        isActivelyMirroring = false
        isSplashing = false
        try {
            val dm = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            dm.unregisterDisplayListener(displayListener)
        } catch (_: Throwable) {
            // unregisterDisplayListener throws IllegalArgumentException if
            // listener was never registered — fine, we may be in a cleanup
            // path before startMirror() got that far.
        }
        releaseWakeLock()
        // Tear-down order matters:
        //   1. detach VD from any surface so producer (SurfaceFlinger) stops
        //      writing into a buffer queue we're about to release
        //   2. stop the letterbox pipeline — its EGL window surface is bound
        //      to the encoder's input surface; releasing the encoder first
        //      would leave GL calls writing to a freed buffer queue
        //   3. stop the encoder (releases its input surface)
        //   4. release VD + projection
        try { virtualDisplay?.surface = null } catch (_: Throwable) {}
        letterbox?.stop(); letterbox = null
        splash?.stop(); splash = null
        encoder?.stop(); encoder = null
        try { virtualDisplay?.release() } catch (_: Throwable) {}
        virtualDisplay = null
        try { projection?.stop() } catch (_: Throwable) {}
        projection = null
        pendingConfig = null
        sessionSourceW = 0
        sessionSourceH = 0
        mdns.stop()
        mirrorPorts.stop()
        pxc.stop()
        stopSelf()
    }

    /**
     * Holds a `SCREEN_DIM_WAKE_LOCK` that keeps the display on (at minimum
     * brightness, see [MainActivity.enterPocketMode]) so MediaProjection
     * doesn't go black when the user pockets the phone. The flag is
     * deprecated since API 17 but still functional and documented as the
     * supported mechanism for "show something dim and keep capturing".
     */
    private fun acquireWakeLock() {
        if (wakeLock != null) return
        val pm = getSystemService(POWER_SERVICE) as android.os.PowerManager
        @Suppress("DEPRECATION")
        val lock = pm.newWakeLock(
            android.os.PowerManager.SCREEN_DIM_WAKE_LOCK or
                android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "RidePanel::PocketMode",
        )
        try {
            lock.setReferenceCounted(false)
            lock.acquire(10 * 60 * 60 * 1000L /* 10 hours max */)
            wakeLock = lock
            AppLog.i(TAG, "wake lock acquired (SCREEN_DIM)")
        } catch (t: Throwable) {
            AppLog.w(TAG, "wake lock acquire failed", t)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            try { if (it.isHeld) it.release() } catch (_: Throwable) {}
            AppLog.i(TAG, "wake lock released")
        }
        wakeLock = null
    }

    /** Public entry point for the test-send button — safe to call any time. */
    fun triggerTestSend() {
        handleTestSend()
    }

    override fun onDestroy() {
        instance = null
        stopMirror()
        super.onDestroy()
    }

    /**
     * Promote the service to foreground. By default we use the generic
     * `dataSync` type because `onCreate` runs *before* Activity hands us a
     * MediaProjection token, and Android 14+ rejects FGS type
     * `mediaProjection` without that token (logs as `SecurityException:
     * Starting FGS with type mediaProjection ... requires Media projection
     * screen capture permission`). After [startMirror] obtains the token we
     * re-promote with `mediaProjection`.
     */
    private fun startInForeground(withMediaProjectionType: Boolean) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(CHANNEL_ID, "Mirror", NotificationManager.IMPORTANCE_LOW)
            nm.createNotificationChannel(ch)
        }
        val notif: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("RidePanel")
            .setContentText("Streaming screen on TCP ${PxcFrame.PORT}")
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            val type = if (withMediaProjectionType) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            }
            startForeground(NOTIF_ID, notif, type)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    companion object {
        /** Live MirrorService instance, or null when no mirror session is active. */
        @Volatile var instance: MirrorService? = null

        /**
         * True only while a session is *actively* projecting (token alive,
         * encoder running). `instance` alone isn't enough: there's a window
         * between [stopMirror] calling `stopSelf()` and Android delivering
         * `onDestroy()` during which `instance != null` but nothing is being
         * streamed — checking that bool from UI would incorrectly skip the
         * "request new projection" branch and leave the user with a
         * locked-landscape Maps but no mirror feed.
         */
        @Volatile var isActivelyMirroring: Boolean = false
            internal set(value) {
                field = value
                // Keep the UI-facing "connected to a head-unit" flag in
                // sync. Once full mirroring is on, the splash phase is
                // by definition over, so we just OR them.
                isHeadUnitConnected = value || isHeadUnitConnected
                if (!value) {
                    // Going false means stopMirror cleanup — also clear
                    // the broader flag unless splash explicitly stays on
                    // (which it doesn't in the current flow).
                    isHeadUnitConnected = false
                }
            }

        /**
         * True whenever the service has any kind of live session with the
         * head-unit — splash phase (`isSplashing`) or full capture
         * (`isActivelyMirroring`). UI code uses this to drive the
         * "Connect" → "Connected" label on saved devices; the latter
         * flag alone would mis-report "not connected" while the splash
         * is on the head-unit but the user hasn't tapped Start/Maps yet.
         */
        @Volatile var isHeadUnitConnected: Boolean = false
            internal set

        const val ACTION_START = "cz.blikacka.ridepanel.START"
        const val ACTION_START_SPLASH = "cz.blikacka.ridepanel.START_SPLASH"
        const val ACTION_STOP = "cz.blikacka.ridepanel.STOP"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_DATA = "data"

        private const val TAG = "RidePanel.Service"
        private const val CHANNEL_ID = "ridepanel"
        private const val NOTIF_ID = 1
    }
}
