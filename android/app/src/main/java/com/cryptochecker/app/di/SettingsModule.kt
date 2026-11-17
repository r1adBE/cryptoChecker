package com.cryptochecker.app.di

import com.cryptochecker.app.data.settings.DataStoreSettingsRepository
import com.cryptochecker.app.domain.settings.SettingsRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Einstellungen: Schnittstelle aus :domain, Ablage (DataStore) aus :data. */
@Suppress("unused")
@Module
@InstallIn(SingletonComponent::class)
abstract class SettingsModule {
    @Binds
    abstract fun settingsRepository(impl: DataStoreSettingsRepository): SettingsRepository
}
