package com.cryptochecker.app.di

import com.cryptochecker.app.data.WatchStoreAdapter
import com.cryptochecker.app.domain.watch.WatchStore
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Merkliste der Oberfläche: Schnittstelle aus :domain, Umsetzung (Room) aus :data. */
@Suppress("unused")
@Module
@InstallIn(SingletonComponent::class)
abstract class WatchStoreModule {
    @Binds
    abstract fun watchStore(impl: WatchStoreAdapter): WatchStore
}
