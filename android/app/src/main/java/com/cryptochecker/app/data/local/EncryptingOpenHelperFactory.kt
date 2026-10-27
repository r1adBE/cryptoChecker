package com.cryptochecker.app.data.local

import android.app.ActivityManager
import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import com.cryptochecker.app.data.local.DatabaseEncryptionMigration.Decision
import com.cryptochecker.app.data.portfolio.PortfolioDatabase
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import timber.log.Timber
import java.io.File

/**
 * Öffnet die Room-Datenbanken verschlüsselt (SQLCipher). Entschieden wird erst beim ersten Zugriff auf die Datei —
 * Room greift nie auf dem Hauptthread zu, die einmalige Überführung einer alten unverschlüsselten Datei
 * ([DatabaseEncryptionMigration]) läuft darum nie dort. Je nach Entscheidung öffnet der Helfer:
 * - verschlüsselt über SQLCipher (`SupportOpenHelperFactory`, roher Schlüssel aus [DatabaseKeyStore]);
 * - unverschlüsselt über das Android-SQLite wie vor der Verschlüsselung (Rückfall, der nächste Start versucht es erneut);
 * - nur im Arbeitsspeicher, wenn eine Datenbank mit Verschlüsselungspflicht ([encryptionRequired]: Portfolio und
 *   Merkliste) ohne Schlüssel neu entstehen müsste: nie unverschlüsselt auf dem Speicher, wie iOS (Änderungen
 *   bleiben im Speicher, Portfolio und Merkliste nennen es); der nächste Start versucht es erneut
 *   ([DatabaseEncryptionNotice.inMemory]). Eine schon unverschlüsselt vorhandene Datei mit Pflicht bleibt ohne Schlüssel
 *   unberührt: ihr Inhalt kommt in den Arbeitsspeicher ([PlaintextMemoryCopy],
 *   [DatabaseEncryptionNotice.isVolatileCopy]), Änderungen werden nicht gespeichert — auch mit Schlüssel,
 *   aber gescheiterter Überführung;
 * - gibt der Keystore den Schlüssel einer verschlüsselten Datei gerade nicht heraus: im Vordergrund und für Datenbanken
 *   aus [inMemoryWhenKeyUnavailable] (App: Portfolio und Merkliste) leer im Arbeitsspeicher für diesen Lauf — die
 *   Oberfläche stürzte sonst beim ersten Zugriff ab; die Datei bleibt unberührt, der gespeicherte Stand kommt nach einem
 *   Neustart zurück ([DatabaseEncryptionNotice.storedKept]). Sonst (Hintergrund, Systemsicherung) gar nicht (Ausnahme;
 *   der nächste Zugriff versucht es erneut, verändert wird nichts).
 * War die verschlüsselte Datei ohne Schlüssel unlesbar, liegt sie beiseite und die App nennt das, bis der Nutzer es
 * bestätigt ([StorageRecoveryNotice]); holt ein früherer Schlüssel sie beim nächsten Start zurück, nennt sie das. Ein unverschlüsselter Rückfall steht unter Einstellungen › Sicherung, bis ein Start die
 * Datei verschlüsselt ([DatabaseEncryptionNotice]).
 */
