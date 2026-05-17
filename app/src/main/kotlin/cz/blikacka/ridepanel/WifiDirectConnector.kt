package cz.blikacka.ridepanel

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.NetworkInfo
import android.net.wifi.WpsInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Looper
import androidx.core.content.ContextCompat

/**
 * Drives Wi-Fi Direct (Wi-Fi P2P) discovery + connect, mirroring the
 * `WifiDirectService` flow in the decompiled APK
 * (`net/easyconn/carman/common/base/y2.java`).
 *
 * Use after a successful QR scan: pass the head-unit's Wi-Fi Direct MAC
 * address to [connectToMac]. We `discoverPeers()`, wait for the matching
 * device to appear in `requestPeers`, then `connect()` with WPS-PBC.
 *
 * Once the group forms, both the phone and the head-unit have IPs in the
 * `192.168.49.0/24` subnet that Android's P2P stack hands out; the head-unit
 * can then dial TCP 10922 on the phone (the [PxcServer] is already
 * listening, and the [MdnsPublisher] keeps advertising on the new
 * interface).
 *
 * Permissions: API 33+ requires NEARBY_WIFI_DEVICES; older releases use
 * ACCESS_FINE_LOCATION. Both are declared in the manifest with appropriate
 * `maxSdkVersion` guards.
 */
