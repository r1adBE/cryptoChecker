package com.cryptochecker.app.data.portfolio

import android.content.Context
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.cryptochecker.app.data.local.AppDatabase
import com.cryptochecker.app.domain.portfolio.LegacyStorageMigration
import com.cryptochecker.app.domain.portfolio.PortfolioTxType
import timber.log.Timber
import java.io.File

/**
 * Portfolio in einer eigenen Datenbankdatei ([DB_NAME]): Transaktionen, Portfolio-Alarme und beobachtete Adressen.
 * Getrennt von [AppDatabase], damit die Android-Systemsicherung (backup_rules.xml,
 * data_extraction_rules.xml) die Merkliste sichert, das Portfolio aber nur, wenn der Nutzer es
 * erlaubt (Einstellung «Portfolio in Systemsicherung», [PortfolioBackupMirror]). Die Datei selbst
 * steht nie in den Sicherungsregeln.
 *
 * Bis Datenbank-Version 12 lagen die Tabellen in [AppDatabase]; [AppDatabase.MIGRATION_12_13]
 * benennt sie dort in `legacy_*` um, [PortfolioLegacyImport] übernimmt sie beim ersten Öffnen
 * hierher und entfernt sie danach aus der Hauptdatenbank.
 */
@Database(
    entities = [
        PortfolioTxEntity::class,
        PortfolioAlarmEntity::class,
        WatchedAddressEntity::class,
    ],
    version = PortfolioDatabase.VERSION,
    exportSchema = true,
)
@TypeConverters(PortfolioDecimalConverters::class, PortfolioRestoreStateConverters::class)
abstract class PortfolioDatabase : RoomDatabase() {
    abstract fun getPortfolioDao(): PortfolioDao
    abstract fun getPortfolioAlarmDao(): PortfolioAlarmDao
    abstract fun getWatchedAddressDao(): WatchedAddressDao

