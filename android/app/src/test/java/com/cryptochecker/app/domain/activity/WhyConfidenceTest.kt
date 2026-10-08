package com.cryptochecker.app.domain.activity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WhyConfidenceTest {

    private fun r(kind: ReasonKind, value: Double = 0.0, secondary: Double? = null, strong: Boolean = false) =
        Reason(kind, ReasonTone.NEUTRAL, value, secondary, strong)

    @Test
    fun noDataNoConfidence() {
        assertNull(WhyConfidenceRules.evaluate(emptyList(), hasMarketData = true))
        assertNull(WhyConfidenceRules.evaluate(listOf(r(ReasonKind.COIN_ONLY, 5.0, 0.1, true)), hasMarketData = false))
        // Nur Stimmung: kein prüfbarer Hinweis
        assertNull(WhyConfidenceRules.evaluate(listOf(r(ReasonKind.SENTIMENT, 50.0)), hasMarketData = true))
    }

    @Test
    fun highWithThreeStrongAgreeingAndFullData() {
        val c = WhyConfidenceRules.evaluate(
            listOf(
                r(ReasonKind.COIN_ONLY, 8.0, 0.3, strong = true),
                r(ReasonKind.VOLUME_HIGH, 4.0, strong = true),
                r(ReasonKind.VOLATILITY_HIGH, 3.5, 2.0, strong = true),
                r(ReasonKind.LEVERAGE_BALANCED, 0.01),
                r(ReasonKind.SENTIMENT, 60.0),
            ),
            hasMarketData = true,
        )!!
        assertEquals(WhyConfidenceLevel.HIGH, c.level)
        assertEquals(3, c.agreeing)
        assertEquals(4, c.total)
        assertFalse(c.partialData)
    }

    @Test
    fun mediumWhenAgreeingButNotEnoughStrong() {
        val c = WhyConfidenceRules.evaluate(
            listOf(
                r(ReasonKind.MARKET_WIDE, 2.0, 2.5),
                r(ReasonKind.VOLUME_HIGH, 2.2),
                r(ReasonKind.VOLATILITY_NORMAL, 1.0, 0.2),
            ),
            hasMarketData = true,
        )!!
        assertEquals(WhyConfidenceLevel.MEDIUM, c.level)
        assertEquals(2, c.agreeing)
        assertEquals(3, c.total)
    }

    @Test
    fun thinVolumeBlocksHigh() {
        val c = WhyConfidenceRules.evaluate(
            listOf(
                r(ReasonKind.AGAINST_MARKET, -6.0, 2.0, strong = true),
                r(ReasonKind.VOLUME_LOW, 0.4),
                r(ReasonKind.VOLATILITY_HIGH, 4.0, -3.0, strong = true),
                r(ReasonKind.LEVERAGE_SHORTS, -0.2, null, strong = true),
            ),
            hasMarketData = true,
        )!!
        assertEquals(3, c.agreeing)
        assertEquals(WhyConfidenceLevel.MEDIUM, c.level)
    }

    @Test
    fun lowWithOneWeakHint() {
        val c = WhyConfidenceRules.evaluate(
            listOf(
                r(ReasonKind.MARKET_WIDE, 1.8, 0.4),
                r(ReasonKind.VOLUME_NORMAL, 1.0),
                r(ReasonKind.VOLATILITY_NORMAL, 0.5, 0.1),
            ),
            hasMarketData = true,
        )!!
        assertEquals(1, c.agreeing)
        assertEquals(WhyConfidenceLevel.LOW, c.level)
    }

    @Test
    fun lowWithPartialData() {
        // Ohne Marktvergleich (keine Referenz) — auch mit starken Hinweisen nur «niedrig»
        val noMarket = WhyConfidenceRules.evaluate(
            listOf(
                r(ReasonKind.VOLUME_HIGH, 5.0, strong = true),
                r(ReasonKind.VOLATILITY_HIGH, 4.0, 3.0, strong = true),
                r(ReasonKind.LEVERAGE_LONGS, 0.2, null, strong = true),
            ),
            hasMarketData = true,
        )!!
        assertTrue(noMarket.partialData)
        assertEquals(WhyConfidenceLevel.LOW, noMarket.level)

        // Nur zwei Hinweise prüfbar
        val two = WhyConfidenceRules.evaluate(
            listOf(r(ReasonKind.COIN_ONLY, 7.0, 0.1, strong = true), r(ReasonKind.LEVERAGE_LONGS, 0.2, null, strong = true)),
            hasMarketData = true,
        )!!
        assertTrue(two.partialData)
        assertEquals(2, two.agreeing)
        assertEquals(2, two.total)
        assertEquals(WhyConfidenceLevel.LOW, two.level)
    }

    @Test
    fun calmAgreesWithQuietHints() {
        val c = WhyConfidenceRules.evaluate(
            listOf(
                r(ReasonKind.MARKET_CALM, 0.3, 0.5),
                r(ReasonKind.VOLUME_NORMAL, 1.0),
                r(ReasonKind.VOLATILITY_NORMAL, 0.6, 0.1),
                r(ReasonKind.LEVERAGE_BALANCED, 0.01),
            ),
            hasMarketData = true,
        )!!
        assertEquals(4, c.agreeing)
        assertEquals(WhyConfidenceLevel.HIGH, c.level)

        // Ruhiger Markt, aber hohe Volatilität beim Coin: Widerspruch, höchstens «mittel»
        val mixed = WhyConfidenceRules.evaluate(
            listOf(
                r(ReasonKind.MARKET_CALM, 0.3, 0.5),
                r(ReasonKind.VOLUME_NORMAL, 1.0),
                r(ReasonKind.VOLATILITY_HIGH, 2.5, 1.0),
                r(ReasonKind.LEVERAGE_BALANCED, 0.01),
            ),
            hasMarketData = true,
        )!!
        assertEquals(3, mixed.agreeing)
        assertEquals(WhyConfidenceLevel.MEDIUM, mixed.level)
    }

    @Test
    fun bitcoinLeaderCountsWhenMoving() {
        val c = WhyConfidenceRules.evaluate(
            listOf(
                r(ReasonKind.MARKET_LEADER, 4.0, 3.0, strong = true),
                r(ReasonKind.VOLUME_HIGH, 3.2, strong = true),
                r(ReasonKind.VOLATILITY_HIGH, 3.1, 1.5, strong = true),
            ),
            hasMarketData = true,
        )!!
        assertEquals(3, c.agreeing)
        assertEquals(3, c.total)
        assertEquals(WhyConfidenceLevel.HIGH, c.level)
    }
}
