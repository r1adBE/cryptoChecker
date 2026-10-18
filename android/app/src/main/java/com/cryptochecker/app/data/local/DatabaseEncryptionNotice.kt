package com.cryptochecker.app.data.local

import android.content.Context
import androidx.core.content.edit
import com.cryptochecker.app.data.local.DatabaseEncryptionMigration.Decision
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Speichert, welche Datenbanken unverschlüsselt geöffnet wurden ([EncryptionPending]), damit Einstellungen › Sicherung
 * das auch nennt, wenn eine Datenbank in diesem Prozess noch nicht geöffnet wurde (z. B. das Portfolio). Gesetzt und
 * gelöscht von [EncryptingOpenHelperFactory] bei jeder Entscheidung.
 *
 * Eigene SharedPreferences-Datei [PREFS], nicht in den Sicherungsregeln: Der Zustand gehört zu diesem Gerät.
 */
object DatabaseEncryptionNotice {
    const val PREFS = "database_encryption_pending"
    private const val KEY_DATABASES = "databases"

    /** Zähler der Änderungen in diesem Prozess: Die Seite prüft bei jeder Änderung erneut ([isPending]). */
    private val changed = MutableStateFlow(0)
    val changes: StateFlow<Int> = changed.asStateFlow()

    /** Entscheidung für [database] festhalten (vor dem Öffnen, darum sofort geschrieben). */
    @Synchronized
    fun note(context: Context, database: String, decision: Decision) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val before = prefs.getStringSet(KEY_DATABASES, emptySet()).orEmpty()
        val after = EncryptionPending.updated(before, database, decision)
        if (after == before) return
        prefs.edit(commit = true) { putStringSet(KEY_DATABASES, after) }
        changed.update { it + 1 }
    }

    private val inMemoryNames = MutableStateFlow<Set<String>>(emptySet())

    /**
     * Datenbanken, die in diesem Prozess nur im Arbeitsspeicher liegen ([Decision.Refused]: kein Schlüssel, nie
     * unverschlüsselt angelegt). Nicht gespeichert: Jeder Start entscheidet neu. Das Portfolio nennt es, und was Daten
     * aus anderen Dateien übernimmt und dort löscht (Systemsicherungs-Kopie, Altbestand), wartet bis dahin.
     */
    val inMemory: StateFlow<Set<String>> = inMemoryNames.asStateFlow()

    /** Liegt [database] in diesem Prozess nur im Arbeitsspeicher? Erst nach dem ersten Öffnen verlässlich. */
    fun isInMemory(database: String): Boolean = database in inMemoryNames.value

    /** Von [EncryptingOpenHelperFactory] bei [Decision.Refused] gesetzt. */
    internal fun markInMemory(database: String) {
        inMemoryNames.update { it + database }
    }

    /** Liegt mindestens eine Datenbank unverschlüsselt? Nicht auf dem Hauptthread. */
    fun isPending(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return EncryptionPending.showNotice(prefs.getStringSet(KEY_DATABASES, emptySet()).orEmpty())
    }
}
