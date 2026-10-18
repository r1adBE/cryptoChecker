package com.cryptochecker.app.data.local

/**
 * Verschlüsselung der Datenbanken (SQLCipher, Build 59): entscheidet vor dem ersten Öffnen einer Datenbankdatei,
 * wie sie geöffnet wird, und überführt eine unverschlüsselte Datei einmalig in eine verschlüsselte. Reine
 * Ablauflogik ohne Android: Dateien und SQL über [Files], Schlüssel über [Keys] (App: `SqlCipherDatabaseFiles`,
 * `DatabaseKeyStore`; Unit-Test: Fälschungen).
 *
 * Regeln — nie Daten verlieren:
 * 1. Die unverschlüsselte Datei bleibt unverändert, bis die verschlüsselte Kopie vollständig geprüft ist
 *    (`user_version`, Zeilen und Prüfsumme je Tabelle, [Fingerprint]) und kein WAL mit Inhalt mehr daneben liegt.
 * 2. Ersetzt wird durch atomares Umbenennen der Kopie auf den Dateinamen: Ein Abbruch davor lässt die alte Datei
 *    stehen, danach ist die neue vollständig. Ob eine Datei verschlüsselt ist, sagt ihr Kopf ([FileKind]) — kein
 *    Merker in den Einstellungen, der nach einer Wiederherstellung (alte, unverschlüsselte Datei) falsch wäre.
 * 3. Jeder Fehler: Kopie löschen, unverschlüsselt öffnen wie bis Build 58, beim nächsten Start erneut versuchen.
 * 4. Ein neuer Schlüssel entsteht nur, solange keine verschlüsselte Datei von ihm abhängt. Ist der Schlüssel einer
 *    verschlüsselten Datei endgültig verloren, wird die Datei beiseitegelegt (nicht gelöscht) und neu begonnen;
 *    die App nennt das, bis der Nutzer es bestätigt ([Decision.Encrypted.setAside]).
 * 5. Eine Datei mit Verschlüsselungspflicht ([encryptionRequired], das Portfolio) entsteht nie unverschlüsselt: Ohne
 *    Schlüssel wird sie gar nicht angelegt ([Decision.Refused]); eine schon unverschlüsselt vorhandene (früherer
 *    Fehlschlag) öffnet weiter wie bisher und wird beim nächsten Start mit Schlüssel überführt ([planOpen]).
 * 6. Frühere Schlüssel bleiben (nummeriert, [Keys.previous]), solange eine beiseitegelegte Datei auf einen warten kann.
 *    Öffnet beim nächsten Start einer davon eine beiseitegelegte Datei («endgültig» war doch nur vorübergehend), kommt sie
 *    zurück; die inzwischen neu begonnene Datei bleibt samt Schlüssel daneben ([Decision.Encrypted.restored]).
 *
 * Die Room-Migrationen laufen erst danach beim Öffnen durch Room — auf der verschlüsselten Datei; der Export
 * übernimmt Schema und `user_version` unverändert.
 */
