package com.cryptochecker.app.data.portfolio

import android.content.Context
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.sqlite.db.SupportSQLiteDatabase
import com.cryptochecker.app.data.local.AppDatabase
import com.cryptochecker.app.data.local.DatabaseEncryptionNotice
import com.cryptochecker.app.data.local.StorageRecoveryNotice
import timber.log.Timber
import java.io.File

/**
 * Portfolio in einer eigenen Datenbankdatei ([DB_NAME]): Transaktionen, Portfolio-Alarme und beobachtete Adressen.
 * Getrennt von [AppDatabase], damit die Android-Systemsicherung (backup_rules.xml,
 * data_extraction_rules.xml) die Merkliste sichert, das Portfolio aber nur, wenn der Nutzer es
 * erlaubt (Einstellung «Portfolio in Systemsicherung», [PortfolioBackupMirror]). Die Datei selbst
 * steht nie in den Sicherungsregeln.
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
        const val VERSION = 1
        const val DB_NAME = "cryptochecker_portfolio.db"

        /**
         * Liegt das Portfolio in diesem Prozess nur im Arbeitsspeicher (kein Schlüssel, nie unverschlüsselt angelegt,
         * [DatabaseEncryptionNotice.inMemory])? Erst nach dem ersten Öffnen verlässlich.
         */
        fun inMemory(): Boolean = DatabaseEncryptionNotice.isInMemory(DB_NAME)

        /**
         * Beim Anlegen der Datei: Portfolio aus der Systemsicherung übernehmen (nur wenn eine da ist —
         * also nach einer Wiederherstellung mit eingeschaltetem Schalter). Bei jedem Öffnen: übernommene Kopie
         * löschen bzw. eine beim letzten Start beiseitegelegte Kopie ein zweites Mal versuchen.
         * Fehler werden nur protokolliert: Die Datenbank öffnet trotzdem.
         */
        fun callback(
            context: Context,
            mirrorFile: File = PortfolioBackupMirror.file(context),
        ): RoomDatabase.Callback = object : RoomDatabase.Callback() {
            /** In [onCreate] übernommen: die Klartext-Kopie wird nach dem Anlegen (Transaktion abgeschlossen) gelöscht. */
            @Volatile private var takenOver = false

            /** In [onCreate] beiseitegelegt: der zweite Versuch ([PortfolioBackupMirror.retryFailed]) erst beim nächsten Start. */
            @Volatile private var setAsideNow = false

            override fun onCreate(db: SupportSQLiteDatabase) {
                // Nur im Arbeitsspeicher (kein Schlüssel): nichts übernehmen — die Kopie bliebe sonst nur bis zum Ende des
                // Prozesses erhalten; sie wartet auf den nächsten Start mit Schlüssel
                if (inMemory()) return
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
                            .onSuccess { setAsideNow = it != null }
                            .onFailure { e -> Timber.w(e, "Portfolio: unlesbare Kopie nicht beiseitegelegt") }
                    }
            }

            override fun onOpen(db: SupportSQLiteDatabase) {
                // Nur im Arbeitsspeicher: keine Kopie übernehmen (sie würde danach gelöscht); der nächste Start mit
                // Schlüssel holt das nach
                if (inMemory()) return
                // Übernommene Kopie der Systemsicherung: Klartext, wird nicht mehr gebraucht (erst jetzt — beim Anlegen
                // hätte ein Abbruch der Transaktion sonst die einzige Kopie gekostet)
                if (takenOver) {
                    takenOver = false
                    runCatching { mirrorFile.delete() }.onFailure { Timber.w(it, "Portfolio: übernommene Kopie nicht gelöscht") }
                }
                // Beim letzten Start beiseitegelegte Kopie: ein zweiter Versuch, dann weg (nie dauerhaft Klartext); geht es
                // auch jetzt nicht, nennt die App das einmal
                if (!setAsideNow) {
                    runCatching {
                        val lost = PortfolioBackupMirror.retryFailed(mirrorFile.absoluteFile.parentFile) { file ->
                            db.beginTransaction()
                            try {
                                PortfolioBackupMirror.importInto(db, file, keepIds = false) { invalid ->
                                    PortfolioRestoreNotice.add(context, invalid)
                                }
                                db.setTransactionSuccessful()
                            } finally {
                                db.endTransaction()
                            }
                        }
                        if (lost > 0) StorageRecoveryNotice.recordSystemBackupNotImported(context)
                    }.onFailure { Timber.w(it, "Portfolio: beiseitegelegte Kopie nicht verarbeitet") }
                }
            }
        }
    }
}
