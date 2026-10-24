package com.cryptochecker.app.domain.refresh

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections

/**
 * Sperre mit Nacharbeit ([RefreshLock.withLockThen] und Varianten): Der Aufrufer ist vor der Nacharbeit fertig, die
 * Sperre wird dazwischen nie frei und erst nach der Nacharbeit freigegeben — auch nach einem Fehler oder wenn die
 * Nacharbeit nie startet.
 */
class RefreshLockThenTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, _ -> })

    private suspend fun awaitUnlocked(lock: RefreshLock) = withTimeout(5_000) {
        while (lock.isLocked) delay(5)
    }

    @Test
    fun callerFinishesFirstAndTheLockStaysHeldUntilTheTailEnds() = runBlocking {
        val lock = RefreshLock()
        val gate = CompletableDeferred<Unit>()
        val order = Collections.synchronizedList(ArrayList<String>())
        val tail: LockedTail = {
            gate.await()
            order += "tail"
        }
        val (value, job) = lock.withLockThen(scope) { _ ->
            order += "block"
            "fertig" to tail
        }
        order += "caller"
        assertEquals("fertig", value)
        assertNotNull(job)
        assertTrue("Sperre bleibt gehalten", lock.isLocked)
        // Kein anderer Schreiber dazwischen
        assertNull(lock.tryWithLock { "dazwischen" })
        gate.complete(Unit)
        withTimeout(5_000) { job!!.join() }
        awaitUnlocked(lock)
        assertEquals(listOf("block", "caller", "tail"), order.toList())
        assertEquals("danach", lock.tryWithLock { "danach" })
    }

    @Test
    fun waitingWriterRunsOnlyAfterTheTail() = runBlocking {
        val lock = RefreshLock()
        val gate = CompletableDeferred<Unit>()
        val order = Collections.synchronizedList(ArrayList<String>())
        val tail: LockedTail = {
            gate.await()
            order += "tail"
        }
        val (_, job) = lock.withLockThen(scope) { _ -> Unit to tail }
        val waiting = async(Dispatchers.Default) { lock.withLock { order += "next" } }
        delay(100)
        assertTrue(order.isEmpty())
        gate.complete(Unit)
        withTimeout(5_000) { waiting.await() }
        assertNotNull(job)
        assertEquals(listOf("tail", "next"), order.toList())
    }

    @Test
    fun withoutTailTheLockIsReleasedAtOnce() = runBlocking {
        val lock = RefreshLock()
        val (value, job) = lock.withLockThen(scope) { _ -> 7 to null }
        assertEquals(7, value)
        assertNull(job)
        assertFalse(lock.isLocked)
    }

    @Test
    fun failingTailReleasesTheLock() = runBlocking {
        val lock = RefreshLock()
        val tail: LockedTail = { error("Nacharbeit gescheitert") }
        val (_, job) = lock.withLockThen(scope) { _ -> Unit to tail }
        withTimeout(5_000) { job!!.join() }
        awaitUnlocked(lock)
        assertFalse(lock.isLocked)
    }

    @Test
    fun tailInAFinishedScopeNeverStartsButReleasesTheLock() = runBlocking {
        val lock = RefreshLock()
        val finished = CoroutineScope(Job().apply { cancel() })
        var ran = false
        val tail: LockedTail = { ran = true }
        val (_, job) = lock.withLockThen(finished) { _ -> Unit to tail }
        withTimeout(5_000) { job!!.join() }
        awaitUnlocked(lock)
        assertFalse(ran)
    }

    @Test
    fun failingBlockReleasesTheLock() = runBlocking {
        val lock = RefreshLock()
        val result = runCatching { lock.withLockThen<Unit>(scope) { _ -> error("Block gescheitert") } }
        assertTrue(result.isFailure)
        assertFalse(lock.isLocked)
    }

    @Test
    fun tryAndWithinVariantsGiveUpWhileBusy() = runBlocking {
        val lock = RefreshLock()
        val gate = CompletableDeferred<Unit>()
        val tail: LockedTail = { gate.await() }
        val (_, job) = lock.withLockThen(scope) { _ -> Unit to tail }
        assertNull(lock.tryWithLockThen(scope) { _ -> Unit to null })
        assertNull(lock.withLockWithinThen(50, scope) { _ -> Unit to null })
        gate.complete(Unit)
        withTimeout(5_000) { job!!.join() }
        awaitUnlocked(lock)
        assertEquals(1, lock.tryWithLockThen(scope) { _ -> 1 to null }?.first)
        assertEquals(2, lock.withLockWithinThen(1_000, scope) { _ -> 2 to null }?.first)
    }

    @Test
    fun stagedTailRunsAfterAFailingBlockAndKeepsTheLockUntilItEnds() = runBlocking {
        val lock = RefreshLock()
        val gate = CompletableDeferred<Unit>()
        val ran = CompletableDeferred<Unit>()
        val tail: LockedTail = {
            gate.await()
            ran.complete(Unit)
        }
        val result = runCatching {
            lock.withLockThen<Unit>(scope) { proof ->
                lock.stageTail(proof, tail)
                error("Fehler nach dem Vormerken")
            }
        }
        // Der Fehler geht an den Aufrufer, die Nacharbeit läuft trotzdem und hält die Sperre bis zu ihrem Ende
        assertEquals("Fehler nach dem Vormerken", result.exceptionOrNull()?.message)
        assertTrue(lock.isLocked)
        assertNull(lock.tryWithLock { "dazwischen" })
        gate.complete(Unit)
        withTimeout(5_000) { ran.await() }
        awaitUnlocked(lock)
        assertEquals("danach", lock.tryWithLock { "danach" })
    }

    @Test
    fun stagedTailRunsWhenTheBlockEndsNormally() = runBlocking {
        val lock = RefreshLock()
        var ran = false
        val staged: LockedTail = { ran = true }
        val (value, job) = lock.withLockThen(scope) { proof ->
            lock.stageTail(proof, staged)
            5 to null
        }
        assertEquals(5, value)
        withTimeout(5_000) { job!!.join() }
        awaitUnlocked(lock)
        assertTrue(ran)
    }

    @Test
    fun returnedTailWinsOverTheStagedOne() = runBlocking {
        val lock = RefreshLock()
        val order = Collections.synchronizedList(ArrayList<String>())
        val staged: LockedTail = { order += "vorgemerkt" }
        val returned: LockedTail = { order += "zurückgegeben" }
        val (_, job) = lock.withLockThen(scope) { proof ->
            lock.stageTail(proof, staged)
            Unit to returned
        }
        withTimeout(5_000) { job!!.join() }
        awaitUnlocked(lock)
        assertEquals(listOf("zurückgegeben"), order.toList())
    }

    @Test
    fun stagingOutsideAThenBlockFails() = runBlocking {
        val lock = RefreshLock()
        val staged: LockedTail = {}
        val result = runCatching { lock.withLock { proof -> lock.stageTail(proof, staged) } }
        assertTrue(result.exceptionOrNull() is IllegalStateException)
        assertFalse(lock.isLocked)
    }
}
