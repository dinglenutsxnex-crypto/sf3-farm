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

    var prefs: Prefs? = null

    fun setAccounts(list: List<FarmAccount>) {
        _accounts.value = list.map { a ->
            AccountUi(a, wins = prefs?.getWins(a.guid) ?: 0)
        }
        _board.value = BoardUi()
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

    fun start(region: String?, winsPerAccount: Int, outcome: DuelOutcome) {
        if (running) return
        val list = _accounts.value.filter { it.acct.selected }
        if (list.isEmpty()) return
        running = true
        val hosts = region?.let { r -> Regions.ALL.firstOrNull { it.name == r }?.hosts }
        jobs = list.map { ui ->
            scope.launch {
                val host = hosts?.firstOrNull() ?: ui.acct.host.ifEmpty { Regions.ALL[0].hosts[0] }
                val sess = DuelSession(
                    guid = ui.acct.guid,
                    sysid = ui.acct.sysid,
                    host = host,
                    targetWins = winsPerAccount,
                    outcome = outcome,
                ) { p ->
                    prefs?.addWins(ui.acct.guid, p.wins)
                    update(ui.acct.guid) {
                        it.copy(wins = prefs?.getWins(ui.acct.guid) ?: p.wins,
                            fails = p.fails, rating = p.rating, status = p.status)
                    }
                }
                // note wins persisted cumulatively; engine counts this run only
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

    fun stop() {
        running = false
        jobs.forEach { it.cancel() }
        boardJob?.cancel()
        jobs = emptyList()
        boardJob = null
        _accounts.value = _accounts.value.map { it.copy(status = "stopped") }
    }

    private fun update(guid: String, f: (AccountUi) -> AccountUi) {
        _accounts.value = _accounts.value.map { if (it.acct.guid == guid) f(it) else it }
    }

    private var boardConn: Sf3Conn? = null
    private var boardGuid = ""
    private var boardSysid = ""
    private var boardHost = ""

    private suspend fun pollBoard() = withContext(Dispatchers.IO) {
        try {
            val ui = _accounts.value.firstOrNull { it.acct.selected } ?: return@withContext
            if (boardConn == null || boardGuid != ui.acct.guid || boardHost != ui.acct.host) {
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
                boardSysid = ui.acct.sysid
                boardHost = ui.acct.host
            }
            val r = boardConn!!.call("get_leaderboards", farm.core.Proto.msg { vint(1, 322L) })
            val pay = r.payload
            if (r.err != null || pay == null) throw IllegalStateException("lb err=${r.err}")
            val rows = boardRows(pay)
            val names = _accounts.value.map { it.acct.name.lowercase() }.toSet()
            _accounts.value = _accounts.value.map { a ->
                a.copy(rank = if (a.acct.name.lowercase() in names) rankOf(rows, a.acct.name) else a.rank)
            }
            _board.value = BoardUi(System.currentTimeMillis(), rows.take(10))
        } catch (e: Exception) {
            try { boardConn?.close() } catch (_: Exception) {}
            boardConn = null
            _board.value = BoardUi(System.currentTimeMillis(), _board.value.top, "board: ${e.message?.take(80)}")
        }
    }
}
