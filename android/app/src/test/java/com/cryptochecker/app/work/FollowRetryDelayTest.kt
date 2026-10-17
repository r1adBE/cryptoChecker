package com.cryptochecker.app.work

import org.junit.Assert.assertEquals
import org.junit.Test

/** Nachführen des Bedarfs nach Lesefehlern: 1, 2, 4 … höchstens 60 s ([BackgroundWorkPolicy.followRetryDelayMillis]). */
class FollowRetryDelayTest {

    @Test
    fun growsExponentiallyAndIsCapped() {
        assertEquals(1_000L, BackgroundWorkPolicy.followRetryDelayMillis(0))
        assertEquals(2_000L, BackgroundWorkPolicy.followRetryDelayMillis(1))
        assertEquals(32_000L, BackgroundWorkPolicy.followRetryDelayMillis(5))
        assertEquals(BackgroundWorkPolicy.FOLLOW_RETRY_MAX_MILLIS, BackgroundWorkPolicy.followRetryDelayMillis(6))
        assertEquals(BackgroundWorkPolicy.FOLLOW_RETRY_MAX_MILLIS, BackgroundWorkPolicy.followRetryDelayMillis(1_000))
        assertEquals(1_000L, BackgroundWorkPolicy.followRetryDelayMillis(-1))
    }
}
