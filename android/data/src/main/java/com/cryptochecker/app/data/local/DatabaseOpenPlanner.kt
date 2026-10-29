package com.cryptochecker.app.data.local

/**
 * Verschlüsselung der Datenbanken (SQLCipher): entscheidet vor dem ersten Öffnen einer Datenbankdatei, wie sie geöffnet
 * wird. Reine Ablauflogik ohne Android: Dateien und SQL über [Files], Schlüssel über [Keys] (App: `SqlCipherDatabaseFiles`,
 * `DatabaseKeyStore`; Unit-Test: Fälschungen).
 *
 * Regeln — nie Daten verlieren:
 * 1. Jede Datenbankdatei ist verschlüsselt. Ohne Schlüssel entsteht keine Datei: Die App hält die Datenbank dann nur im
 *    Arbeitsspeicher ([Decision.Refused]); der nächste Start versucht es erneut.
 * 2. Liegt statt der Datei eine wiederhergestellte, unverschlüsselte Kopie der Systemsicherung bereit
 *    ([Files.hasRestoredCopy]), wird sie verschlüsselt und geprüft zur Datei ([Files.encryptRestoredCopy]); erst danach
 *    verschwindet die Kopie. Ohne Schlüssel oder bei einem Fehler bleibt die Kopie für den nächsten Start, und es entsteht
 *    keine (leere) Datei, die sie verdrängen würde ([Decision.Refused]).
 * 3. Ein neuer Schlüssel entsteht nur, solange keine verschlüsselte Datei von ihm abhängt. Ist der Schlüssel einer
 *    verschlüsselten Datei endgültig verloren, wird die Datei beiseitegelegt (nicht gelöscht) und neu begonnen;
 *    die App nennt das, bis der Nutzer es bestätigt ([Decision.Encrypted.setAside]).
 * 4. Gibt der Keystore den Schlüssel einer verschlüsselten Datei gerade nicht heraus ([Decision.KeyUnavailable]),
 *    bleibt die Datei unberührt; die App hält die Datenbank dann für diesen Lauf im Arbeitsspeicher oder wartet
 *    ([OpenPlan.KEEP_FILE_IN_MEMORY], [OpenPlan.WAIT]).
 * 5. Frühere Schlüssel bleiben (nummeriert, [Keys.previous]), solange eine beiseitegelegte Datei auf einen warten kann.
 *    Öffnet beim nächsten Start einer davon eine beiseitegelegte Datei («endgültig» war doch nur vorübergehend), kommt sie
 *    zurück; die inzwischen neu begonnene Datei bleibt samt Schlüssel daneben ([Decision.Encrypted.restored]).
 *    Beiseitegelegte und ersetzte Dateien bleiben nicht für immer: gleiche Frist ([AsideRetention], [pruneAside],
 *    [pruneReplaced]).
 * 6. Nur lesen ([readOnly], Systemsicherung): nichts beiseitelegen, zurückholen, löschen oder anlegen. Öffnet der
 *    gespeicherte Schlüssel die Datei nicht, gilt der Schlüssel als gerade nicht lesbar ([Decision.KeyUnavailable]) — die
 *    Sicherung bricht ab, statt ein leeres Portfolio zu sichern; die App entscheidet beim nächsten eigenen Öffnen.
 */
