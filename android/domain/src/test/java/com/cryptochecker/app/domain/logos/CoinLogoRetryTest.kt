package com.cryptochecker.app.domain.logos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CoinLogoRetryTest {

    @Test
    fun `sync is needed after a network failure once online again`() {
        assertTrue(CoinLogoRetry.syncNeeded(pendingAfterNetworkFailure = true, online = true))
    }

    @Test
    fun `no sync while still offline`() {
        assertFalse(CoinLogoRetry.syncNeeded(pendingAfterNetworkFailure = true, online = false))
    }

    @Test
    fun `no extra sync without a failed one`() {
        assertFalse(CoinLogoRetry.syncNeeded(pendingAfterNetworkFailure = false, online = true))
        assertFalse(CoinLogoRetry.syncNeeded(pendingAfterNetworkFailure = false, online = false))
    }

    @Test
    fun `network failure does not count as attempt, server failure does`() {
        // Kein Netz nach der Installation: keine Stunde Pause
        assertFalse(CoinLogoRetry.recordsAttempt(networkFailure = true))
        // Server-Fehler: bisherige Sperre (eine Stunde) bleibt
        assertTrue(CoinLogoRetry.recordsAttempt(networkFailure = false))
    }

    @Test
    fun `failed downloads offline are a network failure`() {
        assertTrue(CoinLogoRetry.downloadsFailedForNetwork(online = false, failed = 1, attempted = 40))
    }

    @Test
    fun `all downloads failed while online count as network failure`() {
        assertTrue(CoinLogoRetry.downloadsFailedForNetwork(online = true, failed = 40, attempted = 40))
        // Zu wenige, um auf das Netz zu schliessen: einzelne kaputte Bilder
        assertFalse(CoinLogoRetry.downloadsFailedForNetwork(online = true, failed = 2, attempted = 2))
    }

    @Test
    fun `backoff grows and is capped`() {
        assertEquals(0L, CoinLogoRetry.backoffMillis(0))
        assertEquals(5_000L, CoinLogoRetry.backoffMillis(1))
        assertEquals(15_000L, CoinLogoRetry.backoffMillis(2))
        assertEquals(30_000L, CoinLogoRetry.backoffMillis(3))
        assertEquals(60_000L, CoinLogoRetry.backoffMillis(4))
        assertEquals(CoinLogoRetry.RETRY_MAX_MILLIS, CoinLogoRetry.backoffMillis(9))
        assertEquals(CoinLogoRetry.RETRY_MAX_MILLIS, CoinLogoRetry.backoffMillis(1_000))
    }

    @Test
    fun `retry waits for the rest of the backoff`() {
        // Zweiter Fehlschlag vor 4 s: noch 11 s warten
        assertEquals(11_000L, CoinLogoRetry.retryDelayMillis(failures = 2, lastFailureAt = 100_000L, now = 104_000L))
    }

    @Test
    fun `retry is immediate when network returns long after the failure`() {
        // Installation ohne Netz, Netz erst eine Minute später: sofort nachholen
        assertEquals(0L, CoinLogoRetry.retryDelayMillis(failures = 1, lastFailureAt = 0L, now = 60_000L))
        assertEquals(0L, CoinLogoRetry.retryDelayMillis(failures = 0, lastFailureAt = 0L, now = 0L))
    }

    @Test
    fun `clock going back waits the full backoff`() {
        assertEquals(30_000L, CoinLogoRetry.retryDelayMillis(failures = 3, lastFailureAt = 500_000L, now = 100_000L))
    }

    @Test
    fun `single broken images online keep the one-day block`() {
        assertFalse(CoinLogoRetry.downloadsFailedForNetwork(online = true, failed = 3, attempted = 40))
        assertFalse(CoinLogoRetry.downloadsFailedForNetwork(online = true, failed = 0, attempted = 40))
        assertFalse(CoinLogoRetry.downloadsFailedForNetwork(online = false, failed = 0, attempted = 0))
    }
}
