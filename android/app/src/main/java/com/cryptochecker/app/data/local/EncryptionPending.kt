package com.cryptochecker.app.data.local

import com.cryptochecker.app.data.local.DatabaseEncryptionMigration.Decision

/**
 * Hinweis «Verschlüsselung noch nicht aktiv» unter Einstellungen › Sicherung: Welche Datenbanken liegen (noch)
 * unverschlüsselt, weil die Überführung scheiterte oder kein Schlüssel entstehen konnte ([Decision.Plaintext])?
 * Der Rückfall ist gewollt (nie Daten verlieren, nächster Start versucht es erneut) — er soll aber nicht still
 * bleiben. Reine Logik ohne Android ([EncryptionPendingTest]); gespeichert von [DatabaseEncryptionNotice].
 * Gegenstück auf iOS: `ProtectedStorage.LaunchOutcome.encryptionPending`.
 */
object EncryptionPending {

    /**
     * Vorgemerkte Datenbanken nach der Entscheidung für [database]: unverschlüsselt geöffnet → dazu, verschlüsselt →
     * weg. [Decision.KeyUnavailable] öffnet nichts und ändert nichts an der Datei — der Stand bleibt. [Decision.Refused]
     * (Portfolio ohne Schlüssel nur im Arbeitsspeicher) zählt wie unverschlüsselt: Verschlüsselung noch nicht aktiv.
     */
    fun updated(pending: Set<String>, database: String, decision: Decision): Set<String> = when (decision) {
        Decision.Plaintext, Decision.Refused, Decision.PlaintextKept -> pending + database
        is Decision.Encrypted -> pending - database
        is Decision.KeyUnavailable -> pending
    }

    /** Hinweis zeigen, solange mindestens eine Datenbank unverschlüsselt ist. */
    fun showNotice(pending: Set<String>): Boolean = pending.isNotEmpty()
}
