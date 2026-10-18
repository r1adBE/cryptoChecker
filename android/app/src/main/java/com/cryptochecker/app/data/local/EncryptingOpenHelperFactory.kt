package com.cryptochecker.app.data.local

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import com.cryptochecker.app.data.local.DatabaseEncryptionMigration.Decision
import com.cryptochecker.app.data.portfolio.PortfolioDatabase
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import timber.log.Timber

/**
 * Öffnet die Room-Datenbanken verschlüsselt (SQLCipher). Entschieden wird erst beim ersten Zugriff auf die Datei —
 * Room greift nie auf dem Hauptthread zu, die einmalige Überführung einer alten unverschlüsselten Datei
 * ([DatabaseEncryptionMigration]) läuft darum nie dort. Je nach Entscheidung öffnet der Helfer:
 * - verschlüsselt über SQLCipher (`SupportOpenHelperFactory`, roher Schlüssel aus [DatabaseKeyStore]);
 * - unverschlüsselt über das Android-SQLite wie bis Build 58 (Rückfall, der nächste Start versucht es erneut);
 * - nur im Arbeitsspeicher, wenn eine Datenbank mit Verschlüsselungspflicht ([encryptionRequired], das Portfolio) ohne
 *   Schlüssel neu entstehen müsste: nie unverschlüsselt auf dem Speicher, wie iOS (Änderungen bleiben im Speicher, das
 *   Portfolio nennt es); der nächste Start versucht es erneut ([DatabaseEncryptionNotice.inMemory]);
 * - gar nicht, solange der Keystore den Schlüssel einer verschlüsselten Datei gerade nicht herausgibt (Ausnahme;
 *   der nächste Zugriff versucht es erneut, verändert wird nichts).
 * War die verschlüsselte Datei ohne Schlüssel unlesbar, liegt sie beiseite und die App nennt das, bis der Nutzer es
 * bestätigt ([StorageRecoveryNotice]); holt ein früherer Schlüssel sie beim nächsten Start zurück, nennt sie das. Ein unverschlüsselter Rückfall steht unter Einstellungen › Sicherung, bis ein Start die
 * Datei verschlüsselt ([DatabaseEncryptionNotice]).
 */
class EncryptingOpenHelperFactory(
    private val context: Context,
    private val keys: DatabaseEncryptionMigration.Keys = DatabaseKeyStore(context),
    private val files: DatabaseEncryptionMigration.Files = SqlCipherDatabaseFiles(context),
    /** Datenbanken, die ohne Schlüssel nie unverschlüsselt angelegt werden (Portfolio: Beträge, Adressen, xpub). */
    private val encryptionRequired: Set<String> = setOf(PortfolioDatabase.DB_NAME),
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
            val decision = DatabaseEncryptionMigration(
                files,
                keys,
                log = { message, error -> if (error == null) Timber.i(message) else Timber.w(error, message) },
                encryptionRequired = { it in encryptionRequired },
            ).prepare(name)
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
                Decision.Refused -> {
                    // Vor dem Öffnen gesetzt: Room-Callbacks (Übernahme aus Kopien, die danach gelöscht würden) sehen es schon
                    DatabaseEncryptionNotice.markInMemory(name)
                    // Gleiche Callbacks (Schema, Migrationen), aber ohne Namen: SQLite im Arbeitsspeicher, keine Datei
                    val inMemory = SupportSQLiteOpenHelper.Configuration(configuration.context, null, configuration.callback)
                    FrameworkSQLiteOpenHelperFactory().create(inMemory)
                }
                is Decision.KeyUnavailable -> throw DatabaseKeyUnavailableException(name, decision.error)
            }
            writeAheadLogging?.let(helper::setWriteAheadLoggingEnabled)
            delegate = helper
            return helper
        }
    }
}

/**
 * Keystore gibt den Schlüssel einer verschlüsselten Datenbank gerade nicht heraus (vorübergehend, nichts verändert).
 * Hintergrund-Einstiege (Worker, Empfänger, Widgets) überspringen den Lauf und versuchen es später erneut
 * ([isDatabaseKeyUnavailable]); die Oberfläche zeigt ihren üblichen Fehlerzustand.
 */
class DatabaseKeyUnavailableException(database: String, cause: Throwable) :
    IllegalStateException("$database: Schlüssel gerade nicht verfügbar", cause)

/** Liegt (auch verschachtelt, z. B. von Room umhüllt) ein [DatabaseKeyUnavailableException] vor? */
fun Throwable.isDatabaseKeyUnavailable(): Boolean =
    generateSequence(this) { it.cause.takeIf { c -> c !== it } }.take(MAX_CAUSE_DEPTH).any { it is DatabaseKeyUnavailableException }

private const val MAX_CAUSE_DEPTH = 16