class WifiDirectConnector(
    private val context: Context,
    private val listener: Listener,
) {

    interface Listener {
        fun onState(state: State, message: String? = null)
        fun onPeers(peers: List<WifiP2pDevice>) {}
        fun onConnected(info: WifiP2pInfo, group: WifiP2pGroup?) {}
    }

    enum class State { IDLE, DISCOVERING, CONNECTING, CONNECTED, ERROR }

    private val manager = context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    private var channel: WifiP2pManager.Channel? = null
    private var receiver: BroadcastReceiver? = null

    @Volatile
    private var pendingMac: String? = null
    @Volatile
    private var pendingName: String? = null
    private val mainHandler = android.os.Handler(Looper.getMainLooper())
    private val discoveryFallback = Runnable { fallbackConnect() }

    fun hasPermissions(): Boolean {
        val perm = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.NEARBY_WIFI_DEVICES
                   else Manifest.permission.ACCESS_FINE_LOCATION
        return ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
    }

    fun start() {
        val mgr = manager ?: run {
            listener.onState(State.ERROR, "Wi-Fi P2P unavailable"); return
        }
        if (channel != null) return
        channel = mgr.initialize(context, Looper.getMainLooper()) {
            listener.onState(State.IDLE, "channel disconnected")
        }
        val r = createReceiver()
        receiver = r
        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction("android.net.wifi.p2p.DISCOVERY_STATE_CHANGE")
            addAction("android.net.wifi.p2p.THIS_DEVICE_CHANGED")
        }
        if (Build.VERSION.SDK_INT >= 33) {
            ContextCompat.registerReceiver(context, r, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(r, filter)
        }
    }

    fun stop() {
        mainHandler.removeCallbacks(discoveryFallback)
        pendingMac = null
        pendingName = null
        receiver?.let { runCatching { context.unregisterReceiver(it) } }
        receiver = null
        val ch = channel ?: return
        try { manager?.cancelConnect(ch, null) } catch (_: Throwable) {}
        try { manager?.removeGroup(ch, null) } catch (_: Throwable) {}
        channel = null
        listener.onState(State.IDLE)
    }

    @SuppressLint("MissingPermission")
    fun connectToMac(mac: String, name: String? = null) {
        if (!hasPermissions()) {
            listener.onState(State.ERROR, "Wi-Fi P2P permission missing"); return
        }
        val mgr = manager ?: run {
            listener.onState(State.ERROR, "Wi-Fi P2P unavailable"); return
        }
        val ch = channel ?: run {
            listener.onState(State.ERROR, "Wi-Fi P2P channel not initialized"); return
        }
        // Drop any in-flight connect from an earlier attempt — otherwise
        // Android keeps the stale request and ignores the new one.
        try {
            mgr.cancelConnect(ch, object : WifiP2pManager.ActionListener {
                override fun onSuccess() { AppLog.d(TAG, "cancelled previous connect") }
                override fun onFailure(reason: Int) { /* nothing pending */ }
            })
        } catch (_: Throwable) {}

        pendingMac = mac.trim().lowercase()
        pendingName = name
        AppLog.i(TAG, "connectToMac mac=$mac name=$name — starting discoverPeers")
        listener.onState(State.DISCOVERING, mac)
        mgr.discoverPeers(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() { AppLog.d(TAG, "discoverPeers started") }
            override fun onFailure(reason: Int) {
                AppLog.w(TAG, "discoverPeers failed: ${reasonName(reason)} — trying fallback connect anyway")
            }
        })
        // Original Carbit behaviour (HWQrFragment.v0()): if discovery doesn't
        // surface the device within ~20s, fabricate a WifiP2pDevice from the
        // QR data and call connect() with it. Android's P2P stack accepts a
        // raw deviceAddress and re-runs discovery internally.
        mainHandler.removeCallbacks(discoveryFallback)
        mainHandler.postDelayed(discoveryFallback, 20_000)
    }

    @SuppressLint("MissingPermission")
    private fun fallbackConnect() {
        val targetMac = pendingMac ?: return
        val mgr = manager ?: return
        val ch = channel ?: return
        AppLog.w(TAG, "discovery timeout — fabricating WifiP2pDevice for $targetMac and forcing connect()")
        val fake = WifiP2pDevice().apply {
            deviceAddress = targetMac
            deviceName = pendingName ?: targetMac
            // status=3 (UNAVAILABLE) makes Android skip the device; use 1 (INVITED).
            status = 1
        }
        pendingMac = null
        pendingName = null
        connectInternal(mgr, ch, fake)
    }

    @SuppressLint("MissingPermission")
    private fun onPeersChanged() {
        val mgr = manager ?: return
        val ch = channel ?: return
        if (!hasPermissions()) return
        mgr.requestPeers(ch) { peers ->
            val list = peers.deviceList.toList()
            AppLog.d(TAG, "peers: ${list.size} — ${list.joinToString { "${it.deviceName ?: "?"}=${it.deviceAddress}" }}")
            listener.onPeers(list)
            val target = pendingMac ?: return@requestPeers
            val match = list.firstOrNull {
                it.deviceAddress.equals(target, true) ||
                    (pendingName != null && it.deviceName == pendingName)
            }
            if (match != null) {
                AppLog.i(TAG, "discovered target: ${match.deviceName} (${match.deviceAddress})")
                mainHandler.removeCallbacks(discoveryFallback)
                pendingMac = null
                pendingName = null
                connectInternal(mgr, ch, match)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectInternal(
        mgr: WifiP2pManager, ch: WifiP2pManager.Channel, peer: WifiP2pDevice
    ) {
        listener.onState(State.CONNECTING, peer.deviceName ?: peer.deviceAddress)
        // Tear down any leftover P2P group BEFORE issuing connect(). Without
        // this, a head-unit that still holds a group from a previous failed
        // session silently swallows our connect — the Android stack returns
        // success but never fires CONNECTION_CHANGED. Mirrors the
        // removeGroup() that Carbit's WifiDirectService calls on every retry.
        try {
            mgr.removeGroup(ch, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    AppLog.d(TAG, "removed previous P2P group; proceeding to connect()")
                    issueConnect(mgr, ch, peer)
                }
                override fun onFailure(reason: Int) {
                    // No group to remove (reason=2 BUSY / 3 NO_SERVICE_REQUESTS
                    // typically means "nothing was there"). Proceed regardless.
                    AppLog.d(TAG, "removeGroup ${reasonName(reason)} — proceeding to connect()")
                    issueConnect(mgr, ch, peer)
                }
            })
        } catch (t: Throwable) {
            AppLog.w(TAG, "removeGroup threw — proceeding anyway", t)
            issueConnect(mgr, ch, peer)
        }
    }

    @SuppressLint("MissingPermission")
    private fun issueConnect(
        mgr: WifiP2pManager, ch: WifiP2pManager.Channel, peer: WifiP2pDevice
    ) {
        val cfg = WifiP2pConfig().apply {
            deviceAddress = peer.deviceAddress
            // Prefer no group-owner role unless the peer can't do PBC; mirrors
            // y2.c() in the decompiled APK.
            groupOwnerIntent = -1
            wps.setup = when {
                peer.wpsPbcSupported() -> WpsInfo.PBC
                peer.wpsKeypadSupported() -> WpsInfo.KEYPAD
                peer.wpsDisplaySupported() -> WpsInfo.DISPLAY
                else -> WpsInfo.PBC
            }
        }
        mgr.connect(ch, cfg, object : WifiP2pManager.ActionListener {
            override fun onSuccess() { AppLog.d(TAG, "connect() submitted") }
            override fun onFailure(reason: Int) {
                listener.onState(State.ERROR, "connect failed: ${reasonName(reason)}")
            }
        })
    }

    private fun createReceiver() = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            when (intent?.action) {
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                    AppLog.i(TAG, "STATE_CHANGED state=" +
                        if (state == WifiP2pManager.WIFI_P2P_STATE_ENABLED) "ENABLED" else "DISABLED")
                }
                "android.net.wifi.p2p.DISCOVERY_STATE_CHANGE" -> {
                    val s = intent.getIntExtra("discoveryState", -1)
                    AppLog.d(TAG, "DISCOVERY_STATE_CHANGE state=$s (1=stopped, 2=started)")
                }
                "android.net.wifi.p2p.THIS_DEVICE_CHANGED" -> {
                    @Suppress("DEPRECATION")
                    val me = intent.getParcelableExtra<WifiP2pDevice>(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE)
                    AppLog.d(TAG, "THIS_DEVICE_CHANGED me=${me?.deviceName}/${me?.deviceAddress} status=${me?.status}")
                }
                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> onPeersChanged()
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    @Suppress("DEPRECATION")
                    val info = if (Build.VERSION.SDK_INT >= 33)
                        intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_INFO, WifiP2pInfo::class.java)
                    else
                        intent.getParcelableExtra<WifiP2pInfo>(WifiP2pManager.EXTRA_WIFI_P2P_INFO)
                    @Suppress("DEPRECATION")
                    val group = if (Build.VERSION.SDK_INT >= 33)
                        intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_GROUP, WifiP2pGroup::class.java)
                    else
                        intent.getParcelableExtra<WifiP2pGroup>(WifiP2pManager.EXTRA_WIFI_P2P_GROUP)
                    @Suppress("DEPRECATION")
                    val networkInfo = intent.getParcelableExtra<NetworkInfo>(WifiP2pManager.EXTRA_NETWORK_INFO)
                    AppLog.i(TAG, "CONNECTION_CHANGED groupFormed=${info?.groupFormed} " +
                        "isGO=${info?.isGroupOwner} owner=${info?.groupOwnerAddress?.hostAddress} " +
                        "netState=${networkInfo?.state} extra=${networkInfo?.extraInfo}")
                    if (info?.groupFormed == true && networkInfo?.isConnected == true) {
                        listener.onState(State.CONNECTED, info.groupOwnerAddress?.hostAddress)
                        listener.onConnected(info, group)
                    } else if (networkInfo?.state == NetworkInfo.State.CONNECTING) {
                        listener.onState(State.CONNECTING, "negotiating WPS-PBC")
                    }
                }
            }
        }
    }

    private fun reasonName(reason: Int) = when (reason) {
        WifiP2pManager.P2P_UNSUPPORTED -> "P2P_UNSUPPORTED"
        WifiP2pManager.ERROR -> "ERROR"
        WifiP2pManager.BUSY -> "BUSY"
        WifiP2pManager.NO_SERVICE_REQUESTS -> "NO_SERVICE_REQUESTS"
        else -> "code $reason"
    }

    companion object { private const val TAG = "RidePanel.WifiP2P" }
}