class DatabaseEncryptionMigration(
    private val files: Files,
    private val keys: Keys,
    private val log: (message: String, error: Throwable?) -> Unit = { _, _ -> },
    /** Darf [database] ohne Schlüssel nie unverschlüsselt angelegt werden (Portfolio)? Siehe [planOpen]. */
    private val encryptionRequired: (database: String) -> Boolean = { false },
) {

    /** Art einer Datenbankdatei nach ihrem Kopf (unverschlüsselt: «SQLite format 3\u0000»). */
    enum class FileKind { ABSENT, PLAINTEXT, ENCRYPTED }

    /** Inhalt einer Tabelle für den Vergleich vor und nach dem Export. */
    data class TableFingerprint(val rows: Long, val checksum: Long)

    /** Inhalt einer Datei: `user_version` und alle Tabellen (inkl. `sqlite_sequence`, ohne übrige `sqlite_*`). */
    data class Fingerprint(val userVersion: Int, val tables: Map<String, TableFingerprint>)

    /** Gespeicherter Schlüssel einer Datenbank. */
    sealed interface KeyLookup {
        /** Schlüssel als SQLCipher-Passphrase ([rawKeyLiteral]). */
        class Found(val passphrase: ByteArray) : KeyLookup

        /** Keiner gespeichert oder endgültig unbrauchbar (Keystore-Schlüssel weg oder ungültig). */
        data object Absent : KeyLookup

        /** Gerade nicht lesbar (vorübergehender Keystore-Fehler): nichts verändern. */
        class Unavailable(val error: Throwable) : KeyLookup
    }

    interface Keys {
        fun load(database: String): KeyLookup

        /**
         * Neuen Schlüssel anlegen und speichern; ein gespeicherter wird nicht überschrieben, sondern als weiterer früherer
         * Schlüssel nummeriert daneben behalten ([previous]). Passphrase wie [KeyLookup.Found].
         */
        fun create(database: String): ByteArray

        /**
         * Von [create] beiseitegelegte frühere Schlüssel löschen. Nur aufgerufen, nachdem sich die verschlüsselte Datei
         * mit dem gespeicherten (neuen) Schlüssel öffnen liess und keine beiseitegelegte Datei mehr auf einen wartet;
         * Fehler bleiben still.
         */
        fun discardPrevious(database: String) {}

        /** Noch lesbare frühere Schlüssel, neuester zuerst ([PreviousKey.id] für [promote]); unlesbare fehlen. */
        fun previous(database: String): List<PreviousKey> = emptyList()

        /**
         * Früheren Schlüssel [id] wieder zum gespeicherten machen; der bisher gespeicherte bleibt als «ersetzt» daneben
         * (er gehört zur beiseitegelegten neuen Datei).
         */
        fun promote(database: String, id: String) {
            throw UnsupportedOperationException("promote")
        }
    }

    /** Früherer Schlüssel ([Keys.previous]): Kennung (Dateiname) und Passphrase wie [KeyLookup.Found]. */
    class PreviousKey(val id: String, val passphrase: ByteArray)

    /** Datei- und SQL-Schicht; `database` ist der Dateiname wie bei Room (z. B. `cryptochecker.db`). */
    interface Files {
        fun kind(database: String): FileKind

        /** Liegt eine (verschlüsselte) Exportkopie neben der Datei — Rest eines abgebrochenen Laufs? */
        fun hasExportCopy(database: String): Boolean

        fun deleteExportCopy(database: String)

        /** WAL der unverschlüsselten Datei vollständig in die Hauptdatei schreiben (`wal_checkpoint(TRUNCATE)`). */
        fun checkpoint(database: String)

        /** Bytes im WAL neben der Datei (0 = keines oder leer). */
        fun walBytes(database: String): Long

        /** `ATTACH … KEY`, `sqlcipher_export`, `user_version` übernehmen, `DETACH` — in die Exportkopie. */
        fun exportEncrypted(database: String, passphrase: ByteArray)

        fun fingerprintOriginal(database: String): Fingerprint

        fun fingerprintExportCopy(database: String, passphrase: ByteArray): Fingerprint

        /** `-wal`, `-shm` und `-journal` der unverschlüsselten Datei löschen. */
        fun deleteSidecars(database: String)

        /** Exportkopie atomar auf den Dateinamen umbenennen (ersetzt die alte Datei). */
        fun replaceWithExportCopy(database: String)

        /** Datei samt `-wal`/`-shm` unter neuem Namen beiseitelegen (nicht löschen). */
        fun setAside(database: String)

        /**
         * Lässt sich die verschlüsselte Datei mit [passphrase] lesen? false nur, wenn SQLite sie damit nicht als
         * Datenbank erkennt (falscher Schlüssel); andere Fehler werfen.
         */
        fun opens(database: String, passphrase: ByteArray): Boolean

        /** Mit [setAside] beiseitegelegte Dateien (Kennungen für [asideOpens]/[restoreAside]), neueste zuerst. */
        fun asideCopies(database: String): List<String> = emptyList()

        /** Lässt sich die beiseitegelegte Datei [copy] mit [passphrase] lesen? Wie [opens]. */
        fun asideOpens(database: String, copy: String, passphrase: ByteArray): Boolean = false

        /**
         * Beiseitegelegte Datei [copy] samt `-wal`/`-shm` wieder auf den Dateinamen; die bisherige Datei bleibt unter
         * neuem Namen erhalten (nie gelöscht). Scheitert das zweite Umbenennen, kommt die bisherige zurück.
         */
        fun restoreAside(database: String, copy: String) {
            throw UnsupportedOperationException("restoreAside")
        }
    }

    sealed interface Decision {
        /**
         * Verschlüsselt öffnen (oder neu anlegen). [migrated]: eben aus der unverschlüsselten Datei überführt;
         * [setAside]: die alte verschlüsselte Datei war ohne Schlüssel unlesbar und liegt jetzt daneben; [restored]: eine
         * früher beiseitegelegte Datei öffnete mit einem früheren Schlüssel und ist wieder die Datenbank.
         */
        class Encrypted(
            val passphrase: ByteArray,
            val migrated: Boolean = false,
            val setAside: Boolean = false,
            val restored: Boolean = false,
        ) : Decision

        /** Unverschlüsselt öffnen wie bis Build 58 (Rückfall; der nächste Start versucht es erneut). */
        data object Plaintext : Decision

        /**
         * Kein Schlüssel und keine Datei, aber Verschlüsselungspflicht: nichts anlegen. Die App hält die Datenbank nur im
         * Arbeitsspeicher (nie unverschlüsselt auf dem Speicher); der nächste Start versucht es erneut.
         */
        data object Refused : Decision

        /** Verschlüsselte Datei, Schlüssel gerade nicht lesbar: nicht öffnen und nichts verändern. */
        class KeyUnavailable(val error: Throwable) : Decision
    }

    /** Vor dem ersten Öffnen von [database]: Datei vorbereiten und sagen, wie sie zu öffnen ist. */
    fun prepare(database: String): Decision =
        when (files.kind(database)) {
            FileKind.ABSENT -> newDatabase(database)
            FileKind.PLAINTEXT -> migrate(database)
            FileKind.ENCRYPTED -> openEncrypted(database)
        }

    /**
     * Noch keine Datei: Room legt sie verschlüsselt an; ohne Schlüssel unverschlüsselt (später überführt) — mit
     * Verschlüsselungspflicht gar nicht ([Decision.Refused]).
     */
    private fun newDatabase(database: String): Decision {
        // Verwaiste Kopie (Quelle fehlt): kann nie die einzige sein — die Quelle wird nur durch sie ersetzt
        if (files.hasExportCopy(database)) runCatching { files.deleteExportCopy(database) }
        val passphrase = usableOrNewKey(database) ?: return withoutKey(FileKind.ABSENT, database)
        return Decision.Encrypted(passphrase)
    }

    private fun migrate(database: String): Decision {
        // Alte unverschlüsselte Datei ohne Schlüssel: weiter öffnen wie bisher (nie Daten verlieren), auch mit Pflicht
        val passphrase = usableOrNewKey(database) ?: return withoutKey(FileKind.PLAINTEXT, database)
        return try {
            // Rest eines abgebrochenen Laufs: unvollständig oder ungeprüft, die Quelle ist noch da
            if (files.hasExportCopy(database)) files.deleteExportCopy(database)
            files.checkpoint(database)
            // Ein WAL mit Inhalt würde beim Löschen der Nebendateien Daten mitnehmen, falls danach das
            // Umbenennen scheitert — dann lieber unverschlüsselt weiter
            val walBytes = files.walBytes(database)
            check(walBytes == 0L) { "WAL nach Checkpoint nicht leer ($walBytes Bytes)" }
            val before = files.fingerprintOriginal(database)
            files.exportEncrypted(database, passphrase)
            val after = files.fingerprintExportCopy(database, passphrase)
            check(before == after) { "Exportkopie weicht ab: ${difference(before, after)}" }
            files.deleteSidecars(database)
            files.replaceWithExportCopy(database)
            log("$database verschlüsselt (${before.tables.size} Tabellen)", null)
            Decision.Encrypted(passphrase, migrated = true)
        } catch (e: Exception) {
            log("$database: Verschlüsselung fehlgeschlagen, unverschlüsselt weiter (nächster Start)", e)
            runCatching { files.deleteExportCopy(database) }
                .onFailure { log("$database: Exportkopie nicht gelöscht", it) }
            Decision.Plaintext
        }
    }

    private fun openEncrypted(database: String): Decision {
        when (val lookup = keys.load(database)) {
            is KeyLookup.Unavailable -> return Decision.KeyUnavailable(lookup.error)
            is KeyLookup.Found -> {
                // Vor Room prüfen: Room löscht eine Datei, die SQLite nicht als Datenbank erkennt (onCorruption) —
                // mit falschem Schlüssel wäre das die ganze verschlüsselte Datenbank
                val opens = try {
                    files.opens(database, lookup.passphrase)
                } catch (e: Exception) {
                    return Decision.KeyUnavailable(e)
                }
                if (opens) {
                    // Wartet eine beiseitegelegte Datei auf einen früheren Schlüssel, der sie doch öffnet: zurückholen
                    restoreFromAside(database)?.let { return it }
                    // Gespeicherter Schlüssel öffnet die Datei: frühere nur löschen, wenn keine beiseitegelegte Datei mehr
                    // auf einen warten kann
                    if (runCatching { files.asideCopies(database).isEmpty() }.getOrDefault(false)) {
                        runCatching { keys.discardPrevious(database) }
                            .onFailure { log("$database: früherer Schlüssel nicht gelöscht", it) }
                    }
                    return Decision.Encrypted(lookup.passphrase)
                }
                log("$database: gespeicherter Schlüssel passt nicht", null)
            }
            KeyLookup.Absent -> log("$database: Schlüssel verloren", null)
        }
        // Vor dem Beiseitelegen: öffnet ein früherer Schlüssel die Datei, gilt wieder er (nichts beiseitelegen)
        previousOpening(database)?.let { return it }
        return try {
            // Ohne passenden Schlüssel ist die Datei endgültig unlesbar: beiseitelegen statt löschen, dann neu
            // beginnen. Erst danach ein neuer Schlüssel — vorher hinge die alte Datei an einem fremden Schlüssel.
            files.setAside(database)
            Decision.Encrypted(keys.create(database), setAside = true)
        } catch (e: Exception) {
            Decision.KeyUnavailable(e)
        }
    }

    /** Früherer Schlüssel, der die (aktuelle) Datei öffnet → wieder der gespeicherte; null = keiner. */
    private fun previousOpening(database: String): Decision? {
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
    private fun restoreFromAside(database: String): Decision? {
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
    private fun withoutKey(kind: FileKind, database: String): Decision =
        when (planOpen(kind, keyUsable = false, encryptionRequired = encryptionRequired(database))) {
            OpenPlan.REFUSE -> {
                log("$database: kein Schlüssel, nicht unverschlüsselt angelegt (nur im Arbeitsspeicher)", null)
                Decision.Refused
            }
            OpenPlan.ENCRYPTED, OpenPlan.PLAINTEXT -> Decision.Plaintext
        }

    /** Wie eine Datei zu öffnen ist ([planOpen]). */
    enum class OpenPlan {
        /** Verschlüsselt öffnen, anlegen oder überführen (eigene Regeln für eine schon verschlüsselte Datei). */
        ENCRYPTED,

        /** Unverschlüsselt öffnen: eine schon unverschlüsselte Datei, oder ohne Pflicht neu unverschlüsselt anlegen. */
        PLAINTEXT,

        /** Nicht auf dem Speicher anlegen (nur im Arbeitsspeicher halten), der nächste Start versucht es erneut. */
        REFUSE,
    }

    /**
     * Gespeicherter Schlüssel, sonst ein neuer. Nur für Dateien, die (noch) nicht verschlüsselt sind: Kein
     * verschlüsselter Inhalt hängt am alten Schlüssel, ersetzen ist darum gefahrlos. null = kein Schlüssel möglich.
     */
    private fun usableOrNewKey(database: String): ByteArray? {
        val lookup = keys.load(database)
        if (lookup is KeyLookup.Found) return lookup.passphrase
        return try {
            keys.create(database)
        } catch (e: Exception) {
            log("$database: kein Schlüssel, unverschlüsselt weiter", e)
            null
        }
    }

    companion object {
        /**
         * Regel ohne Android (Unit-Test): Art der vorhandenen Datei × brauchbarer Schlüssel (gespeichert oder neu angelegt)
         * × Verschlüsselungspflicht → wie öffnen. Mit Schlüssel immer verschlüsselt. Ohne Schlüssel: eine schon
         * unverschlüsselte Datei weiter unverschlüsselt (sie liegt schon so da; Überführung beim nächsten Start), eine
         * fehlende nur ohne Pflicht neu unverschlüsselt — mit Pflicht (Portfolio: Beträge, Adressen, xpub) gar nicht, wie
         * iOS `ProtectedFile.write(requireSealed:)`. Eine verschlüsselte Datei folgt eigenen Regeln (Schlüssel gerade nicht
         * lesbar → nichts tun; verloren → beiseitelegen), nie unverschlüsselt.
         */
        fun planOpen(kind: FileKind, keyUsable: Boolean, encryptionRequired: Boolean): OpenPlan = when {
            keyUsable || kind == FileKind.ENCRYPTED -> OpenPlan.ENCRYPTED
            kind == FileKind.PLAINTEXT -> OpenPlan.PLAINTEXT
            encryptionRequired -> OpenPlan.REFUSE
            else -> OpenPlan.PLAINTEXT
        }

        /** Länge des zufälligen Datenbankschlüssels (AES-256). */
        const val KEY_BYTES = 32

        /**
         * SQLCipher-Passphrase für einen rohen Schlüssel: `x'<64 Hex-Zeichen>'`. So nutzt SQLCipher die Bytes direkt
         * als Schlüssel — ohne PBKDF2 bei jedem Öffnen einer Verbindung (der Schlüssel ist schon zufällig).
         */
        fun rawKeyLiteral(key: ByteArray): ByteArray {
            require(key.size == KEY_BYTES) { "Schlüssel muss $KEY_BYTES Bytes haben" }
            val hex = key.joinToString("") { "%02x".format(it.toInt() and 0xff) }
            return "x'$hex'".toByteArray(Charsets.US_ASCII)
        }

        /**
         * Prüfsumme einer Zeile (FNV-1a, 64 Bit) über Typ und Wert jeder Spalte: `1`, `1.0` und `"1"` unterscheiden
         * sich. Je Tabelle werden die Zeilen-Prüfsummen addiert ([addRow]) — unabhängig von der Reihenfolge, in der
         * der Export die Zeilen schreibt.
         */
        fun rowChecksum(values: List<Any?>): Long {
            var hash = FNV_OFFSET
            fun mix(byte: Int) {
                hash = (hash xor (byte.toLong() and 0xff)) * FNV_PRIME
            }
            fun mixLong(v: Long) {
                for (shift in 0 until 64 step 8) mix((v ushr shift).toInt())
            }
            fun mixBytes(bytes: ByteArray) {
                mixLong(bytes.size.toLong())
                bytes.forEach { mix(it.toInt()) }
            }
            for (value in values) {
                when (value) {
                    null -> mix(0)
                    is Long -> { mix(1); mixLong(value) }
                    is Int -> { mix(1); mixLong(value.toLong()) }
                    is Double -> { mix(2); mixLong(value.toRawBits()) }
                    is String -> { mix(3); mixBytes(value.toByteArray(Charsets.UTF_8)) }
                    is ByteArray -> { mix(4); mixBytes(value) }
                    else -> throw IllegalArgumentException("Unbekannter Spaltentyp ${value::class.simpleName}")
                }
            }
            return hash
        }

        /** Zeile zur Tabellen-Prüfsumme hinzufügen (Addition mit Überlauf, reihenfolgeunabhängig). */
        fun addRow(table: TableFingerprint, values: List<Any?>): TableFingerprint =
            TableFingerprint(table.rows + 1, table.checksum + rowChecksum(values))

        /** Kurzbeschreibung der Abweichung fürs Protokoll (Tabellennamen, keine Inhalte). */
        fun difference(before: Fingerprint, after: Fingerprint): String = buildList {
            if (before.userVersion != after.userVersion) add("user_version ${before.userVersion} → ${after.userVersion}")
            (before.tables.keys + after.tables.keys).sorted().forEach { table ->
                val a = before.tables[table]
                val b = after.tables[table]
                when {
                    a == null -> add("$table nur in der Kopie")
                    b == null -> add("$table fehlt in der Kopie")
                    a.rows != b.rows -> add("$table: ${a.rows} → ${b.rows} Zeilen")
                    a.checksum != b.checksum -> add("$table: Prüfsumme")
                }
            }
        }.joinToString()

        private const val FNV_OFFSET = -0x340d631b7bdddcdbL // 0xcbf29ce484222325
        private const val FNV_PRIME = 0x100000001b3L
    }
}
