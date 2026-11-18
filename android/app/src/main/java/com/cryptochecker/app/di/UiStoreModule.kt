package com.cryptochecker.app.di

import com.cryptochecker.app.data.FavoritesRepository
import com.cryptochecker.app.data.portfolio.PortfolioAlarmRepository
import com.cryptochecker.app.domain.alarm.PortfolioAlarmStore
import com.cryptochecker.app.domain.portfolio.PortfolioSnapshotReader
import com.cryptochecker.app.domain.watch.FavoriteStore
import com.cryptochecker.app.widget.PortfolioSnapshotStore
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Ablagen für die Oberfläche: Schnittstellen aus :domain, Umsetzungen aus :data bzw. :widget. */
@Suppress("unused")
@Module
@InstallIn(SingletonComponent::class)
abstract class UiStoreModule {
    @Binds
    abstract fun portfolioAlarmStore(impl: PortfolioAlarmRepository): PortfolioAlarmStore

    @Binds
    abstract fun portfolioSnapshotReader(impl: PortfolioSnapshotStore): PortfolioSnapshotReader

    @Binds
    abstract fun favoriteStore(impl: FavoritesRepository): FavoriteStore
}