class EncryptingOpenHelperFactory(
    private val context: Context,
    private val keys: DatabaseEncryptionMigration.Keys = DatabaseKeyStore(context),
    private val files: DatabaseEncryptionMigration.Files = SqlCipherDatabaseFiles(context),
    /**
     * Datenbanken, die ohne Schlüssel nie unverschlüsselt angelegt werden: Portfolio (Beträge, Adressen, xpub) und Merkliste
     * (Paare, Alarme, Notizen; früher ohne Schlüssel unverschlüsselt angelegt). Ohne Schlüssel und ohne Datei nur im
     * Arbeitsspeicher; den Inhalt dieses Laufs übernimmt ein späterer Schlüssel nicht (der Hinweis sagt das).
     */
    private val encryptionRequired: Set<String> = ENCRYPTION_REQUIRED,
    /**
     * Darf [database] bei gerade nicht lesbarem Schlüssel für diesen Lauf im Arbeitsspeicher öffnen? Standard nein:
     * Die eigene Instanz der Systemsicherung
     * ([com.cryptochecker.app.data.portfolio.PortfolioBackupMirror.writeForBackup]) soll dann abbrechen, statt ein
     * leeres Portfolio zu sichern. Die App ([com.cryptochecker.app.di.LocalDataModule]) erlaubt es für Portfolio und
     * Merkliste.
     */
    private val inMemoryWhenKeyUnavailable: (database: String) -> Boolean = { false },
    /** Ist die App gerade sichtbar? Nur dann der Arbeitsspeicher statt der Ausnahme ([DatabaseEncryptionMigration.planOpen]). */
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
            // Prozessweit je Datenbank nur eine Überführung zugleich: Die Systemsicherung
            // (PortfolioBackupMirror) baut eine eigene Fabrik — @Synchronized gilt nur je Instanz
            val decision = synchronized(prepareLock(name)) { DatabaseEncryptionMigration(
                files,
                keys,
                log = { message, error -> if (error == null) Timber.i(message) else Timber.w(error, message) },
                encryptionRequired = { it in encryptionRequired },
                // Beiseitegelegte Kopien erst nach bestätigtem Hinweis und Frist löschen (AsideRetention)
                asideExpired = { db, copies ->
                    AsideRetention.expired(
                        AsideRetention.SeenStore(File(context.noBackupFilesDir, "${DatabaseKeyStore.DIRECTORY}/$db.aside-seen")),
                        copies, System.currentTimeMillis(), allowed = !StorageRecoveryNotice.isSetAsidePending(context, db),
                    )
                },
            ).prepare(name) }
            // Unverschlüsselt bleiben nennt Einstellungen › Sicherung (EncryptionPending); verschlüsselt löscht es
            DatabaseEncryptionNotice.note(context, name, decision)
            val helper = when (decision) {
                is Decision.Encrypted -> {
                    if (decision.setAside) StorageRecoveryNotice.record(context, name)
                    // Beiseitegelegte Datei mit früherem Schlüssel zurückgeholt: «wieder entsperrt» statt «nicht entsperrt»
                    if (decision.restored) StorageRecoveryNotice.recordRestored(context, name)
                    SupportOpenHelperFactory(decision.passphrase).create(configuration)
                }
                Decision.Plaintext -> FrameworkSQLiteOpenHelperFactory().create(configuration)
                Decision.PlaintextKept -> {
                    // Datei mit gespeichertem Stand, aber nie im Klartext weiterschreiben (wie iOS): Hinweis «nicht gespeichert».
                    // Als Kopie gemerkt: Alarme im Hintergrund warten, bis ein Start die Datei überführt (revertsOnRestart)
                    DatabaseEncryptionNotice.markInMemory(name, copied = true)
                    inMemoryHelper(copyFrom = configuration.context.getDatabasePath(name))
                }
                Decision.Refused -> {
                    // Vor dem Öffnen gesetzt: Room-Callbacks (Übernahme aus Kopien, die danach gelöscht würden) sehen es schon
                    DatabaseEncryptionNotice.markInMemory(name)
                    inMemoryHelper()
                }
                is Decision.KeyUnavailable -> {
                    val plan = DatabaseEncryptionMigration.planOpen(
                        DatabaseEncryptionMigration.FileKind.ENCRYPTED,
                        keyUsable = false,
                        encryptionRequired = name in encryptionRequired,
                        keyUnavailable = true,
                        inMemoryAllowed = inMemoryWhenKeyUnavailable(name),
                        foreground = runCatching { foreground() }.getOrDefault(false),
                    )
                    if (plan != DatabaseEncryptionMigration.OpenPlan.KEEP_FILE_IN_MEMORY) {
                        throw DatabaseKeyUnavailableException(name, decision.error)
                    }
                    Timber.w(decision.error, "%s: Schlüssel gerade nicht verfügbar, für diesen Lauf nur im Arbeitsspeicher", name)
                    // Wie bei Refused vor dem Öffnen gesetzt: keine Übernahme aus Kopien oder Altbestand, kein VACUUM, kein
                    // Merker «erledigt» — die Datei bleibt, wie sie ist (nie überschrieben, gelöscht oder beiseitegelegt)
                    DatabaseEncryptionNotice.markInMemory(name, storedKept = true)
                    inMemoryHelper()
                }
            }
            writeAheadLogging?.let(helper::setWriteAheadLoggingEnabled)
            delegate = helper
            return helper
        }

        /**
         * Gleiche Callbacks (Schema, Migrationen), aber ohne Namen: SQLite im Arbeitsspeicher, keine Datei. [copyFrom]: Inhalt
         * dieser unverschlüsselten Datei beim Öffnen übernehmen ([PlaintextMemoryCopy]), die Datei bleibt unberührt.
         */
        private fun inMemoryHelper(copyFrom: java.io.File? = null): SupportSQLiteOpenHelper {
            val callback = copyFrom?.let { PlaintextMemoryCopy(it, configuration.callback) } ?: configuration.callback
            val inMemory = SupportSQLiteOpenHelper.Configuration(configuration.context, null, callback)
            return FrameworkSQLiteOpenHelperFactory().create(inMemory)
        }
    }

    companion object {
        /** Standard von `encryptionRequired`: Portfolio und Merkliste (Unit-Test `EncryptingOpenHelperFactoryDefaultsTest`). */
        val ENCRYPTION_REQUIRED: Set<String> = setOf(PortfolioDatabase.DB_NAME, AppDatabase.DB_NAME)

        private val PREPARE_LOCKS = java.util.concurrent.ConcurrentHashMap<String, Any>()

        /** Sperre für die Überführung von [database], gleich für alle Fabriken im Prozess. */
        internal fun prepareLock(database: String): Any = PREPARE_LOCKS.getOrPut(database) { Any() }
    }
}

/**
 * Ist dieser Prozess gerade sichtbar (eine Activity im Vordergrund)? Worker, Empfänger, Widgets und die Systemsicherung
 * laufen mit geringerer Wichtigkeit.
 */
internal fun processInForeground(): Boolean {
    val info = ActivityManager.RunningAppProcessInfo()
    ActivityManager.getMyMemoryState(info)
    return info.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
}
