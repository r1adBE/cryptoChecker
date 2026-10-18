package com.cryptochecker.app.di

import com.cryptochecker.app.data.HttpLogger
import com.cryptochecker.app.data.remote.HttpLoggerImpl
import okhttp3.Interceptor
import okhttp3.logging.HttpLoggingInterceptor

/**
 * Debug-Build: voller Antworttext im Mitschnitt (für die Rohanzeige des Entwickler-Protokolls).
 * Gegenstück in src/withoutHttpLog (alle übrigen Build-Typen) liefert null — dort fehlt logging-interceptor (debugImplementation).
 */
internal object HttpLogInterceptorProvider {
    fun create(httpLogger: HttpLogger): Interceptor? {
        val sink = httpLogger as? HttpLoggerImpl ?: return null
        val logger = object : HttpLoggingInterceptor.Logger {
            override fun log(message: String) = sink.log(message)
        }
        return HttpLoggingInterceptor(logger).also { it.level = HttpLoggingInterceptor.Level.BODY }
    }
}
