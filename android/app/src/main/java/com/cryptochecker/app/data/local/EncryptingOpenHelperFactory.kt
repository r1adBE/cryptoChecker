package com.cryptochecker.app.data.local

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import com.cryptochecker.app.data.local.DatabaseEncryptionMigration.Decision
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import timber.log.Timber

/**
 * Öffnet die Room-Datenbanken verschlüsselt (SQLCipher). Entschieden wird erst beim ersten Zugriff auf die Datei —
 * Room greift nie auf dem Hauptthread zu, die einmalige Überführung einer alten unverschlüsselten Datei
 * ([DatabaseEncryptionMigration]) läuft darum nie dort. Je nach Entscheidung öffnet der Helfer:
 * - verschlüsselt über SQLCipher (`SupportOpenHelperFactory`, roher Schlüssel aus [DatabaseKeyStore]);
 * - unverschlüsselt über das Android-SQLite wie bis Build 58 (Rückfall, der nächste Start versucht es erneut);
 * - gar nicht, solange der Keystore den Schlüssel einer verschlüsselten Datei gerade nicht herausgibt (Ausnahme;
 *   der nächste Zugriff versucht es erneut, verändert wird nichts).
 * War die verschlüsselte Datei ohne Schlüssel unlesbar, liegt sie beiseite und die App nennt das einmal
 * ([StorageRecoveryNotice]).
 */
class EncryptingOpenHelperFactory(
    private val context: Context,
    private val keys: DatabaseEncryptionMigration.Keys = DatabaseKeyStore(context),
    private val files: DatabaseEncryptionMigration.Files = SqlCipherDatabaseFiles(context),
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
            val decision = DatabaseEncryptionMigration(files, keys) { message, error ->
                if (error == null) Timber.i(message) else Timber.w(error, message)
            }.prepare(name)
            val helper = when (decision) {
                is Decision.Encrypted -> {
                    if (decision.setAside) StorageRecoveryNotice.record(context, name)
                    SupportOpenHelperFactory(decision.passphrase).create(configuration)
                }
                Decision.Plaintext -> FrameworkSQLiteOpenHelperFactory().create(configuration)
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
