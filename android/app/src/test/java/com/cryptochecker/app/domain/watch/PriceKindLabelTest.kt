package com.cryptochecker.app.domain.watch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Benennung des Kurses im Aktionsblatt: Mark-Ersatz vor Mittelkurs vor Stundenkurs. Wie iOS. */
class PriceKindLabelTest {
    @Test
    fun markFallbackWinsOverMid() {
        assertEquals(PriceKindLabel.MARK, PriceKindLabel.of(priceIsMid = true, markFallback = true, hourlyClose = false))
        assertEquals(PriceKindLabel.MID, PriceKindLabel.of(priceIsMid = true, markFallback = false, hourlyClose = false))
        assertEquals(PriceKindLabel.HOURLY, PriceKindLabel.of(priceIsMid = false, markFallback = false, hourlyClose = true))
        assertNull(PriceKindLabel.of(priceIsMid = false, markFallback = false, hourlyClose = false))
    }

    @Test
    fun dailyReferenceForEcbRates() {
        val label = PriceKindLabel.of(priceIsMid = false, markFallback = false, hourlyClose = false, dailyReference = true)
        assertEquals(PriceKindLabel.DAILY_REFERENCE, label)
        assertEquals(PriceKindLabel.MARK, PriceKindLabel.of(false, markFallback = true, hourlyClose = false, dailyReference = true))
        assertEquals(true, com.cryptochecker.marketdata.model.market.EcbReference().priceIsDailyReference)
    }
}
