package com.cryptochecker.wear

import com.google.android.gms.tasks.TaskCompletionSource
import com.google.android.gms.tasks.Tasks
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.io.IOException
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeoutException

/**
 * [WatchTaskAwait]: wartet ohne blockierten Thread auf eine Aufgabe der Play-Dienste — Ergebnis, Fehler und Frist wie
 * `Tasks.await`, dazu sofortiger Abbruch. [WatchDataLayer] nennt `Tasks.await` und `runCatching` nicht mehr.
 */
class WatchTaskAwaitTest {

    @Test
    fun returnsTheResultOnceTheListenerIsCalled() = runBlocking {
        val done = CompletableDeferred<(Result<Int>) -> Unit>()
        val waiting = async(start = CoroutineStart.UNDISPATCHED) { WatchTaskAwait.await<Int>(LONG_MS) { done.complete(it) } }
        assertFalse(waiting.isCompleted)
        done.await()(Result.success(7))
        assertEquals(7, waiting.await())
    }

    @Test
    fun throwsTheFailureOfTheListener() = runBlocking {
        val error = IOException("no play services")
        val thrown = runCatching { WatchTaskAwait.await<Int>(LONG_MS) { it(Result.failure(error)) } }.exceptionOrNull()
        assertSame(error, thrown)
    }

    @Test
    fun timesOutLikeTasksAwait() = runBlocking {
        var late: ((Result<Int>) -> Unit)? = null
        val thrown = runCatching { WatchTaskAwait.await<Int>(SHORT_MS) { late = it } }.exceptionOrNull()
        assertTrue(thrown.toString(), thrown is TimeoutException)
        // Ein Ergebnis nach der Frist verfällt, ohne Fehler
        late!!(Result.success(1))
    }

    @Test
    fun cancellationEndsTheWaitAtOnceAndIsNotSwallowed() = runBlocking {
        var late: ((Result<Int>) -> Unit)? = null
        var outcome: Result<Int>? = null
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            outcome = WatchTaskAwait.catching { WatchTaskAwait.await<Int>(LONG_MS) { late = it } }
        }
        withTimeout(SHORT_MS * 20) { job.cancelAndJoin() }
        assertTrue(job.isCancelled)
        assertEquals("catching darf den Abbruch nicht als Ergebnis liefern", null, outcome)
        late!!(Result.success(1))
    }

    @Test
    fun catchingKeepsEveryOtherFailureAsResult() {
        assertEquals(3, WatchTaskAwait.catching { 3 }.getOrNull())
        val error = IllegalStateException("x")
        assertSame(error, WatchTaskAwait.catching<Int> { throw error }.exceptionOrNull())
        try {
            WatchTaskAwait.catching<Int> { throw CancellationException("cancelled") }
            fail("CancellationException muss weitergehen")
        } catch (expected: CancellationException) {
            // erwartet
        }
    }

    @Test
    fun awaitsAPlayServicesTask() = runBlocking {
        assertEquals("ok", WatchTaskAwait.await(Tasks.forResult("ok"), 1))

        val source = TaskCompletionSource<String>()
        val waiting = async(start = CoroutineStart.UNDISPATCHED) { WatchTaskAwait.await(source.task, 5) }
        assertFalse(waiting.isCompleted)
        source.setResult("later")
        assertEquals("later", waiting.await())

        val error = IOException("api unavailable")
        val thrown = runCatching { WatchTaskAwait.await(Tasks.forException<String>(error), 1) }.exceptionOrNull()
        assertTrue(thrown.toString(), thrown is ExecutionException)
        assertSame(error, thrown?.cause)

        // Von den Play-Diensten abgebrochene Aufgabe: ein Fehler, kein Abbruch der Coroutine
        val canceled = WatchTaskAwait.catching { WatchTaskAwait.await(Tasks.forCanceled<String>(), 1) }
        assertTrue(canceled.exceptionOrNull().toString(), canceled.exceptionOrNull() is ExecutionException)
    }

    /** Quelltext-Wache: die Daten-Schicht blockiert keinen Thread mehr und schluckt keinen Abbruch. */
    @Test
    fun dataLayerNeitherBlocksNorSwallowsCancellation() {
        val source = listOf("src/main/java", "wear/src/main/java", "android/wear/src/main/java")
            .map { File(it, "com/cryptochecker/wear/WatchDataLayer.kt") }.first { it.isFile }.readText(Charsets.UTF_8)
        assertFalse("Tasks.await blockiert einen Thread", source.contains("Tasks.await"))
        assertFalse("runCatching schluckt den Abbruch", Regex("""\brunCatching\b""").containsMatchIn(source))
        assertEquals(4, Regex("""WatchTaskAwait\.await\(""").findAll(source).count())
        assertEquals(5, Regex("""WatchTaskAwait\.catching \{""").findAll(source).count())
    }

    private companion object {
        const val LONG_MS = 60_000L
        const val SHORT_MS = 50L
    }
}
