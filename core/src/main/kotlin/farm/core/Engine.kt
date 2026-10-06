package farm.core

/** Duel result codes (BrawlerFinishRequest f2). */
enum class DuelOutcome { WIN, LOSS }

/** One account's duel state + progress counters. All win counts are ABSOLUTE
 * server-side totals (BrawlerWinsCnt), never session-relative. */
data class DuelProgress(
    val guid: String,
    var name: String = "?",
    var serverWins: Long = 0,
    var runWins: Long = 0,
    var fails: Long = 0,
    var rating: Long? = null,
    var status: String = "idle",
)

object DuelBlobs {
    val WIN_ITEMS = hex("0a0508d10c10040a0508d20c10040a0508d93410020a0508dc341002")
    val WIN_STATS = hex("0802101f1a020101220209052a02010132080000803f0000803f3a08000000000000000042086666e63e6666e63e4a02030352020000")
    val ROUND_ENTRIES = listOf("08031001", "08041002", "08051003", "08061002", "0807").map(::hex)
}

private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

fun buildDuelFinish(blob: ByteArray, won: Boolean): ByteArray {
    return if (won) {
        Proto.msg {
            bytes(1, blob)
            vint(2, 1L)
            vint(3, 2L)
            for (e in DuelBlobs.ROUND_ENTRIES) bytes(4, e)
            vint(5, 2L)
            bytes(6, DuelBlobs.WIN_ITEMS)
            bytes(7, DuelBlobs.WIN_STATS)
        }
    } else {
        Proto.msg {
            bytes(1, blob)
            vint(2, 2L)
            vint(3, 2L)
        }
    }
}

/**
 * Owns one account's session: connect/login, recover stuck duels, run duel
 * rounds until the ABSOLUTE server win total reaches [targetTotal]
 * (<=0 = unlimited). Server total is re-read every 25 wins and at start, so
 * previous rounds (by us, by hand, by other runs) always count and restarts
 * resume exactly. Transport failure -> reconnect + relogin with backoff.
 */
class DuelSession(
    val guid: String,
    val sysid: String,
    val host: String,
    val targetTotal: Long = 0,
    val outcome: DuelOutcome = DuelOutcome.WIN,
    val syncEvery: Int = 25,
    val onProgress: (DuelProgress) -> Unit = {},
) {
    val progress = DuelProgress(guid)
    @Volatile var stop = false
    private var failsInRow = 0

    private fun backoff(): Long {
        failsInRow++
        return minOf(30_000L, 1000L shl minOf(failsInRow, 5))
    }

    private fun syncServerTotal(c: Sf3Conn): Long? {
        return try {
            val me = c.barePlayer() ?: return null
            if (me.name != "?") progress.name = me.name
            me.wins
        } catch (_: Exception) {
            null
        }
    }

    fun run() {
        while (!stop && !reached()) {
            try {
                Sf3Conn(host).use { c ->
                    c.handshake()
                    val le = c.login(guid, sysid)
                    if (le != null) {
                        progress.status = "login err=$le"
                        onProgress(progress)
                        Thread.sleep(backoff())
                        return@use
                    }
                    failsInRow = 0
                    syncServerTotal(c)?.let {
                        progress.serverWins = it
                        onProgress(progress)
                    }
                    if (reached()) return@use
                    // failsafe: finish what a dead run left open
                    val me = c.barePlayer()
                    if (me?.openDuelBlob != null) {
                        progress.status = "closing stuck duel"
                        onProgress(progress)
                        c.call("brawler_finish", buildDuelFinish(me.openDuelBlob, won = true))
                    }
                    progress.status = "grinding"
                    onProgress(progress)
                    var sincePing = 0
                    var sinceSync = 0
                    while (!stop && !reached()) {
                        if (sincePing++ % 10 == 0) {
                            try {
                                c.call("ping", Sf3.pingPayload(c.session!!, System.currentTimeMillis()))
                            } catch (_: Exception) {
                            }
                        }
                        val s = c.call("brawler_start", null)
                        if (s.err != null || s.payload == null) {
                            if (s.err == 50003L) {
                                val m2 = c.barePlayer()
                                val b = m2?.openDuelBlob
                                if (b != null) c.call("brawler_finish", buildDuelFinish(b, won = true))
                                progress.fails++
                                onProgress(progress)
                                continue
                            }
                            throw IllegalStateException("start err=${s.err} ${s.errText}")
                        }
                        val blob = fbytes(Proto.fields(s.payload), 1)
                            ?: throw IllegalStateException("no enemy blob")
                        val f = c.call("brawler_finish", buildDuelFinish(blob, won = outcome == DuelOutcome.WIN))
                        if (f.err == null && f.payload != null) {
                            progress.runWins++
                            val fr = Proto.fields(f.payload)
                            progress.rating = flong(fr, 1) ?: progress.rating
                        } else {
                            progress.fails++
                        }
                        if (++sinceSync >= syncEvery) {
                            sinceSync = 0
                            syncServerTotal(c)?.let { progress.serverWins = it }
                        }
                        onProgress(progress)
                    }
                }
            } catch (e: Exception) {
                if (stop) break
                progress.status = "reconnect: ${e.message?.take(60)}"
                onProgress(progress)
                try {
                    Thread.sleep(backoff())
                } catch (_: InterruptedException) {
                    break
                }
            }
        }
        progress.status = if (stop) "stopped" else "target reached"
        onProgress(progress)
    }

    private fun reached(): Boolean = targetTotal > 0 && progress.serverWins >= targetTotal
}
