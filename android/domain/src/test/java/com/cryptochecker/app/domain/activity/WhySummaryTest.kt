package com.cryptochecker.app.domain.activity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WhySummaryTest {

    private fun r(kind: ReasonKind, value: Double = 0.0, secondary: Double? = null, tone: ReasonTone = ReasonTone.NEUTRAL) =
        Reason(kind, tone, value, secondary)

    @Test
    fun calmWinsAndSuppressesThin() {
        val brief = WhySummary.brief(listOf(r(ReasonKind.MARKET_CALM), r(ReasonKind.VOLUME_LOW, 0.4)))
        assertEquals(WhyHeadline.CALM, brief.headline)
        assertTrue(brief.extras.isEmpty())
    }

    @Test
    fun coinOnly() {
        assertEquals(
            WhyHeadline.COIN_VOLUME,
            WhySummary.brief(listOf(r(ReasonKind.COIN_ONLY, 5.0, 0.2), r(ReasonKind.VOLUME_HIGH, 3.0))).headline
        )
        assertEquals(
            WhyHeadline.COIN,
            WhySummary.brief(listOf(r(ReasonKind.COIN_ONLY, 5.0, 0.2), r(ReasonKind.VOLUME_NORMAL, 1.0))).headline
        )
    }

    @Test
    fun againstMarket() {
        assertEquals(WhyHeadline.AGAINST, WhySummary.brief(listOf(r(ReasonKind.AGAINST_MARKET, -4.0, 2.0))).headline)
    }

    @Test
    fun marketWideAndLeader() {
        assertEquals(
            WhyHeadline.MARKET_VOLUME,
            WhySummary.brief(listOf(r(ReasonKind.MARKET_WIDE, 2.0, 2.5), r(ReasonKind.VOLUME_HIGH, 2.5))).headline
        )
        assertEquals(WhyHeadline.MARKET, WhySummary.brief(listOf(r(ReasonKind.MARKET_WIDE, 2.0, 2.5))).headline)
        assertEquals(WhyHeadline.MARKET, WhySummary.brief(listOf(r(ReasonKind.MARKET_LEADER, 2.0))).headline)
        assertEquals(
            WhyHeadline.MARKET_VOLUME,
            WhySummary.brief(listOf(r(ReasonKind.MARKET_LEADER, -3.0), r(ReasonKind.VOLUME_HIGH, 2.0))).headline
        )
        // Bitcoin unter der Marktschwelle: ruhig
        assertEquals(WhyHeadline.CALM, WhySummary.brief(listOf(r(ReasonKind.MARKET_LEADER, 0.4))).headline)
    }

    @Test
    fun extras() {
        val brief = WhySummary.brief(
            listOf(r(ReasonKind.MARKET_WIDE, 2.0, 3.0), r(ReasonKind.VOLUME_LOW, 0.5), r(ReasonKind.LEVERAGE_LONGS, 0.08))
        )
        assertEquals(WhyHeadline.MARKET, brief.headline)
        assertEquals(listOf(WhyExtra.THIN, WhyExtra.LONGS), brief.extras)
        assertEquals(listOf(WhyExtra.SHORTS), WhySummary.brief(listOf(r(ReasonKind.LEVERAGE_SHORTS, -0.08))).extras)
    }

    @Test
    fun emptyWithoutReasons() {
        assertTrue(WhySummary.brief(emptyList()).isEmpty)
        assertTrue(WhySummary.brief(listOf(r(ReasonKind.SENTIMENT, 50.0))).isEmpty)
    }

    @Test
    fun marks() {
        assertEquals(WhyMark.SUPPORTS, WhySummary.mark(r(ReasonKind.VOLUME_HIGH, 3.0)))
        assertEquals(WhyMark.SUPPORTS, WhySummary.mark(r(ReasonKind.VOLATILITY_HIGH, 2.5)))
        assertEquals(WhyMark.SUPPORTS, WhySummary.mark(r(ReasonKind.MARKET_WIDE, 2.0, 1.0)))
        assertEquals(WhyMark.NEUTRAL, WhySummary.mark(r(ReasonKind.MARKET_WIDE, 2.0, -1.0)))
        assertEquals(WhyMark.SUPPORTS, WhySummary.mark(r(ReasonKind.MARKET_LEADER, -2.0)))
        assertEquals(WhyMark.NEUTRAL, WhySummary.mark(r(ReasonKind.MARKET_LEADER, 1.0)))
        assertEquals(WhyMark.CAUTION, WhySummary.mark(r(ReasonKind.LEVERAGE_LONGS, 0.08)))
        assertEquals(WhyMark.CAUTION, WhySummary.mark(r(ReasonKind.LEVERAGE_SHORTS, -0.08)))
        assertEquals(WhyMark.CAUTION, WhySummary.mark(r(ReasonKind.VOLUME_LOW, 0.5)))
        assertEquals(WhyMark.CAUTION, WhySummary.mark(r(ReasonKind.AGAINST_MARKET, -4.0, 2.0)))
        assertEquals(WhyMark.CAUTION, WhySummary.mark(r(ReasonKind.SENTIMENT, 10.0, tone = ReasonTone.WARNING)))
        assertEquals(WhyMark.NEUTRAL, WhySummary.mark(r(ReasonKind.SENTIMENT, 50.0)))
        listOf(
            ReasonKind.VOLUME_NORMAL, ReasonKind.LEVERAGE_BALANCED, ReasonKind.MARKET_CALM, ReasonKind.VOLATILITY_NORMAL,
        ).forEach { assertEquals(WhyMark.NEUTRAL, WhySummary.mark(r(it))) }
    }
}
