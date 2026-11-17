package com.cryptochecker.app.domain.remote.util
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Wie [runCatching], schluckt aber keinen Abbruch: Ist die eigene Coroutine abgebrochen (Bildschirm
 * zu, Job beendet, äusseres withTimeout), wird weitergeworfen. Die Zeitüberschreitung einer INNEREN
 * Anfrage (eigenes withTimeout, OkHttp) gilt dagegen als gewöhnlicher Fehler.
 */
suspend inline fun <R> runCatchingCancellable(block: () -> R): Result<R> =
    try {
        Result.success(block())
    } catch (e: CancellationException) {
        currentCoroutineContext().ensureActive()
        Result.failure(e)
    } catch (e: Throwable) {
        Result.failure(e)
    }