    companion object {
        const val VERSION = 5
        const val DB_NAME = "cryptochecker_portfolio.db"

        /**
         * v1 → v2: Menge, Preis und (neu) Gebühr exakt als Text statt REAL (1 Satoshi = 0.00000001 BTC
         * ohne Gleitkomma-Rundung, [PortfolioDecimalConverters]); dazu Gebühr-Einheit und Börse je
         * Transaktion (alle neuen Spalten optional, NULL = keine Angabe). SQLite kann den Typ einer
         * Spalte nicht ändern: Tabelle neu anlegen, Zeilen mit gleicher Id übernehmen (REAL → Text
         * über CAST, z. B. «0.1», «1.0e-08»), alte Tabelle ersetzen. Die Alarme bleiben unverändert.
         */
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `portfolio_tx_new` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`coin` TEXT NOT NULL, `type` TEXT NOT NULL, `amount` TEXT NOT NULL, `priceUsdt` TEXT, " +
                        "`time` INTEGER NOT NULL, `note` TEXT, `fee` TEXT, `feeUnit` TEXT, `exchange` TEXT)"
                )
                db.execSQL(
                    "INSERT INTO `portfolio_tx_new` (`id`, `coin`, `type`, `amount`, `priceUsdt`, `time`, `note`) " +
                        "SELECT `id`, `coin`, `type`, CAST(`amount` AS TEXT), CAST(`priceUsdt` AS TEXT), `time`, `note` " +
                        "FROM `portfolio_tx`"
                )
                db.execSQL("DROP TABLE `portfolio_tx`")
                db.execSQL("ALTER TABLE `portfolio_tx_new` RENAME TO `portfolio_tx`")
            }
        }

        /**
         * v2 → v3 (Build 46): weitere Arten (Umbuchung, Belohnung, Ein-/Auszahlung — nur neue Namen in `type`)
         * und dazu Spalten für Umbuchung (`transferFrom`, `transferTo`) und die Gebühr in einem dritten Coin
         * (`feeCoin`, `feeCoinAmount`, `feeCoinPriceUsdt`; Zahlen als Text wie v2) sowie `importKey` (CSV-Import,
         * mit Index für die Suche nach Doppelten) und `acquiredTime` (Einzahlung: ursprüngliches Kaufdatum, ms).
         * Dazu der Tausch Coin gegen Coin (Art `SWAP`): `toCoin`, `toAmount`, `swapValueUsdt` (Zahlen als Text) und
         * `importBatch` (Kennung des CSV-Importlaufs, «Import rückgängig»).
         * Alle optional, NULL = keine Angabe; bestehende Zeilen bleiben
         * unverändert. Neu die Tabelle der beobachteten Adressen ([WatchedAddressEntity]) samt den beobachteten
         * Guthaben (Spalte `observations`, JSON).
         */
        val MIGRATION_2_3: Migration = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `portfolio_tx` ADD COLUMN `transferFrom` TEXT")
                db.execSQL("ALTER TABLE `portfolio_tx` ADD COLUMN `transferTo` TEXT")
                db.execSQL("ALTER TABLE `portfolio_tx` ADD COLUMN `feeCoin` TEXT")
                db.execSQL("ALTER TABLE `portfolio_tx` ADD COLUMN `feeCoinAmount` TEXT")
                db.execSQL("ALTER TABLE `portfolio_tx` ADD COLUMN `feeCoinPriceUsdt` TEXT")
                db.execSQL("ALTER TABLE `portfolio_tx` ADD COLUMN `importKey` TEXT")
                db.execSQL("ALTER TABLE `portfolio_tx` ADD COLUMN `acquiredTime` INTEGER")
                db.execSQL("ALTER TABLE `portfolio_tx` ADD COLUMN `toCoin` TEXT")
                db.execSQL("ALTER TABLE `portfolio_tx` ADD COLUMN `toAmount` TEXT")
                db.execSQL("ALTER TABLE `portfolio_tx` ADD COLUMN `swapValueUsdt` TEXT")
                db.execSQL("ALTER TABLE `portfolio_tx` ADD COLUMN `importBatch` TEXT")
                db.execSQL(IMPORT_KEY_INDEX_SQL)
                db.execSQL(WatchedAddressEntity.CREATE_SQL)
                db.execSQL(WatchedAddressEntity.INDEX_SQL)
            }
        }

        /**
         * v3 → v4 (Build 58, Entscheid des Inhabers vom 17. Okt. 2026): eigene Spalten statt der Notlösungen aus Build 56/57 —
         * `giftToSpouse` (statt Merker «@spouse» im Feld «nach» einer Schenkung), `linkedImportKey` (zweiter Herkunftsschlüssel
         * einer verknüpften Umbuchung, statt «A ⇄ B» in `importKey`) und `importRestoreState` (Zustand vor der Verknüpfung als
         * JSON, statt «⟲ …» im Schlüssel). Bestehende Zeilen mit einer alten Form werden umgeschrieben
         * ([LegacyStorageMigration], gleiche Regeln wie beim Wiederherstellen alter Sicherungen und auf iOS); alle anderen
         * bleiben unverändert. Zuerst gelesen, dann geschrieben (kein Schreiben während der Cursor offen ist).
         */
        val MIGRATION_3_4: Migration = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `portfolio_tx` ADD COLUMN `giftToSpouse` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `portfolio_tx` ADD COLUMN `linkedImportKey` TEXT")
                db.execSQL("ALTER TABLE `portfolio_tx` ADD COLUMN `importRestoreState` TEXT")
                val converted = ArrayList<Pair<Long, LegacyStorageMigration.Fields>>()
                db.query(
                    "SELECT `id`, `type`, `transferTo`, `importKey` FROM `portfolio_tx` " +
                        "WHERE `transferTo` IS NOT NULL OR `importKey` LIKE '%${LegacyStorageMigration.LINK.trim()}%'"
                ).use { c ->
                    while (c.moveToNext()) {
                        val type = PortfolioTxType.fromName(c.getString(1))
                        val before = LegacyStorageMigration.Fields(
                            transferTo = if (c.isNull(2)) null else c.getString(2),
                            giftToSpouse = false,
                            importKey = if (c.isNull(3)) null else c.getString(3),
                            linkedImportKey = null,
                            importRestoreState = null,
                        )
                        val after = LegacyStorageMigration.convert(type, before)
                        if (after != before) converted += c.getLong(0) to after
                    }
                }
                val restoreText = PortfolioRestoreStateConverters()
                for ((id, f) in converted) {
                    db.execSQL(
                        "UPDATE `portfolio_tx` SET `transferTo` = ?, `giftToSpouse` = ?, `importKey` = ?, " +
                            "`linkedImportKey` = ?, `importRestoreState` = ? WHERE `id` = ?",
                        arrayOf<Any?>(
                            f.transferTo, if (f.giftToSpouse) 1L else 0L, f.importKey, f.linkedImportKey,
                            restoreText.toText(f.importRestoreState), id,
                        ),
                    )
                }
            }
        }

        /**
         * v4 → v5 (Build 76): eigene Merker statt Schlüssen aus anderen Feldern — `feeRow` (Gebühr-Zeile, bisher «Verkauf zum
         * Preis 0 mit Import-Herkunft») und `interest` (Zins, bisher aus der Notiz «interest/lending/Zins»). Beide einmal nach
         * der alten Regel abgeleitet ([LegacyStorageMigration.legacyFeeRow], [LegacyStorageMigration.legacyInterest]); danach
         * zählt nur noch das Feld. Zuerst gelesen, dann geschrieben (kein Schreiben während der Cursor offen ist); Preise
         * exakt über [PortfolioDecimalConverters] (Text, z. B. «0», «0.00» oder «1.0e-08» aus v1).
         */
        val MIGRATION_4_5: Migration = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `portfolio_tx` ADD COLUMN `feeRow` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `portfolio_tx` ADD COLUMN `interest` INTEGER NOT NULL DEFAULT 0")
                val feeRows = ArrayList<Long>()
                val interest = ArrayList<Long>()
                val decimals = PortfolioDecimalConverters()
                db.query(
                    "SELECT `id`, `type`, `priceUsdt`, `importKey`, `note` FROM `portfolio_tx` WHERE `type` IN ('SELL', 'REWARD')"
                ).use { c ->
                    while (c.moveToNext()) {
                        val type = PortfolioTxType.fromName(c.getString(1))
                        val price = if (c.isNull(2)) null else decimals.fromText(c.getString(2))
                        val importKey = if (c.isNull(3)) null else c.getString(3)
                        val note = if (c.isNull(4)) null else c.getString(4)
                        if (LegacyStorageMigration.legacyFeeRow(type, importKey, price)) feeRows += c.getLong(0)
                        if (LegacyStorageMigration.legacyInterest(type, note)) interest += c.getLong(0)
                    }
                }
                feeRows.forEach { db.execSQL("UPDATE `portfolio_tx` SET `feeRow` = 1 WHERE `id` = ?", arrayOf<Any?>(it)) }
                interest.forEach { db.execSQL("UPDATE `portfolio_tx` SET `interest` = 1 WHERE `id` = ?", arrayOf<Any?>(it)) }
            }
        }

        /** Index nach Room-Konvention (`index_<Tabelle>_<Spalte>`), passend zu [PortfolioTxEntity]. */
        const val IMPORT_KEY_INDEX_SQL =
            "CREATE INDEX IF NOT EXISTS `index_portfolio_tx_importKey` ON `portfolio_tx` (`importKey`)"

        /** Alle Migrationen der Portfolio-Datenbank (LocalDataModule, Tests). */
        val ALL_MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)

        /**
         * Beim Anlegen der Datei: Portfolio aus der Systemsicherung übernehmen (nur wenn eine da ist —
         * also nach einer Wiederherstellung mit eingeschaltetem Schalter). Bei jedem Öffnen: Altbestand
         * aus der Hauptdatenbank übernehmen (einmalig, siehe [PortfolioLegacyImport]).
         * Fehler werden nur protokolliert: Die Datenbank öffnet trotzdem, der Altbestand bleibt in der
         * Hauptdatenbank und der nächste Start versucht es erneut.
         */
        fun callback(
            context: Context,
            appDatabase: AppDatabase,
            mirrorFile: File = PortfolioBackupMirror.file(context),
        ): RoomDatabase.Callback = object : RoomDatabase.Callback() {
            /** In [onCreate] übernommen: die Klartext-Kopie wird nach dem Anlegen (Transaktion abgeschlossen) gelöscht. */
            @Volatile private var takenOver = false

            override fun onCreate(db: SupportSQLiteDatabase) {
                // Läuft in der Anlege-Transaktion; erst wird die ganze Datei gelesen und geprüft
                // Ungültige Einträge nicht still verwerfen: beim nächsten Öffnen des Portfolios einmal nennen
                runCatching {
                    PortfolioBackupMirror.importInto(db, mirrorFile) { invalid -> PortfolioRestoreNotice.add(context, invalid) }
                }
                    .onSuccess { count ->
                        if (count > 0) Timber.i("Portfolio: %d Einträge aus der Systemsicherung übernommen", count)
                        takenOver = mirrorFile.isFile
                    }
                    .onFailure {
                        Timber.w(it, "Portfolio: Übernahme aus der Systemsicherung fehlgeschlagen")
                        // Die Datenbank startet leer; ohne Beiseitelegen überschriebe die nächste Sicherung
                        // (PortfolioBackupMirror.writeForBackup) die einzige Kopie mit einem leeren Portfolio
                        runCatching { PortfolioBackupMirror.setAside(mirrorFile) }
                            .onFailure { e -> Timber.w(e, "Portfolio: unlesbare Kopie nicht beiseitegelegt") }
                    }
            }

            override fun onOpen(db: SupportSQLiteDatabase) {
                // Übernommene Kopie der Systemsicherung: Klartext, wird nicht mehr gebraucht (erst jetzt — beim Anlegen
                // hätte ein Abbruch der Transaktion sonst die einzige Kopie gekostet)
                if (takenOver) {
                    takenOver = false
                    runCatching { mirrorFile.delete() }.onFailure { Timber.w(it, "Portfolio: übernommene Kopie nicht gelöscht") }
                }
                runCatching { PortfolioLegacyImport.run(portfolioDb = db, mainDb = { appDatabase.openHelper.writableDatabase }) }
                    .onFailure { Timber.w(it, "Portfolio: Übernahme aus der Hauptdatenbank fehlgeschlagen (nächster Start)") }
            }
        }
    }
}
