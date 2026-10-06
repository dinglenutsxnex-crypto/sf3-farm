package farm.core

import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest

/** Hashes, wire constants, stateful game client (one TCP session). */
object Sf3 {
    const val APP_ID = "com.nekki.shadowfight3"
    const val V = "19217"
    const val CONFIG_VER = "1.45.0.175.16722-prod"
    const val APP_VER = "1.45.5"
    const val MODEL = "google Pixel 4"
    const val BNAME = "UnityClient_ShadowFight3_UnityClientShadowFight3Release_ConfigurationAndroid"
    const val X_CERT = "D61109D768EDAA3AD2EFA9EF357BD1AE33D5F0AB"
    const val D_SUM = "9C4483BC"
    const val D2_NET = "2137978293"
    const val PORT = 443

    fun md5Hex(s: String): String = digest("MD5", s).joinToString("") { "%02x".format(it) }
    fun sha1Hex(s: String): String = digest("SHA-1", s).joinToString("") { "%02x".format(it) }
    private fun digest(alg: String, s: String): ByteArray =
        MessageDigest.getInstance(alg).digest(s.toByteArray(Charsets.UTF_8))

    fun loginPassword(session: String, guid: String) = md5Hex(session + guid).lowercase()
    fun loginF(session: String) = sha1Hex(session + X_CERT).uppercase()
    fun sumVal(session: String) = sha1Hex(session + D_SUM).uppercase()

    fun loginPayload(guid: String, pw: String, sysid: String, f: String): ByteArray {
        val primary = Proto.msg {
            vint(1, 1L)
            str(2, "{\"login\":\"$guid\",\"password\":\"$pw\"}")
        }
        val secondary = Proto.msg { vint(1, 6L); str(2, sysid) }
        val ext = "{\"platform\":\"Android\",\"v\":\"$V\",\"app_id\":\"$APP_ID\"," +
                "\"f\":\"$f\",\"bnumber\":\"$V\",\"bname\":\"$BNAME\"}"
        return Proto.msg {
            vint(1, 6L)
            bytes(2, primary)
            bytes(3, secondary)
            bytes(4, ext.toByteArray(Charsets.UTF_8))
        }
    }

    fun pingPayload(session: String, tsMs: Long): ByteArray {
        val net = sha1Hex(session + D2_NET).uppercase()
        val inner = Proto.msg {
            str(1, "net_data")
            str(2, net)
        }
        return Proto.msg {
            bytes(1, Proto.msg { vint(1, tsMs) })
            bytes(2, inner)
        }
    }
}

/** Safe field-list accessor. */
internal fun fl(m: Map<Int, List<Any>>, f: Int): List<Any> {
    @Suppress("UNCHECKED_CAST")
    return (m[f] as? List<Any>) ?: emptyList()
}

internal fun flong(m: Map<Int, List<Any>>, f: Int, i: Int = 0): Long? = fl(m, f).getOrNull(i) as? Long
internal fun fbytes(m: Map<Int, List<Any>>, f: Int, i: Int = 0): ByteArray? = fl(m, f).getOrNull(i) as? ByteArray

data class PlayerMini(
    val stateId: Long,
    val exp: Long,
    val level: Int,
    val name: String,
    val wins: Long?,
    val streak: Long?,
    val currencies: Map<Int, Long>,
    val openDuelBlob: ByteArray?,
)

class Sf3Conn(host: String, timeoutMs: Int = 15000) : AutoCloseable {
    private val sock = Socket()
    var req = 0L
        private set
    var session: String? = null
        private set

    init {
        sock.tcpNoDelay = true
        sock.connect(InetSocketAddress(host, Sf3.PORT), timeoutMs)
        sock.soTimeout = timeoutMs
    }

    data class Resp(val rid: Long?, val cmd: String?, val err: Long?, val errText: String?, val payload: ByteArray?)

    private val framer = Framer()

    private fun sendRaw(b: ByteArray) {
        sock.getOutputStream().write(b)
        sock.getOutputStream().flush()
    }

    private fun nextFrame(): ByteArray {
        while (true) {
            val buf = ByteArray(32768)
            val n = sock.getInputStream().read(buf)
            if (n == -1) throw IllegalStateException("connection closed by server")
            val frames = framer.feed(buf.copyOf(n))
            if (frames.isNotEmpty()) return frames[0].body
        }
    }

    fun handshake(): String {
        req += 1
        sendRaw(Proto.envelope("HANDSHAKE", Proto.msg { bytes(1, "SFA-NEBU-1".toByteArray()) }, req))
        val f = Proto.fields(nextFrame())
        val hs = Proto.fields(fbytes(f, 3) ?: error("no hs payload"))
        session = fbytes(hs, 2)?.toString(Charsets.UTF_8) ?: error("no session")
        return session!!
    }

    fun login(guid: String, sysid: String): Long? {
        val s = session ?: error("handshake first")
        val pay = Sf3.loginPayload(guid, Sf3.loginPassword(s, guid), sysid, Sf3.loginF(s))
        req += 1
        sendRaw(Proto.envelope("LOGIN", pay, req))
        val r = read()
        sock.soTimeout = 2000
        try {
            while (true) nextFrame()
        } catch (_: Exception) {
        }
        sock.soTimeout = 15000
        return r.err
    }

    fun read(): Resp {
        val f = Proto.fields(nextFrame())
        return Resp(
            flong(f, 1),
            fbytes(f, 2)?.toString(Charsets.UTF_8),
            flong(f, 4),
            fbytes(f, 5)?.toString(Charsets.UTF_8),
            fbytes(f, 3),
        )
    }

    fun call(cmd: String, payload: ByteArray?): Resp {
        req += 1
        sendRaw(Proto.envelope(cmd, payload, req))
        return read()
    }

    fun barePlayer(): PlayerMini? {
        val s = session ?: return null
        val h = Proto.msg { str(1, "sum"); str(2, Sf3.sumVal(s)) }
        val pay = Proto.msg {
            str(1, Sf3.CONFIG_VER)
            bytes(2, ByteArray(0))
            bytes(3, h)
            str(4, Sf3.MODEL)
            str(5, Sf3.APP_VER)
        }
        val r = call("get_player", pay)
        if (r.err != null || r.payload == null) return null
        val top = Proto.fields(r.payload)
        val innerRaw = fbytes(top, 1) ?: return null
        val inner = Proto.fields(innerRaw)
        val spRaw = fbytes(inner, 1) ?: return null
        val sp = Proto.fields(spRaw)
        val curs = mutableMapOf<Int, Long>()
        for (c in fl(inner, 4)) {
            if (c !is ByteArray) continue
            val m = Proto.fields(c)
            val id = flong(m, 1) ?: continue
            val v = flong(m, 2) ?: continue
            curs[id.toInt()] = v
        }
        var blob: ByteArray? = null
        val f13 = fbytes(inner, 13)
        if (f13 != null) blob = fbytes(Proto.fields(f13), 1)
        return PlayerMini(
            stateId = flong(inner, 9) ?: 0L,
            exp = flong(inner, 3) ?: 0L,
            level = (flong(sp, 4) ?: 1L).toInt(),
            name = fbytes(sp, 3)?.toString(Charsets.UTF_8) ?: "?",
            wins = flong(inner, 16),
            streak = flong(inner, 21),
            currencies = curs,
            openDuelBlob = blob,
        )
    }

    override fun close() {
        try {
            sock.close()
        } catch (_: Exception) {
        }
    }
}
