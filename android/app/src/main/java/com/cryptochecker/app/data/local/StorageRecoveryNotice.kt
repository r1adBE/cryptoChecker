package com.cryptochecker.app.data.local

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Einmaliger Hinweis «Gespeicherte Daten liessen sich nicht entsperren» (gespiegelt in `StorageRecoveryNotice.swift`):
 * Der Schlüssel einer verschlüsselten Datenbank war endgültig weg (Keystore-Schlüssel ungültig oder verloren), die
 * Datei liegt unverändert daneben (`*.unreadable`) und die App hat leer begonnen. Weg zurück: eine Sicherungsdatei
 * einspielen; mit «Portfolio in Systemsicherung» übernimmt die neue Portfolio-Datenbank die Kopie von selbst.
 *
 * Eigene SharedPreferences-Datei [PREFS], nicht in den Sicherungsregeln: Der Hinweis gehört zu diesem Gerät.
 */
object StorageRecoveryNotice {
    const val PREFS = "storage_recovery_notice"
    private const val KEY_DATABASES = "databases"

    /**
     * Zähler der Vormerkungen in diesem Prozess: Die Datenbank öffnet oft erst nach dem ersten Bild — der Hinweis
     * ([take]) wird bei jeder Änderung erneut geprüft, nicht erst beim nächsten Start.
     */
    private val recorded = MutableStateFlow(0)
    val changes: StateFlow<Int> = recorded.asStateFlow()

    /** Betroffene Datenbank vormerken (vor dem Öffnen, darum sofort geschrieben). */
    fun record(context: Context, database: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val databases = prefs.getStringSet(KEY_DATABASES, emptySet()).orEmpty() + database
        prefs.edit(commit = true) { putStringSet(KEY_DATABASES, databases) }
        recorded.update { it + 1 }
    }

    /** Vorgemerkt? Liest und löscht (true = Hinweis einmal zeigen). Nicht auf dem Hauptthread. */
    fun take(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getStringSet(KEY_DATABASES, emptySet()).isNullOrEmpty()) return false
        prefs.edit(commit = true) { remove(KEY_DATABASES) }
        return true
    }
}
