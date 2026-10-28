package com.cryptochecker.app.di

import com.cryptochecker.app.data.ExchangeRepository
import com.cryptochecker.app.data.ExchangeRepositoryImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Verbindet die Schnittstelle [ExchangeRepository] mit ihrer Umsetzung. */
@Suppress("unused")
@Module
@InstallIn(SingletonComponent::class)
abstract class ExchangeModule {

    @Binds
    abstract fun provideExchangeRepository(impl: ExchangeRepositoryImpl): ExchangeRepository
}
