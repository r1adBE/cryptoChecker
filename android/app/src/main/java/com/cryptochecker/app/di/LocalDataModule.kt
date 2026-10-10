package com.cryptochecker.app.di

import android.content.Context
import androidx.room.Room
import com.cryptochecker.app.data.local.AppDatabase
import com.cryptochecker.app.data.local.MarketDao
import com.cryptochecker.app.data.local.WatchDao
import com.cryptochecker.app.data.portfolio.PortfolioAlarmDao
import com.cryptochecker.app.data.portfolio.PortfolioDao
import com.cryptochecker.app.data.portfolio.PortfolioDatabase
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
            .addMigrations(*AppDatabase.ALL_MIGRATIONS)
            .build()
    }

    /**
     * Portfolio in eigener Datei (Systemsicherung nur mit Erlaubnis, siehe PortfolioBackupMirror).
     * Der Callback übernimmt beim Öffnen einmalig den Altbestand aus [AppDatabase].
     */
    @Provides
    @Singleton
    fun providePortfolioDatabase(
        @ApplicationContext context: Context,
        appDatabase: AppDatabase,
    ): PortfolioDatabase =
        Room.databaseBuilder(context, PortfolioDatabase::class.java, PortfolioDatabase.DB_NAME)
            .addCallback(PortfolioDatabase.callback(context, appDatabase))
            .build()

    @Provides
    fun provideMarketDao(database: AppDatabase): MarketDao = database.getMarketDao()

    @Provides
    fun provideWatchDao(database: AppDatabase): WatchDao = database.getWatchDao()

    @Provides
    fun providePortfolioDao(database: PortfolioDatabase): PortfolioDao = database.getPortfolioDao()

    @Provides
    fun providePortfolioAlarmDao(database: PortfolioDatabase): PortfolioAlarmDao = database.getPortfolioAlarmDao()
}
