package com.cryptochecker.app.domain.refresh

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Refresh-Sperre ([RefreshLock]): Beweis [RefreshLockScope] nur im Block, Wiedereintritt bricht ab statt zu
 * verklemmen, belegte Sperre bei [RefreshLock.tryWithLock] / [RefreshLock.withLockWithin] → null.
 */
class RefreshLockTest {

    /** Steht für Arbeit, die die Sperre verlangt (wie RefreshResultProcessor.process). */
    private fun needsLock(@Suppress("UNUSED_PARAMETER") lock: RefreshLockScope): String = "geschrieben"

    @Test
    fun blockGetsProofAndHoldsTheLock() = runBlocking {
        val lock = RefreshLock()
        assertFalse(lock.isLocked)
        val result = lock.withLock { proof ->
            assertTrue(lock.isLocked)
            needsLock(proof)
        }
        assertEquals("geschrieben", result)
        assertFalse(lock.isLocked)
    }

    @Test
    fun reentryFailsFastInsteadOfDeadlocking() = runBlocking {
        val lock = RefreshLock()
        withTimeout(5_000) {
            lock.withLock {
                try {
                    lock.withLock { fail("darf nicht laufen") }
                    fail("Wiedereintritt nicht erkannt")
                } catch (expected: IllegalStateException) {
                    // erwartet
                }
                try {
                    lock.tryWithLock { fail("darf nicht laufen") }
                    fail("Wiedereintritt (tryWithLock) nicht erkannt")
                } catch (expected: IllegalStateException) {
                    // erwartet
                }
            }
        }
        // Danach wieder frei
        assertFalse(lock.isLocked)
        assertEquals(1, lock.withLock { 1 })
    }

    @Test
    fun reentryFromChildCoroutineIsDetected() = runBlocking {
        val lock = RefreshLock()
        var detected = false
        withTimeout(5_000) {
            lock.withLock {
                coroutineScope {
                    launch {
                        try {
                            lock.withLock { }
                        } catch (expected: IllegalStateException) {
                            detected = true
                        }
                    }
                }
            }
        }
        assertTrue(detected)
    }

    @Test
    fun busyLockMakesTryAndTimedVariantsGiveUp() = runBlocking {
        val lock = RefreshLock()
        val held = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val holder = launch {
            lock.withLock {
                held.complete(Unit)
                release.await()
            }
        }
        held.await()
        // Andere Coroutine: kein Wiedereintritt, nur belegt
        assertNull(lock.tryWithLock { "nie" })
        assertNull(lock.withLockWithin(50) { "nie" })
        release.complete(Unit)
        holder.join()
        assertNotNull(lock.tryWithLock { "jetzt" })
        assertEquals("jetzt", lock.withLockWithin(1_000) { "jetzt" })
    }

    @Test
    fun exceptionReleasesTheLock() = runBlocking {
        val lock = RefreshLock()
        try {
            lock.withLock { throw IllegalArgumentException("kaputt") }
        } catch (expected: IllegalArgumentException) {
            // erwartet
        }
        assertFalse(lock.isLocked)
    }
}
