// Schlüssel-Fälle der Verschlüsselung (Teil von `DatabaseOpenPlanner`): frühere Schlüssel, beiseitegelegte
// Dateien zurückholen, Schlüssel für eine neue Datei, Frist für beiseitegelegte und ersetzte Kopien.
// Erweiterungsfunktionen; Ablauf und Regeln bleiben in der Klasse.
package com.cryptochecker.app.data.local

import com.cryptochecker.app.data.local.DatabaseOpenPlanner.Decision
import com.cryptochecker.app.data.local.DatabaseOpenPlanner.KeyLookup

/** Früherer Schlüssel, der die (aktuelle) Datei öffnet → wieder der gespeicherte; null = keiner. */
fun DatabaseOpenPlanner.previousOpening(database: String): Decision? {
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
fun DatabaseOpenPlanner.restoreFromAside(database: String): Decision? {
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

/**
 * Gespeicherter Schlüssel, sonst ein neuer; wirft, wenn keiner entstehen kann. Nur solange keine verschlüsselte Datei an
 * einem Schlüssel hängt (neue Datei, wiederhergestellte Kopie): ersetzen ist dann gefahrlos.
 */
fun DatabaseOpenPlanner.Keys.loadOrCreate(database: String): ByteArray =
    (load(database) as? KeyLookup.Found)?.passphrase ?: create(database)

/** [loadOrCreate] für eine neue Datei; null = kein Schlüssel möglich. */
fun DatabaseOpenPlanner.usableOrNewKey(database: String): ByteArray? =
    try {
        keys.loadOrCreate(database)
    } catch (e: Exception) {
        log("$database: kein Schlüssel", e)
        null
    }

/**
 * Beiseitegelegte Kopien löschen, die [DatabaseOpenPlanner.asideExpired] freigibt ([AsideRetention]).
 * Nur aus dem Weg «gespeicherter Schlüssel öffnet die aktuelle Datei» und nach [restoreFromAside] — die aktuelle Datei
 * ist nie darunter. Fehler bleiben still (die Kopie bleibt, der nächste Start versucht es erneut).
 */
fun DatabaseOpenPlanner.pruneAside(database: String) {
    val copies = runCatching { files.asideCopies(database) }.getOrDefault(emptyList())
    if (copies.isEmpty()) return
    val expired = runCatching { asideExpired(database, copies) }.getOrDefault(emptyList())
    for (copy in expired.filter { it in copies }) {
        runCatching { files.deleteAside(database, copy) }
            .onSuccess { log("$database: beiseitegelegte Kopie nach Frist gelöscht", null) }
            .onFailure { log("$database: beiseitegelegte Kopie nicht gelöscht", it) }
    }
}

/**
 * Durch eine zurückgeholte Datei ersetzte Dateien löschen, die [DatabaseOpenPlanner.replacedExpired] freigibt (gleiche
 * Frist wie beiseitegelegte, [AsideRetention]): Sie sind eine Kopie der Nutzerdaten mehr, die die App nie mehr öffnet.
 * Wartet danach keine ersetzte Datei mehr, gehen auch die als «ersetzt» behaltenen Schlüssel. Nur aus dem Weg
 * «gespeicherter Schlüssel öffnet die aktuelle Datei»; Fehler bleiben still (der nächste Start versucht es erneut).
 */
fun DatabaseOpenPlanner.pruneReplaced(database: String) {
    val copies = runCatching { files.replacedCopies(database) }.getOrNull() ?: return
    if (copies.isNotEmpty()) {
        val expired = runCatching { replacedExpired(database, copies) }.getOrDefault(emptyList())
        for (copy in expired.filter { it in copies }) {
            runCatching { files.deleteReplaced(database, copy) }
                .onSuccess { log("$database: ersetzte Datei nach Frist gelöscht", null) }
                .onFailure { log("$database: ersetzte Datei nicht gelöscht", it) }
        }
    }
    if (runCatching { files.replacedCopies(database).isEmpty() }.getOrDefault(false)) {
        runCatching { keys.discardReplaced(database) }.onFailure { log("$database: ersetzter Schlüssel nicht gelöscht", it) }
    }
}
