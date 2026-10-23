package com.cryptochecker.app.data

import com.cryptochecker.app.data.portfolio.PortfolioJson
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
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
    fun `backup larger than the read limit is not written`() {
        BackupLimits.checkWritable(10, max = 10)
        assertThrows(BackupLimits.ExportTooLargeException::class.java) { BackupLimits.checkWritable(11, max = 10) }
        // Schreiben und Lesen mit derselben Grenze, gleich wie iOS (`BackupLimits.maxBytes`)
        assertEquals(64 * 1024 * 1024, BackupLimits.MAX_BYTES)
        assertThrows(BackupLimits.ExportTooLargeException::class.java) { BackupLimits.checkWritable(BackupLimits.MAX_BYTES + 1) }
        // CSV-Import bleibt bei 16 MB
        assertEquals(16 * 1024 * 1024, BackupLimits.CSV_MAX_BYTES)
    }

    @Test
    fun `backup is written compact and read back`() {
        val root = JSONObject()
            .put("format", BackupFormat.FORMAT)
            .put("version", BackupFormat.VERSION)
            .put("watches", JSONArray().put(JSONObject().put("id", 7).put("note", "zwei\nZeilen")))
        val compact = BackupFormat.encode(root).toString(Charsets.UTF_8)
        // Keine Einrückung, kein Zeilenumbruch (der in der Notiz steht maskiert im Text)
        assertFalse(compact.contains("\n"))
        assertFalse(compact.contains("  "))
        assertTrue(compact.length < root.toString(2).length)
        val plain = BackupFormat.parse(compact) as BackupFormat.BackupFile.Plain
        assertEquals("zwei\nZeilen", JSONObject(plain.json).getJSONArray("watches").getJSONObject(0).getString("note"))
    }

    @Test
    fun `indented backup from older versions is still read`() {
        val indented = """
            {
              "format": "cryptochecker-backup",
              "version": 1,
              "createdAt": 1760000000000,
              "watches": [
                {
                  "id": 3,
                  "marketKey": "binance",
                  "baseAsset": "BTC",
                  "quoteAsset": "USDT"
                }
              ],
              "alarms": []
            }
        """.trimIndent()
        val plain = BackupFormat.parse(indented) as BackupFormat.BackupFile.Plain
        val root = JSONObject(plain.json)
        assertEquals(BackupFormat.FORMAT, root.getString("format"))
        assertEquals("BTC", root.getJSONArray("watches").getJSONObject(0).getString("baseAsset"))
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
