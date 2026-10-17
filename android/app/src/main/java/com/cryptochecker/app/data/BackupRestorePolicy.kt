package com.cryptochecker.app.data

/**
 * Regeln beim Wiederherstellen (reines Kotlin, testbar; gespiegelt in `BackupRestorePolicy.swift`).
 *
 * Eine Sicherung ohne Passwort hat keine Echtheitsprüfung: Eine bearbeitete Datei im gleichen Format
 * wird angenommen. Darum schaltet sie die Portfolio-Sperre nie aus, wenn sie gerade an ist.
 */
object BackupRestorePolicy {

    /**
     * Was mit «appLock» aus der Sicherung geschieht: [apply] = neuer Wert (null = unverändert lassen);
     * [keptOn] = die Sicherung wollte die Sperre ausschalten, sie bleibt aber an (Hinweis in der Meldung).
     */
    data class AppLockDecision(val apply: Boolean?, val keptOn: Boolean)

    /**
     * [wanted] = Wert in der Sicherung (null = fehlt), [current] = Sperre jetzt, [encrypted] = Sicherung
     * mit Passwort, [canAuthenticate] = Gerät kann entsperren (sonst sperrte «an» das Portfolio aus).
     */
    fun appLock(wanted: Boolean?, current: Boolean, encrypted: Boolean, canAuthenticate: Boolean): AppLockDecision =
        when (wanted) {
            null -> AppLockDecision(apply = null, keptOn = false)
            true -> AppLockDecision(apply = if (canAuthenticate) true else null, keptOn = false)
            false ->
                if (current && !encrypted) AppLockDecision(apply = null, keptOn = true)
                else AppLockDecision(apply = false, keptOn = false)
        }

    /** Ungültige Einträge aus der Systemsicherung, die noch zu melden sind (aufsummiert, ohne Überlauf). */
    fun noticeTotal(pending: Int, added: Int): Int =
        (pending.coerceAtLeast(0).toLong() + added.coerceAtLeast(0)).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
}
