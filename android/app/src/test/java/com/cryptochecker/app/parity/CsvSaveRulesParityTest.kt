package com.cryptochecker.app.parity

import com.cryptochecker.app.data.portfolio.PortfolioJson
import com.cryptochecker.app.domain.portfolio.PortfolioDecimal
import com.cryptochecker.app.domain.portfolio.csvimport.CsvImport
import com.cryptochecker.app.domain.portfolio.csvimport.CsvImportParser
import com.cryptochecker.app.domain.portfolio.csvimport.CsvImportRateDays
import java.math.BigDecimal
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Was der CSV-Import speichert (`csv_import.json` › «priceText», «importKeys», «cleanImportKeys», «rateDays», «rateCover»):
 * Preise mit ihren gültigen Stellen, lange Herkunftsschlüssel als Hash, Schlüssel in UTF-8-Bytes begrenzt und Tageskurse
 * neu geladen, wenn eine andere Zeitzone einen Tag verschiebt. Gegenstück: `Tests/CsvSaveRulesTests.swift`.
 */
class CsvSaveRulesParityTest {

    private val data = ParityJson.load("csv_import.json")

    private fun cases(name: String) = data[name].list().map { it.obj() }

    @Test
    fun preiseBehaltenIhreStellen() {
        val all = cases("priceText")
        assertTrue(all.size >= 10)
        for (c in all) {
            val value = BigDecimal(c["value"] as String)
            assertEquals("priceText $c", c["expected"], PortfolioDecimal.priceText(value))
            // Gespeichert und wieder gelesen: unverändert (Lesegrenze 40 Nachkommastellen)
            val stored = PortfolioDecimal.priceText(value)
            val read = requireNotNull(PortfolioDecimal.parse(stored)) { "unlesbar: $stored" }
            assertEquals("roundtrip $c", 0, PortfolioDecimal.price(value).compareTo(read))
        }
        // Mengen bleiben bei 18 Nachkommastellen
        assertEquals("0", PortfolioDecimal.text(BigDecimal("0.0000000000000000004")))
    }

    @Test
    fun langeSchluesselWerdenZumHash() {
        for (c in cases("importKeys")) {
            val keys = c["keys"].list().map { it as String }
            val out = CsvImport.withOccurrence(keys)
            assertEquals(c["name"] as String, c["expected"].list(), out)
            out.forEach { assertTrue("${c["name"]}: gespeichert", PortfolioJson.cleanImportKey(it) == it) }
        }
        assertEquals(PortfolioJson.MAX_IMPORT_KEY, CsvImport.MAX_KEY_BYTES)
    }

    @Test
    fun schluesselInUtf8BytesBegrenzt() {
        for (c in cases("cleanImportKeys")) {
            assertEquals("cleanImportKey $c", c["expected"], PortfolioJson.cleanImportKey(c["key"] as String))
        }
    }

    private fun days(v: Any?): Map<String, LongRange> = v.obj().mapValues { (_, r) ->
        val (first, last) = r.list().map { LocalDate.parse(it as String).toEpochDay() }
        first..last
    }

    @Test
    fun andereZeitzoneLaedtKurseNeuWennEinTagHerausfaellt() {
        val files = data["files"].list().map { it.obj() }
        val all = cases("rateDays")
        assertTrue(all.any { it["cover"] == false } && all.any { it["cover"] == true })
        for (c in all) {
            val csv = files.first { it["name"] == c["file"] }["csv"] as String
            val device = c["deviceZone"] as String
            val loaded = CsvImportParser.parse(csv, device, c["loaded"] as String?).let { CsvImportRateDays.of(it.rows, it.fxZone) }
            val reread = CsvImportParser.parse(csv, device, c["reread"] as String?).let { CsvImportRateDays.of(it.rows, it.fxZone) }
            val m = c["name"] as String
            assertEquals("$m loaded", days(c["loadedDays"]), loaded)
            assertEquals("$m reread", days(c["rereadDays"]), reread)
            assertEquals("$m cover", c["cover"], CsvImportRateDays.cover(loaded, reread))
        }
        for (c in cases("rateCover")) {
            assertEquals(c["name"] as String, c["expected"], CsvImportRateDays.cover(days(c["loaded"]), days(c["needed"])))
        }
        assertFalse(CsvImportRateDays.USDT_USD in CsvImport.FX_FIAT)
    }
}
