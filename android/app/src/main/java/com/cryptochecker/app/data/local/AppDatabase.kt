package com.cryptochecker.app.data.local

import androidx.room.Database
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.room.RoomDatabase
import com.cryptochecker.app.data.local.AppDatabase.Companion.VERSION
import com.cryptochecker.app.data.local.model.AlarmEntity
import com.cryptochecker.app.data.local.model.MarketEntity
import com.cryptochecker.app.data.local.model.MarketPairEntity
import com.cryptochecker.app.data.local.model.WatchEntity
import com.cryptochecker.app.data.portfolio.PortfolioDao
import com.cryptochecker.app.data.portfolio.PortfolioTxEntity

@Database(
    entities = [
        MarketEntity::class,
        MarketPairEntity::class,
        WatchEntity::class,
        AlarmEntity::class,
        PortfolioTxEntity::class,
    ],
    version = VERSION,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun getMarketDao(): MarketDao
    abstract fun getWatchDao(): WatchDao
    abstract fun getPortfolioDao(): PortfolioDao

    companion object {
        const val VERSION = 10
        const val DB_NAME = "cryptochecker.db"

        /** Datenbank des Vorgängerprojekts; wird beim ersten Start entfernt. */
        const val LEGACY_DB_NAME = "MarketDatabase.db"

        /** Fügt die Merkspalte für die Melde-Schwelle hinzu. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE watches ADD COLUMN notifiedPrice REAL")
            }
        }

        /** Favoriten in der Watchlist. */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE watches ADD COLUMN favorite INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** Alarm «bewegt sich um x % in y Stunden»: Zeitfenster und Fensterbeginn. */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE alarms ADD COLUMN windowHours INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE alarms ADD COLUMN referenceAt INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** Bestand je Paar und Gruppen (mehrere Merklisten). */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE watches ADD COLUMN holdings REAL")
                db.execSQL("ALTER TABLE watches ADD COLUMN groupName TEXT")
            }
        }

        /**
         * Portfolio: eigene Tabelle für Käufe und Verkäufe. Typen und NOT NULL
         * exakt wie in PortfolioTxEntity (Room prüft das Schema beim Öffnen).
         * Die Spalte watches.holdings bleibt bestehen; ihr Inhalt wird einmalig
         * von PortfolioRepository.migrateHoldingsOnce übernommen.
         */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `portfolio_tx` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`coin` TEXT NOT NULL, " +
                        "`type` TEXT NOT NULL, " +
                        "`amount` REAL NOT NULL, " +
                        "`priceUsdt` REAL, " +
                        "`time` INTEGER NOT NULL, " +
                        "`note` TEXT)"
                )
            }
        }

        /** Notiz je Paar. */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE watches ADD COLUMN note TEXT")
            }
        }

        /** Währung des Schwellwerts bei Kursalarmen (null = Quote-Währung). */
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE alarms ADD COLUMN currency TEXT")
            }
        }

        /** Veränderung über 24 Stunden je Paar (abgeleitet, wird bei der nächsten Aktualisierung gefüllt). */
        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE watches ADD COLUMN change24h REAL")
            }
        }

        /** Fügt den Zeitpunkt der letzten Meldung hinzu. */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE watches ADD COLUMN notifiedAt INTEGER NOT NULL DEFAULT 0"
                )

                // Bestehende Einträge haben einen Vergleichskurs, aber noch
                // keinen Zeitstempel. Ohne diesen Startwert bliebe die
                // Altersangabe bei der ersten Meldung nach dem Update leer.
                db.execSQL(
                    """
                    UPDATE watches SET notifiedAt = lastUpdate
                    WHERE notifiedPrice IS NOT NULL AND lastUpdate > 0
                    """.trimIndent()
                )
            }
        }
    }
}
