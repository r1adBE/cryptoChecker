package com.cryptochecker.app.data.portfolio

import android.content.Context
import androidx.core.content.edit
import com.cryptochecker.app.data.BackupRestorePolicy

/**
 * Einmaliger Hinweis nach einer Systemsicherung: «X Einträge … waren ungültig» (gespiegelt in
 * `PortfolioRestoreNotice.swift`). [PortfolioBackupMirror.importInto] zählt die ungültigen Einträge,
 * das Portfolio zeigt den Hinweis beim nächsten Öffnen einmal ([take]).
 *
 * Eigene SharedPreferences-Datei [PREFS], bewusst NICHT in backup_rules.xml / data_extraction_rules.xml:
 * der Hinweis gehört zu diesem Gerät.
 */
object PortfolioRestoreNotice {
    const val PREFS = "portfolio_restore_notice"
    private const val KEY_INVALID = "invalid"

    /** Ungültige Einträge vormerken (aufsummiert). */
    fun add(context: Context, invalid: Int) {
        if (invalid <= 0) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // commit: läuft beim Anlegen der Datenbank, der Prozess kann gleich danach enden
        prefs.edit(commit = true) {
            putInt(KEY_INVALID, BackupRestorePolicy.noticeTotal(prefs.getInt(KEY_INVALID, 0), invalid))
        }
    }

    /** Vorgemerkte Anzahl lesen und löschen (0 = nichts zu melden). Nicht auf dem Haupt-Thread. */
    fun take(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val count = prefs.getInt(KEY_INVALID, 0)
        if (count > 0) prefs.edit(commit = true) { remove(KEY_INVALID) }
        return count
    }
}
