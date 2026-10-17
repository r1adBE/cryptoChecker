package com.cryptochecker.app.data.remote.util

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RunCatchingCancellableTest {

    @Test
    fun successAndFailureLikeRunCatching() = runBlocking {
        assertEquals(42, runCatchingCancellable { 42 }.getOrNull())
        val failed = runCatchingCancellable { throw IllegalStateException("x") }
        assertTrue(failed.exceptionOrNull() is IllegalStateException)
    }

    @Test
    fun innerTimeoutIsAnOrdinaryFailure() = runBlocking {
        val result = runCatchingCancellable { withTimeout(10) { awaitCancellation() } }
        assertTrue(result.isFailure)
    }

    @Test
    fun ownCancellationIsRethrown() = runBlocking {
        val started = CompletableDeferred<Unit>()
        var reachedAfter = false
        val job = launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
            runCatchingCancellable {
                started.complete(Unit)
                awaitCancellation()
            }
            // Mit runCatching ginge es hier weiter
            reachedAfter = true
        }
        started.await()
        job.cancel()
        job.join()
        assertFalse(reachedAfter)
    }
}
