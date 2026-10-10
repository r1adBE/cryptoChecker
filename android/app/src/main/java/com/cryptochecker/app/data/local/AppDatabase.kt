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
import com.cryptochecker.app.data.portfolio.PortfolioLegacyImport

@Database(
    entities = [
        MarketEntity::class,
        MarketPairEntity::class,
        WatchEntity::class,
        AlarmEntity::class,
        // Portfolio (portfolio_tx, portfolio_alarms) seit v13 in PortfolioDatabase (eigene Datei)
    ],
    version = VERSION,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun getMarketDao(): MarketDao
    abstract fun getWatchDao(): WatchDao

    companion object {
        const val VERSION = 13
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

        /**
         * Alarm «Portfolio-Wert»: eigene Tabelle ohne Fremdschlüssel (siehe PortfolioAlarmEntity).
         * Typen und NOT NULL exakt wie in PortfolioAlarmEntity; keine Standardwerte (die Entity
         * hat keine `defaultValue`), damit Room das Schema beim Öffnen als gleich erkennt.
         */
        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `portfolio_alarms` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`kind` TEXT NOT NULL, " +
                        "`threshold` REAL NOT NULL, " +
                        "`currency` TEXT, " +
                        "`enabled` INTEGER NOT NULL, " +
                        "`repeating` INTEGER NOT NULL, " +
                        "`referenceAt` INTEGER NOT NULL, " +
                        "`lastTriggeredAt` INTEGER NOT NULL, " +
                        "`lastTriggeredValue` REAL)"
                )
            }
        }

        /**
         * Paare auf Aktien, Rohstoffe, Devisen und Pre-IPO («TradFi») bekommen ein Kennzeichen. Die
         * gespeicherten Listen der Futures-Börsen, die TradFi führen, werden geleert: Binance hat diese
         * Kontrakte bisher verworfen, bei den anderen fehlt das Kennzeichen. Die nächste Auswahl lädt
         * die Listen neu und vollständig.
         * Standardwert wie die Entity (`defaultValue = "0"`), damit Room das Schema als gleich erkennt.
         */
        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE market_pairs ADD COLUMN tradFi INTEGER NOT NULL DEFAULT 0")
                db.execSQL(
                    "DELETE FROM market_pairs WHERE marketId IN " +
                        "(SELECT id FROM markets WHERE marketKey IN " +
                        "('BinanceFutures', 'BybitFutures', 'OkexFutures', 'MexcFutures', 'BitgetFutures'))"
                )
            }
        }

        /**
         * Portfolio zieht in eine eigene Datenbankdatei (PortfolioDatabase), damit die Systemsicherung
         * es nur mit Erlaubnis enthält. Hier wird nichts gelöscht, nur umbenannt: Room prüft nur die
         * Tabellen seiner Entities, `legacy_*` stört nicht. PortfolioLegacyImport kopiert die Zeilen
         * beim ersten Öffnen der Portfolio-Datenbank, prüft die Anzahl und löscht erst dann die
         * `legacy_*`-Tabellen. Schlägt das fehl, bleibt der Altbestand für den nächsten Start liegen.
         */
        val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                listOf(
                    "portfolio_tx" to PortfolioLegacyImport.LEGACY_TX,
                    "portfolio_alarms" to PortfolioLegacyImport.LEGACY_ALARMS,
                ).forEach { (table, legacy) ->
                    val exists = db.query(
                        "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?", arrayOf<Any?>(table)
                    ).use { it.moveToFirst() }
                    if (exists) db.execSQL("ALTER TABLE `$table` RENAME TO `$legacy`")
                }
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

        /**
         * Alle Migrationen in Reihenfolge, für die App ([com.cryptochecker.app.di.LocalDataModule]) und
         * den Migrationstest (androidTest `DatabaseMigrationTest`).
         */
        val ALL_MIGRATIONS: Array<Migration>
            get() = arrayOf(
                MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7,
                MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12,
                MIGRATION_12_13,
            )
    }
}
