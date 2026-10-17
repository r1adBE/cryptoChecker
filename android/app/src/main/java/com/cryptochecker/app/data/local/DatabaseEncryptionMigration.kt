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
 *    die App nennt das einmal ([Decision.Encrypted.setAside]).
 *
 * Die Room-Migrationen laufen erst danach beim Öffnen durch Room — auf der verschlüsselten Datei; der Export
 * übernimmt Schema und `user_version` unverändert.
 */
class DatabaseEncryptionMigration(
    private val files: Files,
    private val keys: Keys,
    private val log: (message: String, error: Throwable?) -> Unit = { _, _ -> },
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

        /** Neuen Schlüssel anlegen und speichern (ersetzt einen gespeicherten); Passphrase wie [KeyLookup.Found]. */
        fun create(database: String): ByteArray

        /**
         * Von [create] beiseitegelegten früheren Schlüssel löschen. Nur aufgerufen, nachdem sich die verschlüsselte
         * Datei mit dem gespeicherten (neuen) Schlüssel öffnen liess; Fehler bleiben still.
         */
        fun discardPrevious(database: String) {}
    }

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
    }

    sealed interface Decision {
        /**
         * Verschlüsselt öffnen (oder neu anlegen). [migrated]: eben aus der unverschlüsselten Datei überführt;
         * [setAside]: die alte verschlüsselte Datei war ohne Schlüssel unlesbar und liegt jetzt daneben.
         */
        class Encrypted(val passphrase: ByteArray, val migrated: Boolean = false, val setAside: Boolean = false) : Decision

        /** Unverschlüsselt öffnen wie bis Build 58 (Rückfall; der nächste Start versucht es erneut). */
        data object Plaintext : Decision

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

    /** Noch keine Datei: Room legt sie verschlüsselt an; ohne Schlüssel unverschlüsselt (später überführt). */
    private fun newDatabase(database: String): Decision {
        // Verwaiste Kopie (Quelle fehlt): kann nie die einzige sein — die Quelle wird nur durch sie ersetzt
        if (files.hasExportCopy(database)) runCatching { files.deleteExportCopy(database) }
        val passphrase = usableOrNewKey(database) ?: return Decision.Plaintext
        return Decision.Encrypted(passphrase)
    }

    private fun migrate(database: String): Decision {
        val passphrase = usableOrNewKey(database) ?: return Decision.Plaintext
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
                    // Gespeicherter Schlüssel öffnet die Datei: ein früher beiseitegelegter wird nicht mehr gebraucht
                    runCatching { keys.discardPrevious(database) }
                        .onFailure { log("$database: früherer Schlüssel nicht gelöscht", it) }
                    return Decision.Encrypted(lookup.passphrase)
                }
                log("$database: gespeicherter Schlüssel passt nicht", null)
            }
            KeyLookup.Absent -> log("$database: Schlüssel verloren", null)
        }
        return try {
            // Ohne passenden Schlüssel ist die Datei endgültig unlesbar: beiseitelegen statt löschen, dann neu
            // beginnen. Erst danach ein neuer Schlüssel — vorher hinge die alte Datei an einem fremden Schlüssel.
            files.setAside(database)
            Decision.Encrypted(keys.create(database), setAside = true)
        } catch (e: Exception) {
            Decision.KeyUnavailable(e)
        }
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
