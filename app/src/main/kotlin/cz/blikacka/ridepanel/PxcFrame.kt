package cz.blikacka.ridepanel

import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Wire format used by Carbit Ride / EasyConn between the phone (server)
 * and the head-unit (client) on TCP port 10922.
 *
 * Header is 16 bytes, little-endian:
 *   [0..3]   cmd        - command id, also identifies the channel for the
 *                         very first frame of a new TCP connection
 *   [4..7]   length     - total frame length INCLUDING the 16-byte header
 *   [8..11]  magic      - cmd XOR length (sanity check)
 *   [12..15] reserved   - read but unused; we send zeros
 *
 * Payload follows: (length - 16) bytes.
 *
 * Channels observed in the decompiled APK (first cmd of a new socket)
 * and their corresponding data-channel partner:
 *
 *   0x10000 - main control                   (b0)        ↔ 0x20000 main data
 *   0x30000 - secondary control              (f0)        ↔ 0x40000 data
 *   0x33000 - tertiary control               (a0)        ↔ 0x34000 data
 *   0x50000 - extra control                  (e0)        ↔ ...
 *   0x60000 - extra control                              ↔ 0x70000 data
 *
 * Each channel-open is acknowledged with `cmd + 1` and an empty body. After
 * that, the same TCP connection carries ECP_C2P_* request frames; every
 * request gets an automatic response `cmd + 1` per `t0.d()` in
 * `net/easyconn/carman/sdk_communication/t0.java`.
 */
object PxcFrame {

    const val PORT = 10922
    const val HEADER_SIZE = 16

    // Channel-open commands
    const val CH_MAIN_CTRL: Int = 0x10000
    const val CH_MAIN_DATA: Int = 0x20000

    // ECP_C2P_* request command ids (head-unit -> phone)
    const val ECP_C2P_CLIENT_INFO: Int = 65552       // 0x10010
    const val ECP_C2P_CHECK_NETWORK: Int = 65568     // 0x10020 (ih.k)
    const val ECP_C2P_CAR_INFO: Int = 65584          // 0x10030 (ih.f)
    const val ECP_C2P_CHECK_SN: Int = 66528          // 0x10320 (ih.m)
    const val ECP_C2P_DOWNLOAD_CAR_UUID_LICENSE: Int = 66496  // 0x10380 (ih.r)
    const val ECP_C2P_REGISTER_CAR_UUID: Int = 66512 // 0x10390 (ih.o0)
    const val ECP_C2P_HARDWARE_AUTH_STATE: Int = 67440  // 0x10770 (ih.w)

    fun encodeHeader(cmd: Int, payloadLen: Int): ByteArray {
        val total = payloadLen + HEADER_SIZE
        val buf = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(cmd)
        buf.putInt(total)
        buf.putInt(cmd xor total)
        buf.putInt(0)
        return buf.array()
    }

    /** Read a frame from [input]; returns null if the stream ended cleanly. */
    fun read(input: InputStream): Frame? {
        val header = ByteArray(HEADER_SIZE)
        if (!readFully(input, header)) return null
        val bb = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        val cmd = bb.int
        val length = bb.int
        val magic = bb.int
        if ((cmd xor length) != magic) {
            throw IllegalStateException("PXC magic mismatch: cmd=$cmd len=$length magic=$magic")
        }
        val payloadLen = length - HEADER_SIZE
        if (payloadLen < 0) throw IllegalStateException("PXC negative payload: $payloadLen")
        val payload = ByteArray(payloadLen)
        if (payloadLen > 0 && !readFully(input, payload)) {
            throw IllegalStateException("PXC truncated payload")
        }
        return Frame(cmd, payload)
    }

    fun write(output: OutputStream, cmd: Int, payload: ByteArray, off: Int = 0, len: Int = payload.size) {
        synchronized(output) {
            output.write(encodeHeader(cmd, len))
            if (len > 0) output.write(payload, off, len)
            output.flush()
        }
    }

    private fun readFully(input: InputStream, buf: ByteArray): Boolean {
        var read = 0
        while (read < buf.size) {
            val n = input.read(buf, read, buf.size - read)
            if (n < 0) return false
            if (n == 0) return false
            read += n
        }
        return true
    }

    data class Frame(val cmd: Int, val payload: ByteArray) {
        override fun equals(other: Any?): Boolean =
            other is Frame && cmd == other.cmd && payload.contentEquals(other.payload)
        override fun hashCode(): Int = 31 * cmd + payload.contentHashCode()
    }
}
