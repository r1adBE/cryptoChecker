package com.cryptochecker.app.widget

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PulseBackgroundFetchTest {

    @Test
    fun nothingPendingReturnsAtOnce() = runBlocking {
        assertTrue(PulseBackgroundFetch().await(0L))
    }

    @Test
    fun waitsForRunningFetch() = runBlocking {
        val fetch = PulseBackgroundFetch()
        var drawn = false
        fetch.launch {
            delay(50)
            drawn = true
        }
        assertTrue(fetch.await(5_000L))
        assertTrue(drawn)
    }

    @Test
    fun givesUpAfterTimeoutWithoutCancellingFetch() = runBlocking {
        val fetch = PulseBackgroundFetch()
        val release = CompletableDeferred<Unit>()
        fetch.launch { release.await() }
        assertFalse(fetch.await(30L))
        assertFalse(fetch.await(0L))
        release.complete(Unit)
        assertTrue(fetch.await(5_000L))
    }
}
