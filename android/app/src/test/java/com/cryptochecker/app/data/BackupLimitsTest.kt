package com.cryptochecker.app.data

import com.cryptochecker.app.data.portfolio.PortfolioJson
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/** Grenzen beim Einlesen einer Sicherung und gezählte ungültige Portfolio-Einträge; Swift: `BackupLimitsTests`. */
class BackupLimitsTest {

    @Test
    fun `file larger than the limit is rejected`() {
        assertEquals(10, BackupLimits.readLimited(ByteArray(10).inputStream(), max = 10).size)
        assertThrows(BackupLimits.TooLargeException::class.java) {
            BackupLimits.readLimited(ByteArray(11).inputStream(), max = 10)
        }
    }

    @Test
    fun `deep nesting is rejected, brackets in texts do not count`() {
        BackupLimits.checkDepth("""{"a":[{"b":"[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[["}]}""", max = 4)
        assertThrows(BackupLimits.TooLargeException::class.java) { BackupLimits.checkDepth("[".repeat(5), max = 4) }
        // Maskiertes Anführungszeichen beendet den Text nicht
        BackupLimits.checkDepth("""{"a":"x\"[[[[[[["}""", max = 2)
    }

    @Test
    fun `invalid portfolio entries are counted, not silently dropped`() {
        val array = JSONArray(
            """[{"coin":"BTC","type":"BUY","amountText":"1","time":1},
               {"coin":"ETH","type":"BUY","time":2},
               "kein Objekt",
               {"coin":"","type":"SELL","amountText":"1"}]"""
        )
        val parsed = PortfolioJson.parseTxs(array)
        assertEquals(1, parsed.items.size)
        assertEquals(3, parsed.invalid)
    }

    @Test
    fun `portfolio note is cut to 120 characters`() {
        val o = org.json.JSONObject("""{"coin":"BTC","type":"BUY","amountText":"1","time":1}""").put("note", "x".repeat(300))
        assertEquals(120, PortfolioJson.jsonToTx(o)!!.note!!.length)
        assertNull(PortfolioJson.jsonToTx(o.put("note", "   "))!!.note)
    }
}
