package com.cryptochecker.app.data.local

import android.app.backup.BackupAgent
import android.app.backup.BackupDataInput
import android.app.backup.BackupDataOutput
import android.app.backup.FullBackupDataOutput
import android.content.Context
import android.os.Build
import android.os.ParcelFileDescriptor
import com.cryptochecker.app.data.local.DatabaseEncryptionMigration.FileKind
import com.cryptochecker.app.data.local.DatabaseEncryptionMigration.KeyLookup
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
 * (geprüft wie die Überführung), lässt die normale Sicherung nach den XML-Regeln laufen (`super`, gleiche Regeln und
 * Bedingungen wie bisher: nur Ende-zu-Ende-verschlüsselte Cloud-Sicherung oder Gerät zu Gerät) und löscht sie danach.
 * Nach einer Wiederherstellung wird die Kopie zur (unverschlüsselten) Hauptdatenbank ([onRestoreFinished]); der erste
 * Start verschlüsselt sie wie ein Update ([DatabaseEncryptionMigration]). Eine Sicherung älterer Versionen
 * bringt `cryptochecker.db` selbst mit — auf die Kopie umgeleitet ([onRestoreFile]), ebenso beim ersten Start verschlüsselt.
 *
 * Android 8/8.1 (API 26/27) ohne Kopie ([SystemBackupScope]): Dort wirkt `requireFlags` nicht, die Merkliste ginge
 * unverschlüsselt in die Cloud; sie wird dort weder gesichert noch wiederhergestellt.
 *
 * Läuft im eingeschränkten App-Prozess der Systemsicherung (ohne `CryptoCheckerApp`, ohne Hilt): nur Context, Dateien
 * und Keystore. Das Portfolio geht nur mit dem Schalter «Portfolio in Systemsicherung» mit (Merker-Datei,
 * [PortfolioBackupMirror.isEnabled]): dann schreibt [onFullBackup] auch dessen Kopie und löscht sie danach — sie liegt
 * nie länger als die Sicherung im Klartext da (in älteren Versionen dauernd).
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

    /**
     * Sicherung älterer Versionen: `cryptochecker.db` (+ `-wal`) steht nicht mehr in den Regeln, Android
     * verwürfe sie (geprüft wird gegen die heutigen Regeln). Darum auf den Namen der Kopie umgeleitet, der in den
     * Regeln steht; [onRestoreFinished] übernimmt sie dann wie eine neue Sicherung.
     */
    override fun onRestoreFile(data: ParcelFileDescriptor, size: Long, destination: File, type: Int, mode: Long, mtime: Long) {
        val target = runCatching { DatabaseSystemBackup(this).legacyRestoreTarget(destination) }.getOrNull()
        super.onRestoreFile(data, size, target ?: destination, type, mode, mtime)
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
    private val keys: DatabaseEncryptionMigration.Keys = DatabaseKeyStore(context),
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
            FileKind.PLAINTEXT -> null
            FileKind.ENCRYPTED -> (keys.load(database) as? KeyLookup.Found)?.passphrase
                ?: throw IOException("Schlüssel der Hauptdatenbank nicht verfügbar")
        }
        try {
            // create: ATTACH legt die Kopie neu an (siehe SqlCipherDatabaseFiles.exportEncrypted)
            SqlCipherDatabaseFiles.open(main, passphrase, create = true).use { db ->
                db.execSQL("ATTACH DATABASE ? AS plaintext KEY ''", arrayOf<Any?>(export.path))
                try {
                    db.query("SELECT sqlcipher_export('plaintext')").use { it.moveToFirst() }
                    db.execSQL("PRAGMA plaintext.user_version = ${db.version}")
                } finally {
                    db.execSQL("DETACH DATABASE plaintext")
                }
            }
            val before = files.fingerprint(main, passphrase)
            val after = files.fingerprint(export, passphrase = null)
            check(before == after) { "Kopie weicht ab: ${DatabaseEncryptionMigration.difference(before, after)}" }
        } catch (e: Exception) {
            delete()
            throw e
        }
    }

    /**
     * Beim App-Start: Liegt noch eine Kopie herum, ist eine Sicherung abgebrochen (Prozess beendet, `finally` lief nicht)
     * — die unverschlüsselte Datei weg. Nur ohne Hauptdatenbank ist sie eine noch nicht übernommene Wiederherstellung,
     * dann wird sie übernommen statt gelöscht. Muss vor dem ersten Öffnen der Datenbank laufen.
     */
    fun cleanUpLeftover() {
        if (!export.exists()) {
            delete() // verwaiste Nebendateien
            return
        }
        if (main.exists()) {
            delete()
        } else {
            runCatching { adoptRestored() }.onFailure { Timber.w(it, "Systemsicherung: liegengebliebene Kopie nicht übernommen") }
        }
    }

    /** Kopie samt Nebendateien entfernen (unverschlüsselt — nie länger als die Sicherung liegen lassen). */
    fun delete() {
        for (f in listOf(export, File(export.path + "-journal"), File(export.path + "-wal"), File(export.path + "-shm"))) {
            f.delete()
        }
    }

    /**
     * Nach einer Wiederherstellung: Die Kopie ersetzt die Hauptdatenbank (wie früher die gesicherte Datei selbst;
     * Android stellt nur in eine frische Installation wieder her). Beim nächsten Öffnen wird sie verschlüsselt.
     */
    fun adoptRestored() {
        if (!export.exists()) return
        for (suffix in listOf("-wal", "-shm", "-journal")) File(main.path + suffix).delete()
        if (!export.renameTo(main)) throw IOException("Kopie nicht übernommen")
        // WAL nur aus alten Sicherungen (umgeleitet, [legacyRestoreTarget]): gehört zur Datei, mit ihr umbenennen
        val wal = File(export.path + "-wal")
        if (wal.exists() && !wal.renameTo(File(main.path + "-wal"))) throw IOException("WAL nicht übernommen")
    }

    /** Ziel für eine wiederhergestellte Datei einer alten Sicherung (Hauptdatenbank selbst), sonst null. */
    fun legacyRestoreTarget(destination: File): File? {
        val path = destination.canonicalPath
        return when (path) {
            main.canonicalPath -> export
            main.canonicalPath + "-wal" -> File(export.path + "-wal")
            else -> null
        }
    }
}
