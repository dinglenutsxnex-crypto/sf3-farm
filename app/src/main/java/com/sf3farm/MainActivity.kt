package com.sf3farm

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import farm.core.DuelOutcome
import farm.core.Regions
import farm.core.parseAccountsCsv
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var adapter: AccountAdapter
    private lateinit var tvBoard: TextView
    private lateinit var tvStatus: TextView
    private lateinit var spRegion: Spinner
    private lateinit var spMode: Spinner
    private lateinit var etWins: EditText
    private val pickCsv = 41

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        setContentView(R.layout.activity_main)
        if (FarmRunner.prefs == null) FarmRunner.prefs = Prefs(applicationContext)

        val rv: RecyclerView = findViewById(R.id.rv_accounts)
        rv.layoutManager = LinearLayoutManager(this)
        adapter = AccountAdapter(emptyList()) { guid, sel -> FarmRunner.toggleOne(guid, sel) }
        rv.adapter = adapter
        tvBoard = findViewById(R.id.tv_board)
        tvStatus = findViewById(R.id.tv_status)
        spRegion = findViewById(R.id.sp_region)
        spMode = findViewById(R.id.sp_mode)
        etWins = findViewById(R.id.et_wins)
        etWins.setText("1100")
        etWins.hint = "absolute total wins (0=inf)"

        spRegion.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item,
            listOf("as listed") + Regions.ALL.map { it.name }).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        spMode.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item,
            listOf("win", "loss")).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }

        findViewById<Button>(R.id.btn_csv).setOnClickListener { openCsv() }
        findViewById<Button>(R.id.btn_all).setOnClickListener { FarmRunner.toggleAll(true) }
        findViewById<Button>(R.id.btn_none).setOnClickListener { FarmRunner.toggleAll(false) }
        findViewById<Button>(R.id.btn_start).setOnClickListener { startRun() }
        findViewById<Button>(R.id.btn_stop).setOnClickListener { stopRun() }

        lifecycleScope.launch {
            FarmRunner.totals.collectLatest { t ->
                val bar: android.widget.ProgressBar = findViewById(R.id.pb_total)
                if (t.target > 0) {
                    bar.max = 1000
                    bar.progress = ((t.wins.toDouble() / t.target.toDouble()) * 1000).toInt().coerceIn(0, 1000)
                } else {
                    bar.isIndeterminate = t.running
                }
                findViewById<TextView>(R.id.tv_total).text =
                    "TOTAL ${t.wins}/${if (t.target > 0) t.target else "inf"}  ${"%.0f".format(t.perMin)}/min"
            }
        }
        lifecycleScope.launch {
            FarmRunner.accounts.collectLatest { list ->
                adapter.submit(list)
                val n = list.count { it.acct.selected }
                tvStatus.text = if (FarmRunner.running) "RUNNING ($n selected)" else "idle ($n selected)"
            }
        }
        lifecycleScope.launch {
            FarmRunner.board.collectLatest { bd ->
                if (bd.top.isEmpty()) {
                    tvBoard.text = bd.error ?: "board: waiting for first poll (5s)…"
                } else {
                    val ours = FarmRunner.accounts.value
                        .mapNotNull { a -> bd.top.indexOfFirst { it.name.equals(a.acct.name, ignoreCase = true) }
                            .takeIf { it >= 0 }?.let { (a.acct.name to it + 1) } }
                        .joinToString(" ") { "${it.first}#${it.second}" }
                    val top = bd.top.take(5).joinToString("\n") { r -> "#${bd.top.indexOf(r) + 1} ${r.name} ${r.rating}" }
                    tvBoard.text = "TOP:\n$top\n\nOURS: ${ours.ifEmpty { "(none in top 10 shown)" }}"
                }
            }
        }
    }

    private fun openCsv() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("text/csv", "text/comma-separated-values", "text/plain"))
        }
        @Suppress("DEPRECATION")
        startActivityForResult(i, pickCsv)
    }

    @Deprecated("legacy picker callback")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (req == pickCsv && res == Activity.RESULT_OK) {
            val uri: Uri = data?.data ?: return
            try {
                contentResolver.openInputStream(uri)?.use { ins ->
                    val text = ins.readBytes().toString(Charsets.UTF_8)
                    val accs = parseAccountsCsv(text)
                    if (accs.isEmpty()) {
                        Toast.makeText(this, "no accounts parsed (need guid column)", Toast.LENGTH_LONG).show()
                    } else {
                        FarmRunner.setAccounts(accs)
                        Toast.makeText(this, "${accs.size} accounts loaded (all selected)", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                Toast.makeText(this, "csv read failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun startRun() {
        if (FarmRunner.accounts.value.none { it.acct.selected }) {
            Toast.makeText(this, "load a CSV first", Toast.LENGTH_SHORT).show()
            return
        }
        val regionRaw = spRegion.selectedItem as String
        val region = if (regionRaw == "as listed") null else regionRaw
        val wins = etWins.text.toString().toLongOrNull() ?: 0L
        val mode = if ((spMode.selectedItem as String) == "loss") DuelOutcome.LOSS else DuelOutcome.WIN
        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(Intent(this, FarmService::class.java))
        } else {
            startService(Intent(this, FarmService::class.java))
        }
        FarmRunner.start(region, wins, mode)
        Toast.makeText(this, "grinding started", Toast.LENGTH_SHORT).show()
    }

    private fun stopRun() {
        FarmRunner.stop()
        stopService(Intent(this, FarmService::class.java))
    }
}
