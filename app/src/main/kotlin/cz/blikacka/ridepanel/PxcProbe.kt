package cz.blikacka.ridepanel

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import org.json.JSONObject

/**
 * Discovery-style probe phone fires at a head-unit it just discovered via
 * mDNS — exact replica of `s.java::Q()` and the embedded `f` class
 * (`net.easyconn.carman.sdk_communication.s$f`) in the decompiled APK.
 *
 * Wire-level:
 *   - phone opens a TCP socket to the head-unit's mDNS-advertised IP+port
 *   - phone writes one PXC frame: cmd `0x70000010`, body =
 *       `{"phoneType":"Android","packageName":"<our pkg>"}` (UTF-8)
 *   - phone reads back exactly one PXC frame: cmd must be `0x70000011`
 *     (probe + 1) and body JSON must contain `"status":"true"`
 *   - phone CLOSES the socket either way
 *
 * The probe is the explicit "phone is alive" notification — after a
 * successful probe the head-unit dials TCP 10922 on the phone and the
 * regular [PxcServer] takes over.
 */
object PxcProbe {

    /** s$f.getCMD(): 1879048208 == 0x70000010. */
    const val CMD_PROBE: Int = 0x70000010
    const val CMD_PROBE_RESPONSE: Int = CMD_PROBE + 1

    data class Result(val ok: Boolean, val message: String)

    /** Synchronous; caller is responsible for running on a worker thread. */
    fun probe(
        host: InetAddress,
        port: Int,
        packageName: String,
        bindLocalAddress: InetAddress? = null,
        timeoutMs: Int = 3_000,
    ): Result {
        val socket = Socket()
        return try {
            if (bindLocalAddress != null) {
                runCatching { socket.bind(InetSocketAddress(bindLocalAddress, 0)) }
                    .onFailure { AppLog.w(TAG, "bind to $bindLocalAddress failed", it) }
            }
            socket.tcpNoDelay = true
            socket.connect(InetSocketAddress(host, port), timeoutMs)
            socket.soTimeout = timeoutMs
            val out = socket.getOutputStream()
            val input = socket.getInputStream()

            val body = JSONObject().apply {
                put("phoneType", "Android")
                put("packageName", packageName)
            }.toString().toByteArray(StandardCharsets.UTF_8)
            AppLog.i(TAG, "probing ${host.hostAddress}:$port — ${body.size} B JSON")
            PxcFrame.write(out, CMD_PROBE, body)

            val response = PxcFrame.read(input)
                ?: return Result(false, "probe response: stream closed")
            if (response.cmd != CMD_PROBE_RESPONSE) {
                return Result(false, "probe response cmd=0x${response.cmd.toString(16)} expected 0x${CMD_PROBE_RESPONSE.toString(16)}")
            }
            val responseBody = response.payload.toString(Charsets.UTF_8)
            AppLog.i(TAG, "probe response body: $responseBody")
            val status = try { JSONObject(responseBody).optString("status") } catch (_: Throwable) { "" }
            if (!"true".equals(status, ignoreCase = true)) {
                return Result(false, "probe status=$status (expected 'true')")
            }
            Result(true, "probe ok @ ${host.hostAddress}:$port")
        } catch (t: Throwable) {
            AppLog.w(TAG, "probe to ${host.hostAddress}:$port failed", t)
            Result(false, "probe error: ${t.javaClass.simpleName}: ${t.message ?: ""}")
        } finally {
            runCatching { socket.close() }
        }
    }

    private const val TAG = "RidePanel.Probe"
}
