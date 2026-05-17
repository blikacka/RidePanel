package cz.blikacka.ridepanel

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Browses for `_EasyConn._tcp.` mDNS services and resolves them to IP+port.
 * Mirrors the role `s.java` (`MDNSClient`) plays in the decompiled APK —
 * the *consumer* side: head-unit publishes, phone discovers.
 *
 * Each freshly-resolved service is delivered to [onResolved]. Subsequent
 * notifications for the same `huid` (or service name, if huid missing) are
 * suppressed so we don't probe the head-unit ten times in a row.
 */
class MdnsDiscoverer(
    private val context: Context,
    private val onResolved: (Found) -> Unit,
) {
    data class Found(
        val name: String,
        val host: InetAddress,
        val port: Int,
        val packageName: String?,
        val ecName: String?,
        val huid: String?,
        val channel: String?,
        val flavor: String?,
        val huname: String?,
    )

    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val running = AtomicBoolean(false)
    private val seen = ConcurrentHashMap<String, Long>()

    private val listener = object : NsdManager.DiscoveryListener {
        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            AppLog.e(TAG, "discovery start failed: $errorCode")
        }
        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
            AppLog.w(TAG, "discovery stop failed: $errorCode")
        }
        override fun onDiscoveryStarted(serviceType: String) {
            AppLog.i(TAG, "discovery started for $serviceType")
        }
        override fun onDiscoveryStopped(serviceType: String) {
            AppLog.i(TAG, "discovery stopped for $serviceType")
        }
        override fun onServiceFound(serviceInfo: NsdServiceInfo) {
            AppLog.i(TAG, "service found: ${serviceInfo.serviceName} (${serviceInfo.serviceType})")
            resolve(serviceInfo)
        }
        override fun onServiceLost(serviceInfo: NsdServiceInfo) {
            AppLog.d(TAG, "service lost: ${serviceInfo.serviceName}")
            seen.remove(serviceInfo.serviceName)
        }
    }

    fun start() {
        if (running.getAndSet(true)) return
        try {
            nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (t: Throwable) {
            AppLog.e(TAG, "discoverServices() threw", t)
            running.set(false)
        }
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        try { nsd.stopServiceDiscovery(listener) } catch (_: Throwable) {}
        seen.clear()
    }

    private fun resolve(info: NsdServiceInfo) {
        if (Build.VERSION.SDK_INT >= 34) {
            val cb = object : NsdManager.ServiceInfoCallback {
                override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {
                    AppLog.w(TAG, "ServiceInfoCallback register failed: $errorCode")
                }
                override fun onServiceInfoCallbackUnregistered() {}
                override fun onServiceLost() {}
                override fun onServiceUpdated(updated: NsdServiceInfo) {
                    deliverIfNew(updated)
                    try { nsd.unregisterServiceInfoCallback(this) } catch (_: Throwable) {}
                }
            }
            try {
                nsd.registerServiceInfoCallback(info, java.util.concurrent.Executors.newSingleThreadExecutor(), cb)
            } catch (t: Throwable) {
                AppLog.w(TAG, "registerServiceInfoCallback failed, falling back to deprecated resolveService", t)
                @Suppress("DEPRECATION")
                nsd.resolveService(info, legacyResolveListener)
            }
        } else {
            @Suppress("DEPRECATION")
            nsd.resolveService(info, legacyResolveListener)
        }
    }

    @Suppress("DEPRECATION")
    private val legacyResolveListener = object : NsdManager.ResolveListener {
        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
            AppLog.w(TAG, "resolveService failed: $errorCode (${serviceInfo.serviceName})")
        }
        override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
            deliverIfNew(serviceInfo)
        }
    }

    private fun deliverIfNew(info: NsdServiceInfo) {
        val host = info.host ?: run {
            AppLog.w(TAG, "resolved service ${info.serviceName} has no host")
            return
        }
        val port = info.port
        val attrs: Map<String, ByteArray> = if (Build.VERSION.SDK_INT >= 21) info.attributes else emptyMap()
        val packageName = attrs["packagename"]?.toString(Charsets.UTF_8)
        val huid = attrs["huid"]?.toString(Charsets.UTF_8)
        val channel = attrs["channel"]?.toString(Charsets.UTF_8)
        val flavor = attrs["flavor"]?.toString(Charsets.UTF_8)
        val ecName = attrs["ec_name"]?.toString(Charsets.UTF_8)
        val huname = attrs["huname"]?.toString(Charsets.UTF_8)

        // Skip ourselves — Android NSD echoes our own publication back.
        val ourPkg = context.packageName
        if (packageName != null && packageName == ourPkg) {
            AppLog.d(TAG, "skipping our own service ${info.serviceName}")
            return
        }
        val key = huid ?: info.serviceName
        val now = System.currentTimeMillis()
        val last = seen[key]
        if (last != null && (now - last) < DUPLICATE_WINDOW_MS) {
            AppLog.d(TAG, "skipping duplicate ${info.serviceName} (last=${now - last}ms ago)")
            return
        }
        seen[key] = now
        val found = Found(info.serviceName, host, port, packageName, ecName, huid, channel, flavor, huname)
        AppLog.i(TAG, "resolved -> $found")
        onResolved(found)
    }

    companion object {
        private const val TAG = "RidePanel.MdnsBrowse"
        const val SERVICE_TYPE = "_EasyConn._tcp."
        private const val DUPLICATE_WINDOW_MS = 30_000L
    }
}
