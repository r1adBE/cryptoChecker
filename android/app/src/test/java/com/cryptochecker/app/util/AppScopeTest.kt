package com.cryptochecker.app.util

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/** Seit Build 103: prozessweiter Bereich fängt Ausnahmen ab und läuft danach weiter. */
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
}
