package cz.blikacka.ridepanel

import java.io.ByteArrayOutputStream

/**
 * Carbit / EasyConn BLE wire format used over the HUD GATT characteristics.
 *
 * On wire (one frame, [length] + 1 bytes total):
 *
 *   [0]            0x24 ('$')
 *   [1]            cmd (uint8)
 *   [2]            length (uint8)  = payloadLen + 4
 *   [3 .. len-2]   payload (payloadLen bytes)
 *   [len-1]        checksum = XOR of bytes [0], [1], [2] and every payload byte
 *   [len]          0x0A (LF)
 *
 * Source: `qg/a.java` (encoder) and `qg/g.java` (decoder).
 *
 * Frames larger than the negotiated MTU - 3 are split across multiple GATT
 * writes/notifications; the receiver concatenates until the trailing LF
 * arrives and the checksum verifies.
 *
 * Commands actually used by the pairing flow we care about (from
 * `net/easyconn/carman/ble/model/PacketCommand.java` and the `qg` package):
 *
 *   48 (0x30)  EC_BTP_CLIENT_INFO          phone -> car   {phoneType, phoneID}
 *   80 (0x50)  EC_BTP_REQUEST_BUILD_NET    bidirectional  empty / BuildNetStatusInfo JSON
 *   82 (0x52)  EC_BTP_NOTIFY_AP_INFO       phone -> car   PhoneApInfo JSON
 *   88 (0x58)  HANDSHAKE_OUTER_RESPONSE    car -> phone   key/uuid/pwd/flavor/(carBrand,Model,Cfg,Mtu)
 *   89 (0x59)  HANDSHAKE_INNER_RESPONSE    car -> phone   channel/uuid/pwd/supportFunction/blesdk/wpn
 *   96 (0x60)  VERIFY_RESULT               phone -> car   license byte (0=pass, 1=fail) + msg
 */
object BleFrame {

    const val START: Byte = 0x24
    const val LF: Byte = 0x0A

    const val CMD_EC_BTP_CLIENT_INFO: Byte = 48
    const val CMD_EC_BTP_REQUEST_BUILD_NET: Byte = 80
    const val CMD_EC_BTP_NOTIFY_BUILD_NET_FINISH: Byte = 81
    const val CMD_EC_BTP_NOTIFY_AP_INFO: Byte = 82
    /** Phone → car. Sent after VERIFY_RESULT when the INNER handshake
     *  response (cmd 0x59) advertises `blesdk!=1 && wpn==1`. Payload is
     *  32B phoneName (zero-padded) + 1B count + N × (4B IPv4 + 4B mask).
     *  Mirrors `qg/d.d(EC_BTP_P2C_EXTEND_CLIENT_INFO)` in decompiled APK. */
    const val CMD_EXTEND_CLIENT_INFO: Byte = 84
    const val CMD_HANDSHAKE_OUTER: Byte = 88
    const val CMD_HANDSHAKE_INNER: Byte = 89
    const val CMD_VERIFY_RESULT: Byte = 96.toByte()

    /** Head-unit pushes this when it wants the phone to set its wall-clock.
     *  Phone replies with the same cmd byte + 8B little-endian millis
     *  (System.currentTimeMillis + TimeZone.rawOffset). */
    const val CMD_SYNC_TIME: Byte = 1
    /** Head-unit pushes this when it wants the phone's clock as a localized
     *  string. Phone replies with cmd + UTF-8 bytes of
     *  "dd.MM.yyyy HH:mm:ss:zzz" (max 120 B). */
    const val CMD_QUERY_TIME: Byte = 85

    fun encode(cmd: Byte, payload: ByteArray): ByteArray {
        val length = payload.size + 4
        require(length in 4..255) { "BLE frame too large: $length" }
        val out = ByteArray(length + 1)
        out[0] = START
        out[1] = cmd
        out[2] = length.toByte()
        if (payload.isNotEmpty()) System.arraycopy(payload, 0, out, 3, payload.size)
        out[length - 1] = checksum(START, cmd, length, payload)
        out[length] = LF
        return out
    }

    private fun checksum(start: Byte, cmd: Byte, length: Int, payload: ByteArray): Byte {
        var x = (start.toInt() and 0xff) xor (cmd.toInt() and 0xff) xor (length and 0xff)
        for (b in payload) x = x xor (b.toInt() and 0xff)
        return x.toByte()
    }

    /** Reassembles complete frames from raw BLE notification chunks. */
    class Reassembler {
        private val buf = ByteArrayOutputStream(256)

        /** Returns the complete frames decoded after consuming [chunk]. */
        fun feed(chunk: ByteArray): List<Frame> {
            if (chunk.isNotEmpty() && chunk[0] == START) buf.reset()
            buf.write(chunk, 0, chunk.size)
            val out = mutableListOf<Frame>()
            while (true) {
                val raw = buf.toByteArray()
                if (raw.size < 4) break
                if (raw[0] != START) {
                    // out of sync — drop one byte and re-scan
                    buf.reset(); buf.write(raw, 1, raw.size - 1)
                    continue
                }
                val length = raw[2].toInt() and 0xff
                val total = length + 1
                if (raw.size < total) break
                val payload = raw.copyOfRange(3, length - 1)
                val expected = checksum(START, raw[1], length, payload)
                if (raw[length - 1] != expected || raw[length] != LF) {
                    buf.reset(); buf.write(raw, 1, raw.size - 1)
                    continue
                }
                out += Frame(raw[1], payload)
                buf.reset()
                if (raw.size > total) buf.write(raw, total, raw.size - total)
            }
            return out
        }
    }

    data class Frame(val cmd: Byte, val payload: ByteArray) {
        override fun equals(other: Any?): Boolean =
            other is Frame && cmd == other.cmd && payload.contentEquals(other.payload)
        override fun hashCode(): Int = 31 * cmd.toInt() + payload.contentHashCode()
    }
}
