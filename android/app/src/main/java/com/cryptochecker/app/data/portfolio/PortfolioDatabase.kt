package com.cryptochecker.app.data.portfolio

import android.content.Context
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.cryptochecker.app.data.local.AppDatabase
import timber.log.Timber
import java.io.File

/**
 * Portfolio in einer eigenen Datenbankdatei ([DB_NAME]): Käufe/Verkäufe und Portfolio-Alarme.
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
    ],
    version = PortfolioDatabase.VERSION,
    exportSchema = true,
)
abstract class PortfolioDatabase : RoomDatabase() {
    abstract fun getPortfolioDao(): PortfolioDao
    abstract fun getPortfolioAlarmDao(): PortfolioAlarmDao

    companion object {
        const val VERSION = 1
        const val DB_NAME = "cryptochecker_portfolio.db"

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
            override fun onCreate(db: SupportSQLiteDatabase) {
                // Läuft in der Anlege-Transaktion; erst wird die ganze Datei gelesen und geprüft
                runCatching { PortfolioBackupMirror.importInto(db, mirrorFile) }
                    .onSuccess { count -> if (count > 0) Timber.i("Portfolio: %d Einträge aus der Systemsicherung übernommen", count) }
                    .onFailure {
                        Timber.w(it, "Portfolio: Übernahme aus der Systemsicherung fehlgeschlagen")
                        // Die Datenbank startet leer; ohne Beiseitelegen überschriebe die nächste Kopie
                        // (PortfolioBackupMirror.run) die einzige Sicherung mit einem leeren Portfolio
                        runCatching { PortfolioBackupMirror.setAside(mirrorFile) }
                            .onFailure { e -> Timber.w(e, "Portfolio: unlesbare Kopie nicht beiseitegelegt") }
                    }
            }

            override fun onOpen(db: SupportSQLiteDatabase) {
                runCatching { PortfolioLegacyImport.run(portfolioDb = db, mainDb = { appDatabase.openHelper.writableDatabase }) }
                    .onFailure { Timber.w(it, "Portfolio: Übernahme aus der Hauptdatenbank fehlgeschlagen (nächster Start)") }
            }
        }
    }
}
