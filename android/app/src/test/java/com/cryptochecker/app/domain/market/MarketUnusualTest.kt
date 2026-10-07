package com.cryptochecker.app.domain.market

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MarketUnusualTest {

    private fun coin(
        symbol: String,
        change: Double,
        volume: Double? = 1_000.0,
        cap: Double? = 100_000.0,
        funding: Double? = null,
    ) = UnusualCoin(symbol, symbol, change, volume, cap, funding)

    /** Zehn ruhige Coins mit gleichem Umsatz-Anteil (1 %), BTC +1 %. */
    private fun calmUniverse(btc: Double = 1.0) =
        listOf(coin("BTC", btc)) + (1..9).map { coin("C$it", btc) }

    private fun input(coins: List<UnusualCoin>, own: Map<String, Double> = emptyMap()) =
        UnusualInput(coins, own, time = 1_000L)

    @Test
    fun calmMarket_nothingNotable() {
        val report = MarketUnusual.evaluate(input(calmUniverse()))
        assertNotNull(report)
        assertTrue(report!!.rows.isEmpty())
        assertEquals(1.0, report.btc24h!!, 1e-9)
    }

    @Test
    fun noCoins_null() {
        assertNull(MarketUnusual.evaluate(input(emptyList())))
    }

    @Test
    fun strongerAndWeakerThanBtc_atThreePoints() {
        val coins = calmUniverse() + coin("UP", 4.0) + coin("DN", -1.9) + coin("NEAR", 3.9)
        val rows = MarketUnusual.evaluate(input(coins))!!.rows
        val up = rows.first { it.symbol == "UP" }
        assertEquals(UnusualFactKind.STRONGER_THAN_BTC, up.fact.kind)
        // −1.9 vs +1.0: Abstand 2.9 → nicht auffällig, und unter 2 % → auch nicht «gegen den Markt»
        assertTrue(rows.none { it.symbol == "DN" })
        // +3.9 vs +1.0: 2.9 Pp. → nicht auffällig
        assertTrue(rows.none { it.symbol == "NEAR" })
    }

    @Test
    fun weakerThanBtc_sameDirection() {
        val coins = calmUniverse(btc = -1.0) + coin("WEAK", -5.0)
        val row = MarketUnusual.evaluate(input(coins))!!.rows.single()
        assertEquals(UnusualFactKind.WEAKER_THAN_BTC, row.fact.kind)
    }

    @Test
    fun againstTheMarket_replacesRelativeFact() {
        val coins = calmUniverse(btc = 1.5) + coin("CONTRA", -2.5)
        val row = MarketUnusual.evaluate(input(coins))!!.rows.single()
        assertEquals(UnusualFactKind.AGAINST_MARKET, row.fact.kind)
        assertEquals(1, row.facts.size)
    }

    @Test
    fun againstTheMarket_needsBtcToMove() {
        // BTC praktisch flach (+0.2 %): «andere Richtung» bedeutet nichts; Abstand 2.7 Pp. auch nicht
        val coins = calmUniverse(btc = 0.2) + coin("X", -2.5)
        assertTrue(MarketUnusual.evaluate(input(coins))!!.rows.isEmpty())
    }

    @Test
    fun btcItself_neverComparedWithItself() {
        val facts = MarketUnusual.facts(coin("BTC", 8.0), btc = 8.0, typical = null)
        assertTrue(facts.isEmpty())
    }

    @Test
    fun volume_againstUniverseMedian() {
        // Umsatz-Anteil 2.4 % gegenüber üblich 1 %
        val coins = calmUniverse() + coin("VOL", 1.0, volume = 2_400.0)
        val row = MarketUnusual.evaluate(input(coins))!!.rows.single()
        assertEquals(UnusualFactKind.VOLUME, row.fact.kind)
        assertEquals(2.4, row.fact.volumeRatio!!, 1e-9)
    }

    @Test
    fun volume_ownHistoryWins() {
        // Für diesen Coin sind 3 % üblich: 2.4 % ist dann nicht auffällig
        val coins = calmUniverse() + coin("VOL", 1.0, volume = 2_400.0)
        val report = MarketUnusual.evaluate(input(coins, own = mapOf("VOL" to 0.03)))!!
        assertTrue(report.rows.isEmpty())
    }

    @Test
    fun volume_tooFewCoinsForMedian_noVolumeFact() {
        val coins = listOf(coin("BTC", 1.0), coin("A", 1.0, volume = 9_000.0), coin("B", 1.0))
        assertTrue(MarketUnusual.evaluate(input(coins))!!.rows.isEmpty())
    }

    @Test
    fun volume_missingCapOrVolume_skipped() {
        assertNull(MarketUnusual.turnover(coin("A", 1.0, volume = null)))
        assertNull(MarketUnusual.turnover(coin("A", 1.0, cap = 0.0)))
        assertNull(MarketUnusual.turnover(coin("A", 1.0, volume = Double.NaN)))
        assertEquals(0.01, MarketUnusual.turnover(coin("A", 1.0))!!, 1e-12)
    }

    @Test
    fun funding_thresholds() {
        val high = MarketUnusual.facts(coin("A", 1.0, funding = 0.05), btc = 1.0, typical = null)
        assertEquals(UnusualFactKind.FUNDING_HIGH, high.single().kind)
        val negative = MarketUnusual.facts(coin("A", 1.0, funding = -0.03), btc = 1.0, typical = null)
        assertEquals(UnusualFactKind.FUNDING_NEGATIVE, negative.single().kind)
        assertTrue(MarketUnusual.facts(coin("A", 1.0, funding = 0.049), btc = 1.0, typical = null).isEmpty())
        assertTrue(MarketUnusual.facts(coin("A", 1.0, funding = -0.029), btc = 1.0, typical = null).isEmpty())
    }

    @Test
    fun mostNotableFactFirst_andRowsRankedByScore() {
        val coins = calmUniverse() +
            // 12 Pp. stärker (Stärke 4) und Funding knapp hoch (1.0)
            coin("BIG", 13.0, funding = 0.05) +
            // Volumen 3× (Stärke 1.5)
            coin("VOL", 1.0, volume = 3_000.0) +
            // 4 Pp. stärker (1.33)
            coin("MID", 5.0)
        val rows = MarketUnusual.evaluate(input(coins))!!.rows
        assertEquals(listOf("BIG", "VOL", "MID"), rows.map { it.symbol })
        assertEquals(UnusualFactKind.STRONGER_THAN_BTC, rows[0].fact.kind)
        assertEquals(2, rows[0].facts.size)
        assertEquals(4.0 + 0.25, rows[0].score, 1e-9)
    }

    @Test
    fun atMostFiveRows() {
        val coins = calmUniverse() + (1..8).map { coin("U$it", 4.0 + it) }
        val rows = MarketUnusual.evaluate(input(coins))!!.rows
        assertEquals(MarketUnusual.MAX_ROWS, rows.size)
        assertEquals("U8", rows.first().symbol)
    }

    @Test
    fun withoutBtc_onlyVolumeAndFunding() {
        val coins = (1..6).map { coin("C$it", 1.0) } + coin("X", 15.0, funding = -0.05)
        val report = MarketUnusual.evaluate(input(coins))!!
        assertNull(report.btc24h)
        assertEquals(UnusualFactKind.FUNDING_NEGATIVE, report.rows.single().fact.kind)
    }

    @Test
    fun median_evenAndOdd() {
        assertEquals(2.0, MarketUnusual.median(listOf(3.0, 1.0, 2.0))!!, 1e-12)
        assertEquals(2.5, MarketUnusual.median(listOf(4.0, 1.0, 2.0, 3.0))!!, 1e-12)
        assertNull(MarketUnusual.median(emptyList()))
    }

    @Test
    fun history_oneSamplePerDay_keepsFourteenDays_dropsUnknownCoins() {
        var history = emptyMap<String, List<TurnoverSample>>()
        for (day in 100L..120L) {
            history = MarketUnusual.updateHistory(history, listOf(coin("A", 1.0, volume = day.toDouble())), day)
        }
        // Gleicher Tag zweimal: der jüngste Wert gilt
        history = MarketUnusual.updateHistory(history, listOf(coin("A", 1.0, volume = 5_000.0)), 120L)
        val a = history.getValue("A")
        assertEquals(MarketUnusual.HISTORY_DAYS, a.size)
        assertEquals(107L, a.first().day)
        assertEquals(0.05, a.last().value, 1e-12)
        // Coin nicht mehr dabei: verschwindet
        val other = MarketUnusual.updateHistory(history, listOf(coin("B", 1.0)), 121L)
        assertNull(other["A"])
        assertNotNull(other["B"])
    }

    @Test
    fun ownTypical_needsFivePreviousDays() {
        val samples = (1L..4L).map { TurnoverSample(it, 0.01 * it) } + TurnoverSample(10L, 0.9)
        assertNull(MarketUnusual.ownTypical(samples, today = 10L))
        val more = samples + TurnoverSample(5L, 0.05)
        // Vortage 1–5: 0.01 … 0.05 → Median 0.03 (heute zählt nicht)
        assertEquals(0.03, MarketUnusual.ownTypical(more, today = 10L)!!, 1e-12)
    }
}
