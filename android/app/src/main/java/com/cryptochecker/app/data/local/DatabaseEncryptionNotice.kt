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
     * unverschlüsselt angelegt; dazu [isVolatileCopy] und [storedKept]). Nicht gespeichert: Jeder Start entscheidet neu. Das Portfolio nennt es, und was Daten
     * aus anderen Dateien übernimmt und dort löscht (Systemsicherungs-Kopie, Altbestand), wartet bis dahin.
     */
    val inMemory: StateFlow<Set<String>> = inMemoryNames.asStateFlow()

    /** Liegt [database] in diesem Prozess nur im Arbeitsspeicher? Erst nach dem ersten Öffnen verlässlich. */
    fun isInMemory(database: String): Boolean = database in inMemoryNames.value

    private val keptNames = MutableStateFlow<Set<String>>(emptySet())

    /**
     * Teilmenge von [inMemory]: Die verschlüsselte Datei ist da, nur gab der Keystore ihren Schlüssel gerade nicht heraus
     * ([DatabaseEncryptionMigration.OpenPlan.KEEP_FILE_IN_MEMORY]). Der gespeicherte Stand ist jetzt nicht zu sehen und
     * kommt nach einem Neustart zurück; was in diesem Lauf geändert wird, geht verloren. Die Datei bleibt unberührt.
     */
    val storedKept: StateFlow<Set<String>> = keptNames.asStateFlow()

    /** Liegt [database] nur im Arbeitsspeicher, obwohl die Datei mit gespeichertem Stand da ist ([storedKept])? */
    fun isStoredKept(database: String): Boolean = database in keptNames.value

    private val copiedNames = MutableStateFlow<Set<String>>(emptySet())

    /**
     * Teilmenge von [inMemory] (eigens gemerkt): Der Inhalt einer alten unverschlüsselten Datei liegt für diesen
     * Lauf als Kopie im Arbeitsspeicher ([Decision.PlaintextKept], [PlaintextMemoryCopy]). Paare und Alarme sind die
     * gespeicherten, aber was dieser Lauf an ihnen ändert — auch «ausgelöst» eines Alarms —, ist im nächsten Prozess wieder
     * der Stand der Datei.
     */
    fun isVolatileCopy(database: String): Boolean = database in copiedNames.value

    /**
     * Kommt mit dem nächsten Prozess der Stand der Datei zurück und verwirft, was dieser Lauf geschrieben hat ([isStoredKept]
     * oder [isVolatileCopy])? Dann keine Alarme im Hintergrund prüfen (jeder neue Prozess meldete dieselben Alarme erneut)
     * und gespeicherte Protokolle nicht gegen die Ids dieses Laufs kürzen. Ohne Datei ([Decision.Refused]) nicht: Dort ist
     * mit dem Lauf auch der Alarm weg.
     */
    fun revertsOnRestart(database: String): Boolean = isStoredKept(database) || isVolatileCopy(database)

    /**
     * Von [EncryptingOpenHelperFactory] bei [Decision.Refused] gesetzt, mit [copied] bei [Decision.PlaintextKept], mit
     * [storedKept] bei [Decision.KeyUnavailable] im Vordergrund. Erst die Variante, dann [inMemory]: Wer [inMemory]
     * beobachtet, sieht die Variante schon.
     */
    internal fun markInMemory(database: String, storedKept: Boolean = false, copied: Boolean = false) {
        if (storedKept) keptNames.update { it + database }
        if (copied) copiedNames.update { it + database }
        inMemoryNames.update { it + database }
    }

    /**
     * Nur für Tests (WatchlistLockDeviceTest): Vermerke von [markInMemory] für [database] wieder entfernen,
     * damit die Sperre der Merkliste nicht in die übrigen Tests desselben Prozesses hineinwirkt. Die App ruft es nie auf.
     */
    internal fun clearInMemoryForTest(database: String) {
        keptNames.update { it - database }
        copiedNames.update { it - database }
        inMemoryNames.update { it - database }
    }

    /** Liegt mindestens eine Datenbank unverschlüsselt? Nicht auf dem Hauptthread. */
    fun isPending(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return EncryptionPending.showNotice(prefs.getStringSet(KEY_DATABASES, emptySet()).orEmpty())
    }
}
