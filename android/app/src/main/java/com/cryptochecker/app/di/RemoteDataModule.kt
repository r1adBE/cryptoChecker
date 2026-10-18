package com.cryptochecker.app.di

import com.cryptochecker.app.BuildConfig
import com.cryptochecker.app.data.HttpLogger
import com.cryptochecker.app.data.remote.HttpLoggerImpl
import com.cryptochecker.app.data.remote.HttpUserAgent
import com.cryptochecker.app.data.remote.util.CallStartInterceptor
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.Dispatcher
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

/** Netz: ein gemeinsamer OkHttp-Client mit kurzen Fristen, eigenem User-Agent und Mitschnitt. */
@Module
@InstallIn(SingletonComponent::class)
object RemoteDataModule {

    /** Kennung wie auf iOS statt «okhttp/…». */
    private val USER_AGENT = HttpUserAgent.android(BuildConfig.VERSION_NAME)

    /** Gleichzeitige Anfragen insgesamt: Standard 64 → 32; mehr bringt auf dem Telefon nichts. */
    const val MAX_REQUESTS = 32

    /**
     * Gleichzeitige Anfragen je Host: OkHttp-Standard 5, wie iOS (`MarketHTTP.maxRequestsPerHost`). Höher hiesse mehr
     * parallele Abfragen bei derselben Börse — eher ins Rate-Limit. Wer wartet, wartet in der Warteschlange, ohne
     * dass seine Fristen laufen (callTimeout und Wache ab der Ausführung, CallStartInterceptor).
     */
    const val MAX_REQUESTS_PER_HOST = 5

    /**
     * Kurse kommen zügig oder scheitern rasch. Ohne callTimeout griff nur die Wache in callMarket
     * (35 s je Anfrage). Der User-Agent wird vor dem Mitschnitt gesetzt, damit er dort erscheint.
     */
    @Singleton
    @Provides
    fun provideOkHttpClient(httpLogger: HttpLogger): OkHttpClient {
        val dispatcher = Dispatcher().apply {
            maxRequests = MAX_REQUESTS
            maxRequestsPerHost = MAX_REQUESTS_PER_HOST
        }
        val builder = OkHttpClient.Builder()
            .dispatcher(dispatcher)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            // Zuerst: meldet der Wache den Start der Ausführung (Warteschlange zählt nicht)
            .addInterceptor(CallStartInterceptor)
            .addInterceptor(UserAgentInterceptor(USER_AGENT))
        // Mitschnitt nur im Debug-Build (src/debug); der Release liefert null und enthält
        // logging-interceptor gar nicht (debugImplementation).
        HttpLogInterceptorProvider.create(httpLogger)?.let(builder::addInterceptor)
        return builder.build()
    }

    @Singleton
    @Provides
    fun provideHttpLoggerFlow(): HttpLogger = HttpLoggerImpl()
}

/** Setzt den eigenen User-Agent, ausser die Anfrage bringt schon einen eigenen mit ([HttpUserAgent.shouldSet]). */
private class UserAgentInterceptor(private val agent: String) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        if (!HttpUserAgent.shouldSet(original.header(HttpUserAgent.HEADER))) return chain.proceed(original)
        return chain.proceed(original.newBuilder().header(HttpUserAgent.HEADER, agent).build())
    }
}
