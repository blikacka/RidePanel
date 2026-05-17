package cz.blikacka.ridepanel

import android.net.Uri

/**
 * Parser for the QR payload Carbit / EasyConn head-units display.
 *
 * Examples seen in the wild:
 *
 *   www.carbit.com.cn/down6/645/644/_ylqxos?modelid=…&sn=…&action=8
 *       &ssid=qj-5G-80bf&pwd=88888888&auth=WPA2
 *       &mac=54:44:4a:03:80:bf&name=qj-5G-80bf       ← AP mode
 *
 *   http://www.carbit.com.cn/?name=Carbit-1234&mac=aa:bb:…&sn=…    ← Wi-Fi Direct
 *
 *   http://www.carbit.com.cn/?action=8&bm=aa:bb:…                  ← BLE-driven
 *
 * Picking strategy — verified against `QrScanView.verifyScanResult()` and
 * `HWQrFragment.M0()` in the decompiled APK:
 *
 *   - `name` present (non-empty)              → Wi-Fi Direct path. The `mac`
 *                                                field is the head-unit's
 *                                                Wi-Fi P2P device address,
 *                                                NOT a regular AP BSSID.
 *                                                `ssid`/`pwd` are ignored
 *                                                (left over for legacy AP
 *                                                fallback that the original
 *                                                app simply discards).
 *   - else `ssid` + `pwd` present             → AP mode fallback (phone
 *                                                joins head-unit's hosted
 *                                                Wi-Fi). Original app
 *                                                rejects this with
 *                                                "qr_type_not_match" but we
 *                                                give it a try since the
 *                                                creds are right there.
 *   - else                                    → Unsupported.
 *
 * The host check is loose (`carbit.com.cn`) and we tolerate a missing scheme.
 */
object CarbitQr {

    sealed interface Result {
        /** Wi-Fi Direct (P2P) MAC of the head-unit — feed to [WifiDirectConnector]. */
        data class WifiDirect(val name: String, val mac: String, val sn: String?) : Result

        /** Head-unit hosts an AP — phone should join it. */
        data class WifiAp(
            val name: String?,
            val ssid: String,
            val pwd: String,
            val auth: String?,
        ) : Result {
            override fun toString(): String =
                "WifiAp(name=$name, ssid=$ssid, pwd=***(${pwd.length}), auth=$auth)"
        }

        /**
         * QR carries both: try the simpler AP join first (no WPS-PBC button
         * needed on the head-unit), then fall back to Wi-Fi Direct if that
         * fails or the SSID isn't visible.
         */
        data class Both(
            val name: String,
            val mac: String,
            val sn: String?,
            val ssid: String,
            val pwd: String,
            val auth: String?,
        ) : Result {
            fun asAp() = WifiAp(name, ssid, pwd, auth)
            fun asDirect() = WifiDirect(name, mac, sn)
            override fun toString(): String =
                "Both(name=$name, mac=$mac, ssid=$ssid, pwd=***(${pwd.length}), auth=$auth, sn=$sn)"
        }

        data class Unsupported(val reason: String) : Result
    }

    fun parse(text: String?): Result {
        if (text.isNullOrBlank()) return Result.Unsupported("empty QR")
        val normalized = if (text.contains("://")) text else "http://$text"
        val uri = try { Uri.parse(normalized) } catch (_: Throwable) {
            return Result.Unsupported("invalid URL")
        }
        val host = uri.host?.lowercase() ?: ""
        if (!host.endsWith("carbit.com.cn")) return Result.Unsupported("not a Carbit QR")

        val name = uri.getQueryParameter("name")?.takeUnless { it.isBlank() }
        val mac = uri.getQueryParameter("mac")?.takeUnless { it.isBlank() }
        val sn = uri.getQueryParameter("sn")?.takeUnless { it.isBlank() }
        val ssid = uri.getQueryParameter("ssid")?.takeUnless { it.isBlank() }
        val pwd = (uri.getQueryParameter("pwd") ?: uri.getQueryParameter("password"))
            ?.takeUnless { it.isBlank() }
        val auth = uri.getQueryParameter("auth")?.takeUnless { it.isBlank() }

        // Both paths available → try the simpler one (AP join) first.
        if (name != null && mac != null && ssid != null && pwd != null) {
            return Result.Both(name, mac, sn, ssid, pwd, auth)
        }
        // P2P-only variant (what `QrScanView` / `HWQrFragment` walk in the
        // original APK).
        if (name != null && mac != null) {
            return Result.WifiDirect(name, mac, sn)
        }
        // AP-only variant.
        if (ssid != null && pwd != null) {
            return Result.WifiAp(name, ssid, pwd, auth)
        }
        return Result.Unsupported("missing fields (name+mac or ssid+pwd)")
    }
}
