package com.cryptochecker.app.util

import com.cryptochecker.app.data.local.DatabaseKeyUnavailableException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Prozessweiter Bereich fängt Ausnahmen ab und läuft danach weiter. Ebenso der Bereich der
 * Empfänger ([ReceiverAsync], über [appScope]) und dessen Zeitgrenze ([ReceiverAsync.launchBounded], Kern von launchAsync).
 */
class AppScopeTest {

    @Test
    fun handlerCatchesAndScopeKeepsRunning() = runBlocking {
        val caught = CopyOnWriteArrayList<Throwable>()
        val handled = CompletableDeferred<Unit>()
        val scope = kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.SupervisorJob() + Dispatchers.Default +
                appExceptionHandler("Test") { caught += it; handled.complete(Unit) }
        )
        scope.launch { throw IllegalStateException("kaputt") }
        withTimeout(5_000) { handled.await() }
        assertEquals(1, caught.size)
        assertTrue(caught.single() is IllegalStateException)
        assertTrue(scope.isActive)

        val second = CompletableDeferred<Int>()
        scope.launch { second.complete(42) }
        assertEquals(42, withTimeout(5_000) { second.await() })
    }

    @Test
    fun appScopeSurvivesFailingJob() = runBlocking {
        val scope = appScope(Dispatchers.Default, "Test")
        scope.launch { throw IllegalStateException("kaputt") }.join()
        assertTrue(scope.isActive)
        val done = CompletableDeferred<Unit>()
        scope.launch { done.complete(Unit) }
        withTimeout(5_000) { done.await() }
    }

    @Test
    fun receiverScopeSurvivesFailingJob() = runBlocking {
        // Auch «Schlüssel nicht verfügbar» landet nur im Protokoll (Hinweis statt Fehler), der Bereich bleibt
        ReceiverAsync.scope.launch { throw DatabaseKeyUnavailableException("test.db", IllegalStateException("Keystore")) }.join()
        ReceiverAsync.scope.launch { throw IllegalStateException("kaputt") }.join()
        assertTrue(ReceiverAsync.scope.isActive)
    }

    @Test
    fun launchBoundedFinishesAfterTimeout() = runBlocking {
        val done = CompletableDeferred<Unit>()
        var finished = false
        ReceiverAsync.launchBounded("Test", timeoutMillis = 50, onDone = { done.complete(Unit) }) {
            delay(60_000)
            finished = true
        }
        // Zeitgrenze statt 60 s: onDone (finish()) kommt, der Block läuft nicht zu Ende
        withTimeout(5_000) { done.await() }
        assertTrue(!finished)
        assertTrue(ReceiverAsync.scope.isActive)
    }

    @Test
    fun launchBoundedFinishesAfterFailure() = runBlocking {
        val done = CompletableDeferred<Unit>()
        ReceiverAsync.launchBounded("Test", onDone = { done.complete(Unit) }) { throw IllegalStateException("kaputt") }
        withTimeout(5_000) { done.await() }
        assertTrue(ReceiverAsync.scope.isActive)
    }

    @Test
    fun launchBoundedFinishesAfterSuccess() = runBlocking {
        val done = CompletableDeferred<Unit>()
        val ran = CompletableDeferred<Int>()
        ReceiverAsync.launchBounded("Test", onDone = { done.complete(Unit) }) { ran.complete(7) }
        withTimeout(5_000) { done.await() }
        assertEquals(7, ran.await())
    }
}
