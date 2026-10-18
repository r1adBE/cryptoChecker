package com.cryptochecker.app.di

import com.cryptochecker.app.data.HttpLogger
import okhttp3.Interceptor

/**
 * Alle Build-Typen ausser Debug (Release und die Baseline-Profile-Typen, siehe app/build.gradle.kts):
 * kein Mitschnitt. Eine Sammelabfrage bringt mehrere Megabyte, die sonst bei jedem
 * Durchlauf als Text im Flow lägen; zudem bleibt logging-interceptor ganz aus der App
 * (debugImplementation, Gegenstück in src/debug).
 */
internal object HttpLogInterceptorProvider {
    @Suppress("UNUSED_PARAMETER")
    fun create(httpLogger: HttpLogger): Interceptor? = null
}
