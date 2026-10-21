package com.cryptochecker.app.data.local

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
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
 *  - [Kind.SYSTEM_BACKUP_NOT_IMPORTED] (nur Android): Die Portfolio-Kopie aus der Android-Systemsicherung liess sich auch
 *    beim zweiten Versuch nicht übernehmen und wurde gelöscht ([com.cryptochecker.app.data.portfolio.PortfolioBackupMirror.retryFailed]).
 *    Einmal; Weg zurück ist die eigene Sicherungsdatei.
 *  - [Kind.SETTINGS_RESET] (seit Build 97): Die Einstellungsdatei war beschädigt und wurde durch Standardwerte ersetzt
 *    ([com.cryptochecker.app.settings.settingsDataStore]) — Portfolio-Sperre, Intervall und Währung stehen dann still auf
 *    dem Standard (Sperre aus). Bleibt vorgemerkt, bis der Nutzer den Hinweis schliesst, damit er sicher gesehen wird.
 *    Nicht bei einer neuen Installation und nicht, wenn die Datei bloss nicht lesbar war (dann ersetzt DataStore nichts).
 *    Seit Build 102 gilt die Sperre danach als an, bis der Nutzer sie selbst setzt ([confirmAppLock]); ein blosser
 *    Lesefehler wird ebenfalls gemeldet (`SettingsLockFallback`).
 *
 * Eigene SharedPreferences-Datei [PREFS], nicht in den Sicherungsregeln: Die Hinweise gehören zu diesem Gerät.
 */
object StorageRecoveryNotice {
    const val PREFS = "storage_recovery_notice"
    private const val KEY_DATABASES = "databases"
    private const val KEY_RESTORED = "restored_databases"
    private const val KEY_RESTORE_INTERRUPTED = "restore_interrupted"
    private const val KEY_SYSTEM_BACKUP_NOT_IMPORTED = "system_backup_not_imported"
    private const val KEY_SETTINGS_RESET = "settings_reset"
    private const val KEY_APP_LOCK_UNKNOWN = "app_lock_unknown"
    private const val KEY_ROLLBACK_ATTEMPTED_FOR = "restore_rollback_attempted_for"
    private const val ROLLED_BACK = "rolled_back"
    private const val INCOMPLETE = "incomplete"

    enum class Kind { SET_ASIDE, KEY_RESTORED, RESTORE_ROLLED_BACK, RESTORE_INCOMPLETE, SYSTEM_BACKUP_NOT_IMPORTED, SETTINGS_RESET }

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

    /** Wartet für [database] noch der Hinweis «nicht entsperrt» auf Bestätigung? Dann bleiben beiseitegelegte Kopien. */
    fun isSetAsidePending(context: Context, database: String): Boolean =
        database in prefs(context).getStringSet(KEY_DATABASES, emptySet()).orEmpty()

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
     * Seit Build 93: Für das Journal mit Zeitpunkt [journalCreatedAt] wollte ein Start schon zurückrollen (gescheitert)
     * oder gab auf — der nächste Start rollt es nicht mehr zurück ([com.cryptochecker.app.data.RestoreRecoveryPolicy]).
     * Ein neues Journal hat einen anderen Zeitpunkt, darum muss der Wert nie gelöscht werden.
     */
    fun recordRollBackAttempt(context: Context, journalCreatedAt: Long) {
        prefs(context).edit(commit = true) { putLong(KEY_ROLLBACK_ATTEMPTED_FOR, journalCreatedAt) }
    }

    /** Gegenstück zu [recordRollBackAttempt]; null = noch kein Versuch vermerkt. */
    fun rollBackAttemptedFor(context: Context): Long? =
        prefs(context).let { if (it.contains(KEY_ROLLBACK_ATTEMPTED_FOR)) it.getLong(KEY_ROLLBACK_ATTEMPTED_FOR, 0L) else null }

    /** Portfolio-Kopie der Systemsicherung endgültig nicht übernommen (und gelöscht) vormerken. */
    fun recordSystemBackupNotImported(context: Context) {
        prefs(context).edit(commit = true) { putBoolean(KEY_SYSTEM_BACKUP_NOT_IMPORTED, true) }
        recorded.update { it + 1 }
    }

