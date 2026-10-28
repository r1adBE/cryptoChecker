package com.cryptochecker.app.data

import com.cryptochecker.app.data.local.model.AlarmCondition
import com.cryptochecker.app.data.local.model.AlarmEntity
import com.cryptochecker.app.data.local.model.WatchEntity
import com.cryptochecker.app.data.portfolio.PortfolioAlarmEntity
import com.cryptochecker.app.data.portfolio.PortfolioJson
import com.cryptochecker.app.data.portfolio.PortfolioTxEntity
import com.cryptochecker.app.domain.alarm.PortfolioAlarmKind
import com.cryptochecker.app.domain.portfolio.PortfolioTxType
import com.cryptochecker.app.domain.portfolio.WatchedAddressRules
import com.cryptochecker.app.parity.ParityJson
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.math.BigDecimal

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
    fun `every part the export writes is required`() {
        val settings = JSONObject(File(ParityJson.dir(), "backup_settings.json").readText()).getJSONObject("settings")
        fun favorites() = JSONObject().put("EXCHANGE", JSONArray()).put("COIN", JSONArray().put("BTC")).put("QUOTE", JSONArray())
        fun full() = JSONObject()
            .put("format", BackupFormat.FORMAT).put("version", BackupFormat.VERSION).put("createdAt", 1L)
            .put("watches", JSONArray()).put("alarms", JSONArray())
            .put("favorites", favorites()).put("settings", JSONObject(settings.toString()))
            .put("portfolio", JSONArray()).put("portfolioAlarms", JSONArray())
            .put(WatchedAddressRules.BACKUP_KEY, JSONArray())
        BackupFields.checkParts(full())
        listOf("createdAt", "watches", "alarms", "favorites", "settings", "portfolio", "portfolioAlarms", WatchedAddressRules.BACKUP_KEY)
            .forEach { key ->
                assertThrows(key, IllegalArgumentException::class.java) { BackupFields.checkParts(full().apply { remove(key) }) }
            }
        // Falscher Typ zählt wie fehlend
        assertThrows(IllegalArgumentException::class.java) { BackupFields.checkParts(full().put("portfolio", JSONObject())) }
        assertThrows(IllegalArgumentException::class.java) { BackupFields.checkParts(full().put("settings", JSONArray())) }
        // Jede Favoriten-Art als Liste von Texten, jedes Feld der Einstellungen
        assertThrows(IllegalArgumentException::class.java) {
            BackupFields.checkParts(full().put("favorites", favorites().apply { remove("QUOTE") }))
        }
        assertThrows(IllegalArgumentException::class.java) {
            BackupFields.checkParts(full().put("favorites", favorites().put("COIN", JSONArray().put(1))))
        }
        assertThrows(IllegalArgumentException::class.java) {
            BackupFields.checkParts(full().put("settings", JSONObject(settings.toString()).apply { remove("appLock") }))
        }
        // Ohne «version» unlesbar
        assertThrows(IllegalArgumentException::class.java) { BackupFormat.checkVersion(full().apply { remove("version") }) }
    }

    @Test
    fun `deep nesting is rejected, brackets in texts do not count`() {
        BackupLimits.checkDepth("""{"a":[{"b":"[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[["}]}""", max = 4)
        assertThrows(BackupLimits.TooLargeException::class.java) { BackupLimits.checkDepth("[".repeat(5), max = 4) }
        // Maskiertes Anführungszeichen beendet den Text nicht
        BackupLimits.checkDepth("""{"a":"x\"[[[[[[["}""", max = 2)
    }

    /** Transaktion so, wie die Sicherung sie schreibt. */
    private fun tx(coin: String = "BTC", type: PortfolioTxType = PortfolioTxType.BUY) =
        PortfolioJson.txToJson(PortfolioTxEntity(id = 1, coin = coin, type = type, amount = BigDecimal.ONE, priceUsdt = null, time = 1L))

    @Test
    fun `invalid portfolio entries are counted, not silently dropped`() {
        val array = JSONArray()
            .put(tx())
            .put(tx().put("amountText", "0"))
            .put(tx().put("type", "UNBEKANNT"))
            .put(tx(coin = "ETH").put("coin", ""))
        val parsed = PortfolioJson.parseTxs(array)
        assertEquals(1, parsed.items.size)
        assertEquals(3, parsed.invalid)
    }

    @Test
    fun `portfolio note is cut to 120 characters`() {
        val o = tx().put("note", "x".repeat(300))
        assertEquals(120, PortfolioJson.jsonToTx(o)!!.note!!.length)
        assertNull(PortfolioJson.jsonToTx(o.put("note", "   "))!!.note)
    }

    /** Pflicht- und wahlfreie Felder je Eintrag wie `backup_entries.json` (iOS `testEntryFieldsMatchFixture`). */
    @Test
    fun `entry fields match the fixture`() {
        val fixture = JSONObject(File(ParityJson.dir(), "backup_entries.json").readText())
        fun names(o: JSONObject) = o.keys().asSequence().associateWith { o.getString(it) }
        for (entry in BackupFields.ENTRIES) {
            val spec = fixture.getJSONObject(entry.part)
            assertEquals(entry.part, names(spec.getJSONObject("required")), entry.required.mapValues { it.value.name })
            assertEquals(entry.part, names(spec.getJSONObject("optional")), entry.optional.mapValues { it.value.name })
            val sample = spec.getJSONObject("sample")
            BackupFields.checkEntry(entry, sample)
            entry.required.keys.forEach { key ->
                assertThrows("${entry.part} ohne $key", IllegalArgumentException::class.java) {
                    BackupFields.checkEntry(entry, JSONObject(sample.toString()).apply { remove(key) })
                }
            }
            entry.optional.keys.forEach { key -> BackupFields.checkEntry(entry, JSONObject(sample.toString()).apply { remove(key) }) }
            assertThrows(IllegalArgumentException::class.java) { BackupFields.checkEntry(entry, "kein Objekt") }
        }
        val cases = fixture.getJSONArray("invalid")
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val entry = BackupFields.ENTRIES.single { it.part == c.getString("part") }
            val sample = JSONObject(fixture.getJSONObject(entry.part).getJSONObject("sample").toString()).put(c.getString("key"), c.get("value"))
            assertThrows(c.getString("name"), IllegalArgumentException::class.java) { BackupFields.checkEntry(entry, sample) }
        }
        // Was der Schreiber schreibt, besteht die Prüfung
        BackupFields.checkEntry(
            BackupFields.WATCH,
            BackupWatchlistCodec.watchToJson(WatchEntity(id = 1, exchangeKey = "Binance", exchangeName = "Binance", baseAsset = "BTC", quoteAsset = "USDT")),
        )
        BackupFields.checkEntry(
            BackupFields.ALARM,
            BackupWatchlistCodec.alarmToJson(AlarmEntity(id = 1, watchId = 1, condition = AlarmCondition.PRICE_ABOVE, threshold = 1.0)),
        )
        BackupFields.checkEntry(BackupFields.TX, tx())
        BackupFields.checkEntry(
            BackupFields.PORTFOLIO_ALARM,
            PortfolioJson.alarmToJson(PortfolioAlarmEntity(id = 1, kind = PortfolioAlarmKind.CHANGE_UP, threshold = 5.0)),
        )
    }
}
