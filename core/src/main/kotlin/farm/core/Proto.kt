package farm.core

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.Deflater
import java.util.zip.Inflater

/** Nebuchadnezzar framing + protobuf field helpers (pure JVM, no Android). */
object Proto {

    fun writeVarint(v0: Long, out: MutableList<Byte>) {
        var v = v0
        while (v and -0x80L != 0L) {
            out.add(((v and 0x7F) or 0x80L).toByte())
            v = v ushr 7
        }
        out.add((v and 0x7F).toByte())
    }

    fun readVarint(data: ByteArray, start: Int): Pair<Long, Int>? {
        var v = 0L; var s = 0; var i = start
        while (i < data.size) {
            val b = data[i++].toInt() and 0xFF
            v = v or ((b and 0x7F).toLong() shl s)
            if (b and 0x80 == 0) return v to (i - start)
            s += 7
            if (s >= 64) break
        }
        return null
    }

    class W {
        private val b = mutableListOf<Byte>()
        fun vint(n: Int, v: Long) { writeVarint((n.toLong() shl 3) or 0L, b); writeVarint(v, b) }
        fun bytes(n: Int, d: ByteArray) { writeVarint((n.toLong() shl 3) or 2L, b); writeVarint(d.size.toLong(), b); d.forEach { b.add(it) } }
        fun str(n: Int, s: String) = bytes(n, s.toByteArray(Charsets.UTF_8))
        fun fixed64(n: Int, d: Double) {
            writeVarint((n.toLong() shl 3) or 1L, b)
            ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putDouble(d).array().forEach { b.add(it) }
        }
        fun bytesOut(): ByteArray = b.toByteArray()
    }

    fun msg(block: W.() -> Unit): ByteArray = W().apply(block).bytesOut()

    /** field -> values (Long for varint, ByteArray for bytes). */
    fun fields(data: ByteArray): Map<Int, List<Any>> {
        val out = mutableMapOf<Int, MutableList<Any>>()
        var p = 0
        while (p < data.size) {
            val (tag, n1) = readVarint(data, p) ?: break
            p += n1
            val fn = (tag shr 3).toInt(); val wt = (tag and 7L).toInt()
            when (wt) {
                0 -> { val (v, n2) = readVarint(data, p) ?: break; p += n2; out.getOrPut(fn) { mutableListOf() }.add(v) }
                2 -> {
                    val (ln, n2) = readVarint(data, p) ?: break; p += n2
                    val l = ln.toInt()
                    if (p + l > data.size) break
                    out.getOrPut(fn) { mutableListOf() }.add(data.copyOfRange(p, p + l)); p += l
                }
                1 -> { if (p + 8 > data.size) break; out.getOrPut(fn) { mutableListOf() }.add(data.copyOfRange(p, p + 8)); p += 8 }
                5 -> { if (p + 4 > data.size) break; out.getOrPut(fn) { mutableListOf() }.add(data.copyOfRange(p, p + 4)); p += 4 }
                else -> break
            }
        }
        return out
    }

    fun asLong(m: Map<Int, List<Any>>, f: Int, i: Int = 0): Long? = (m[f]?.getOrNull(i) as? Long)
    fun asBytes(m: Map<Int, List<Any>>, f: Int, i: Int = 0): ByteArray? = (m[f]?.getOrNull(i) as? ByteArray)

    /** Envelope {1: counter, 2: cmd, 3: payload?} + frame (0x01 raw / 0x02 deflated). */
    fun envelope(cmd: String, params: ByteArray?, counter: Long): ByteArray {
        val body = msg {
            vint(1, counter)
            str(2, cmd)
            if (params != null) bytes(3, params)
        }
        return if (body.size <= 255) byteArrayOf(0x01, body.size.toByte()) + body
        else {
            val c = deflate(body)
            byteArrayOf(0x02) + ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(c.size).array() + c
        }
    }

    fun deflate(d: ByteArray): ByteArray {
        val z = Deflater(6, true); z.setInput(d); z.finish()
        val out = ByteArrayOutputStream(d.size); val buf = ByteArray(8192)
        while (!z.finished()) { val n = z.deflate(buf); if (n > 0) out.write(buf, 0, n) }
        z.end(); return out.toByteArray()
    }

    fun inflate(d: ByteArray): ByteArray? = try {
        val z = Inflater(true); z.setInput(d)
        val out = ByteArrayOutputStream(d.size * 4); val buf = ByteArray(8192)
        while (!z.finished()) {
            val n = z.inflate(buf)
            if (n > 0) out.write(buf, 0, n) else if (z.needsInput()) break
        }
        z.end(); out.toByteArray().takeIf { it.isNotEmpty() }
    } catch (_: Exception) { null }

    data class Frame(val flag: Int, val body: ByteArray)
}
