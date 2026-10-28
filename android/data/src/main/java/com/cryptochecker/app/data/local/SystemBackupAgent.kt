package com.cryptochecker.app.data.local

import android.app.backup.BackupAgent
import android.app.backup.BackupDataInput
import android.app.backup.BackupDataOutput
import android.app.backup.FullBackupDataOutput
import android.content.Context
import android.os.Build
import android.os.ParcelFileDescriptor
import com.cryptochecker.app.data.local.DatabaseOpenPlanner.FileKind
import com.cryptochecker.app.data.local.DatabaseOpenPlanner.KeyLookup
import com.cryptochecker.app.data.portfolio.PortfolioBackupMirror
import timber.log.Timber
import java.io.File
import java.io.IOException

/**
 * Systemsicherung der Hauptdatenbank (Merkliste, Alarme, Gruppen, Notizen) trotz Verschlüsselung.
 *
 * Die verschlüsselte `cryptochecker.db` taugt nicht für Auto Backup oder die Übertragung auf ein neues Gerät: Ihr
 * Schlüssel liegt im Keystore dieses Geräts und geht nie mit. Darum steht in den Sicherungsregeln statt der Datei
 * die Kopie [EXPORT_NAME] — und die gibt es nur während einer Sicherung: [onFullBackup] schreibt sie unverschlüsselt
 * (geprüft, [SqlCipherDatabaseFiles.exportVerified]), lässt die normale Sicherung nach den XML-Regeln laufen (`super`, nach deren
 * Bedingungen: nur Ende-zu-Ende-verschlüsselte Cloud-Sicherung oder Gerät zu Gerät) und löscht sie danach.
 * Nach einer Wiederherstellung wird die Kopie gleich verschlüsselt zur Hauptdatenbank ([onRestoreFinished],
 * [DatabaseSystemBackup.adoptRestored]). Ohne Schlüssel bleibt sie liegen, und der nächste Start verschlüsselt sie,
 * bevor Room die Datenbank öffnet ([DatabaseOpenPlanner]).
 *
 * Android 8/8.1 (API 26/27) ohne Kopie ([SystemBackupScope]): Dort wirkt `requireFlags` nicht, die Merkliste ginge
 * unverschlüsselt in die Cloud; sie wird dort weder gesichert noch wiederhergestellt.
 *
 * Läuft im eingeschränkten App-Prozess der Systemsicherung (ohne `CryptoCheckerApp`, ohne Hilt): nur Context, Dateien
 * und Keystore. Das Portfolio geht nur mit dem Schalter «Portfolio in Systemsicherung» mit (Merker-Datei,
 * [PortfolioBackupMirror.isEnabled]): dann schreibt [onFullBackup] auch dessen Kopie und löscht sie danach — sie liegt
 * nie länger als die Sicherung im Klartext da.
 */
class SystemBackupAgent : BackupAgent() {

    override fun onFullBackup(data: FullBackupDataOutput) {
        val export = DatabaseSystemBackup(this)
        // Android 8/8.1: Die Regeln (xml/backup_rules.xml) nehmen die Kopie nicht mit — ohne requireFlags ginge sie
        // auch ohne Ende-zu-Ende-Verschlüsselung in die Cloud. Also gar nicht erst anlegen (Rest wie gewohnt)
        if (!SystemBackupScope.writesDatabaseCopy(Build.VERSION.SDK_INT)) {
            export.delete()
            runCatching { PortfolioBackupMirror.deleteNow(this) }
            super.onFullBackup(data)
            return
        }
        try {
            // Ohne Kopie diese Sicherung abbrechen: Die vorige (mit Merkliste) bleibt dann bestehen, statt von
            // einer Sicherung ohne Merkliste ersetzt zu werden
            try {
                export.write()
            } catch (e: Exception) {
                Timber.w(e, "Systemsicherung: Kopie der Hauptdatenbank fehlgeschlagen")
                throw IOException("Kopie der Hauptdatenbank fehlgeschlagen", e)
            }
            // Portfolio nur mit dem Schalter; ohne Kopie ebenso abbrechen (die vorige Sicherung mit Portfolio bleibt)
            try {
                if (PortfolioBackupMirror.isEnabled(this)) PortfolioBackupMirror.writeForBackup(this)
                else PortfolioBackupMirror.deleteNow(this)
            } catch (e: Exception) {
                Timber.w(e, "Systemsicherung: Kopie des Portfolios fehlgeschlagen")
                throw IOException("Kopie des Portfolios fehlgeschlagen", e)
            }
            super.onFullBackup(data)
        } finally {
            export.delete()
            runCatching { PortfolioBackupMirror.deleteNow(this) }
                .onFailure { Timber.w(it, "Systemsicherung: Kopie des Portfolios nicht gelöscht") }
        }
    }

