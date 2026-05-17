package cz.blikacka.ridepanel

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persists a small list of head-units we've successfully connected to so the
 * user doesn't have to re-scan the QR every time. Backed by SharedPreferences
 * (single JSON-array string under the key [KEY_LIST]).
 *
 * Records are keyed by lower-cased MAC — re-pairing the same head-unit
 * updates the existing row rather than appending a duplicate. The list is
 * ordered most-recently-connected first, capped at [MAX_ENTRIES] entries to
 * keep the main-screen UI usable.
 */
class KnownDevices(context: Context) {

    data class Entry(
        val mac: String,
        val name: String?,
        val ssid: String?,
        val pwd: String?,
        val auth: String?,
        val sn: String?,
        val lastConnected: Long,
    )

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun list(): List<Entry> {
        val raw = prefs.getString(KEY_LIST, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            val out = ArrayList<Entry>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val mac = o.optString("mac").takeIf { it.isNotEmpty() } ?: continue
                out += Entry(
                    mac = mac.lowercase(),
                    name = o.optString("name").ifEmpty { null },
                    ssid = o.optString("ssid").ifEmpty { null },
                    pwd = o.optString("pwd").ifEmpty { null },
                    auth = o.optString("auth").ifEmpty { null },
                    sn = o.optString("sn").ifEmpty { null },
                    lastConnected = o.optLong("lastConnected", 0L),
                )
            }
            out.sortedByDescending { it.lastConnected }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    fun upsert(entry: Entry) {
        val key = entry.mac.lowercase()
        val current = list().filterNot { it.mac == key }.toMutableList()
        current.add(0, entry.copy(mac = key, lastConnected = System.currentTimeMillis()))
        save(current.take(MAX_ENTRIES))
    }

    fun forget(mac: String) {
        val key = mac.lowercase()
        save(list().filterNot { it.mac == key })
    }

    private fun save(entries: List<Entry>) {
        val arr = JSONArray()
        for (e in entries) {
            arr.put(JSONObject().apply {
                put("mac", e.mac)
                e.name?.let { put("name", it) }
                e.ssid?.let { put("ssid", it) }
                e.pwd?.let { put("pwd", it) }
                e.auth?.let { put("auth", it) }
                e.sn?.let { put("sn", it) }
                put("lastConnected", e.lastConnected)
            })
        }
        prefs.edit().putString(KEY_LIST, arr.toString()).apply()
    }

    companion object {
        private const val PREFS = "ridepanel"
        private const val KEY_LIST = "known_devices"
        private const val MAX_ENTRIES = 8
    }
}
