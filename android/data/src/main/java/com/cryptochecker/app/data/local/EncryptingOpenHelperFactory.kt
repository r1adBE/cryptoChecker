package com.cryptochecker.app.data.local

import android.app.ActivityManager
import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import com.cryptochecker.app.data.local.DatabaseOpenPlanner.Decision
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import timber.log.Timber
import java.io.File

/**
 * Öffnet die Room-Datenbanken verschlüsselt (SQLCipher). Entschieden wird erst beim ersten Zugriff auf die Datei
 * ([DatabaseOpenPlanner]) — Room greift nie auf dem Hauptthread zu, das Verschlüsseln einer wiederhergestellten Kopie der
 * Systemsicherung läuft darum nie dort. Je nach Entscheidung öffnet der Helfer:
 * - verschlüsselt über SQLCipher (`SupportOpenHelperFactory`, roher Schlüssel aus [DatabaseKeyStore]);
 * - nur im Arbeitsspeicher, wenn die Datenbank ohne Schlüssel neu entstehen müsste: nie unverschlüsselt auf dem Speicher,
 *   wie iOS (Änderungen bleiben im Speicher, Portfolio und Merkliste nennen es); der nächste Start versucht es erneut
 *   ([DatabaseEncryptionNotice.inMemory]);
 * - gibt der Keystore den Schlüssel einer verschlüsselten Datei gerade nicht heraus: im Vordergrund und für Datenbanken
 *   aus [inMemoryWhenKeyUnavailable] (App: Portfolio und Merkliste) leer im Arbeitsspeicher für diesen Lauf — die
 *   Oberfläche stürzte sonst beim ersten Zugriff ab; die Datei bleibt unberührt, der gespeicherte Stand kommt nach einem
 *   Neustart zurück ([DatabaseEncryptionNotice.storedKept]). Sonst (Hintergrund, Systemsicherung) gar nicht (Ausnahme;
 *   der nächste Zugriff versucht es erneut, verändert wird nichts).
 * War die verschlüsselte Datei ohne Schlüssel unlesbar, liegt sie beiseite und die App nennt das, bis der Nutzer es
 * bestätigt ([StorageRecoveryNotice]); holt ein früherer Schlüssel sie beim nächsten Start zurück, nennt sie das. Liegt eine
 * Datenbank nur im Arbeitsspeicher, weil kein Schlüssel entstehen konnte, steht das unter Einstellungen › Sicherung, bis
 * ein Start sie verschlüsselt anlegt ([DatabaseEncryptionNotice], [EncryptionPending]).
 */
