package farm.core

/** Regions: node hosts + matching. Accounts are node-scoped per region. */
object Regions {
    data class Region(val name: String, val hosts: List<String>)

    val ALL = listOf(
        Region("Mumbai", listOf("52.66.28.201", "13.126.233.176")),
        Region("Tokyo", listOf(
            "ec2-35-73-37-159.ap-northeast-1.compute.amazonaws.com",
            "ec2-35-72-167-122.ap-northeast-1.compute.amazonaws.com")),
        Region("US", listOf(
            "ec2-34-215-125-222.us-west-2.compute.amazonaws.com",
            "ec2-34-214-128-171.us-west-2.compute.amazonaws.com")),
        Region("EU", listOf("54.246.136.56")),
    )

    fun forHost(host: String): Region? = simpleMatch(host)

    fun simpleMatch(host: String): Region? = when {
        host.contains("52.66.") || host.contains("13.126.") || host.contains("ap-south-1") -> ALL[0]
        host.contains("35.73.") || host.contains("35.72.") || host.contains("ap-northeast-1") -> ALL[1]
        host.contains("34.215.") || host.contains("34.214.") || host.contains("us-west-2") -> ALL[2]
        host.contains("54.246.") -> ALL[3]
        else -> null
    }
}

data class FarmAccount(
    val name: String,
    val guid: String,
    val sysid: String,
    val host: String,
    var selected: Boolean = true,
)

data class BoardRow(val pid: Long, val name: String, val power: Long, val rating: Long, val faction: Long)

/** Our CSV columns: worker,name,guid,sysid,host,chained,lvl,faction,duels,fails,coins1,code,wall_s */
fun parseAccountsCsv(text: String): List<FarmAccount> {
    val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
    if (lines.isEmpty()) return emptyList()
    val head = splitCsv(lines[0]).map { it.trim().lowercase() }
    fun col(vararg names: String): Int {
        for (n in names) {
            val i = head.indexOf(n)
            if (i >= 0) return i
        }
        return -1
    }
    val iName = col("name", "displayname")
    val iGuid = col("guid", "login", "userguid")
    val iSys = col("sysid", "systemid", "device")
    val iHost = col("host", "server", "node")
    if (iGuid < 0) return emptyList()
    return lines.drop(1).mapNotNull { ln ->
        val c = splitCsv(ln)
        val g = c.getOrNull(iGuid)?.trim() ?: return@mapNotNull null
        if (g.isEmpty()) return@mapNotNull null
        FarmAccount(
            name = if (iName >= 0) c.getOrNull(iName)?.trim().orEmpty() else "?",
            guid = g,
            sysid = if (iSys >= 0) c.getOrNull(iSys)?.trim().orEmpty() else "",
            host = if (iHost >= 0) c.getOrNull(iHost)?.trim().orEmpty() else Regions.ALL[0].hosts[0],
        )
    }
}

private fun splitCsv(line: String): List<String> {
    // simple CSV: no embedded commas in our files (names have no commas except... be safe)
    val out = mutableListOf<String>()
    val cur = StringBuilder()
    var q = false
    for (ch in line) {
        when {
            ch == '"' -> q = !q
            ch == ',' && !q -> { out.add(cur.toString()); cur.clear() }
            else -> cur.append(ch)
        }
    }
    out.add(cur.toString())
    return out
}

fun boardRows(payload: ByteArray): List<BoardRow> {
    val top = Proto.fields(payload)
    val inner = Proto.fields(fbytes(top, 2) ?: return emptyList())
    return fl(inner, 1).mapNotNull { r ->
        if (r !is ByteArray) return@mapNotNull null
        val m = Proto.fields(r)
        val pid = flong(m, 1) ?: return@mapNotNull null
        val nm = (fbytes(m, 2)?.toString(Charsets.UTF_8)) ?: "?"
        BoardRow(pid, nm, flong(m, 3) ?: 0L, flong(m, 4) ?: 0L, flong(m, 5) ?: 0L)
    }
}

fun rankOf(rows: List<BoardRow>, name: String): Int? {
    val want = name.lowercase()
    rows.forEachIndexed { i, r -> if (r.name.lowercase() == want) return i + 1 }
    return null
}
