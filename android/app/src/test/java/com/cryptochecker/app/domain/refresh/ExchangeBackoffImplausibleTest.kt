package com.cryptochecker.app.domain.refresh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Eine Pause weiter als die längste mögliche in der Zukunft (Uhr zurückgestellt) gilt als abgelaufen. */
class ExchangeBackoffImplausibleTest {

    private val now = 1_700_000_000_000L

    @Test
    fun pauseBeyondGroupMaxIsExpired() {
        val plausible = ExchangeBackoff.State(strikes = 1, pausedUntil = now + ExchangeBackoff.GROUP_MAX_MILLIS)
        assertTrue(ExchangeBackoff.isPaused(plausible, now))
        val absurd = plausible.copy(pausedUntil = now + ExchangeBackoff.GROUP_MAX_MILLIS + 1)
        assertFalse(ExchangeBackoff.isPaused(absurd, now))
        assertFalse(ExchangeBackoff.isPaused(plausible.copy(pausedUntil = Long.MAX_VALUE), now))
    }

    @Test
    fun newRateLimitIsNotHeldBackByAnImpossiblePause() {
        val absurd = ExchangeBackoff.State(strikes = 1, pausedUntil = now + 30L * 24 * 60 * 60_000L)
        val next = ExchangeBackoff.next(absurd, ExchangeBackoff.Outcome.RATE_LIMITED, now)!!
        // Stufe 2 = 1 Min. ab jetzt, nicht die alte Monats-Pause
        assertEquals(now + 60_000L, next.pausedUntil)
        assertTrue(ExchangeBackoff.isPaused(next, now))
    }

    @Test
    fun groupPauseStartsFreshAfterAnImpossiblePause() {
        val absurd = ExchangeBackoff.State(strikes = 5, pausedUntil = now + 30L * 24 * 60 * 60_000L)
        val state = ExchangeBackoff.groupPause(absurd, now, httpCode = 429, retryAfterSeconds = null)
        assertEquals(1, state.strikes)
        assertEquals(now + ExchangeBackoff.BASE_MILLIS, state.pausedUntil)
    }
}
