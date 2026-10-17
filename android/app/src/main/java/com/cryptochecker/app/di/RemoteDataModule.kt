package com.cryptochecker.app.di

import com.cryptochecker.app.BuildConfig
import com.cryptochecker.app.data.HttpLogger
import com.cryptochecker.app.data.remote.HttpLoggerImpl
import com.cryptochecker.app.data.remote.HttpUserAgent
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.logging.HttpLoggingInterceptor
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

/** Netz: ein gemeinsamer OkHttp-Client mit kurzen Fristen, eigenem User-Agent und Mitschnitt. */
@Module
@InstallIn(SingletonComponent::class)
object RemoteDataModule {

    /** Kennung wie auf iOS statt «okhttp/…». */
    private val USER_AGENT = HttpUserAgent.android(BuildConfig.VERSION_NAME)

    /**
     * Kurse kommen zügig oder scheitern rasch. Ohne callTimeout griff nur die Wache in callMarket
     * (35 s je Anfrage). Der User-Agent wird vor dem Mitschnitt gesetzt, damit er dort erscheint.
     */
    @Singleton
    @Provides
    fun provideOkHttpClient(httpLoggingInterceptor: HttpLoggingInterceptor): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .addInterceptor(UserAgentInterceptor(USER_AGENT))
            .addInterceptor(httpLoggingInterceptor)
            .build()

    /**
     * Debug: voller Antworttext im Mitschnitt (für die Rohanzeige). Release: aus — eine Sammelabfrage
     * bringt mehrere Megabyte, die sonst bei jedem Durchlauf zusätzlich als Text im Flow lägen.
     */
    @Singleton
    @Provides
    fun provideHttpLoggingInterceptor(httpLogger: HttpLogger): HttpLoggingInterceptor {
        val level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BODY else HttpLoggingInterceptor.Level.NONE
        return HttpLoggingInterceptor(httpLogger as HttpLoggingInterceptor.Logger).also { it.level = level }
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
