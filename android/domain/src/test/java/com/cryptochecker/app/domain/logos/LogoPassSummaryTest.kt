package com.cryptochecker.app.domain.logos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LogoPassSummaryTest {
    @Test
    fun roundTrip() {
        val pass = LogoPassSummary(1_700_000_000_000, 12, 3, 0, 284, 1)
        assertEquals("1700000000000;12;3;0;284;1", pass.encode())
        assertEquals(pass, LogoPassSummary.decode(pass.encode()))
    }

    @Test
    fun unreadableIsNull() {
        assertNull(LogoPassSummary.decode(null))
        assertNull(LogoPassSummary.decode("1;2;3"))
        assertNull(LogoPassSummary.decode("x;1;2;3;4;5"))
        assertEquals(0, LogoPassSummary.decode("1;-5;0;0;0;0")?.added)
    }

    @Test
    fun onlyForbiddenLeavesOpen() {
        assertTrue(CoinLogoRetry.leavesOpen(403))
        assertFalse(CoinLogoRetry.leavesOpen(404))
        assertFalse(CoinLogoRetry.leavesOpen(500))
    }
}
