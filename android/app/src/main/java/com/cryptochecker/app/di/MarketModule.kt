package com.cryptochecker.app.di

import com.cryptochecker.app.data.MarketRepository
import com.cryptochecker.app.data.MarketRepositoryImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Suppress("unused")
@Module
@InstallIn(SingletonComponent::class)
abstract class MarketModule {

    @Binds
    abstract fun provideMarketRepository(
        marketRepositoryImp: MarketRepositoryImpl
    ): MarketRepository
}
