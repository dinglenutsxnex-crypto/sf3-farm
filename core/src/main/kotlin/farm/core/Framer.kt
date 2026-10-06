package farm.core

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Incremental frame parser over a growing buffer. */
class Framer {
    private val buf = ByteArrayOutputStream()

    fun feed(d: ByteArray): List<Proto.Frame> {
        buf.write(d)
        val raw = buf.toByteArray()
        val out = mutableListOf<Proto.Frame>()
        var p = 0
        while (p < raw.size) {
            val t = raw[p].toInt() and 0xFF
            var ln = -1
            var h = 0
            if (t == 0 || t == 2) {
                if (p + 5 > raw.size) break
                ln = ByteBuffer.wrap(raw, p + 1, 4).order(ByteOrder.LITTLE_ENDIAN).int and 0x7FFFFFFF
                if (ln <= 0 || ln > 8 * 1024 * 1024) break
                h = 5
            } else if (t == 1 || t == 3) {
                if (p + 2 > raw.size) break
                ln = raw[p + 1].toInt() and 0xFF
                if (ln <= 0) break
                h = 2
            } else {
                break
            }
            if (p + h + ln > raw.size) break
            var body = raw.copyOfRange(p + h, p + h + ln)
            if (t == 2 || t == 3) body = Proto.inflate(body) ?: break
            out.add(Proto.Frame(t, body))
            p += h + ln
        }
        buf.reset()
        if (p < raw.size) buf.write(raw, p, raw.size - p)
        return out
    }
}
