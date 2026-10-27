// Schlüssel-Fälle der Verschlüsselung (Teil von `DatabaseEncryptionMigration`): frühere Schlüssel, beiseitegelegte
// Dateien zurückholen, Entscheidung ohne Schlüssel, Schlüssel für noch unverschlüsselte Dateien.
// Erweiterungsfunktionen; Ablauf und Regeln bleiben in der Klasse.
package com.cryptochecker.app.data.local

import com.cryptochecker.app.data.local.DatabaseEncryptionMigration.Decision
import com.cryptochecker.app.data.local.DatabaseEncryptionMigration.FileKind
import com.cryptochecker.app.data.local.DatabaseEncryptionMigration.KeyLookup
import com.cryptochecker.app.data.local.DatabaseEncryptionMigration.OpenPlan
import com.cryptochecker.app.data.local.DatabaseEncryptionMigration.Companion.planOpen

/** Früherer Schlüssel, der die (aktuelle) Datei öffnet → wieder der gespeicherte; null = keiner. */
fun DatabaseEncryptionMigration.previousOpening(database: String): Decision? {
    val previous = runCatching { keys.previous(database) }.getOrDefault(emptyList())
    for (key in previous) {
        val opens = runCatching { files.opens(database, key.passphrase) }.getOrDefault(false)
        if (!opens) continue
        // Mit diesem Schlüssel öffnen, auch wenn das Speichern als «gespeichert» scheitert (nächster Start erneut)
        runCatching { keys.promote(database, key.id) }
            .onSuccess { log("$database: früherer Schlüssel passt wieder", null) }
            .onFailure { log("$database: früherer Schlüssel nicht übernommen", it) }
        return Decision.Encrypted(key.passphrase)
    }
    return null
}

/**
 * Beiseitegelegte Datei, die ein früherer Schlüssel öffnet → wieder die Datenbank (die inzwischen neu begonnene
 * Datei bleibt unter neuem Namen, ihr Schlüssel als «ersetzt»). Erst die Dateien, dann der Schlüssel: bricht es
 * dazwischen ab, findet der nächste Start den früheren Schlüssel über [previousOpening]. null = nichts zurückgeholt.
 */
fun DatabaseEncryptionMigration.restoreFromAside(database: String): Decision? {
    val copies = runCatching { files.asideCopies(database) }.getOrDefault(emptyList())
    if (copies.isEmpty()) return null
    val previous = runCatching { keys.previous(database) }.getOrDefault(emptyList())
    if (previous.isEmpty()) return null
    for (copy in copies) {
        for (key in previous) {
            val opens = runCatching { files.asideOpens(database, copy, key.passphrase) }.getOrDefault(false)
            if (!opens) continue
            try {
                files.restoreAside(database, copy)
            } catch (e: Exception) {
                log("$database: beiseitegelegte Datei nicht zurückgeholt", e)
                return null
            }
            // Die Datei ist jetzt die zurückgeholte: nur noch mit diesem Schlüssel öffnen — nie mit dem gespeicherten,
            // sonst hielte Room sie für beschädigt. Scheitert das Speichern des Schlüssels, holt ihn der nächste Start
            runCatching { keys.promote(database, key.id) }
                .onFailure { log("$database: früherer Schlüssel nicht übernommen", it) }
            log("$database: beiseitegelegte Datei mit früherem Schlüssel zurückgeholt", null)
            return Decision.Encrypted(key.passphrase, restored = true)
        }
    }
    return null
}

/** Entscheidung ohne brauchbaren Schlüssel nach [planOpen]. */
fun DatabaseEncryptionMigration.withoutKey(kind: FileKind, database: String): Decision =
    when (planOpen(kind, keyUsable = false, encryptionRequired = encryptionRequired(database))) {
        OpenPlan.REFUSE -> {
            log("$database: kein Schlüssel, nicht unverschlüsselt angelegt (nur im Arbeitsspeicher)", null)
            Decision.Refused
        }
        OpenPlan.COPY_TO_MEMORY -> {
            log("$database: kein Schlüssel, unverschlüsselte Datei bleibt unberührt (Inhalt im Arbeitsspeicher)", null)
            Decision.PlaintextKept
        }
        OpenPlan.ENCRYPTED, OpenPlan.PLAINTEXT, OpenPlan.KEEP_FILE_IN_MEMORY, OpenPlan.WAIT -> Decision.Plaintext
    }

/**
 * Gespeicherter Schlüssel, sonst ein neuer. Nur für Dateien, die (noch) nicht verschlüsselt sind: Kein
 * verschlüsselter Inhalt hängt am alten Schlüssel, ersetzen ist darum gefahrlos. null = kein Schlüssel möglich.
 */
fun DatabaseEncryptionMigration.usableOrNewKey(database: String): ByteArray? {
    val lookup = keys.load(database)
    if (lookup is KeyLookup.Found) return lookup.passphrase
    return try {
        keys.create(database)
    } catch (e: Exception) {
        log("$database: kein Schlüssel, unverschlüsselt weiter", e)
        null
    }
}

/**
 * Beiseitegelegte Kopien löschen, die [DatabaseEncryptionMigration.asideExpired] freigibt ([AsideRetention]).
 * Nur aus dem Weg «gespeicherter Schlüssel öffnet die aktuelle Datei» und nach [restoreFromAside] — die aktuelle Datei
 * ist nie darunter. Fehler bleiben still (die Kopie bleibt, der nächste Start versucht es erneut).
 */
fun DatabaseEncryptionMigration.pruneAside(database: String) {
    val copies = runCatching { files.asideCopies(database) }.getOrDefault(emptyList())
    if (copies.isEmpty()) return
    val expired = runCatching { asideExpired(database, copies) }.getOrDefault(emptyList())
    for (copy in expired.filter { it in copies }) {
        runCatching { files.deleteAside(database, copy) }
            .onSuccess { log("$database: beiseitegelegte Kopie nach Frist gelöscht", null) }
            .onFailure { log("$database: beiseitegelegte Kopie nicht gelöscht", it) }
    }
}