    override fun onRestoreFinished() {
        runCatching { DatabaseSystemBackup(this).adoptRestored() }
            .onFailure { Timber.w(it, "Systemsicherung: wiederhergestellte Hauptdatenbank nicht übernommen") }
    }

    // Nur Auto Backup (fullBackupOnly): Schlüssel-Wert-Sicherung gibt es nicht
    override fun onBackup(oldState: ParcelFileDescriptor?, data: BackupDataOutput?, newState: ParcelFileDescriptor?) = Unit

    override fun onRestore(data: BackupDataInput?, appVersionCode: Int, newState: ParcelFileDescriptor?) = Unit

    companion object {
        /**
         * Muss zu xml-v28/backup_rules.xml und data_extraction_rules.xml passen (domain="database"); in
         * xml/backup_rules.xml (Android 8/8.1) bewusst nicht ([SystemBackupScope]).
         */
        const val EXPORT_NAME = "cryptochecker_system_backup.db"
    }
}

/** Unverschlüsselte Kopie der Hauptdatenbank für die Dauer einer Systemsicherung ([SystemBackupAgent]). */
class DatabaseSystemBackup(
    private val context: Context,
    private val keys: DatabaseOpenPlanner.Keys = DatabaseKeyStore(context),
    private val database: String = AppDatabase.DB_NAME,
    private val exportName: String = SystemBackupAgent.EXPORT_NAME,
) {
    private val files = SqlCipherDatabaseFiles(context)
    private val main: File get() = context.getDatabasePath(database)
    private val export: File get() = context.getDatabasePath(exportName)

    /** Kopie schreiben und prüfen; ohne lesbare Hauptdatenbank keine Kopie (die Sicherung enthält dann keine Merkliste). */
    fun write() {
        delete()
        val passphrase = when (SqlCipherDatabaseFiles.kindOf(main)) {
            FileKind.ABSENT -> return
            FileKind.ENCRYPTED -> (keys.load(database) as? KeyLookup.Found)?.passphrase
                ?: throw IOException("Schlüssel der Hauptdatenbank nicht verfügbar")
        }
        files.exportVerified(main, passphrase, export, targetPassphrase = null)
    }

    /**
     * Beim App-Start: Liegt noch eine Kopie herum, ist eine Sicherung abgebrochen (Prozess beendet, `finally` lief nicht)
     * — die unverschlüsselte Datei weg. Nur ohne Hauptdatenbank ist sie eine noch nicht übernommene Wiederherstellung:
     * Sie bleibt, beim ersten Öffnen verschlüsselt sie [DatabaseOpenPlanner] zur Hauptdatenbank.
     */
    fun cleanUpLeftover() {
        if (!export.exists()) {
            delete() // verwaiste Nebendateien
            return
        }
        if (main.exists()) delete()
    }

    /** Kopie samt Nebendateien entfernen (unverschlüsselt — nie länger als die Sicherung liegen lassen). */
    fun delete() {
        for (f in listOf(export, File(export.path + "-journal"), File(export.path + "-wal"), File(export.path + "-shm"))) {
            f.delete()
        }
    }

    /**
     * Nach einer Wiederherstellung (Android stellt nur in eine frische Installation wieder her): Die Kopie wird mit dem
     * gespeicherten oder einem neuen Schlüssel verschlüsselt und geprüft zur Hauptdatenbank, danach gelöscht
     * ([SqlCipherDatabaseFiles.encryptCopy]). Nie unverschlüsselt als Hauptdatenbank: Ist kein Schlüssel möglich (Keystore im
     * Sicherungsprozess nicht verfügbar), bleibt die Kopie, und der nächste Start verschlüsselt sie ([DatabaseOpenPlanner]).
     */
    fun adoptRestored() {
        if (!export.exists()) return
        val passphrase = try {
            keys.loadOrCreate(database)
        } catch (e: Exception) {
            Timber.w(e, "Systemsicherung: kein Schlüssel, wiederhergestellte Kopie bleibt für den nächsten Start")
            return
        }
        files.encryptCopy(export, main, passphrase)
    }
}