    /**
     * Einstellungen beschädigt und durch Standardwerte ersetzt vormerken. Läuft im Ersatz-Handler von DataStore, also
     * vor dem ersten Lesen — darum sofort geschrieben (der Prozess kann gleich danach enden).
     * [lockUnknown] (seit Build 102): Die Portfolio-Sperre gilt als unbekannt und damit als an, bis der Nutzer sie selbst
     * setzt ([confirmAppLock]) — nicht bei einem blossen Lesefehler, dort ist die Datei unverändert.
     */
    fun recordSettingsReset(context: Context, lockUnknown: Boolean = true) {
        prefs(context).edit(commit = true) {
            putBoolean(KEY_SETTINGS_RESET, true)
            if (lockUnknown) putBoolean(KEY_APP_LOCK_UNKNOWN, true)
        }
        recorded.update { it + 1 }
    }

    /**
     * Portfolio-Sperre unbekannt (Einstellungen beschädigt, [recordSettingsReset])? Gibt bei jeder Vormerkung neu aus;
     * gelesen im Hintergrund. Für `SettingsLockFallback`.
     */
    fun appLockUnknown(context: Context): Flow<Boolean> = changes
        .map { prefs(context).getBoolean(KEY_APP_LOCK_UNKNOWN, false) }
        .distinctUntilChanged()
        .flowOn(Dispatchers.IO)

    /** Der Nutzer hat die Portfolio-Sperre selbst gesetzt (oder eine Sicherung sie): nicht mehr unbekannt. */
    fun confirmAppLock(context: Context) {
        val p = prefs(context)
        if (!p.getBoolean(KEY_APP_LOCK_UNKNOWN, false)) return
        p.edit(commit = true) { remove(KEY_APP_LOCK_UNKNOWN) }
        recorded.update { it + 1 }
    }

    /**
     * Rein (testbar, wie iOS `StorageRecoveryNotice.next`): Reihenfolge der Hinweise. Abgebrochenes Wiederherstellen
     * zuerst (betrifft alle Daten), «nicht entsperrt» zuletzt (bleibt ohnehin, bis bestätigt).
     */
    internal fun next(
        restoreInterrupted: String?,
        keyRestored: Boolean,
        systemBackupNotImported: Boolean,
        settingsReset: Boolean,
        setAside: Boolean,
        setAsideHidden: Boolean,
    ): Kind? = when {
        restoreInterrupted != null -> if (restoreInterrupted == ROLLED_BACK) Kind.RESTORE_ROLLED_BACK else Kind.RESTORE_INCOMPLETE
        keyRestored -> Kind.KEY_RESTORED
        systemBackupNotImported -> Kind.SYSTEM_BACKUP_NOT_IMPORTED
        settingsReset -> Kind.SETTINGS_RESET
        setAside && !setAsideHidden -> Kind.SET_ASIDE
        else -> null
    }

    /**
     * Nächster zu zeigender Hinweis; null = keiner. [Kind.SET_ASIDE] bleibt, bis [acknowledge]; die anderen zeigt die App
     * einmal und bestätigt sie gleich ([Kind.SETTINGS_RESET] erst beim Schliessen). Nicht auf dem Hauptthread.
     */
    fun pending(context: Context): Kind? {
        val p = prefs(context)
        return next(
            restoreInterrupted = p.getString(KEY_RESTORE_INTERRUPTED, null),
            keyRestored = !p.getStringSet(KEY_RESTORED, emptySet()).isNullOrEmpty(),
            systemBackupNotImported = p.getBoolean(KEY_SYSTEM_BACKUP_NOT_IMPORTED, false),
            settingsReset = p.getBoolean(KEY_SETTINGS_RESET, false),
            setAside = !p.getStringSet(KEY_DATABASES, emptySet()).isNullOrEmpty(),
            setAsideHidden = setAsideHidden,
        )
    }

    /** Hinweis [kind] erledigt («Verstanden», bzw. gezeigt). Nicht auf dem Hauptthread. */
    fun acknowledge(context: Context, kind: Kind) {
        prefs(context).edit(commit = true) {
            when (kind) {
                Kind.SET_ASIDE -> remove(KEY_DATABASES)
                Kind.KEY_RESTORED -> remove(KEY_RESTORED)
                Kind.RESTORE_ROLLED_BACK, Kind.RESTORE_INCOMPLETE -> remove(KEY_RESTORE_INTERRUPTED)
                Kind.SYSTEM_BACKUP_NOT_IMPORTED -> remove(KEY_SYSTEM_BACKUP_NOT_IMPORTED)
                Kind.SETTINGS_RESET -> remove(KEY_SETTINGS_RESET)
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
