package cz.blikacka.ridepanel

import android.Manifest
import android.app.AlertDialog
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.text.format.Formatter
import android.view.Surface
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pInfo
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import java.net.NetworkInterface

class MainActivity : ComponentActivity() {

    private lateinit var statusView: TextView
    private lateinit var qrView: TextView
    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var knownLabel: TextView
    private lateinit var knownList: LinearLayout
    private lateinit var knownDevices: KnownDevices

    private lateinit var tabContentHome: View
    private lateinit var tabContentLogs: View
    private lateinit var tabContentFaq: View
    private lateinit var tabBtnHome: ImageButton
    private lateinit var tabBtnLogs: ImageButton
    private lateinit var tabBtnFaq: ImageButton

    private enum class Tab { HOME, LOGS, FAQ }
    @Volatile private var currentTab: Tab = Tab.HOME
    private var pairer: BlePxcPairer? = null
    private var wifiDirect: WifiDirectConnector? = null
    /** Long-lived BLE GATT connection started after Wi-Fi handshake is up.
     *  Lives independently from [pairer] (which is for first-time QR pairing
     *  only). The head-unit pushes COMMAND_SYNC_TIME (0x01) / QUERY_TIME
     *  (0x55) over BLE GATT to set its dashboard clock — without this
     *  channel the on-screen clock stays at 00:00. */
    private var bleTimeServer: BlePxcPairer? = null
    /** Last QR result we acted on — captured so the [wifiDirectListener] /
     *  [apCallback] success paths can persist the credentials into
     *  [knownDevices] only after the head-unit actually completed pairing. */
    @Volatile private var pendingKnownEntry: KnownDevices.Entry? = null

    /** Set when the user pressed the Maps button while mirror wasn't yet
     *  running: after they accept the system projection dialog, we chain
     *  into the landscape lock + Maps launch. Reset on every projection
     *  result (success or cancel) so a later Start-mirror button press
     *  doesn't accidentally fire Maps. */
    @Volatile private var pendingMapsLaunch: Boolean = false

    /** MAC of the head-unit we're currently mirroring to, or null when no
     *  saved entry is active. Drives the "Connect" → "Connected" label
     *  toggle in [renderKnownDevices] so the UI doesn't tell the user
     *  to connect to a device they're already mirroring on. */
    @Volatile private var currentlyConnectedMac: String? = null

    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val mapsWasPending = pendingMapsLaunch
        pendingMapsLaunch = false
        if (result.resultCode == RESULT_OK && result.data != null) {
            val intent = Intent(this, MirrorService::class.java).apply {
                action = MirrorService.ACTION_START
                putExtra(MirrorService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(MirrorService.EXTRA_DATA, result.data)
            }
            ContextCompat.startForegroundService(this, intent)
            enterPocketMode()
            // Mirror is now live → put MainActivity in landscape too. Without
            // this the encoder starts capturing a portrait phone display and
            // the head-unit gets a portrait-letterboxed image until the rider
            // physically rotates the device. The system-wide WRITE_SETTINGS
            // lock (set on the Maps path) is what keeps OTHER apps in
            // landscape; this requestedOrientation only governs us.
            applyMirrorOrientation(mirroring = true)
            if (mapsWasPending) {
                // System landscape lock was already engaged in
                // openGoogleMapsLandscape() so it had time to propagate
                // through SettingsObserver/WindowManager before Maps is
                // about to be drawn. Just fire the intent now.
                launchMapsIntent()
            }
        } else if (mapsWasPending) {
            // User cancelled the projection dialog — they don't actually
            // want to start mirroring, so the optimistic landscape lock
            // we engaged in openGoogleMapsLandscape would otherwise leave
            // the phone stuck in landscape. Undo it.
            AppLog.i("Maps", "projection cancelled — restoring system rotation")
            restoreSystemRotation()
        }
    }

    /**
     * "Pocket mode": dim the screen to (almost) zero and keep it on so
     * MediaProjection capture keeps producing frames while the user puts
     * the phone in their pocket. The MirrorService also holds a
     * SCREEN_DIM_WAKE_LOCK as a backup. Reverted in [exitPocketMode].
     */
    @Volatile private var savedBrightness: Float = -1f

    private fun enterPocketMode() {
        val w = window
        savedBrightness = w.attributes.screenBrightness
        val lp = w.attributes
        lp.screenBrightness = 0.01f          // ~1% — visible only in dark
        w.attributes = lp
        w.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        AppLog.i("UI", "pocket mode: brightness 1%, KEEP_SCREEN_ON, sensorLandscape")
    }

    private fun exitPocketMode() {
        val w = window
        val lp = w.attributes
        lp.screenBrightness = if (savedBrightness >= 0f) savedBrightness else
            android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        w.attributes = lp
        w.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        AppLog.i("UI", "pocket mode disabled, brightness restored")
    }

