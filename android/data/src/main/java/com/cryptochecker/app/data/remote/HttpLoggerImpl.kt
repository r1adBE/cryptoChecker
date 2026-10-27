package com.cryptochecker.app.data.remote

import com.cryptochecker.app.data.HttpLogger
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Leitet die Zeilen des OkHttp-Mitschnitts in einen Flow (letzte Zeile bleibt gepuffert). Kennt
 * logging-interceptor nicht selbst: das gibt es nur im Debug-Build (HttpLogInterceptorProvider).
 */
class HttpLoggerImpl : HttpLogger {
    private val lines = MutableSharedFlow<String>(replay = 1)

    override val messageFlow: SharedFlow<String> = lines.asSharedFlow()

    // Nie blockieren: Ohne Sammler ersetzt die neue Zeile einfach die gepufferte
    fun log(message: String) {
        lines.tryEmit(message)
    }
}
