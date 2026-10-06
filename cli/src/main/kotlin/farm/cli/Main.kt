package farm.cli

import farm.core.*
import kotlinx.coroutines.*
import java.io.File

/** Windows console runner: same engine, live text progress. */
fun main(args: Array<String>) {
    var csv = ""
    var region: String? = null
    var wins = 100
    var mode = DuelOutcome.WIN
    var boardId = 322L
    var boardEvery = 5L
    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--csv" -> csv = args.getOrElse(++i) { "" }
            "--region" -> region = args.getOrElse(++i) { null }.takeUnless { it == "all" }
            "--wins" -> wins = args.getOrElse(++i) { "100" }.toIntOrNull() ?: 100
            "--mode" -> mode = if (args.getOrElse(++i) { "win" } == "loss") DuelOutcome.LOSS else DuelOutcome.WIN
            "--board-id" -> boardId = args.getOrElse(++i) { "322" }.toLongOrNull() ?: 322L
            "--board-every-s" -> boardEvery = args.getOrElse(++i) { "5" }.toLongOrNull() ?: 5L
            "--help", "-h" -> {
                println("usage: sf3farm-cli --csv FILE [--region Mumbai|Tokyo|US|EU|all] [--wins N] [--mode win|loss] [--board-id 322] [--board-every-s 5]")
                return
            }
        }
        i++
    }
    if (csv.isEmpty()) {
        println("need --csv FILE")
        return
    }
    val accs = parseAccountsCsv(File(csv).readText())
    if (accs.isEmpty()) {
        println("no accounts parsed (need a guid column)")
        return
    }
    val hosts = region?.let { r -> Regions.ALL.firstOrNull { it.name.equals(r, true) }?.hosts }
    println("accounts=${accs.size} region=${region ?: "as listed"} wins=$wins mode=$mode")
    val states = accs.associate { it.guid to DuelProgress(it.guid, name = it.name) }
    val lock = Any()
    Runtime.getRuntime().addShutdownHook(Thread { println("\nstopping…") })

    runBlocking {
        val jobs = accs.map { a ->
            launch(Dispatchers.IO) {
                val sess = DuelSession(a.guid, a.sysid, hosts?.firstOrNull() ?: a.host.ifEmpty { Regions.ALL[0].hosts[0] }, wins, mode) { p ->
                    synchronized(lock) {
                        states[a.guid]?.let {
                            it.wins = p.wins; it.fails = p.fails
                            it.rating = p.rating; it.status = p.status
                        }
                    }
                }
                sess.run()
            }
        }
        val board = launch(Dispatchers.IO) {
            var conn: Sf3Conn? = null
            var bg = ""
            while (isActive) {
                try {
                    delay(boardEvery * 1000)
                    val a = accs.firstOrNull() ?: continue
                    val host = hosts?.firstOrNull() ?: a.host.ifEmpty { Regions.ALL[0].hosts[0] }
                    if (conn == null || bg != a.guid) {
                        try { conn?.close() } catch (_: Exception) {}
                        conn = Sf3Conn(host)
                        conn!!.handshake()
                        if (conn!!.login(a.guid, a.sysid) != null) throw IllegalStateException("board login failed")
                        bg = a.guid
                    }
                    val r = conn!!.call("get_leaderboards", Proto.msg { vint(1, boardId) })
                    val rpay = r.payload
                    if (r.err == null && rpay != null) {
                        val rows = boardRows(rpay)
                        synchronized(lock) {
                            println("--- board ${rows.size} rows ---")
                            rows.take(10).forEachIndexed { k, row ->
                                println("#${k + 1} ${row.name} ${row.rating}")
                            }
                            accs.forEach { ac ->
                                rankOf(rows, ac.name)?.let { println("  ${ac.name} rank=$it") }
                            }
                        }
                    }
                } catch (e: Exception) {
                    println("board: ${e.message?.take(80)}")
                    try { conn?.close() } catch (_: Exception) {}
                    conn = null
                }
            }
        }
        monitor(jobs, board, states, lock, accs)
        board.cancelAndJoin()
        try { jobs.forEach { it.cancelAndJoin() } } catch (_: Exception) {}
    }
    println("finished")
}

private suspend fun monitor(
    jobs: List<kotlinx.coroutines.Job>,
    board: kotlinx.coroutines.Job,
    states: Map<String, farm.core.DuelProgress>,
    lock: Any,
    accs: List<farm.core.FarmAccount>,
) {
    while (true) {
        kotlinx.coroutines.delay(5000)
        var allDone = false
        synchronized(lock) {
            val done = states.values.count { it.status == "target reached" || it.status == "stopped" }
            println("[${jobs.count { it.isCompleted }}/  accs done] " + states.values.joinToString(" ") {
                "${it.name.take(8)}:${it.wins}w/${it.fails}f"
            }.take(300))
            if (jobs.all { it.isCompleted } || done == accs.size) {
                board.cancel()
                allDone = true
            }
        }
        if (allDone) break
    }
}
