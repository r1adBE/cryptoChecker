package com.cryptochecker.app.data.local

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Hinweise zum Zustand der gespeicherten Daten über der ganzen App (gespiegelt in `StorageRecoveryNotice.swift`):
 *  - [Kind.SET_ASIDE] «Gespeicherte Daten liessen sich nicht entsperren»: Der Schlüssel einer verschlüsselten Datenbank
 *    war weg (Keystore-Schlüssel ungültig oder verloren), die Datei liegt unverändert daneben (`*.unreadable`) und die App
 *    hat leer begonnen. Bleibt, bis der Nutzer ihn bestätigt oder eine Sicherung einspielt ([acknowledgeSetAside]) —
 *    ein einmaliger Hinweis ging zu leicht unter. Weg zurück: Sicherungsdatei einspielen (direkte Aktion im Hinweis).
 *  - [Kind.KEY_RESTORED]: Ein früherer, nummeriert behaltener Schlüssel öffnete die beiseitegelegte Datei beim nächsten
 *    Start doch — sie ist zurück ([DatabaseEncryptionMigration]). Einmal.
 *  - [Kind.RESTORE_ROLLED_BACK] / [Kind.RESTORE_INCOMPLETE]: Ein Wiederherstellen brach ab (Prozess beendet); der Stand
 *    davor wurde aus dem Journal zurückgeschrieben bzw. liess sich nicht zurückschreiben
 *    ([com.cryptochecker.app.data.RestoreJournal]). Einmal.
 *
 * Eigene SharedPreferences-Datei [PREFS], nicht in den Sicherungsregeln: Die Hinweise gehören zu diesem Gerät.
 */
object StorageRecoveryNotice {
    const val PREFS = "storage_recovery_notice"
    private const val KEY_DATABASES = "databases"
    private const val KEY_RESTORED = "restored_databases"
    private const val KEY_RESTORE_INTERRUPTED = "restore_interrupted"
    private const val ROLLED_BACK = "rolled_back"
    private const val INCOMPLETE = "incomplete"

    enum class Kind { SET_ASIDE, KEY_RESTORED, RESTORE_ROLLED_BACK, RESTORE_INCOMPLETE }

    /**
     * Zähler der Vormerkungen in diesem Prozess: Die Datenbank öffnet oft erst nach dem ersten Bild — der Hinweis
     * ([pending]) wird bei jeder Änderung erneut geprüft, nicht erst beim nächsten Start.
     */
    private val recorded = MutableStateFlow(0)
    val changes: StateFlow<Int> = recorded.asStateFlow()

    /** In diesem Prozess weggeklickt (ohne Bestätigung): erst beim nächsten Start wieder zeigen. */
    @Volatile private var setAsideHidden = false

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Betroffene Datenbank vormerken (vor dem Öffnen, darum sofort geschrieben). */
    fun record(context: Context, database: String) {
        val p = prefs(context)
        val databases = p.getStringSet(KEY_DATABASES, emptySet()).orEmpty() + database
        p.edit(commit = true) { putStringSet(KEY_DATABASES, databases) }
        setAsideHidden = false
        recorded.update { it + 1 }
    }

    /** Beiseitegelegte Datei von [database] wieder zurück: nicht mehr «nicht entsperrt», sondern einmal «wieder entsperrt». */
    fun recordRestored(context: Context, database: String) {
        val p = prefs(context)
        val databases = p.getStringSet(KEY_DATABASES, emptySet()).orEmpty() - database
        val restored = p.getStringSet(KEY_RESTORED, emptySet()).orEmpty() + database
        p.edit(commit = true) {
            putStringSet(KEY_DATABASES, databases)
            putStringSet(KEY_RESTORED, restored)
        }
        recorded.update { it + 1 }
    }

    /** Abgebrochenes Wiederherstellen vormerken; [rolledBack] = Stand davor vollständig zurückgeschrieben. */
    fun recordInterruptedRestore(context: Context, rolledBack: Boolean) {
        prefs(context).edit(commit = true) { putString(KEY_RESTORE_INTERRUPTED, if (rolledBack) ROLLED_BACK else INCOMPLETE) }
        recorded.update { it + 1 }
    }

    /**
     * Nächster zu zeigender Hinweis; null = keiner. [Kind.SET_ASIDE] bleibt, bis [acknowledge]; die anderen zeigt die App
     * einmal und bestätigt sie gleich. Nicht auf dem Hauptthread.
     */
    fun pending(context: Context): Kind? {
        val p = prefs(context)
        p.getString(KEY_RESTORE_INTERRUPTED, null)?.let { return if (it == ROLLED_BACK) Kind.RESTORE_ROLLED_BACK else Kind.RESTORE_INCOMPLETE }
        if (!p.getStringSet(KEY_RESTORED, emptySet()).isNullOrEmpty()) return Kind.KEY_RESTORED
        if (!setAsideHidden && !p.getStringSet(KEY_DATABASES, emptySet()).isNullOrEmpty()) return Kind.SET_ASIDE
        return null
    }

    /** Hinweis [kind] erledigt («Verstanden», bzw. gezeigt). Nicht auf dem Hauptthread. */
    fun acknowledge(context: Context, kind: Kind) {
        prefs(context).edit(commit = true) {
            when (kind) {
                Kind.SET_ASIDE -> remove(KEY_DATABASES)
                Kind.KEY_RESTORED -> remove(KEY_RESTORED)
                Kind.RESTORE_ROLLED_BACK, Kind.RESTORE_INCOMPLETE -> remove(KEY_RESTORE_INTERRUPTED)
            }
        }
        recorded.update { it + 1 }
    }

    /** Eine Sicherung wurde eingespielt: der Hinweis «nicht entsperrt» ist erledigt. */
    fun acknowledgeSetAside(context: Context) = acknowledge(context, Kind.SET_ASIDE)

    /** «Später» bzw. weggetippt: in diesem Prozess nicht mehr zeigen, beim nächsten Start wieder. */
    fun hideSetAsideForNow() {
        setAsideHidden = true
        recorded.update { it + 1 }
    }
}
