package com.cryptochecker.app.di

import com.cryptochecker.app.BuildConfig
import com.cryptochecker.app.data.remote.AppUserAgent
import com.cryptochecker.app.data.remote.HttpUserAgent
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Eigener User-Agent mit voller App-Version für Abrufe ausserhalb des gemeinsamen OkHttp-Clients (Logo-Bilder: ohne
 * Abfangschicht; ohne Angabe lehnen manche Bild-Server ab). Eigenes Modul, damit UI-Tests, die [RemoteDataModule]
 * ersetzen (`FakeRemoteDataModule`), ihn trotzdem bekommen.
 */
@Module
@InstallIn(SingletonComponent::class)
object UserAgentModule {

    @Provides
    @AppUserAgent
    fun appUserAgent(): String = HttpUserAgent.android(BuildConfig.VERSION_NAME)
}
