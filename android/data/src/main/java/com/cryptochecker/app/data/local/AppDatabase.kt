package com.cryptochecker.app.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.cryptochecker.app.data.local.AppDatabase.Companion.VERSION
import com.cryptochecker.app.data.local.model.AlarmRow
import com.cryptochecker.app.data.local.model.ExchangeEntity
import com.cryptochecker.app.data.local.model.ExchangePairEntity
import com.cryptochecker.app.data.local.model.WatchRow

@Database(
    entities = [
        ExchangeEntity::class,
        ExchangePairEntity::class,
        WatchRow::class,
        AlarmRow::class,
        // Portfolio (portfolio_tx, portfolio_alarms) liegt in PortfolioDatabase (eigene Datei)
    ],
    version = VERSION,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun getExchangeDao(): ExchangeDao
    abstract fun getWatchDao(): WatchDao

    companion object {
        const val VERSION = 1
        const val DB_NAME = "cryptochecker.db"
    }
}
