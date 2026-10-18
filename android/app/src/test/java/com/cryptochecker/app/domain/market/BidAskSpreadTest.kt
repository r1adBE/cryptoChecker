package com.cryptochecker.app.domain.market

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Abstand Geld/Brief in % der Mitte, Hinweis erst über 2 %. Wie iOS `BidAskSpreadTests`. */
class BidAskSpreadTest {

    @Test
    fun percentOfMid() {
        assertEquals(2.0, BidAskSpread.percentOfMid(99.0, 101.0)!!, 1e-9)
        assertEquals(0.0, BidAskSpread.percentOfMid(100.0, 100.0)!!, 1e-9)
    }

    @Test
    fun onlyWideSpreadsWarn() {
        assertNull(BidAskSpread.widePercent(99.0, 101.0))
        assertEquals(4.878, BidAskSpread.widePercent(100.0, 105.0)!!, 1e-3)
    }

    @Test
    fun missingOrInvertedQuotesGiveNothing() {
        assertNull(BidAskSpread.percentOfMid(-1.0, 101.0))
        assertNull(BidAskSpread.percentOfMid(0.0, 101.0))
        assertNull(BidAskSpread.percentOfMid(102.0, 101.0))
        assertNull(BidAskSpread.percentOfMid(Double.NaN, 101.0))
        assertNull(BidAskSpread.widePercent(100.0, Double.POSITIVE_INFINITY))
    }
}
