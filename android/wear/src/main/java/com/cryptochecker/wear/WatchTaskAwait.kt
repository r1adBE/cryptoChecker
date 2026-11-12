package com.cryptochecker.wear

import com.google.android.gms.tasks.Task
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import java.util.concurrent.TimeoutException
import kotlin.coroutines.resume

/**
 * Wartet auf eine Aufgabe der Play-Dienste, ohne einen Thread zu blockieren (statt `Tasks.await`): Die Coroutine ruht,
 * bis die Aufgabe fertig ist, und reagiert sofort auf Abbruch (Ansicht weg, Frist des Aufrufers). Frist und Fehler wie
 * bei `Tasks.await`: nach [timeoutMs] eine [TimeoutException], ein Fehler der Aufgabe als [ExecutionException]. Ohne
 * `kotlinx-coroutines-play-services` (wäre eine weitere Abhängigkeit).
 */
internal object WatchTaskAwait {

    /** Meldet das Ergebnis im Thread der Play-Dienste (kein Looper nötig); weiter geht es im Dispatcher des Aufrufers. */
    private val direct = Executor { it.run() }

    /** Ergebnis von [task], höchstens [timeoutSeconds] gewartet. */
    suspend fun <T> await(task: Task<T>, timeoutSeconds: Long): T = await<T>(timeoutSeconds * 1000) { done ->
        task.addOnCompleteListener(direct) { finished -> done(outcome(finished)) }
    }

    /**
     * Kern ohne Play-Dienste (für die Tests): [register] meldet einen Zuhörer an, der genau einmal mit dem Ergebnis
     * gerufen wird. Ein Ergebnis nach Abbruch oder Frist verfällt.
     */
    suspend fun <T> await(timeoutMs: Long, register: (done: (Result<T>) -> Unit) -> Unit): T {
        val outcome = withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine<Result<T>> { continuation ->
                register { result -> if (continuation.isActive) continuation.resume(result) }
            }
        } ?: throw TimeoutException("Timed out waiting for Task")
        return outcome.getOrThrow()
    }

    /**
     * Wie `runCatching`, reicht aber den Abbruch der Coroutine weiter. Eine von den Play-Diensten abgebrochene Aufgabe
     * ist kein Abbruch der Coroutine ([outcome] meldet sie als [ExecutionException]) und bleibt ein Fehler im Ergebnis.
     */
    inline fun <R> catching(block: () -> R): Result<R> = try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Result.failure(e)
    }

    private fun <T> outcome(task: Task<T>): Result<T> {
        val error = task.exception
        return when {
            error != null -> Result.failure(ExecutionException(error))
            task.isCanceled -> Result.failure(ExecutionException(IllegalStateException("Task is already canceled")))
            else -> Result.success(task.result)
        }
    }
}
