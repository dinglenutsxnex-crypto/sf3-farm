package farm.core

import org.junit.Assert.*
import org.junit.Test

class ProtoTest {
    @Test fun varintRoundTrip() {
        for (v in listOf(0L, 1L, 127L, 128L, 300L, 1791226939392L, Long.MAX_VALUE ushr 1)) {
            val b = mutableListOf<Byte>()
            Proto.writeVarint(v, b)
            val (back, n) = Proto.readVarint(b.toByteArray(), 0)!!
            assertEquals(v, back)
            assertEquals(b.size, n)
        }
    }

    @Test fun fieldsRoundTrip() {
        val body = Proto.msg {
            vint(1, 42L)
            str(2, "get_player")
            bytes(3, byteArrayOf(1, 2, 3))
        }
        val m = Proto.fields(body)
        assertEquals(42L, flong(m, 1))
        assertEquals("get_player", fbytes(m, 2)!!.toString(Charsets.UTF_8))
        assertArrayEquals(byteArrayOf(1, 2, 3), fbytes(m, 3))
    }

    @Test fun envelopeSmallUses01() {
        val e = Proto.envelope("ping", byteArrayOf(9, 9), 7L)
        assertEquals(0x01, e[0].toInt() and 0xFF)
        assertEquals(e.size - 2, e[1].toInt() and 0xFF)
    }

    @Test fun loginCryptoVectors() {
        // MD5("abc") / SHA1("abc") sanity for the digest plumbing
        assertEquals("900150983cd24fb0d6963f7d28e17f72", Sf3.md5Hex("abc"))
        assertEquals("a9993e364706816aba3e25717850c26c9cd0d89d", Sf3.sha1Hex("abc"))
        assertEquals(32, Sf3.loginPassword("s", "g").length)
        assertEquals(Sf3.loginF("s"), Sf3.loginF("s").uppercase())
    }

    @Test fun duelFinishShapes() {
        val blob = byteArrayOf(0x0a, 0x01, 0x02)
        val w = Proto.fields(buildDuelFinish(blob, won = true))
        assertEquals(1L, flong(w, 2))
        assertEquals(2L, flong(w, 5))
        assertEquals(5, fl(w, 4).size)
        val l = Proto.fields(buildDuelFinish(blob, won = false))
        assertEquals(2L, flong(l, 2))
        assertNull(fl(l, 4).firstOrNull())
    }

    @Test fun csvParse() {
        val csv = "worker,name,guid,sysid,host,chained,lvl\n0,FARM00,aaa,bbb,52.66.28.201,True,10\n"
        val a = parseAccountsCsv(csv)
        assertEquals(1, a.size)
        assertEquals("aaa", a[0].guid)
        assertEquals("52.66.28.201", a[0].host)
        assertTrue(a[0].selected)
    }

    @Test fun csvCaseInsensitiveRank() {
        val rows = listOf(BoardRow(1, "FuCk YoU KeKkI", 0, 100, 1))
        assertEquals(1, rankOf(rows, "fuck you kekki"))
        assertEquals(1, rankOf(rows, "FUCK YOU KEKKI"))
        assertNull(rankOf(rows, "nope"))
    }

    @Test fun regions() {
        assertEquals("Mumbai", Regions.simpleMatch("52.66.28.201")?.name)
        assertEquals("Tokyo", Regions.simpleMatch("ec2-35-73-37-159.ap-northeast-1.compute.amazonaws.com")?.name)
        assertEquals("US", Regions.simpleMatch("ec2-34-214-128-171.us-west-2.compute.amazonaws.com")?.name)
        assertEquals("EU", Regions.simpleMatch("54.246.136.56")?.name)
    }
}