class EncryptingOpenHelperFactory(
    private val context: Context,
    private val keys: DatabaseOpenPlanner.Keys = DatabaseKeyStore(context),
    private val files: DatabaseOpenPlanner.Files = SqlCipherDatabaseFiles(context),
    /**
     * Darf [database] bei gerade nicht lesbarem Schlüssel für diesen Lauf im Arbeitsspeicher öffnen? Standard nein:
     * Die eigene Instanz der Systemsicherung
     * ([com.cryptochecker.app.data.portfolio.PortfolioBackupMirror.writeForBackup]) soll dann abbrechen, statt ein
     * leeres Portfolio zu sichern. Die App ([com.cryptochecker.app.di.LocalDataModule]) erlaubt es für Portfolio und
     * Merkliste.
     */
    private val inMemoryWhenKeyUnavailable: (database: String) -> Boolean = { false },
    /** Ist die App gerade sichtbar? Nur dann der Arbeitsspeicher statt der Ausnahme ([DatabaseOpenPlanner.planOpen]). */
    private val foreground: () -> Boolean = ::processInForeground,
) : SupportSQLiteOpenHelper.Factory {

    override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper {
        // Datenbank im Speicher (Tests): nichts zu verschlüsseln
        if (configuration.name == null) return FrameworkSQLiteOpenHelperFactory().create(configuration)
        return DeferredOpenHelper(configuration)
    }

    private inner class DeferredOpenHelper(private val configuration: SupportSQLiteOpenHelper.Configuration) :
        SupportSQLiteOpenHelper {

        private var delegate: SupportSQLiteOpenHelper? = null
        private var writeAheadLogging: Boolean? = null

        override val databaseName: String? get() = configuration.name

        @Synchronized
        override fun setWriteAheadLoggingEnabled(enabled: Boolean) {
            writeAheadLogging = enabled
            delegate?.setWriteAheadLoggingEnabled(enabled)
        }

        override val writableDatabase: SupportSQLiteDatabase get() = delegate().writableDatabase

        override val readableDatabase: SupportSQLiteDatabase get() = delegate().readableDatabase

        @Synchronized
        override fun close() {
            delegate?.close()
        }

        @Synchronized
        private fun delegate(): SupportSQLiteOpenHelper {
            delegate?.let { return it }
            val name = requireNotNull(configuration.name)
            // Prozessweit je Datenbank nur eine Vorbereitung zugleich: Die Systemsicherung
            // (PortfolioBackupMirror) baut eine eigene Fabrik — @Synchronized gilt nur je Instanz
            val decision = synchronized(prepareLock(name)) { DatabaseOpenPlanner(
                files,
                keys,
                log = { message, error -> if (error == null) Timber.i(message) else Timber.w(error, message) },
                // Beiseitegelegte Kopien erst nach bestätigtem Hinweis und Frist löschen (AsideRetention)
                asideExpired = { db, copies ->
                    AsideRetention.expired(
                        AsideRetention.SeenStore(File(context.noBackupFilesDir, "${DatabaseKeyStore.DIRECTORY}/$db.aside-seen")),
                        copies, System.currentTimeMillis(), allowed = !StorageRecoveryNotice.isSetAsidePending(context, db),
                    )
                },
            ).prepare(name) }
            // Nur im Arbeitsspeicher nennt Einstellungen › Sicherung (EncryptionPending); verschlüsselt löscht es
            DatabaseEncryptionNotice.note(context, name, decision)
            val helper = when (decision) {
                is Decision.Encrypted -> {
                    if (decision.setAside) StorageRecoveryNotice.record(context, name)
                    // Beiseitegelegte Datei mit früherem Schlüssel zurückgeholt: «wieder entsperrt» statt «nicht entsperrt»
                    if (decision.restored) StorageRecoveryNotice.recordRestored(context, name)
                    SupportOpenHelperFactory(decision.passphrase).create(configuration)
                }
                Decision.Refused -> {
                    // Vor dem Öffnen gesetzt: Room-Callbacks (Übernahme aus Kopien, die danach gelöscht würden) sehen es schon
                    DatabaseEncryptionNotice.markInMemory(name)
                    inMemoryHelper()
                }
                is Decision.KeyUnavailable -> {
                    val plan = DatabaseOpenPlanner.planOpen(
                        DatabaseOpenPlanner.FileKind.ENCRYPTED,
                        keyUsable = false,
                        keyUnavailable = true,
                        inMemoryAllowed = inMemoryWhenKeyUnavailable(name),
                        foreground = runCatching { foreground() }.getOrDefault(false),
                    )
                    if (plan != DatabaseOpenPlanner.OpenPlan.KEEP_FILE_IN_MEMORY) {
                        throw DatabaseKeyUnavailableException(name, decision.error)
                    }
                    Timber.w(decision.error, "%s: Schlüssel gerade nicht verfügbar, für diesen Lauf nur im Arbeitsspeicher", name)
                    // Wie bei Refused vor dem Öffnen gesetzt: keine Übernahme aus Kopien oder Altbestand, kein
                    // Merker «erledigt» — die Datei bleibt, wie sie ist (nie überschrieben, gelöscht oder beiseitegelegt)
                    DatabaseEncryptionNotice.markInMemory(name, storedKept = true)
                    inMemoryHelper()
                }
            }
            writeAheadLogging?.let(helper::setWriteAheadLoggingEnabled)
            delegate = helper
            return helper
        }

        /** Gleiche Callbacks (Schema, Migrationen), aber ohne Namen: SQLite im Arbeitsspeicher, keine Datei. */
        private fun inMemoryHelper(): SupportSQLiteOpenHelper {
            val inMemory = SupportSQLiteOpenHelper.Configuration(configuration.context, null, configuration.callback)
            return FrameworkSQLiteOpenHelperFactory().create(inMemory)
        }
    }

    companion object {
        private val PREPARE_LOCKS = java.util.concurrent.ConcurrentHashMap<String, Any>()

        /** Sperre für die Vorbereitung von [database] ([DatabaseOpenPlanner.prepare]), gleich für alle Fabriken im Prozess. */
        fun prepareLock(database: String): Any = PREPARE_LOCKS.getOrPut(database) { Any() }
    }
}

/**
 * Ist dieser Prozess gerade sichtbar (eine Activity im Vordergrund)? Worker, Empfänger, Widgets und die Systemsicherung
 * laufen mit geringerer Wichtigkeit.
 */
fun processInForeground(): Boolean {
    val info = ActivityManager.RunningAppProcessInfo()
    ActivityManager.getMyMemoryState(info)
    return info.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
}
