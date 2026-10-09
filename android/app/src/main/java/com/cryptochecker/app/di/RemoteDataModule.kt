package com.cryptochecker.app.di

import com.cryptochecker.app.BuildConfig
import com.cryptochecker.app.data.HttpLogger
import com.cryptochecker.app.data.remote.HttpLoggerImpl
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object RemoteDataModule {

    @Singleton
    @Provides
    fun provideOkHttpClient(httpLoggingInterceptor: HttpLoggingInterceptor): OkHttpClient =
        OkHttpClient.Builder()
            // Kurse sollen zügig kommen oder schnell scheitern. Ohne callTimeout
            // griff nur der Schutz in callMarket, und der lag bei 35 s je Anfrage.
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .addInterceptor(httpLoggingInterceptor)
            .build()

    /**
     * Im Debug wird der volle Antworttext mitgeschnitten, damit die Rohanzeige
     * im Börsen-Tab etwas zeigt. Im Release bleibt der Mitschnitt aus: Eine
     * Massenabfrage liefert mehrere Megabyte, die sonst bei jedem Zyklus
     * zusätzlich in einen String kopiert und im Flow gehalten würden.
     */
    @Singleton
    @Provides
    fun provideHttpLoggingInterceptor(httpLogger: HttpLogger): HttpLoggingInterceptor =
        HttpLoggingInterceptor(httpLogger as HttpLoggingInterceptor.Logger).apply {
            level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BODY
            else HttpLoggingInterceptor.Level.NONE
        }

    @Singleton
    @Provides
    fun provideHttpLoggerFlow(): HttpLogger =
        HttpLoggerImpl()
}