package com.cryptochecker.app.domain.logos

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Logo-Quellbilder über 4096 × 4096 Pixel werden nicht dekodiert (wie iOS `CoinLogoDecodeTests`). */
class CoinLogoSourceLimitTest {

    @Test
    fun `4096 mal 4096 ist erlaubt`() {
        assertFalse(CoinLogos.sourceTooLarge(4096, 4096))
    }

    @Test
    fun `ein Pixel mehr wird abgelehnt`() {
        assertTrue(CoinLogos.sourceTooLarge(4097, 4096))
    }

    @Test
    fun `30000 mal 30000 wird ohne Ueberlauf abgelehnt`() {
        assertTrue(CoinLogos.sourceTooLarge(30_000, 30_000))
        assertTrue(CoinLogos.sourceTooLarge(Int.MAX_VALUE, Int.MAX_VALUE))
    }

    @Test
    fun `ungueltige Masse werden abgelehnt`() {
        assertTrue(CoinLogos.sourceTooLarge(0, 10))
        assertTrue(CoinLogos.sourceTooLarge(10, -1))
    }
}
