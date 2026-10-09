package com.cryptochecker.app.domain.activity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WhyFactorsTest {

    private fun report(vararg reasons: Reason, price: Double? = 100.0, high30d: Double? = null) = WhyReport(
        price = price,
        change1h = null,
        change24h = null,
        reasons = reasons.toList(),
        hasMarketData = true,
        dataTime = 0L,
        high30d = high30d,
    )

    private val volumeHigh = Reason(ReasonKind.VOLUME_HIGH, ReasonTone.WARNING, 3.4, strong = true)
    private val marketWide = Reason(ReasonKind.MARKET_WIDE, ReasonTone.UP, 2.4, 4.0)
    private val volatilityHigh = Reason(ReasonKind.VOLATILITY_HIGH, ReasonTone.UP, 2.1, 1.2)
    private val fundingBalanced = Reason(ReasonKind.LEVERAGE_BALANCED, ReasonTone.NEUTRAL, 0.01, 8.0)
    private val sentimentNeutral = Reason(ReasonKind.SENTIMENT, ReasonTone.NEUTRAL, 52.0, 3.0)

    @Test
    fun ownerExampleIsRankedStrongestFirstAndCappedAtFive() {
        val factors = WhyFactors.rank(
            report(volumeHigh, marketWide, volatilityHigh, fundingBalanced, sentimentNeutral, high30d = 101.73)
        )
        assertEquals(WhyFactors.MAX_FACTORS, factors.size)
        // Volumen 3,4/2 = 1,7; Nähe zum Hoch 1,7 % → 1,66; Markt 2,4/1,5 = 1,6; Schwankung 2,1/2 = 1,05; OI 8/10 = 0,8
        assertEquals(
            listOf(
                WhyFactorKind.VOLUME, WhyFactorKind.NEAR_HIGH, WhyFactorKind.MARKET,
                WhyFactorKind.VOLATILITY, WhyFactorKind.OPEN_INTEREST,
            ),
            factors.map { it.kind },
        )
        assertTrue(factors.none { it.neutral })
        val volume = factors[0]
        assertEquals(WhyFactorDirection.UP, volume.direction)
        assertEquals(WhyFactorNote.VOLUME_HIGHER, volume.note)
        assertEquals(WhyFactorNote.MARKET_PULLS, factors[2].note)
        assertEquals(WhyFactorNote.HIGH_BELOW, factors[1].note)
        assertEquals(1.7, factors[1].value, 0.01)
        assertEquals(WhyFactorNote.OI_UP, factors[4].note)
    }

    @Test
    fun neutralFactorsComeLastInFixedOrder() {
        val factors = WhyFactors.rank(
            report(
                sentimentNeutral,
                Reason(ReasonKind.VOLUME_NORMAL, ReasonTone.NEUTRAL, 1.1),
                Reason(ReasonKind.LEVERAGE_LONGS, ReasonTone.WARNING, 0.08, null),
                Reason(ReasonKind.MARKET_CALM, ReasonTone.NEUTRAL, 0.3, 0.5),
            )
        )
        assertEquals(
            listOf(WhyFactorKind.FUNDING, WhyFactorKind.VOLUME, WhyFactorKind.MARKET, WhyFactorKind.SENTIMENT),
            factors.map { it.kind },
        )
        assertFalse(factors[0].neutral)
        assertEquals(WhyFactorNote.FUNDING_LONGS, factors[0].note)
        assertTrue(factors.drop(1).all { it.neutral })
    }

    @Test
    fun onlyFactorsWithData() {
        // Ohne Open Interest, ohne 30-Tage-Hoch: keine Zeilen dafür
        val factors = WhyFactors.rank(report(Reason(ReasonKind.LEVERAGE_BALANCED, ReasonTone.NEUTRAL, 0.0, null)))
        assertEquals(listOf(WhyFactorKind.FUNDING), factors.map { it.kind })
        assertEquals(WhyFactorNote.FUNDING_NEUTRAL, factors[0].note)
        assertTrue(WhyFactors.rank(report()).isEmpty())
        assertTrue(WhyFactors.rank(report(price = null, high30d = 120.0)).isEmpty())
    }

    @Test
    fun openInterestBelowFivePercentIsNeutral() {
        val small = WhyFactors.of(report(Reason(ReasonKind.LEVERAGE_BALANCED, ReasonTone.NEUTRAL, 0.0, -3.0)))
            .single { it.kind == WhyFactorKind.OPEN_INTEREST }
        assertTrue(small.neutral)
        assertEquals(WhyFactorNote.OI_FLAT, small.note)
        assertEquals(WhyFactorDirection.NONE, small.direction)
        val drop = WhyFactors.of(report(Reason(ReasonKind.LEVERAGE_SHORTS, ReasonTone.WARNING, -0.07, -12.0)))
            .single { it.kind == WhyFactorKind.OPEN_INTEREST }
        assertFalse(drop.neutral)
        assertEquals(WhyFactorNote.OI_DOWN, drop.note)
        assertEquals(WhyFactorDirection.DOWN, drop.direction)
    }

    @Test
    fun nearHighNotesAndThresholds() {
        fun near(price: Double) = WhyFactors.of(report(price = price, high30d = 100.0)).single()
        assertEquals(WhyFactorNote.HIGH_AT, near(100.0).note)
        // Kurs über dem Tageshoch (laufende Stunde neuer): auf dem Hoch, Abstand 0
        assertEquals(0.0, near(101.0).value, 0.0)
        assertEquals(2.0, near(101.0).strength, 1e-9)
        assertFalse(near(95.0).neutral)
        assertTrue(near(94.0).neutral)
        assertNull(WhyFactors.distanceToHighPercent(0.0, 100.0))
        assertNull(WhyFactors.distanceToHighPercent(50.0, Double.NaN))
    }

    @Test
    fun marketVariants() {
        fun market(r: Reason) = WhyFactors.of(report(r)).single()
        val lags = market(Reason(ReasonKind.MARKET_WIDE, ReasonTone.DOWN, -2.0, 0.4))
        assertEquals(WhyFactorNote.MARKET_COIN_LAGS, lags.note)
        assertEquals(WhyFactorDirection.DOWN, lags.direction)
        val alone = market(Reason(ReasonKind.COIN_ONLY, ReasonTone.UP, 6.0, 0.2, strong = true))
        assertEquals(WhyFactorNote.MARKET_COIN_ALONE, alone.note)
        assertEquals(0.2, alone.value, 0.0)
        assertEquals(2.0, alone.strength, 1e-9)
        val against = market(Reason(ReasonKind.AGAINST_MARKET, ReasonTone.DOWN, -4.0, 2.0, strong = true))
        assertEquals(WhyFactorNote.MARKET_AGAINST, against.note)
        assertEquals(WhyFactorDirection.UP, against.direction)
        val leaderCalm = market(Reason(ReasonKind.MARKET_LEADER, ReasonTone.UP, 0.8, 1.0))
        assertTrue(leaderCalm.neutral)
        assertEquals(WhyFactorNote.MARKET_LEADER_CALM, leaderCalm.note)
        val leaderMoves = market(Reason(ReasonKind.MARKET_LEADER, ReasonTone.DOWN, -3.0, -2.5, strong = true))
        assertEquals(WhyFactorNote.MARKET_LEADER_MOVES, leaderMoves.note)
        assertEquals(WhyFactorDirection.DOWN, leaderMoves.direction)
    }

    @Test
    fun extremeSentimentCountsAndGetsArrowOnBigChange() {
        val fear = WhyFactors.of(report(Reason(ReasonKind.SENTIMENT, ReasonTone.WARNING, 12.0, -9.0))).single()
        assertFalse(fear.neutral)
        assertEquals(WhyFactorDirection.DOWN, fear.direction)
        assertEquals(WhyFactorDirection.NONE, WhyFactors.of(report(sentimentNeutral)).single().direction)
    }

    @Test
    fun high30dNeedsThirtyRecentDays() {
        val day = ActivityAnalyzer.DAY_MILLIS
        val now = 100 * day + 5_000L
        fun candles(n: Int, endOpen: Long) = (0 until n).map { i ->
            val open = endOpen - (n - 1 - i) * day
            HourCandle(open, 10.0, if (i == 3) 50.0 else 20.0 + i * 0.1, 9.0, 10.0, 1.0)
        }
        assertNull(ActivityAnalyzer.high30d(null, now))
        assertNull(ActivityAnalyzer.high30d(candles(29, 100 * day), now))
        // 31 Kerzen: die älteste (Index 0) zählt nicht mehr, Index 3 schon
        assertEquals(50.0, ActivityAnalyzer.high30d(candles(31, 100 * day), now)!!, 0.0)
        // Reihe endet vor drei Tagen: kein Hoch
        assertNull(ActivityAnalyzer.high30d(candles(31, 97 * day), now))
    }
}
