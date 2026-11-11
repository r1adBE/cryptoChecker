package com.cryptochecker.app.domain.refresh

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Frist von [RefreshLock.withLockWithin] / [RefreshLock.withLockWithinThen] genau auf der Grenze: Gibt der Halter die
 * Sperre auf demselben Tick frei, auf dem die Frist abläuft, läuft der Block entweder (dann gibt er sie frei) oder
 * nicht (dann ist nichts gehalten) — nie bleibt die Sperre gehalten zurück. Dazu [lockWithin] direkt mit einer
 * Sperre, deren Erwerb erst nach Ablauf der Frist zurückkommt (der von `withTimeoutOrNull` dokumentierte Fall).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RefreshLockTimeoutBoundaryTest {

    @Test
    fun releaseOnTheTimeoutTickNeverLeavesTheLockHeld() = runTest(StandardTestDispatcher()) {
        val lock = RefreshLock()
        launch { lock.withLock { delay(TIMEOUT) } }
        runCurrent()
        assertTrue(lock.isLocked)
        var ran = false
        val waiter = async { lock.withLockWithin(TIMEOUT) { ran = true; "gelaufen" } }
        advanceUntilIdle()
        val result = waiter.await()
        assertEquals(result != null, ran)
        assertFalse("Sperre darf nach der Grenze nicht gehalten bleiben", lock.isLocked)
        assertEquals(1, lock.tryWithLock { 1 })
    }

    @Test
    fun thenVariantOnTheTimeoutTickNeverLeavesTheLockHeld() = runTest(StandardTestDispatcher()) {
        val lock = RefreshLock()
        launch { lock.withLock { delay(TIMEOUT) } }
        runCurrent()
        var ran = false
        val waiter = async { lock.withLockWithinThen(TIMEOUT, this) { _ -> ran = true; 2 to null } }
        advanceUntilIdle()
        val result = waiter.await()
        assertEquals(result != null, ran)
        assertFalse(lock.isLocked)
        assertEquals(3, lock.tryWithLock { 3 })
    }

    @Test
    fun plainWithTimeoutOrNullLeaksALockAcquiredAfterTheDeadline() = runTest(StandardTestDispatcher()) {
        // Belegt, dass der Fall echt ist: Erwerb gelingt, die Frist greift trotzdem → null, Sperre gehalten
        val mutex = LateMutex()
        val result = withTimeoutOrNull(TIMEOUT) { mutex.lock() }
        assertNull(result)
        assertTrue(mutex.isLocked)
    }

    @Test
    fun lockWithinCountsALockAcquiredAfterTheDeadlineAsAcquired() = runTest(StandardTestDispatcher()) {
        val mutex = LateMutex()
        assertTrue(mutex.lockWithin(TIMEOUT))
        assertTrue(mutex.isLocked)
        mutex.unlock()
        assertFalse(mutex.isLocked)
    }

    @Test
    fun lockWithinGivesUpWithoutHoldingWhileBusy() = runTest(StandardTestDispatcher()) {
        val mutex = Mutex(locked = true)
        assertFalse(mutex.lockWithin(TIMEOUT))
        mutex.unlock()
        assertFalse(mutex.isLocked)
        assertTrue(mutex.lockWithin(TIMEOUT))
        mutex.unlock()
    }

    /**
     * Sperre, deren [lock] die Frist absitzt (der Abbruch wird verschluckt) und erst danach ohne weiteren
     * Unterbrechungspunkt erwirbt — so kommt `lock()` erfolgreich zurück, obwohl die Frist schon gegriffen hat.
     */
    private class LateMutex(private val delegate: Mutex = Mutex()) : Mutex by delegate {
        override suspend fun lock(owner: Any?) {
            try {
                delay(TIMEOUT * 2)
            } catch (ignored: CancellationException) {
                // absichtlich verschluckt: Frist ist abgelaufen, der Erwerb folgt trotzdem
            }
            check(delegate.tryLock(owner)) { "Testsperre unerwartet belegt" }
        }
    }

    private companion object {
        const val TIMEOUT = 100L
    }
}