    private val notifPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* ignored — service still works without it */ }

    private val blePermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.values.all { it }) startPairing()
        else updatePairStatus("BLE permissions denied")
    }

    /** Separate launcher for the time-sync flow — when granted, retries
     *  [startBleTimeServer] which is fired post-Wi-Fi-connect. */
    private val bleTimeServerPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.values.all { it }) {
            AppLog.i("UI", "BLE perms granted; starting time-sync")
            startBleTimeServer()
        } else {
            AppLog.w("UI", "BLE perms denied — head-unit clock won't sync")
        }
    }

    private val qrLauncher = registerForActivityResult(ScanContract()) { result ->
        if (result.contents == null) {
            updateQrStatus("Scan canceled")
        } else {
            handleQrResult(result.contents)
        }
    }

    private val qrPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.values.all { it }) launchQrScanner()
        else updateQrStatus("Camera/Wi-Fi permissions denied")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Surface any uncaught exception in the in-app log so we don't lose
        // the stack trace to the system "app crashed" dialog.
        val prior = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            AppLog.e("Crash", "uncaught on ${thread.name}", throwable)
            // Also dump the full stack — message alone often hides the cause.
            val sw = java.io.StringWriter()
            throwable.printStackTrace(java.io.PrintWriter(sw))
            AppLog.e("Crash", sw.toString())
            try {
                runOnUiThread {
                    runCatching {
                        updateQrStatus(getString(R.string.crash_prefix,
                            throwable.javaClass.simpleName, throwable.message ?: "?"))
                    }
                }
            } catch (_: Throwable) {}
            prior?.uncaughtException(thread, throwable)
        }
        setContentView(R.layout.activity_main)

        // Edge-to-edge handling: with `targetSdk >= 35` (Android 15+)
        // the system enforces edge-to-edge windows by default, so content
        // would otherwise be drawn under the status bar and the
        // gesture-nav bar. Consume the system-bar + cutout insets as
        // padding on the root LinearLayout so titles, tab content, and
        // the bottom tab strip stay inside the safe area. We do this
        // programmatically instead of relying solely on
        // `android:fitsSystemWindows` because the latter is silently
        // ignored on some OEM ROMs that ship aggressive edge-to-edge
        // defaults.
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root_layout)) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }

        statusView = findViewById(R.id.tv_status)
        qrView = findViewById(R.id.tv_qr_status)
        logView = findViewById(R.id.tv_log)
        logScroll = findViewById(R.id.sv_log)
        knownLabel = findViewById(R.id.tv_known_label)
        knownList = findViewById(R.id.ll_known_devices)
        knownDevices = KnownDevices(this)
        statusView.text = getString(
            R.string.status_listening,
            localAddresses().joinToString(", "),
            PxcFrame.PORT,
        )
        renderKnownDevices()

        // Tab pages + bottom tab buttons.
        tabContentHome = findViewById(R.id.tab_content_home)
        tabContentLogs = findViewById(R.id.tab_content_logs)
        tabContentFaq = findViewById(R.id.tab_content_faq)
        tabBtnHome = findViewById(R.id.tab_btn_home)
        tabBtnLogs = findViewById(R.id.tab_btn_logs)
        tabBtnFaq = findViewById(R.id.tab_btn_faq)
        tabBtnHome.setOnClickListener { showTab(Tab.HOME) }
        tabBtnLogs.setOnClickListener { showTab(Tab.LOGS) }
        tabBtnFaq.setOnClickListener { showTab(Tab.FAQ) }
        findViewById<TextView>(R.id.tv_faq).text = getString(R.string.faq_body).trim()
        findViewById<Button>(R.id.btn_buy_coffee).setOnClickListener {
            // PayPal hosted-button donate link. Opens the system browser
            // (or whatever app handles https://) — no in-app webview so
            // there's nothing for us to compromise even if PayPal redirects.
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(DONATE_URL)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
            } catch (t: Throwable) {
                AppLog.e("UI", "buy-coffee intent failed", t)
            }
        }
        showTab(Tab.HOME)

        findViewById<Button>(R.id.btn_start).setOnClickListener { startMirror() }
        findViewById<Button>(R.id.btn_maps).setOnClickListener { openGoogleMapsLandscape() }
        findViewById<Button>(R.id.btn_stop).setOnClickListener {
            startService(Intent(this, MirrorService::class.java).setAction(MirrorService.ACTION_STOP))
            exitPocketMode()
            restoreSystemRotation()
            // Mirror is going away — return MainActivity to portrait so
            // the user lands back on the natural tab layout when they
            // re-open the app.
            applyMirrorOrientation(mirroring = false)
        }
        findViewById<Button>(R.id.btn_qr).setOnClickListener { onQrClicked() }
        findViewById<Button>(R.id.btn_clear_log).setOnClickListener { AppLog.clear() }

        AppLog.setListener { lines ->
            runOnUiThread {
                logView.text = lines.joinToString("\n")
                logScroll.post { logScroll.fullScroll(android.view.View.FOCUS_DOWN) }
            }
        }
        AppLog.i("UI", "RidePanel started; PXC port=${PxcFrame.PORT}, IP=${localAddresses()}")
        // Smoke-test the QR parser at boot so any regression is visible up-front.
        val sampleQr = "http://www.carbit.com.cn/down6/645/644/_ylqxos?modelid=37501&sn=test" +
            "&action=8&ssid=demo-ssid&pwd=12345678&auth=WPA2&mac=aa:bb:cc:dd:ee:ff&name=demo-ssid"
        AppLog.i("Qr", "self-test parse(sample) -> ${CarbitQr.parse(sampleQr)}")

        if (Build.VERSION.SDK_INT >= 33 &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        // Defensive recovery: if a previous session left rotation locked
        // (HU dropped sockets → MirrorService.onStop without us touching
        // restoreSystemRotation, or the app was killed mid-mirror), undo
        // it on the next launch when no mirror is active. Keeps the user
        // from being stuck in forced landscape after a crash.
        if (!MirrorService.isActivelyMirroring) {
            val sp = getSharedPreferences("ridepanel", Context.MODE_PRIVATE)
            if (sp.getBoolean("rotation_locked", false)) {
                AppLog.w("Maps", "previous session left rotation locked — restoring")
                restoreSystemRotation()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Keep MainActivity's orientation in sync with the live mirror
        // state. If the head-unit dropped while we were backgrounded,
        // `isActivelyMirroring` is now false → flip back to portrait so
        // the tab UI shows in its intended layout. If mirror is still
        // alive (user just switched back from Maps), stay in landscape.
        applyMirrorOrientation(MirrorService.isActivelyMirroring)
    }

    override fun onDestroy() {
        AppLog.setListener(null)
        wifiDirect?.stop()
        wifiDirect = null
        stopBleTimeServer()
        // If mirror is no longer running (user pressed stop, or HU dropped
        // the session, or system revoked the projection), make sure we
        // hand the user's auto-rotate back. Skipped while mirror is still
        // alive — they're either backgrounding the app or rotating
        // through configurations.
        if (!MirrorService.isActivelyMirroring) restoreSystemRotation()
        super.onDestroy()
    }

    private fun startMirror() {
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjectionLauncher.launch(mpm.createScreenCaptureIntent())
    }

    // ─── Google Maps + system landscape lock ───────────────────────────────

    /**
     * Single-tap "ride with map" flow.
     *
     * Lock-first ordering matters: the system rotation lock
     * (`Settings.System.USER_ROTATION` + `ACCELEROMETER_ROTATION`)
     * propagates asynchronously through SettingsObserver → WindowManager
     * → rotation policy refresh, which takes 100–300 ms on most devices.
     * Earlier builds locked AFTER the user accepted the projection
     * dialog and immediately called `startActivity(maps)` — Maps's
     * activity creation beat the lock, picking up the still-portrait
     * window state, so Maps opened portrait even though MainActivity
     * had already requested landscape.
     *
     * We now lock *before* showing the projection dialog. By the time
     * the user reads it, taps "Start now", waits for the dialog to
     * close, and the Maps activity starts up, the lock has had multiple
     * frames to propagate. If the user cancels the dialog, the
     * projection-launcher callback restores rotation.
     *
     *  1. Verify `WRITE_SETTINGS` (open grant screen + bail if missing).
     *  2. Lock system landscape **now**.
     *  3a. If mirror NOT running → request MediaProjection. Maps launch
     *      fires from the projection callback once the user accepts.
     *  3b. If mirror IS running → launch Maps immediately.
     */
    private fun openGoogleMapsLandscape() {
        if (!Settings.System.canWrite(this)) {
            AppLog.w("Maps", "WRITE_SETTINGS not granted — opening system grant screen")
            Toast.makeText(this, R.string.toast_write_settings_needed,
                Toast.LENGTH_LONG).show()
            try {
                val grant = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS)
                    .setData(Uri.parse("package:$packageName"))
                startActivity(grant)
            } catch (t: Throwable) {
                AppLog.e("Maps", "could not open WRITE_SETTINGS grant screen", t)
            }
            return
        }

        // Lock immediately. Even if the user cancels the projection dialog
        // we'll unwind this in the launcher callback's failure branch.
        lockSystemLandscape()

        if (!MirrorService.isActivelyMirroring) {
            AppLog.i("Maps", "mirror not running — requesting projection first, " +
                "Maps will fire after user accepts (lock already in place)")
            pendingMapsLaunch = true
            startMirror()
            return
        }

        launchMapsIntent()
    }

    /** Fire the Maps intent directly at the Google Maps package — no system
     *  resolver picker. Falls back to a generic `geo:0,0` intent only if
     *  Google Maps isn't installed at all (in which case the picker is
     *  unavoidable since we need *some* maps app to handle the intent).
     *
     *  Manifest `<queries>` makes the Google Maps package visible to
     *  `getLaunchIntentForPackage` / `setPackage` on Android 11+.
     */
    private fun launchMapsIntent() {
        val pkg = "com.google.android.apps.maps"
        val direct = packageManager.getLaunchIntentForPackage(pkg)
        val intent = if (direct != null) {
            direct.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            direct
        } else {
            AppLog.w("Maps", "Google Maps not installed — falling back to generic geo: intent")
            Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            startActivity(intent)
            AppLog.i("Maps", "launched ${intent.component ?: intent.action}")
        } catch (t: Throwable) {
            AppLog.e("Maps", "Maps launch failed", t)
            Toast.makeText(this, R.string.toast_maps_not_installed, Toast.LENGTH_SHORT).show()
        }
    }

    /** Observer that re-applies our landscape lock when something else
     *  (OEM launcher, third-party orientation app, Bixby Routines, …)
     *  rewrites `Settings.System.USER_ROTATION` or
     *  `Settings.System.ACCELEROMETER_ROTATION`. Without this, on Honor
     *  / OneUI when the user goes Home → Maps the launcher's portrait
     *  preference can leave USER_ROTATION at 0 by the time Maps comes
     *  back, and Maps then opens in portrait even though we initially
     *  locked at 90. Only active while [rotationLockDesired] is true,
     *  registered by [lockSystemLandscape], unregistered by
     *  [restoreSystemRotation]. */
    private val rotationObserver = object : android.database.ContentObserver(
        android.os.Handler(android.os.Looper.getMainLooper()),
    ) {
        override fun onChange(selfChange: Boolean) {
            if (!rotationLockDesired) return
            if (!Settings.System.canWrite(this@MainActivity)) return
            val accel = Settings.System.getInt(
                contentResolver, Settings.System.ACCELEROMETER_ROTATION, 1,
            )
            val rot = Settings.System.getInt(
                contentResolver, Settings.System.USER_ROTATION, Surface.ROTATION_0,
            )
            if (accel == 0 && rot == Surface.ROTATION_90) return  // already what we want
            AppLog.d("Maps", "rotation drifted (accel=$accel user_rotation=$rot) — re-applying")
            try {
                Settings.System.putInt(
                    contentResolver, Settings.System.ACCELEROMETER_ROTATION, 0,
                )
                Settings.System.putInt(
                    contentResolver, Settings.System.USER_ROTATION, Surface.ROTATION_90,
                )
            } catch (t: Throwable) {
                AppLog.w("Maps", "rotation re-apply failed", t)
            }
        }
    }
    @Volatile private var rotationLockDesired: Boolean = false

    /**
     * Disable system auto-rotate and pin USER_ROTATION to landscape
     * (`Surface.ROTATION_90`). Returns false if the writes fail (typically
     * because some OEM ROMs gate USER_ROTATION even with WRITE_SETTINGS).
     *
     * The previous values are persisted in SharedPreferences so
     * [restoreSystemRotation] can put things back the way the user had
     * them — including the "auto-rotate was ON" case.
     */
    private fun lockSystemLandscape(): Boolean {
        return try {
            val sp = getSharedPreferences("ridepanel", Context.MODE_PRIVATE)
            // Only save the originals once per lock cycle. If we've already
            // locked, the saved values would be our OWN writes — useless.
            if (!sp.getBoolean("rotation_locked", false)) {
                val origAccel = Settings.System.getInt(
                    contentResolver, Settings.System.ACCELEROMETER_ROTATION, 1,
                )
                val origRot = Settings.System.getInt(
                    contentResolver, Settings.System.USER_ROTATION, Surface.ROTATION_0,
                )
                sp.edit()
                    .putInt("orig_accel_rotation", origAccel)
                    .putInt("orig_user_rotation", origRot)
                    .putBoolean("rotation_locked", true)
                    .apply()
                AppLog.i("Maps", "saved original rotation: accel=$origAccel user_rotation=$origRot")
            }
            Settings.System.putInt(
                contentResolver, Settings.System.ACCELEROMETER_ROTATION, 0,
            )
            Settings.System.putInt(
                contentResolver, Settings.System.USER_ROTATION, Surface.ROTATION_90,
            )
            // Diagnostic: read both back and log. On some ROMs the write
            // succeeds silently but the system actually ignores it (Pixel
            // 12+ face-detection auto-rotate, Settings.Secure overrides,
            // OEM-specific gating). If the read-back doesn't match what
            // we wrote, the lock will never actually take effect and the
            // user will see Maps rotate to portrait regardless.
            val readBackAccel = Settings.System.getInt(
                contentResolver, Settings.System.ACCELEROMETER_ROTATION, -1,
            )
            val readBackRot = Settings.System.getInt(
                contentResolver, Settings.System.USER_ROTATION, -1,
            )
            val faceAutoRotate = try {
                Settings.Secure.getInt(contentResolver, "camera_autorotate", -1)
            } catch (_: Throwable) { -1 }
            AppLog.i("Maps", "rotation lock applied: " +
                "ACCELEROMETER_ROTATION wrote=0 read=$readBackAccel, " +
                "USER_ROTATION wrote=${Surface.ROTATION_90} read=$readBackRot, " +
                "camera_autorotate (face)=$faceAutoRotate")
            if (readBackAccel != 0 || readBackRot != Surface.ROTATION_90) {
                AppLog.w("Maps", "rotation lock write did NOT stick — OEM may be blocking it. " +
                    "Apps may still auto-rotate.")
            }
            // Start watching for OEM/launcher rewrites. ContentObservers
            // are cheap (one binder callback per setting change) so leaving
            // it registered for the whole mirror session has no measurable
            // impact, and it's the only way to catch Honor/OneUI launchers
            // that silently reset USER_ROTATION when they take foreground.
            rotationLockDesired = true
            try {
                contentResolver.registerContentObserver(
                    Settings.System.getUriFor(Settings.System.USER_ROTATION),
                    false, rotationObserver,
                )
                contentResolver.registerContentObserver(
                    Settings.System.getUriFor(Settings.System.ACCELEROMETER_ROTATION),
                    false, rotationObserver,
                )
            } catch (t: Throwable) {
                AppLog.w("Maps", "rotation observer register failed", t)
            }
            AppLog.i("Maps", "system rotation locked to landscape (ROTATION_90) + observer armed")
            true
        } catch (t: Throwable) {
            AppLog.e("Maps", "lockSystemLandscape failed", t)
            false
        }
    }

    /**
     * Restore the auto-rotate state we saved in [lockSystemLandscape].
     * Safe to call unconditionally — if we never locked, this is a no-op.
     */
    private fun restoreSystemRotation() {
        // Stop the re-apply observer FIRST so we don't fight ourselves
        // as we write the user's original values back.
        rotationLockDesired = false
        try { contentResolver.unregisterContentObserver(rotationObserver) } catch (_: Throwable) {}
        val sp = getSharedPreferences("ridepanel", Context.MODE_PRIVATE)
        if (!sp.getBoolean("rotation_locked", false)) return
        if (!Settings.System.canWrite(this)) {
            AppLog.w("Maps", "restoreSystemRotation: WRITE_SETTINGS revoked, leaving lock in place")
            return
        }
        val origAccel = sp.getInt("orig_accel_rotation", 1)
        val origRot = sp.getInt("orig_user_rotation", Surface.ROTATION_0)
        try {
            // Order matters: set USER_ROTATION first so when we re-enable
            // ACCELEROMETER_ROTATION the system doesn't briefly flip to
            // ROTATION_90's mapping. (Auto-rotate ignores USER_ROTATION
            // when active, so this is a defensive ordering rather than
            // strictly required.)
            Settings.System.putInt(
                contentResolver, Settings.System.USER_ROTATION, origRot,
            )
            Settings.System.putInt(
                contentResolver, Settings.System.ACCELEROMETER_ROTATION, origAccel,
            )
            sp.edit().putBoolean("rotation_locked", false).apply()
            AppLog.i("Maps", "system rotation restored: accel=$origAccel user_rotation=$origRot")
        } catch (t: Throwable) {
            AppLog.e("Maps", "restoreSystemRotation failed", t)
        }
    }

    private fun onPairClicked() {
        val needed = if (Build.VERSION.SDK_INT >= 31)
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) startPairing()
        else blePermLauncher.launch(missing.toTypedArray())
    }

    private fun startPairing() {
        val p = BlePxcPairer(this, listener)
        pairer = p
        p.startScan()
    }

    private val listener = object : BlePxcPairer.Listener {
        override fun onState(state: BlePxcPairer.State, message: String?) {
            runOnUiThread { updatePairStatus("$state${message?.let { " — $it" } ?: ""}") }
        }
        override fun onScanResult(devices: List<BluetoothDevice>) {
            runOnUiThread { showDevicePicker(devices) }
        }
        override fun onBuildNetStatus(
            status: Int,
            info: BlePxcPairer.BuildNetStatusInfo,
        ): BlePxcPairer.PhoneApInfo? {
            // Status 1/2 = head-unit needs the phone's hotspot credentials.
            // We can't programmatically read SoftAp creds on Android 13+,
            // so prompt the user.
            if (status != 1 && status != 2) return null
            return promptHotspotCredentials()
        }
        override fun onPaired() {
            runOnUiThread {
                updatePairStatus("Paired — start mirror to begin")
            }
        }
    }

    private fun promptHotspotCredentials(): BlePxcPairer.PhoneApInfo? {
        val sp = getSharedPreferences("ridepanel", Context.MODE_PRIVATE)
        val ssid = sp.getString("ap_ssid", "") ?: ""
        val pwd = sp.getString("ap_pwd", "") ?: ""
        if (ssid.isNotEmpty() && pwd.isNotEmpty()) {
            return BlePxcPairer.PhoneApInfo(ssid = ssid, pwd = pwd, ip = localAddresses().firstOrNull())
        }
        // Block the BLE worker thread until the user fills in the fields.
        val latch = java.util.concurrent.CountDownLatch(1)
        var result: BlePxcPairer.PhoneApInfo? = null
        runOnUiThread {
            val ssidIn = EditText(this).apply { hint = getString(R.string.dialog_hotspot_ssid_hint) }
            val pwdIn = EditText(this).apply {
                hint = getString(R.string.dialog_hotspot_password_hint)
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(48, 24, 48, 0)
                addView(ssidIn); addView(pwdIn)
            }
            AlertDialog.Builder(this)
                .setTitle(R.string.dialog_hotspot_title)
                .setMessage(R.string.dialog_hotspot_message)
                .setView(box)
                .setPositiveButton(R.string.dialog_ok) { _, _ ->
                    val s = ssidIn.text.toString().trim()
                    val p = pwdIn.text.toString()
                    if (s.isNotEmpty() && p.isNotEmpty()) {
                        sp.edit().putString("ap_ssid", s).putString("ap_pwd", p).apply()
                        result = BlePxcPairer.PhoneApInfo(s, p, ip = localAddresses().firstOrNull())
                    }
                    latch.countDown()
                }
                .setNegativeButton(R.string.dialog_cancel) { _, _ -> latch.countDown() }
                .setOnCancelListener { latch.countDown() }
                .show()
        }
        latch.await()
        return result
    }

    private fun showDevicePicker(devices: List<BluetoothDevice>) {
        if (devices.isEmpty()) { updatePairStatus("Scanning…"); return }
        val unnamed = getString(R.string.dialog_unnamed_device)
        val labels = devices.map { d ->
            val name = try { @Suppress("MissingPermission") d.name } catch (_: Throwable) { null }
            "${name ?: unnamed}\n${d.address}"
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_pick_headunit_title)
            .setItems(labels) { _, idx -> pairer?.connect(devices[idx]) }
            .setOnDismissListener { pairer?.stopScan() }
            .show()
    }

    /** BLE pairing UI was removed — surface its messages in the QR status
     *  line + log so we keep the flow visible. */
    private fun updatePairStatus(s: String) {
        AppLog.i("UI", "Pair: $s")
        runOnUiThread { qrView.text = "BLE: $s" }
    }

    // ─── QR + Wi-Fi Direct ────────────────────────────────────────────────

    private fun onQrClicked() {
        val needed = mutableListOf(Manifest.permission.CAMERA)
        needed += if (Build.VERSION.SDK_INT >= 33) Manifest.permission.NEARBY_WIFI_DEVICES
                  else Manifest.permission.ACCESS_FINE_LOCATION
        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) launchQrScanner()
        else qrPermLauncher.launch(missing.toTypedArray())
    }

    private fun launchQrScanner() {
        val opts = ScanOptions().apply {
            setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            setPrompt("Scan the QR shown on the head-unit")
            setBeepEnabled(true)
            setOrientationLocked(false)
        }
        qrLauncher.launch(opts)
    }

    /** P2P fallback prepared when QR carries both AP creds and Wi-Fi Direct info. */
    @Volatile private var apFailoverToP2p: CarbitQr.Result.WifiDirect? = null

    private fun handleQrResult(text: String) {
        try {
            AppLog.i("Qr", "scanned URL: $text")
            val r = CarbitQr.parse(text)
            AppLog.i("Qr", "parsed -> $r")
            when (r) {
                is CarbitQr.Result.Unsupported -> {
                    AppLog.w("Qr", "QR rejected: ${r.reason}")
                    updateQrStatus("Unsupported: ${r.reason}")
                }
                is CarbitQr.Result.WifiDirect -> {
                    AppLog.i("Qr", "→ Wi-Fi Direct path; name='${r.name}' mac=${r.mac} sn=${r.sn}")
                    updateQrStatus("Wi-Fi Direct: ${r.name} (${r.mac})")
                    autoStartMirrorOnConnect = false
                    apFailoverToP2p = null
                    pendingKnownEntry = KnownDevices.Entry(
                        mac = r.mac, name = r.name, ssid = null, pwd = null,
                        auth = null, sn = r.sn, lastConnected = 0,
                    )
                    ensureWifiDirect().connectToMac(r.mac, r.name)
                }
                is CarbitQr.Result.WifiAp -> {
                    AppLog.i("Qr", "→ AP path; ssid='${r.ssid}' pwd=${r.pwd.length} chars auth=${r.auth} name=${r.name}")
                    updateQrStatus("AP-mode QR: ${r.ssid} — joining…")
                    autoStartMirrorOnConnect = false
                    apFailoverToP2p = null
                    pendingKnownEntry = KnownDevices.Entry(
                        mac = r.ssid, name = r.name, ssid = r.ssid, pwd = r.pwd,
                        auth = r.auth, sn = null, lastConnected = 0,
                    )
                    joinHeadUnitAp(r)
                }
                is CarbitQr.Result.Both -> {
                    AppLog.i("Qr", "→ Both paths available; Wi-Fi Direct primary (per user pref)")
                    autoStartMirrorOnConnect = false
                    apFailoverToP2p = null
                    pendingKnownEntry = KnownDevices.Entry(
                        mac = r.mac, name = r.name, ssid = r.ssid, pwd = r.pwd,
                        auth = r.auth, sn = r.sn, lastConnected = 0,
                    )
                    updateQrStatus("Wi-Fi Direct: ${r.name} (${r.mac})")
                    ensureWifiDirect().connectToMac(r.mac, r.name)
                }
            }
        } catch (t: Throwable) {
            AppLog.e("Qr", "handleQrResult crashed", t)
            updateQrStatus("Crash: ${t.javaClass.simpleName}: ${t.message ?: "?"}")
        }
    }

    private var apRequest: android.net.NetworkRequest? = null
    private val apCallback = object : android.net.ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: android.net.Network) {
            AppLog.i("Ap", "onAvailable network=$network")
            // Got AP — discard the P2P escape hatch.
            apFailoverToP2p = null
            runOnUiThread {
                updateQrStatus("Joined network $network — binding & starting mirror")
                try {
                    (getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager)
                        .bindProcessToNetwork(network)
                    AppLog.i("Ap", "bindProcessToNetwork() ok")
                } catch (t: Throwable) {
                    AppLog.e("Ap", "bindProcessToNetwork failed", t)
                }
                rememberConnected()
                startSplashMode()
                startBleTimeServer()
                if (autoStartMirrorOnConnect) {
                    autoStartMirrorOnConnect = false
                    startMirror()
                }
            }
        }
        override fun onUnavailable() {
            AppLog.w("Ap", "onUnavailable — request expired / declined / SSID not visible")
            val fallback = apFailoverToP2p
            apFailoverToP2p = null
            runOnUiThread {
                if (fallback != null) {
                    AppLog.i("Ap", "→ falling back to Wi-Fi Direct (${fallback.mac})")
                    updateQrStatus("AP unavailable — trying Wi-Fi Direct")
                    ensureWifiDirect().connectToMac(fallback.mac, fallback.name)
                } else {
                    updateQrStatus("AP join failed / declined / SSID not found")
                }
            }
        }
        override fun onLost(network: android.net.Network) {
            AppLog.w("Ap", "onLost network=$network")
            runOnUiThread { updateQrStatus("AP connection lost") }
        }
        override fun onCapabilitiesChanged(
            network: android.net.Network,
            capabilities: android.net.NetworkCapabilities,
        ) {
            AppLog.d("Ap", "onCapabilitiesChanged $network: $capabilities")
        }
        override fun onLinkPropertiesChanged(
            network: android.net.Network,
            linkProperties: android.net.LinkProperties,
        ) {
            AppLog.d("Ap", "onLinkPropertiesChanged $network: iface=${linkProperties.interfaceName} addrs=${linkProperties.linkAddresses}")
        }
    }

    private fun joinHeadUnitAp(ap: CarbitQr.Result.WifiAp, bssidHint: String? = null) {
        if (Build.VERSION.SDK_INT < 29) {
            AppLog.w("Ap", "Android <10 — programmatic join not supported")
            updateQrStatus("AP join requires Android 10+; connect to '${ap.ssid}' manually")
            return
        }
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE)
                as android.net.ConnectivityManager

            // Kick a Wi-Fi scan first — without recent results the picker
            // dialog often shows "no networks found" even when the AP exists.
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
            if (wm != null) {
                val isOn = wm.isWifiEnabled
                AppLog.i("Ap", "Wi-Fi enabled=$isOn ; triggering startScan()")
                @Suppress("DEPRECATION")
                val scanOk = runCatching { wm.startScan() }.getOrDefault(false)
                AppLog.i("Ap", "startScan() returned $scanOk (deprecated since API 28, may be ignored)")
                @Suppress("DEPRECATION")
                val results = runCatching { wm.scanResults }.getOrDefault(emptyList())
                AppLog.i("Ap", "scanResults: ${results.size} entries; matching '${ap.ssid}': " +
                    results.filter { it.SSID == ap.ssid }.joinToString { "${it.SSID}@${it.frequency}MHz rssi=${it.level}" })
            }

            // Tear down any previous request so re-scans don't stack.
            apRequest?.let {
                AppLog.d("Ap", "unregistering previous callback")
                runCatching { cm.unregisterNetworkCallback(apCallback) }
            }

            val builder = android.net.wifi.WifiNetworkSpecifier.Builder().setSsid(ap.ssid)
            val isWpa3 = ap.auth?.contains("WPA3", ignoreCase = true) == true
            if (isWpa3) {
                builder.setWpa3Passphrase(ap.pwd)
            } else {
                builder.setWpa2Passphrase(ap.pwd)
            }
            // BSSID pin makes Android target the *specific* AP, not just any
            // network broadcasting that SSID — useful when the QR carries a
            // MAC that we know matches the head-unit's hotspot.
            if (bssidHint != null) {
                try {
                    val bssid = android.net.MacAddress.fromString(bssidHint)
                    builder.setBssid(bssid)
                    AppLog.i("Ap", "BSSID pinned to $bssidHint")
                } catch (t: Throwable) {
                    AppLog.w("Ap", "ignored bad BSSID '$bssidHint'", t)
                }
            }
            val specifier = builder.build()
            AppLog.i("Ap", "specifier built: ssid='${ap.ssid}' auth=${if (isWpa3) "WPA3" else "WPA2"} bssid=${bssidHint ?: "(any)"}")

            val req = android.net.NetworkRequest.Builder()
                .addTransportType(android.net.NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .setNetworkSpecifier(specifier)
                .build()
            apRequest = req
            AppLog.i("Ap", "requestNetwork() submitted, 30s timeout — system Wi-Fi picker should appear")
            cm.requestNetwork(req, apCallback, 30_000)
        } catch (t: Throwable) {
            AppLog.e("Ap", "joinHeadUnitAp crashed", t)
            updateQrStatus("AP join error: ${t.javaClass.simpleName}: ${t.message ?: "?"}")
        }
    }

    private fun ensureWifiDirect(): WifiDirectConnector {
        wifiDirect?.let { return it }
        val w = WifiDirectConnector(this, wifiDirectListener)
        w.start()
        wifiDirect = w
        return w
    }

    private val wifiDirectListener = object : WifiDirectConnector.Listener {
        override fun onState(state: WifiDirectConnector.State, message: String?) {
            runOnUiThread { updateQrStatus("$state${message?.let { " — $it" } ?: ""}") }
        }
        override fun onConnected(info: WifiP2pInfo, group: WifiP2pGroup?) {
            runOnUiThread {
                val ip = info.groupOwnerAddress?.hostAddress ?: "?"
                updateQrStatus("Connected — group owner $ip; starting mirror…")
                rememberConnected()
                startSplashMode()
                startBleTimeServer()
                // Chain into the mirror request (one-shot — guarded by consumed flag).
                if (autoStartMirrorOnConnect) {
                    autoStartMirrorOnConnect = false
                    startMirror()
                }
            }
        }
    }

    /** Start (or restart) the BLE GATT time-sync session. Safe to call
     *  repeatedly — repeated calls are no-ops once a server is running. If
     *  the BLE permissions aren't granted yet (the QR flow doesn't request
     *  them), launches a non-blocking permission prompt and retries on
     *  grant. Should be called once the head-unit's Wi-Fi network is up. */
    private fun startBleTimeServer() {
        if (bleTimeServer != null) {
            AppLog.d("UI", "BLE time-sync already started, skipping")
            return
        }
        // QR pairing flow doesn't request BLUETOOTH_SCAN/CONNECT, so check
        // here and ask the user — clock-sync is mandatory for usable HUD.
        val needed = if (Build.VERSION.SDK_INT >= 31)
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            AppLog.i("UI", "BLE time-sync: requesting permissions: $missing")
            bleTimeServerPermLauncher.launch(missing.toTypedArray())
            return
        }
        // Pull the known head-unit identifiers we already have at this
        // point: Wi-Fi MAC (always known from QR / KnownDevices) plus
        // optional HUName (filled in later by PXC CLIENT_INFO via
        // [updateBleTimeServerHuName]). The Wi-Fi MAC's last 4 hex chars
        // are shared by the head-unit's BT-Classic and BLE radios in this
        // ecosystem, so they're a deterministic match key.
        val knownMac = currentlyConnectedMac
        AppLog.i("UI", "BLE time-sync: starting (permissions OK, knownMac=$knownMac)")
        val server = BlePxcPairer(
            context = this,
            listener = bleTimeServerListener,
            targetWifiMac = knownMac,
            targetHuName = null,  // filled in later from PXC CLIENT_INFO
        )
        bleTimeServer = server
        server.startTimeServer()
    }

    private fun stopBleTimeServer() {
        bleTimeServer?.disconnect()
        bleTimeServer = null
    }

    /** Minimal listener — we don't care about pairing state transitions in
     *  the time-server flow, just log them for diagnostics. */
    private val bleTimeServerListener = object : BlePxcPairer.Listener {
        override fun onState(state: BlePxcPairer.State, message: String?) {
            AppLog.i("UI", "BLE time-sync state=$state${message?.let { " — $it" } ?: ""}")
        }
    }

    /** Kick MirrorService into its splash-on-HU phase: PXC/Mirror sockets
     *  open, encoder + SplashRenderer push the connected-state bitmap to
     *  the head-unit. User clicking Start mirror / Maps later upgrades
     *  this to full screen capture via the projection token. */
    private fun startSplashMode() {
        if (MirrorService.isActivelyMirroring) return
        AppLog.i("UI", "auto-starting MirrorService in splash mode")
        val intent = Intent(this, MirrorService::class.java).apply {
            action = MirrorService.ACTION_START_SPLASH
        }
        ContextCompat.startForegroundService(this, intent)
    }

    @Volatile private var autoStartMirrorOnConnect: Boolean = false

    private fun updateQrStatus(s: String) {
        qrView.text = "QR/P2P: $s"
    }

    /**
     * Render the saved-head-units list under the QR-status line. Each row is
     * a horizontal `name (mac)  Connect  Forget` strip; tapping Connect runs
     * the same flow as a fresh QR scan, tapping Forget drops the entry.
     */
    private fun renderKnownDevices() {
        val entries = knownDevices.list()
        knownList.removeAllViews()
        if (entries.isEmpty()) {
            knownLabel.visibility = android.view.View.GONE
            knownList.visibility = android.view.View.GONE
            return
        }
        knownLabel.visibility = android.view.View.VISIBLE
        knownList.visibility = android.view.View.VISIBLE
        // An entry is "live" when (a) it's the MAC we last completed a
        // pairing flow on AND (b) the service has *any* live session
        // with the head-unit — splash phase OR full capture. Earlier
        // builds used `isActivelyMirroring` here, which is false during
        // splash (= phone paired but user hasn't tapped Start/Maps yet)
        // and incorrectly displayed "Connect" on the device we were just
        // showing the connected splash for. `isHeadUnitConnected` is
        // the broader flag that covers both phases.
        val liveMac = currentlyConnectedMac
            ?.takeIf { MirrorService.isHeadUnitConnected }
        val unnamed = getString(R.string.dialog_unnamed_device)
        for (entry in entries) {
            val isLive = entry.mac == liveMac
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = 4 }
            }
            val label = TextView(this).apply {
                text = "${entry.name ?: entry.ssid ?: unnamed}\n${entry.mac}"
                textSize = 11f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { gravity = android.view.Gravity.CENTER_VERTICAL }
                isClickable = !isLive
                if (!isLive) setOnClickListener { reconnectKnown(entry) }
            }
            val action = Button(this).apply {
                if (isLive) {
                    text = getString(R.string.btn_known_connected)
                    isEnabled = false
                } else {
                    text = getString(R.string.btn_known_connect)
                    setOnClickListener { reconnectKnown(entry) }
                }
            }
            val forget = Button(this).apply {
                text = getString(R.string.btn_known_forget)
                setOnClickListener {
                    knownDevices.forget(entry.mac)
                    if (entry.mac == currentlyConnectedMac) currentlyConnectedMac = null
                    renderKnownDevices()
                }
            }
            row.addView(label)
            row.addView(action)
            row.addView(forget)
            knownList.addView(row)
        }
    }

    /**
     * Re-run the post-QR connect flow for a saved head-unit. Prefers Wi-Fi
     * Direct when we stored a real MAC, falls back to AP credentials when
     * only those are recorded. When both are present we mirror the
     * `Result.Both` flow from a fresh QR scan: P2P primary, AP fallback.
     */
    private fun reconnectKnown(entry: KnownDevices.Entry) {
        AppLog.i("Qr", "reconnect to known device mac=${entry.mac} name=${entry.name}")
        autoStartMirrorOnConnect = false
        pendingKnownEntry = entry
        val hasDirect = MAC_REGEX.matches(entry.mac)
        val hasAp = !entry.ssid.isNullOrEmpty() && !entry.pwd.isNullOrEmpty()
        when {
            hasDirect && hasAp -> {
                apFailoverToP2p = CarbitQr.Result.WifiDirect(
                    name = entry.name ?: entry.mac,
                    mac = entry.mac,
                    sn = entry.sn,
                )
                updateQrStatus("Saved: ${entry.name ?: entry.ssid} — Wi-Fi Direct…")
                ensureWifiDirect().connectToMac(entry.mac, entry.name)
            }
            hasDirect -> {
                apFailoverToP2p = null
                updateQrStatus("Saved: ${entry.name ?: entry.mac} — Wi-Fi Direct…")
                ensureWifiDirect().connectToMac(entry.mac, entry.name)
            }
            hasAp -> {
                apFailoverToP2p = null
                updateQrStatus("Saved: ${entry.name ?: entry.ssid} — joining AP…")
                joinHeadUnitAp(CarbitQr.Result.WifiAp(
                    name = entry.name,
                    ssid = entry.ssid!!,
                    pwd = entry.pwd!!,
                    auth = entry.auth,
                ))
            }
            else -> updateQrStatus("Saved device has no usable credentials")
        }
    }

    private companion object {
        private val MAC_REGEX = Regex("^[0-9a-fA-F:]{17}$")
        private const val DONATE_URL =
            "https://www.paypal.com/donate/?hosted_button_id=XL58JUWCCWWPC"
    }

    private fun rememberConnected() {
        val e = pendingKnownEntry ?: return
        pendingKnownEntry = null
        knownDevices.upsert(e)
        currentlyConnectedMac = e.mac
        runOnUiThread { renderKnownDevices() }
    }

    // ─── Tab switching + orientation policy ────────────────────────────

    /**
     * Show one of [Tab.HOME] / [Tab.LOGS] / [Tab.FAQ], hide the other two.
     * Uses simple `View.visibility` toggles instead of a ViewPager —
     * keeps state alive across switches (log scroll position, known-devices
     * list) without managing fragments.
     */
    private fun showTab(tab: Tab) {
        currentTab = tab
        tabContentHome.visibility = if (tab == Tab.HOME) View.VISIBLE else View.GONE
        tabContentLogs.visibility = if (tab == Tab.LOGS) View.VISIBLE else View.GONE
        tabContentFaq.visibility = if (tab == Tab.FAQ) View.VISIBLE else View.GONE
        // Dim the inactive tab buttons so the active one stands out;
        // no theme dependency needed, just alpha.
        tabBtnHome.alpha = if (tab == Tab.HOME) 1f else 0.5f
        tabBtnLogs.alpha = if (tab == Tab.LOGS) 1f else 0.5f
        tabBtnFaq.alpha = if (tab == Tab.FAQ) 1f else 0.5f
        // Re-render the known-devices section every time the user lands on
        // Home — the Connect → Connected label depends on
        // [MirrorService.isHeadUnitConnected] which can change while the
        // user is on Logs/FAQ (head-unit drops, splash kicks in, etc.).
        if (tab == Tab.HOME) renderKnownDevices()
    }

    /**
     * Update MainActivity's `requestedOrientation` based on whether a
     * mirror session is active. We default to portrait (3-tab UI is
     * designed for it) and switch to landscape only while projecting,
     * so the captured surface matches what the head-unit expects from
     * the very first frame.
     *
     * System-wide rotation lock (via `Settings.System.USER_ROTATION`)
     * is set separately in [lockSystemLandscape] and only applies when
     * the user pressed the Maps button; this method handles the
     * MainActivity-only case where the user uses the plain "Start mirror"
     * button without touching system settings.
     */
    private fun applyMirrorOrientation(mirroring: Boolean) {
        requestedOrientation = if (mirroring) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
    }

    private fun localAddresses(): List<String> {
        val out = mutableListOf<String>()
        try {
            for (nif in NetworkInterface.getNetworkInterfaces()) {
                if (!nif.isUp || nif.isLoopback) continue
                for (addr in nif.inetAddresses) {
                    if (!addr.isLoopbackAddress && addr.hostAddress?.contains(':') == false) {
                        out += addr.hostAddress!!
                    }
                }
            }
        } catch (_: Throwable) {}
        if (out.isEmpty()) {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            wm?.connectionInfo?.ipAddress?.let {
                @Suppress("DEPRECATION") out += Formatter.formatIpAddress(it)
            }
        }
        return out
    }
}
