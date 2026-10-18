package com.cryptochecker.app.data.remote.util

import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Führt die OkHttp-Anfrage aus, ohne einen Thread zu blockieren. Wird die Coroutine abgebrochen,
 * bricht auch die Anfrage ab.
 */
internal suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    val bridge = ResumeOnReply(cont)
    enqueue(bridge)
    cont.invokeOnCancellation { cancelQuietly() }
}

/** Gibt Antwort oder Fehler von OkHttp an die wartende Coroutine weiter. */
private class ResumeOnReply(private val cont: CancellableContinuation<Response>) : Callback {

    // Ist die Coroutine schon abgebrochen, nimmt niemand die Antwort an: dann samt Body schliessen,
    // sonst bleibt die Verbindung belegt. Fassung mit (Grund, Wert, Kontext) — die mit nur dem Grund
    // ist seit Coroutines 1.9 veraltet.
    @OptIn(ExperimentalCoroutinesApi::class)
    override fun onResponse(call: Call, response: Response) {
        cont.resume(response) { _: Throwable, unused: Response, _: CoroutineContext ->
            runCatching { unused.close() }
        }
    }

    // Nur wenn die Coroutine selbst abgebrochen ist, nimmt niemand den Fehler an. call.isCanceled()
    // taugt dafür nicht: OkHttp bricht die Anfrage auch beim eigenen callTimeout ab — dieser Fehler
    // muss als Zeitüberschreitung ankommen, sonst wartet die Coroutine bis zur äusseren Wache.
    override fun onFailure(call: Call, e: IOException) {
        if (cont.isCancelled) return
        cont.resumeWithException(e)
    }
}

private fun Call.cancelQuietly() {
    runCatching { cancel() }
}
