package com.cryptochecker.app.domain.portfolio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.ZoneId

/**
 * DE 256-€-Grenze (§ 22 Nr. 3) mit zum Tagesschluss geschätzter Belohnung: Vermerk «geschätzt» an der Grenzzeile (Karte
 * und CSV), mit erfasstem Wert keiner. iOS: `TaxDeIncomeEstimatedTests`.
 */
class TaxDeIncomeEstimatedTest {

    private val zone = ZoneId.of("UTC")
    private val day = 19783L // 1.3.2024
    private val rates = mapOf(day to BigDecimal("0.9"))
    private val texts = TaxSummaryTexts(
        title = "T", disclaimer = "D", item = "I", usdt = "USDT", target = "EUR", note = "N", orientation = "O",
        labels = mapOf(TaxLine.DE_INCOME_LIMIT to "LIMIT"), below = "below", reached = "reached", unknown = "unknown",
        incomplete = "inc", noFx = "nofx", estimated = "est",
    )

    private fun limit(price: BigDecimal?): Pair<TaxSummaryLine, String> {
        val reward = PortfolioTrade(1, "ETH", PortfolioTxType.REWARD, BigDecimal("0.01"), price, day * 86_400_000L + 43_200_000L)
        val s = TaxExport.yearSummary(listOf(reward), TaxTemplate.DE, TaxMethod.FIFO, 2024, zone, "EUR", rates, priceAt = { _, _ -> BigDecimal("3000") })
        val line = s.lines.single { it.line == TaxLine.DE_INCOME_LIMIT }
        val csv = TaxExport.summaryCsv(s, texts, emptyList()).split("\r\n").single { it.contains("LIMIT;") }
        return line to csv
    }

    @Test
    fun estimatedDayCloseIsMarkedAtTheLimit() {
        val (line, csv) = limit(null)
        assertEquals(TaxLimitStatus.BELOW, line.status)
        assertTrue(line.estimated)
        assertTrue(csv, csv.endsWith("below, est"))
    }

    @Test
    fun recordedValueIsNotMarked() {
        val (line, csv) = limit(BigDecimal("2900"))
        assertEquals(TaxLimitStatus.BELOW, line.status)
        assertFalse(line.estimated)
        assertTrue(csv, csv.endsWith("below"))
    }
}
