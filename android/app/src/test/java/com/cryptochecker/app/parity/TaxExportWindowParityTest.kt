package com.cryptochecker.app.parity

import com.cryptochecker.app.domain.portfolio.PortfolioTrade
import com.cryptochecker.app.domain.portfolio.PortfolioTxType
import com.cryptochecker.app.domain.portfolio.TaxDayClose
import com.cryptochecker.app.domain.portfolio.TaxExport
import com.cryptochecker.app.domain.portfolio.TaxMatch
import com.cryptochecker.app.domain.portfolio.TaxMethod
import com.cryptochecker.app.domain.portfolio.TaxTemplate
import com.cryptochecker.app.domain.portfolio.gainRows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId

/**
 * Gemeinsame Testfälle `tax_export.json` → `ukBbWindow` (iOS: `TaxExportWindowTests.swift`): UK 30-Tage-Regel über das
 * Ende des Steuerjahrs hinaus — Kurse und Tagesschlüsse bis 30 Tage nach dem 5. April, Einstand in GBP zum Kurs des
 * Zugangsdatums, kein Vermerk «ohne Devisenkurs».
 */
class TaxExportWindowParityTest {

    private val data = ParityJson.load("tax_export.json")["ukBbWindow"].obj()
    private val zone = ZoneId.of(data["zone"] as String)
    private val period = TaxTemplate.UK.period(data["year"].num()!!.toInt())
    private val currency = data["currency"] as String
    private val today = LocalDate.parse(data["today"] as String)
    private val rates = data["rates"].obj().entries.associate { it.key.toLong() to BigDecimal(it.value as String) }

    private val trades = data["trades"].list().map { it.obj() }.map { t ->
        PortfolioTrade(
            id = t["id"].num()!!.toLong(),
            coin = t["coin"] as String,
            type = PortfolioTxType.fromName(t["type"] as String)!!,
            amount = BigDecimal(t["amount"] as String),
            priceUsdt = (t["price"] as String?)?.let { BigDecimal(it) },
            time = t["time"].num()!!.toLong(),
        )
    }

    /** Tagesschluss der Belohnung ohne Preis (wie `feeDisposalPrices`, [TaxDayClose.lookup]). */
    private val priceAt = TaxDayClose.lookup(
        data["closes"].list().map { it.obj() }.associate {
            (it["coin"] as String to LocalDate.ofEpochDay(it["day"].num()!!.toLong())) to BigDecimal(it["close"] as String)
        }
    )

    @Test
    fun priceUntilCoversTheWindowAfterTheTaxYear() {
        data["priceUntil"].list().map { it.list() }.forEach { c ->
            val tpl = TaxTemplate.valueOf(c[0] as String)
            val p = tpl.period(data["year"].num()!!.toInt())
            assertEquals("priceUntil $c", LocalDate.parse(c[2] as String), TaxExport.priceUntil(tpl, p, LocalDate.parse(c[1] as String)))
        }
    }

    @Test
    fun fxSpanReachesTheLatestMatchedAcquisition() {
        val rules = TaxTemplate.UK.eventRules
        val until = TaxExport.priceUntil(TaxTemplate.UK, period, today)
        val (from, last) = TaxExport.fxSpan(trades, TaxMethod.UK_POOL, period, zone, priceAt, rules, until = until, today = today)
        // Erster Tag: Beginn des Steuerjahrs (der Pool-Kauf 1.6.2024 liegt darin)
        assertEquals(period.start, from)
        assertEquals(LocalDate.parse(data["fxUntil"] as String), last)
        // Ohne Fenster: das späteste Datum eines genutzten Zugangs (Rückkauf 20.4.)
        val (_, latest) = TaxExport.fxSpan(trades, TaxMethod.UK_POOL, period, zone, priceAt, rules, today = today)
        assertEquals(LocalDate.parse(data["fxUntilWithoutWindow"] as String), latest)
        // fxFrom unverändert
        assertEquals(from, TaxExport.fxFrom(trades, TaxMethod.UK_POOL, period, zone, priceAt, rules))
    }

    @Test
    fun costInGbpAtTheRateOfTheAcquisitionDate() {
        val rows = TaxExport.gainRows(trades, TaxMethod.UK_POOL, period, zone, currency, rates, priceAt, rules = TaxTemplate.UK.eventRules)
        val expected = data["rows"].list().map { it.obj() }
        assertEquals(expected.size, rows.size)
        for (e in expected) {
            val acquired = (e["acquired"] as String?)?.let { LocalDate.parse(it) }
            val r = rows.single { it.acquiredDate == acquired }
            val label = "row $acquired"
            assertEquals(label, TaxMatch.valueOf(e["match"] as String), r.match)
            assertEquals(label, 0, BigDecimal(e["amount"] as String).compareTo(r.amount))
            assertNotNull(label, r.costTarget)
            assertEquals("$label cost ${r.costTarget}", 0, BigDecimal(e["costTarget"] as String).compareTo(r.costTarget))
            assertEquals("$label proceeds ${r.proceedsTarget}", 0, BigDecimal(e["proceedsTarget"] as String).compareTo(r.proceedsTarget))
            assertFalse("$label noFx", r.fxMissing)
        }
        // Wie früher (Kurse nur bis zum Ende des Steuerjahrs): Zugänge nach dem 5. April ohne Devisenkurs
        val truncated = rates.filterKeys { it <= period.end.toEpochDay() }
        val old = TaxExport.gainRows(trades, TaxMethod.UK_POOL, period, zone, currency, truncated, priceAt, rules = TaxTemplate.UK.eventRules)
        assertEquals(data["truncatedFxMissing"].num()!!.toInt(), old.count { it.fxMissing })
    }
}
