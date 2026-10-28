package com.cryptochecker.app.data.local

import com.cryptochecker.app.data.local.DatabaseOpenPlanner.Decision

/**
 * Hinweis «Verschlüsselung noch nicht aktiv» unter Einstellungen › Sicherung: Welche Datenbanken liegen nur im
 * Arbeitsspeicher, weil kein Schlüssel entstehen konnte ([Decision.Refused])? Gewollt (nie unverschlüsselt auf dem
 * Speicher, nächster Start versucht es erneut) — es soll aber nicht still bleiben. Reine Logik ohne Android
 * ([EncryptionPendingTest]); gespeichert von [DatabaseEncryptionNotice].
 * Gegenstück auf iOS: `ProtectedStorage.LaunchOutcome.encryptionPending`.
 */
object EncryptionPending {

    /**
     * Vorgemerkte Datenbanken nach der Entscheidung für [database]: [Decision.Refused] → dazu, verschlüsselt → weg.
     * [Decision.KeyUnavailable] öffnet nichts und ändert nichts an der Datei — der Stand bleibt.
     */
    fun updated(pending: Set<String>, database: String, decision: Decision): Set<String> = when (decision) {
        Decision.Refused -> pending + database
        is Decision.Encrypted -> pending - database
        is Decision.KeyUnavailable -> pending
    }

    /** Hinweis zeigen, solange mindestens eine Datenbank nicht verschlüsselt angelegt ist. */
    fun showNotice(pending: Set<String>): Boolean = pending.isNotEmpty()
}
