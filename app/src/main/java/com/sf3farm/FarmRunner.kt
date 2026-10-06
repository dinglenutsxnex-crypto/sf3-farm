package com.sf3farm

import farm.core.BoardRow
import farm.core.DuelOutcome
import farm.core.DuelSession
import farm.core.FarmAccount
import farm.core.Regions
import farm.core.Sf3Conn
import farm.core.boardRows
import farm.core.rankOf
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class AccountUi(
    val acct: FarmAccount,
    val wins: Long = 0,
    val runWins: Long = 0,
    val fails: Long = 0,
    val rating: Long? = null,
    val status: String = "idle",
    val rank: Int? = null,
)

data class BoardUi(
    val updatedMs: Long = 0,
    val top: List<BoardRow> = emptyList(),
    val error: String? = null,
)

data class TotalsUi(
    val wins: Long = 0,
    val target: Long = 0,
    val perMin: Double = 0.0,
    val running: Boolean = false,
)

/** Process-wide run state: survives Activity recreation, owned by FarmService. */
object FarmRunner {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var jobs: List<Job> = emptyList()
    private var boardJob: Job? = null
    @Volatile var running = false
        private set

    private val _accounts = MutableStateFlow<List<AccountUi>>(emptyList())
    val accounts: StateFlow<List<AccountUi>> = _accounts
    private val _board = MutableStateFlow(BoardUi())
    val board: StateFlow<BoardUi> = _board
    private val _totals = MutableStateFlow(TotalsUi())
    val totals: StateFlow<TotalsUi> = _totals

    var prefs: Prefs? = null
    private var t0 = 0L
    private var w0 = 0L

    fun setAccounts(list: List<FarmAccount>) {
        _accounts.value = list.map { a ->
            AccountUi(a, wins = prefs?.getWins(a.guid) ?: 0)
        }
        _board.value = BoardUi()
        _totals.value = TotalsUi()
    }

    fun toggleAll(sel: Boolean) {
        _accounts.value = _accounts.value.map {
            it.copy(acct = it.acct.copy(selected = sel))
        }
    }

    fun toggleOne(guid: String, sel: Boolean) {
        _accounts.value = _accounts.value.map {
            if (it.acct.guid == guid) it.copy(acct = it.acct.copy(selected = sel)) else it
        }
    }

    fun start(region: String?, targetTotal: Long, outcome: DuelOutcome) {
        if (running) return
        val list = _accounts.value.filter { it.acct.selected }
        if (list.isEmpty()) return
        running = true
        t0 = System.currentTimeMillis()
        w0 = list.sumOf { prefs?.getWins(it.acct.guid) ?: it.wins }
        _totals.value = TotalsUi(w0, targetTotal * list.size.coerceAtLeast(1), 0.0, true)
        val hosts = region?.let { r -> Regions.ALL.firstOrNull { it.name == r }?.hosts }
        jobs = list.map { ui ->
            scope.launch {
                val host = hosts?.firstOrNull() ?: ui.acct.host.ifEmpty { Regions.ALL[0].hosts[0] }
                val sess = DuelSession(
                    guid = ui.acct.guid,
                    sysid = ui.acct.sysid,
                    host = host,
                    targetTotal = targetTotal,
                    outcome = outcome,
                ) { p ->
                    prefs?.setServerWins(ui.acct.guid, p.serverWins)
                    update(ui.acct.guid) {
                        it.copy(wins = prefs?.getWins(ui.acct.guid) ?: p.serverWins,
                            runWins = p.runWins, fails = p.fails,
                            rating = p.rating, status = p.status)
                    }
                    pushTotals(targetTotal)
                }
                sess.run()
            }
        }
        boardJob = scope.launch {
            while (running) {
                pollBoard()
                delay(5000)
            }
        }
    }

    private fun pushTotals(perAcctTarget: Long) {
        val cur = _accounts.value.filter { it.acct.selected }.sumOf { it.wins }
        val mins = (System.currentTimeMillis() - t0).coerceAtLeast(1) / 60000.0
        _totals.value = TotalsUi(cur, perAcctTarget * _accounts.value.count { it.acct.selected },
            (cur - w0) / mins, running)
    }

    fun stop() {
        running = false
        jobs.forEach { it.cancel() }
        boardJob?.cancel()
        jobs = emptyList()
        boardJob = null
        pushTotals(0)
        _accounts.value = _accounts.value.map { it.copy(status = "stopped") }
        _totals.value = _totals.value.copy(running = false)
    }

    private fun update(guid: String, f: (AccountUi) -> AccountUi) {
        _accounts.value = _accounts.value.map { if (it.acct.guid == guid) f(it) else it }
    }

    private var boardConn: Sf3Conn? = null
    private var boardGuid = ""
    private var boardHost = ""

    private suspend fun pollBoard() = withContext(Dispatchers.IO) {
        try {
            val ui = _accounts.value.firstOrNull { it.acct.selected } ?: return@withContext
            if (boardConn == null || boardGuid != ui.acct.guid) {
                try { boardConn?.close() } catch (_: Exception) {}
                boardConn = Sf3Conn(ui.acct.host.ifEmpty { Regions.ALL[0].hosts[0] })
                boardConn!!.handshake()
                val le = boardConn!!.login(ui.acct.guid, ui.acct.sysid)
                if (le != null) {
                    _board.value = BoardUi(System.currentTimeMillis(), emptyList(), "board login err=$le")
                    try { boardConn?.close() } catch (_: Exception) {}
                    boardConn = null
                    return@withContext
                }
                boardGuid = ui.acct.guid
                boardHost = ui.acct.host
            }
            val r = boardConn!!.call("get_leaderboards", farm.core.Proto.msg { vint(1, 322L) })
            val pay = r.payload
            if (r.err != null || pay == null) throw IllegalStateException("lb err=${r.err}")
            val rows = boardRows(pay)
            _accounts.value = _accounts.value.map { a ->
                a.copy(rank = rankOf(rows, a.acct.name))
            }
            _board.value = BoardUi(System.currentTimeMillis(), rows.take(10))
        } catch (e: Exception) {
            try { boardConn?.close() } catch (_: Exception) {}
            boardConn = null
            _board.value = BoardUi(System.currentTimeMillis(), _board.value.top, "board: ${e.message?.take(80)}")
        }
    }
}
