package com.cryptochecker.app.di

import android.content.Context
import androidx.room.Room
import com.cryptochecker.app.data.local.AppDatabase
import com.cryptochecker.app.data.local.MarketDao
import com.cryptochecker.app.data.local.WatchDao
import com.cryptochecker.app.data.portfolio.PortfolioAlarmDao
import com.cryptochecker.app.data.portfolio.PortfolioDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object LocalDataModule {

    @Provides
    @Singleton
    fun provideAppDatabase(
        @ApplicationContext context: Context,
    ): AppDatabase {
        // Datenbank des Vorgängerprojekts aufräumen (anderer Name, andere Pakete).
        runCatching { context.deleteDatabase(AppDatabase.LEGACY_DB_NAME) }

        return Room.databaseBuilder(context, AppDatabase::class.java, AppDatabase.DB_NAME)
            .addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3, AppDatabase.MIGRATION_3_4, AppDatabase.MIGRATION_4_5, AppDatabase.MIGRATION_5_6, AppDatabase.MIGRATION_6_7, AppDatabase.MIGRATION_7_8, AppDatabase.MIGRATION_8_9, AppDatabase.MIGRATION_9_10, AppDatabase.MIGRATION_10_11, AppDatabase.MIGRATION_11_12)
            .build()
    }

    @Provides
    fun provideMarketDao(database: AppDatabase): MarketDao = database.getMarketDao()

    @Provides
    fun provideWatchDao(database: AppDatabase): WatchDao = database.getWatchDao()

    @Provides
    fun providePortfolioDao(database: AppDatabase): PortfolioDao = database.getPortfolioDao()

    @Provides
    fun providePortfolioAlarmDao(database: AppDatabase): PortfolioAlarmDao = database.getPortfolioAlarmDao()
}
