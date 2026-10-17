package com.cryptochecker.app.data.remote

import com.cryptochecker.app.data.HttpLogger
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import okhttp3.logging.HttpLoggingInterceptor

/** Leitet die Zeilen des OkHttp-Mitschnitts in einen Flow (letzte Zeile bleibt gepuffert). */
internal class HttpLoggerImpl : HttpLoggingInterceptor.Logger, HttpLogger {
    private val lines = MutableSharedFlow<String>(replay = 1)

    override val messageFlow: SharedFlow<String> = lines.asSharedFlow()

    // Nie blockieren: Ohne Sammler ersetzt die neue Zeile einfach die gepufferte
    override fun log(message: String) {
        lines.tryEmit(message)
    }
}