class DatabaseOpenPlanner(
    val files: Files,
    val keys: Keys,
    val log: (message: String, error: Throwable?) -> Unit = { _, _ -> },
    /** Welche beiseitegelegten Kopien weg dürfen ([pruneAside], [AsideRetention]); Standard keine. */
    val asideExpired: (database: String, copies: List<String>) -> List<String> = { _, _ -> emptyList() },
    /**
     * Welche durch eine zurückgeholte Datei ersetzten Dateien weg dürfen ([pruneReplaced], gleiche Frist wie
     * [asideExpired]); Standard keine.
     */
    val replacedExpired: (database: String, copies: List<String>) -> List<String> = { _, _ -> emptyList() },
    /** Nur lesen (Regel 6): Datei und Schlüssel bleiben unverändert. */
    val readOnly: Boolean = false,
) {

    /** Zustand einer Datenbankdatei auf dem Speicher. */
    enum class FileKind { ABSENT, ENCRYPTED }

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

        /** Als «ersetzt» behaltene Schlüssel löschen — nur, wenn keine ersetzte Datei mehr auf einen wartet; Fehler werfen. */
        fun discardReplaced(database: String) {}
    }

    /** Früherer Schlüssel ([Keys.previous]): Kennung (Dateiname) und Passphrase wie [KeyLookup.Found]. */
    class PreviousKey(val id: String, val passphrase: ByteArray)

    /** Datei- und SQL-Schicht; `database` ist der Dateiname wie bei Room (z. B. `cryptochecker.db`). */
    interface Files {
        fun kind(database: String): FileKind

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
         * neuem Namen erhalten (gelöscht erst nach Frist, [pruneReplaced]). Scheitert das zweite Umbenennen, kommt die
         * bisherige zurück.
         */
        fun restoreAside(database: String, copy: String) {
            throw UnsupportedOperationException("restoreAside")
        }

        /** Beiseitegelegte Kopie [copy] samt Nebendateien löschen (nur über [pruneAside], nie die aktuelle Datei). */
        fun deleteAside(database: String, copy: String) {
            throw UnsupportedOperationException("deleteAside")
        }

        /** Von [restoreAside] ersetzte, inzwischen neu begonnene Dateien (`<db>.replaced…`), neueste zuerst. */
        fun replacedCopies(database: String): List<String> = emptyList()

        /** Ersetzte Datei [copy] samt Nebendateien löschen (nur über [pruneReplaced]). */
        fun deleteReplaced(database: String, copy: String) {
            throw UnsupportedOperationException("deleteReplaced")
        }

        /** Liegt für [database] eine wiederhergestellte, unverschlüsselte Kopie der Systemsicherung bereit? */
        fun hasRestoredCopy(database: String): Boolean = false

        /**
         * Wiederhergestellte Kopie mit [passphrase] verschlüsselt zur Datei [database] machen, geprüft (`user_version`,
         * Zeilen und Prüfsumme je Tabelle), dann die Kopie löschen. Wirft bei jedem Fehler; die Kopie bleibt dann, eine
         * Datei entsteht nicht.
         */
        fun encryptRestoredCopy(database: String, passphrase: ByteArray) {
            throw UnsupportedOperationException("encryptRestoredCopy")
        }
    }

    sealed interface Decision {
        /**
         * Verschlüsselt öffnen (oder neu anlegen). [setAside]: die alte verschlüsselte Datei war ohne Schlüssel unlesbar
         * und liegt jetzt daneben; [restored]: eine früher beiseitegelegte Datei öffnete mit einem früheren Schlüssel und
         * ist wieder die Datenbank.
         */
        class Encrypted(
            val passphrase: ByteArray,
            val setAside: Boolean = false,
            val restored: Boolean = false,
        ) : Decision

        /**
         * Keine Datei und kein Schlüssel (oder die wiederhergestellte Kopie liess sich nicht verschlüsseln): nichts
         * anlegen. Die App hält die Datenbank nur im Arbeitsspeicher (nie unverschlüsselt auf dem Speicher); der nächste
         * Start versucht es erneut.
         */
        data object Refused : Decision

        /** Verschlüsselte Datei, Schlüssel gerade nicht lesbar: nicht öffnen und nichts verändern. */
        class KeyUnavailable(val error: Throwable) : Decision
    }

    /** Vor dem ersten Öffnen von [database]: Datei vorbereiten und sagen, wie sie zu öffnen ist. */
    fun prepare(database: String): Decision =
        when (files.kind(database)) {
            FileKind.ABSENT -> if (readOnly) readOnlyRefusal(database, "keine Datei") else newDatabase(database)
            FileKind.ENCRYPTED -> openEncrypted(database)
        }

    /** Nur lesen, aber die Datei liesse sich nicht unverändert öffnen: wie ein gerade nicht lesbarer Schlüssel. */
    private fun readOnlyRefusal(database: String, reason: String): Decision {
        log("$database: $reason, nur lesen — nichts verändert", null)
        return Decision.KeyUnavailable(IllegalStateException("$database: $reason (nur lesen)"))
    }

    /**
     * Noch keine Datei: Room legt sie verschlüsselt an — aus einer wiederhergestellten Kopie, falls eine bereitliegt.
     * Ohne Schlüssel gar nicht ([Decision.Refused]).
     */
    private fun newDatabase(database: String): Decision {
        val passphrase = usableOrNewKey(database) ?: run {
            log("$database: kein Schlüssel, nicht angelegt (nur im Arbeitsspeicher)", null)
            return Decision.Refused
        }
        if (runCatching { files.hasRestoredCopy(database) }.getOrDefault(false)) {
            try {
                files.encryptRestoredCopy(database, passphrase)
                log("$database: wiederhergestellte Kopie verschlüsselt übernommen", null)
            } catch (e: Exception) {
                // Keine leere Datei anlegen: Sie verdrängte die Kopie (beim Start gilt eine Kopie neben einer Datei als Rest)
                log("$database: wiederhergestellte Kopie nicht übernommen (nur im Arbeitsspeicher, nächster Start)", e)
                return Decision.Refused
            }
        }
        return Decision.Encrypted(passphrase)
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
                if (opens && readOnly) return Decision.Encrypted(lookup.passphrase)
                if (opens) {
                    // Wartet eine beiseitegelegte Datei auf einen früheren Schlüssel, der sie doch öffnet: zurückholen
                    restoreFromAside(database)?.let { return it }
                    pruneAside(database)
                    pruneReplaced(database)
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
        if (readOnly) return readOnlyRefusal(database, "kein passender Schlüssel")
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

    /** Wie eine Datei zu öffnen ist ([planOpen]). */
    enum class OpenPlan {
        /** Verschlüsselt öffnen oder anlegen (eigene Regeln für eine schon verschlüsselte Datei). */
        ENCRYPTED,

        /** Nicht auf dem Speicher anlegen (nur im Arbeitsspeicher halten), der nächste Start versucht es erneut. */
        REFUSE,

        /**
         * Verschlüsselte Datei, Schlüssel gerade nicht lesbar: für diesen Lauf eine leere Datenbank im Arbeitsspeicher;
         * die Datei bleibt unberührt (nie überschrieben, gelöscht oder beiseitegelegt), der nächste Start versucht es erneut.
         */
        KEEP_FILE_IN_MEMORY,

        /** Verschlüsselte Datei, Schlüssel gerade nicht lesbar: nicht öffnen (Ausnahme), der nächste Zugriff versucht es erneut. */
        WAIT,
    }

    companion object {
        /**
         * Regel ohne Android (Unit-Test): Zustand der Datei × brauchbarer Schlüssel (gespeichert oder neu angelegt) → wie
         * öffnen. Mit Schlüssel immer verschlüsselt; ohne Schlüssel und ohne Datei gar nicht (wie iOS
         * `ProtectedFile.write(requireSealed:)`). Eine verschlüsselte Datei folgt eigenen Regeln (Schlüssel gerade nicht
         * lesbar → nichts tun; verloren → beiseitelegen).
         *
         * Schlüssel einer verschlüsselten Datei gerade nicht lesbar ([keyUnavailable], [Decision.KeyUnavailable]): Nur wenn
         * die Datenbank das erlaubt ([inMemoryAllowed]: Portfolio, Merkliste) und die App gerade sichtbar ist ([foreground]),
         * für diesen Lauf im Arbeitsspeicher — sonst stürzte die Oberfläche beim ersten Zugriff ab. Im Hintergrund (Worker,
         * Widget, Systemsicherung) lieber warten: Dort überspringen die Einstiege den Lauf, und ein späterer Zugriff mit
         * lesbarem Schlüssel sieht die echte Datei statt einer leeren für den ganzen Prozess.
         */
        fun planOpen(
            kind: FileKind,
            keyUsable: Boolean,
            keyUnavailable: Boolean = false,
            inMemoryAllowed: Boolean = false,
            foreground: Boolean = false,
        ): OpenPlan = when {
            kind == FileKind.ENCRYPTED && keyUnavailable ->
                if (inMemoryAllowed && foreground) OpenPlan.KEEP_FILE_IN_MEMORY else OpenPlan.WAIT
            keyUsable || kind == FileKind.ENCRYPTED -> OpenPlan.ENCRYPTED
            else -> OpenPlan.REFUSE
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
    }
}
