package com.cryptochecker.app.parity

import com.cryptochecker.app.util.json.JsonArray
import com.cryptochecker.app.domain.portfolio.AddressChain
import com.cryptochecker.app.domain.portfolio.CutoffExport
import com.cryptochecker.app.domain.portfolio.TaxLine
import com.cryptochecker.app.domain.portfolio.TaxSummaryTexts
import com.cryptochecker.app.domain.portfolio.TaxWealthRow
import com.cryptochecker.app.domain.portfolio.WatchedAddressRules
import com.cryptochecker.app.domain.portfolio.wealthCoinNote
import org.junit.Assert.assertEquals
import org.junit.Test
import java.math.BigDecimal

/**
 * Sicherung mit Überschneidungs-Entscheid (`address_balance.json` «backupOverlap»), Formelschutz nach führenden
 * Leerzeichen und Hinweis «unvollständig» im CH-Vermögenswert — gleiche Fälle wie iOS `ReviewFixesB89Tests.swift`.
 */
class ReviewFixesB89ParityTest {

    private val data = ParityJson.load("address_balance.json")

    /** Entscheid «trotzdem zählen» (bzw. alte Regel) übersteht Sichern, Einspielen und erneutes Sichern. */
    @Test
    fun backupKeepsOverlapDecision() {
        val c = data["backupOverlap"].obj()
        val parsed = WatchedAddressRules.fromJson(JsonArray(c["text"] as String))
        val expected = c["expected"].list().map { it.obj() }
        assertEquals(expected.size, parsed.size)
        expected.zip(parsed).forEach { (x, e) ->
            val name = x["address"] as String
            assertEquals(name, AddressChain.fromName(x["chain"] as String), e.chain)
            assertEquals(name, x["address"], e.address)
            assertEquals("$name checked", x["overlapChecked"], e.overlapChecked)
            assertEquals("$name legacy", x["overlapLegacy"], e.overlapLegacy)
        }
        // Geschrieben nur die gesetzten Felder; zurückgelesen ohne Verlust
        val json = WatchedAddressRules.toJson(parsed)
        expected.forEachIndexed { i, x ->
            val o = json.getJSONObject(i)
            val written = listOf("overlapChecked", "overlapLegacy").filter { o.has(it) }
            assertEquals("${x["address"]} written", x["written"], written)
        }
        assertEquals(parsed, WatchedAddressRules.fromJson(json))
        // Einspielen übernimmt den Entscheid, und die nächste Sicherung schreibt ihn wieder
        val merged = WatchedAddressRules.merge(emptyList(), parsed)
        expected.zip(merged).forEach { (x, a) ->
            assertEquals("${x["address"]} merge checked", x["overlapChecked"], a.overlapChecked)
            assertEquals("${x["address"]} merge legacy", x["overlapLegacy"], a.overlapLegacy)
            assertEquals("${x["address"]} merge count", x["countInPortfolio"], a.countInPortfolio)
        }
        assertEquals(parsed, WatchedAddressRules.backupEntries(merged))
    }

    /** « =1+1» ist für Tabellen trotz Leerzeichen eine Formel; Zahlen und gewöhnlicher Text bleiben unverändert. */
    @Test
    fun formulaAfterLeadingSpaces() {
        assertEquals("' =1+1", CutoffExport.escape(" =1+1"))
        assertEquals("'   @SUM(A1)", CutoffExport.escape("   @SUM(A1)"))
        assertEquals("' +41", CutoffExport.escape(" +41"))
        assertEquals("' \t-x", CutoffExport.escape(" \t-x"))
        assertEquals("\"' =a;b\"", CutoffExport.escape(" =a;b"))
        assertEquals("  BTC", CutoffExport.escape("  BTC"))
        assertEquals("   ", CutoffExport.escape("   "))
        assertEquals(" -12.50", CutoffExport.escapeNumber(" -12.50"))
    }

    /** Nur ein Teil fehlt: Wert steht, Hinweis «unvollständig – Grund» statt «nicht gerechnet». */
    @Test
    fun wealthNoteForPartialCoin() {
        val texts = TaxSummaryTexts(
            title = "T", disclaimer = "D", item = "Item", usdt = "USDT", target = "CHF", note = "Note", orientation = "O",
            labels = TaxLine.entries.associateWith { it.name }, below = "below", reached = "reached", unknown = "unknown",
            incomplete = "incomplete", noFx = "nofx", wealthMissing = "nc", wealthAddressUnobserved = "unobserved",
        )
        val partial = TaxWealthRow(
            "BTC", BigDecimal("0.1"), BigDecimal("60000"), BigDecimal("6000"), BigDecimal("4800"),
            addressUnobserved = true, incomplete = true,
        )
        assertEquals("incomplete – unobserved", texts.wealthCoinNote(partial))
        val missing = partial.copy(valueUsdt = null, valueTarget = null, incomplete = false)
        assertEquals("nc – unobserved", texts.wealthCoinNote(missing))
    }
}
