package com.cryptochecker.app.data

import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.text.Normalizer
import java.util.Base64
import java.util.Locale
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Passwortschutz für die Sicherungsdatei (reines Kotlin, testbar; gespiegelt in
 * `App/Services/BackupCrypto.swift`). Ein Format für Android und iOS. Geschrieben wird immer v3:
 *
 * ```
 * {"format":"cryptochecker-backup-enc","v":3,"enc":"AES-256-GCM","kdf":"Argon2id",
 *  "m":65536,"t":3,"p":1,"salt":"<Base64, 16 Bytes>","iv":"<Base64, 12 Bytes>",
 *  "data":"<Base64: Chiffrat || 16-Byte-Tag>"}
 * ```
 * Klartext = die bisherige Sicherung (JSON, UTF-8). Schlüssel = Argon2id (RFC 9106, Version 0x13, [Argon2id]) aus dem
 * Passwort (Unicode NFC, UTF-8) und dem Salt, 32 Bytes; m = Speicher in KiB (64 MiB), t = Durchgänge, p = Bahnen.
 * AES-256-GCM mit dem kanonischen Kopf als AAD ([header]): UTF-8 dieses kompakten JSON, Felder in fester Reihenfolge,
 * ohne Leerzeichen, Zahlen dezimal, salt/iv als Base64 mit Auffüllung (aus den dekodierten Bytes neu kodiert):
 * ```
 * {"format":"cryptochecker-backup-enc","v":3,"enc":"AES-256-GCM","kdf":"Argon2id","m":65536,"t":3,"p":1,"salt":"…","iv":"…"}
 * ```
 * Ein veränderter Kopf scheitert damit am GCM-Tag — gemeldet wie ein falsches Passwort ([WrongPasswordException]),
 * denn beides ist nicht zu unterscheiden.
 *
 * v2 (bis Build 76, weiterhin lesbar, unverändert): «kdf» PBKDF2-HMAC-SHA256 mit «iter» (600 000, ältere Dateien
 * 210 000), ohne AAD.
 *
 * Gemeinsame Prüfwerte (beide Seiten, mit Python `cryptography` nachgerechnet): `testdata/parity/backup_crypto.json`.
 * Argon2id mit 64 MiB dauert einige 100 ms bis wenige Sekunden — nur im Hintergrund aufrufen (`BackupManager` läuft
 * auf `Dispatchers.IO`).
 */
object BackupCrypto {
    const val FORMAT = "cryptochecker-backup-enc"
    /** Version neuer Sicherungen (Argon2id + AAD). */
    const val VERSION = 3
    /** Ältere Hülle mit PBKDF2 (weiterhin lesbar). */
    const val VERSION_PBKDF2 = 2
    const val ENC = "AES-256-GCM"
    const val KDF = "Argon2id"
    const val KDF_PBKDF2 = "PBKDF2-HMAC-SHA256"

    /** Argon2id neuer Sicherungen: 64 MiB, 3 Durchgänge, 1 Bahn (RFC 9106, zweite empfohlene Wahl). */
    const val ARGON2_MEMORY_KIB = 65_536
    const val ARGON2_TIME = 3
    const val ARGON2_PARALLELISM = 1

    /** «iter» der v2-Sicherungen mit PBKDF2 (bis Build 76). */
    const val ITERATIONS = 600_000

    /** «iter» noch älterer v2-Sicherungen (weiterhin lesbar). */
    const val LEGACY_ITERATIONS = 210_000
    const val SALT_BYTES = 16
    const val IV_BYTES = 12
    const val TAG_BITS = 128
    const val KEY_BITS = 256

    /**
     * Mindestlänge des Passworts für neue Sicherungen (Unicode-Zeichen; seit Build 69 12, vorher 10 bzw. 8). Gilt
     * nur beim Sichern: ältere Dateien lassen sich mit jedem Passwort entschlüsseln.
     */
    const val MIN_PASSWORD_LENGTH = 12

    /** Grenzen für «iter» beim Lesen: Schutz vor unsinnigen oder absichtlich lähmenden Werten (umfasst 210 000 und 600 000). */
    private const val MIN_ITERATIONS = 10_000
    private const val MAX_ITERATIONS = 10_000_000

    /**
     * Obergrenzen für m, t, p beim Lesen: Eine manipulierte Datei soll das Gerät nicht mit Gigabytes oder Minuten
     * Rechenzeit lähmen. Untergrenze für m ist das Minimum von Argon2 (8 KiB je Bahn).
     */
    const val MAX_ARGON2_MEMORY_KIB = 262_144
    const val MAX_ARGON2_TIME = 10
    const val MAX_ARGON2_PARALLELISM = 4

    /** Schlüsselableitung samt Parametern, wie in der Datei. */
    sealed interface Kdf {
        /** v2: PBKDF2-HMAC-SHA256 mit «iter». */
        data class Pbkdf2(val iterations: Int) : Kdf

        /** v3: Argon2id mit «m» (KiB), «t», «p». */
        data class Argon2(val memoryKiB: Int, val time: Int, val parallelism: Int) : Kdf
    }

    /** Argon2id neuer Sicherungen. */
    val DEFAULT_KDF: Kdf = Kdf.Argon2(ARGON2_MEMORY_KIB, ARGON2_TIME, ARGON2_PARALLELISM)

    /** Verschlüsselte Sicherung (Felder wie im JSON, Bytes schon dekodiert). */
    class Envelope(
        val kdf: Kdf,
        val salt: ByteArray,
        val iv: ByteArray,
        val data: ByteArray,
    ) {
        /** «v»: 2 für PBKDF2, 3 für Argon2id. */
        val version: Int get() = if (kdf is Kdf.Pbkdf2) VERSION_PBKDF2 else VERSION
    }

    /** Passwort falsch oder Datei verändert (GCM-Tag passt nicht; bei v3 auch ein veränderter Kopf). */
    class WrongPasswordException : GeneralSecurityException("Wrong password or damaged file")

    /** Unbekannte Version, Verfahren oder ungültige Felder (z. B. aus einer neueren App). */
    class UnsupportedException(message: String) : GeneralSecurityException(message)

    /** Zu wenig Arbeitsspeicher für Argon2id (m KiB): klare Meldung statt Absturz, kein Rückfall auf ein schwächeres Verfahren. */
    class NotEnoughMemoryException : GeneralSecurityException("Not enough memory for key derivation")

    /** Länge in Unicode-Zeichen (nicht UTF-16-Einheiten), wie `unicodeScalars.count` unter iOS. */
    fun passwordLength(password: String): Int = normalize(password).let { it.codePointCount(0, it.length) }

    fun isPasswordLongEnough(password: String): Boolean = passwordLength(password) >= MIN_PASSWORD_LENGTH

    /** Passwort für eine neue Sicherung erlaubt: lang genug und nicht leicht zu erraten ([BackupPasswordStrength]). */
    fun isPasswordAllowed(password: String): Boolean = !BackupPasswordStrength.isWeak(password)

    /** Formular «Mit Passwort schützen»: aus, oder erlaubtes Passwort und beide Felder gleich. */
    fun canExport(protect: Boolean, password: String, repeat: String): Boolean =
        !protect || (isPasswordAllowed(password) && password == repeat)

    /** Kopf einer verschlüsselten Sicherung? (Klartext-Sicherungen haben kein «enc».) */
    fun isEnvelope(format: String?, enc: String?): Boolean = format == FORMAT && !enc.isNullOrEmpty()

    /**
     * Verschlüsselt [plain]. [password] gehört dem Aufrufer (er überschreibt es danach); abgeleitete Bytes werden hier
     * überschrieben. [kdf] ist für neue Sicherungen immer [DEFAULT_KDF] — v2 schreiben nur noch Tests (Prüfwerte).
     * @throws NotEnoughMemoryException wenn der Speicher für Argon2id nicht reicht.
     */
    fun encrypt(
        plain: ByteArray,
        password: CharArray,
        salt: ByteArray = randomBytes(SALT_BYTES),
        iv: ByteArray = randomBytes(IV_BYTES),
        kdf: Kdf = DEFAULT_KDF,
    ): Envelope {
        require(salt.size == SALT_BYTES && iv.size == IV_BYTES)
        val envelope = Envelope(kdf, salt.copyOf(), iv.copyOf(), ByteArray(0))
        validate(envelope, checkData = false)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, deriveKey(password, envelope), GCMParameterSpec(TAG_BITS, iv))
        if (kdf is Kdf.Argon2) cipher.updateAAD(header(envelope))
        // Ergebnis = Chiffrat || Tag (16 Bytes), wie CryptoKit ciphertext + tag
        return Envelope(kdf, envelope.salt, envelope.iv, cipher.doFinal(plain))
    }

    /**
     * @throws WrongPasswordException bei falschem Passwort oder veränderter Datei (bei v3 auch verändertem Kopf).
     * @throws NotEnoughMemoryException wenn der Speicher für Argon2id nicht reicht.
     */
    fun decrypt(envelope: Envelope, password: CharArray): ByteArray {
        validate(envelope)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, deriveKey(password, envelope), GCMParameterSpec(TAG_BITS, envelope.iv))
        // v2 bleibt ohne AAD (so wurden die Dateien geschrieben)
        if (envelope.kdf is Kdf.Argon2) cipher.updateAAD(header(envelope))
        return try {
            cipher.doFinal(envelope.data)
        } catch (_: AEADBadTagException) {
            throw WrongPasswordException()
        }
    }

    /**
     * Felder aus dem JSON prüfen und dekodieren: v2 mit [iterations], v3 mit [memoryKiB], [time], [parallelism]
     * (die jeweils anderen werden nicht beachtet).
     * @throws UnsupportedException bei unbekannter Version/Verfahren oder ungültigen Feldern.
     */
    fun envelope(
        version: Int,
        enc: String?,
        kdf: String?,
        iterations: Int,
        salt: String?,
        iv: String?,
        data: String?,
        memoryKiB: Int = 0,
        time: Int = 0,
        parallelism: Int = 0,
    ): Envelope {
        // Neuere Hülle: klare Meldung «neuere App-Version» statt «fehlgeschlagen»
        if (version > VERSION) throw BackupVersion.NewerVersionException(version)
        val parsedKdf: Kdf = when (version) {
            VERSION_PBKDF2 -> if (kdf == KDF_PBKDF2) Kdf.Pbkdf2(iterations) else null
            VERSION -> if (kdf == KDF) Kdf.Argon2(memoryKiB, time, parallelism) else null
            else -> throw UnsupportedException("version $version")
        } ?: throw UnsupportedException("$enc / $kdf")
        if (enc != ENC) throw UnsupportedException("$enc / $kdf")
        val decoder = Base64.getDecoder()
        fun bytes(value: String?, name: String): ByteArray = try {
            decoder.decode(value ?: throw UnsupportedException("missing $name"))
        } catch (_: IllegalArgumentException) {
            throw UnsupportedException("invalid $name")
        }
        return Envelope(parsedKdf, bytes(salt, "salt"), bytes(iv, "iv"), bytes(data, "data")).also { validate(it) }
    }

    /** JSON der verschlüsselten Sicherung (feste Reihenfolge der Felder; v2 wie bisher). */
    fun toJson(envelope: Envelope): String {
        val b64 = Base64.getEncoder()
        return buildString {
            append("{\n")
            append("  \"format\": \"").append(FORMAT).append("\",\n")
            append("  \"v\": ").append(envelope.version).append(",\n")
            append("  \"enc\": \"").append(ENC).append("\",\n")
            when (val kdf = envelope.kdf) {
                is Kdf.Pbkdf2 -> {
                    append("  \"kdf\": \"").append(KDF_PBKDF2).append("\",\n")
                    append("  \"iter\": ").append(kdf.iterations).append(",\n")
                }
                is Kdf.Argon2 -> {
                    append("  \"kdf\": \"").append(KDF).append("\",\n")
                    append("  \"m\": ").append(kdf.memoryKiB).append(",\n")
                    append("  \"t\": ").append(kdf.time).append(",\n")
                    append("  \"p\": ").append(kdf.parallelism).append(",\n")
                }
            }
            append("  \"salt\": \"").append(b64.encodeToString(envelope.salt)).append("\",\n")
            append("  \"iv\": \"").append(b64.encodeToString(envelope.iv)).append("\",\n")
            append("  \"data\": \"").append(b64.encodeToString(envelope.data)).append("\"\n")
            append("}")
        }
    }

    /**
     * Kanonischer Kopf einer v3-Hülle als AAD (UTF-8, kompaktes JSON, feste Reihenfolge; gleiche Bytes wie iOS
     * `BackupCrypto.header`). Aus den gelesenen Werten neu gebaut, nie aus dem Dateitext: Leerzeichen oder
     * Feldreihenfolge der Datei spielen keine Rolle, jeder geänderte Wert schon.
     */
    fun header(envelope: Envelope): ByteArray {
        val kdf = envelope.kdf as? Kdf.Argon2 ?: throw UnsupportedException("header v2")
        val b64 = Base64.getEncoder()
        return buildString {
            append("{\"format\":\"").append(FORMAT)
            append("\",\"v\":").append(VERSION)
            append(",\"enc\":\"").append(ENC)
            append("\",\"kdf\":\"").append(KDF)
            append("\",\"m\":").append(kdf.memoryKiB)
            append(",\"t\":").append(kdf.time)
            append(",\"p\":").append(kdf.parallelism)
            append(",\"salt\":\"").append(b64.encodeToString(envelope.salt))
            append("\",\"iv\":\"").append(b64.encodeToString(envelope.iv))
            append("\"}")
        }.toByteArray(Charsets.UTF_8)
    }

    private fun validate(envelope: Envelope, checkData: Boolean = true) {
        when (val kdf = envelope.kdf) {
            is Kdf.Pbkdf2 -> if (kdf.iterations !in MIN_ITERATIONS..MAX_ITERATIONS) {
                throw UnsupportedException("iter ${kdf.iterations}")
            }
            is Kdf.Argon2 -> if (kdf.parallelism !in 1..MAX_ARGON2_PARALLELISM ||
                kdf.time !in 1..MAX_ARGON2_TIME ||
                kdf.memoryKiB !in 8 * kdf.parallelism..MAX_ARGON2_MEMORY_KIB
            ) {
                throw UnsupportedException("argon2 ${kdf.memoryKiB}/${kdf.time}/${kdf.parallelism}")
            }
        }
        if (envelope.salt.size < 8 || envelope.iv.size != IV_BYTES || (checkData && envelope.data.size < TAG_BITS / 8)) {
            throw UnsupportedException("field sizes")
        }
    }

    private fun deriveKey(password: CharArray, envelope: Envelope): SecretKeySpec = when (val kdf = envelope.kdf) {
        is Kdf.Pbkdf2 -> pbkdf2(password, envelope.salt, kdf.iterations)
        is Kdf.Argon2 -> argon2(password, envelope.salt, kdf)
    }

    /** v2, unverändert: PBEKeySpec rechnet die Zeichen selbst in UTF-8 um. */
    private fun pbkdf2(password: CharArray, salt: ByteArray, iterations: Int): SecretKeySpec {
        val chars = normalizedCopy(password)
        val spec = PBEKeySpec(chars, salt, iterations, KEY_BITS)
        try {
            val key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            return SecretKeySpec(key, "AES").also { key.fill(0) }
        } finally {
            spec.clearPassword()
            chars.fill('\u0000')
        }
    }

    private fun argon2(password: CharArray, salt: ByteArray, kdf: Kdf.Argon2): SecretKeySpec {
        val bytes = utf8(password)
        try {
            val key = try {
                Argon2id.hash(bytes, salt, kdf.memoryKiB, kdf.time, kdf.parallelism, KEY_BITS / 8)
            } catch (_: OutOfMemoryError) {
                // m KiB am Stück nicht verfügbar: klar melden, nicht auf schwächere Parameter ausweichen
                throw NotEnoughMemoryException()
            }
            return SecretKeySpec(key, "AES").also { key.fill(0) }
        } finally {
            bytes.fill(0)
        }
    }

    /**
     * Passwort als UTF-8 (NFC), ohne Umweg über einen String, wenn es schon NFC ist (der Normalfall). Sonst entsteht
     * beim Normalisieren unvermeidlich ein String. Ungültige Ersatzzeichen werden zu «?» wie bei `String.toByteArray`.
     */
    private fun utf8(password: CharArray): ByteArray {
        val chars = normalizedCopy(password)
        try {
            val encoded = Charsets.UTF_8.newEncoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE)
                .encode(CharBuffer.wrap(chars))
            val out = ByteArray(encoded.remaining())
            encoded.get(out)
            if (encoded.hasArray()) encoded.array().fill(0)
            return out
        } finally {
            chars.fill('\u0000')
        }
    }

    /** NFC-Kopie des Passworts (der Aufrufer überschreibt sie): «ö» als ein Zeichen, wie es die Tastatur unter iOS liefert. */
    private fun normalizedCopy(password: CharArray): CharArray {
        val buffer = CharBuffer.wrap(password)
        return if (Normalizer.isNormalized(buffer, Normalizer.Form.NFC)) password.copyOf()
        else Normalizer.normalize(buffer, Normalizer.Form.NFC).toCharArray()
    }

    /** NFC: «ö» als ein Zeichen, gleich wie es die Tastatur unter iOS liefert. */
    private fun normalize(password: String): String = Normalizer.normalize(password, Normalizer.Form.NFC)

    private fun randomBytes(count: Int): ByteArray = ByteArray(count).also { SecureRandom().nextBytes(it) }
}

/**
 * Version einer Sicherung (Klartext-Feld «version», gespiegelt in `BackupCrypto.swift`): Eine Sicherung einer NEUEREN
 * App-Version wird ganz abgelehnt ([NewerVersionException], Meldung «neuere App-Version») — nie teilweise übernommen,
 * denn ihre Felder könnten anders gemeint sein. Ältere Versionen (und fehlende Angabe = älteste Sicherungen) bleiben lesbar.
 */
object BackupVersion {
    /** Höchste Version, die diese App liest und schreibt (`BackupManager`: «version»). */
    const val SUPPORTED = 1

    class NewerVersionException(val version: Int) : IllegalStateException("Sicherung Version $version einer neueren App-Version")

    /**
     * [raw] = Wert von «version» (null = fehlt): ganze Zahl (auch als Text) ≤ [SUPPORTED] → diese Zahl, fehlt → 1;
     * grösser → [NewerVersionException]; keine ganze Zahl ≥ 1 → [IllegalArgumentException] (unlesbar).
     */
    fun check(raw: Any?): Int {
        val number: Double = when (raw) {
            null -> return 1
            is Number -> raw.toDouble()
            is String -> raw.trim().toDoubleOrNull() ?: throw IllegalArgumentException("version $raw")
            else -> throw IllegalArgumentException("version $raw")
        }
        if (!number.isFinite() || number % 1.0 != 0.0 || number < 1.0) throw IllegalArgumentException("version $raw")
        if (number > SUPPORTED) throw NewerVersionException(if (number > Int.MAX_VALUE) Int.MAX_VALUE else number.toInt())
        return number.toInt()
    }
}

/**
 * Schwache Passwörter im Blatt «Sichern» (gespiegelt in `BackupCrypto.swift`, gemeinsame Beispiele in
 * `testdata/parity/backup_passwords.json`): sperren das Sichern mit Passwort ([BackupCrypto.canExport]).
 * Entschlüsseln prüft das Passwort nicht.
 */
object BackupPasswordStrength {
    /** Häufige Folgen (klein geschrieben); kommt eine davon vor, ist das Passwort schwach. */
    private val COMMON = listOf(
        "password", "passwort", "12345678", "87654321", "abcdefgh",
    )

    /** Tastaturreihen (QWERTZ, QWERTY, AZERTY); 6 Zeichen in Folge (auch rückwärts) gelten als Tastatur-Spaziergang. */
    private val KEYBOARD_ROWS = listOf(
        "qwertzuiop", "qwertyuiop", "azertyuiop", "asdfghjkl", "qsdfghjklm", "yxcvbnm", "zxcvbnm", "wxcvbn",
    )
    private const val WALK = 6

    /** Wörter, die mit Jahr/Zahl und Zeichen geschmückt ein Passwort nicht stark machen («Summer2026!», «Winter2025»). */
    private val WORDS = listOf(
        // Jahreszeiten
        "spring", "summer", "autumn", "fall", "winter", "fruehling", "frühling", "sommer", "herbst",
        "printemps", "ete", "été", "automne", "hiver",
        // Monate
        "january", "february", "march", "april", "may", "june", "july", "august", "september", "october",
        "november", "december", "januar", "februar", "maerz", "märz", "mai", "juni", "juli", "oktober", "dezember",
        // Häufige Wörter
        "password", "passwort", "welcome", "willkommen", "hello", "hallo", "letmein", "admin", "secret", "geheim",
        "iloveyou", "monkey", "dragon", "sunshine", "football", "fussball", "master", "qwerty", "qwertz",
        "bitcoin", "crypto", "ethereum", "test",
        // Name der App
        "cryptochecker", "crypto-checker", "crypto checker", "bitcoinchecker",
    )

    /** Höchstens so viele Ziffern/Zeichen um ein Wort herum gelten als Schmuck. */
    private const val DECORATION = 6

    /**
     * Schwach (für neue Sicherungen gesperrt):
     *  - kürzer als [BackupCrypto.MIN_PASSWORD_LENGTH], nur Ziffern oder ein einziges Zeichen wiederholt;
     *  - eine häufige Folge oder ein Tastatur-Spaziergang (6 Zeichen einer Reihe, auch rückwärts);
     *  - ein Wort (Jahreszeit, Monat, häufiges Wort, Name der App) mit höchstens 6 Ziffern/Zeichen davor bzw. danach
     *    («Summer2026!», «Winter2025», «CryptoChecker1!»);
     *  - ein wiederholtes Stück («abcabcabcabc», «Test1Test1»);
     *  - fast nur ein Zeichen: höchstens 3 verschiedene Zeichen, mindestens 75 % dasselbe Zeichen oder ein einziges
     *    wiederholtes Zeichen, wenn man Ziffern/Zeichen vorne und hinten weglässt («aaaaaaaaaaa1», «!!bbbbbbbbbb»).
     * Gleiche Regeln wie iOS `BackupPasswordStrength.isWeak` (Fälle in `testdata/parity/backup_passwords.json`).
     * Die frühere Regel «höchstens 2 Zeichenklassen und kürzer als 12 Zeichen» deckt seit Build 69 die
     * Mindestlänge 12 ab (Zeichenklassen spielen ab 12 Zeichen keine Rolle).
     */
    fun isWeak(password: String): Boolean {
        val normalized = Normalizer.normalize(password, Normalizer.Form.NFC)
        val codePoints = normalized.codePoints().toArray()
        if (codePoints.size < BackupCrypto.MIN_PASSWORD_LENGTH) return true
        if (codePoints.all { it in '0'.code..'9'.code }) return true
        if (codePoints.distinct().size == 1) return true
        if (mostlyOneChar(codePoints)) return true
        val lower = normalized.lowercase(Locale.ROOT)
        if (COMMON.any { lower.contains(it) }) return true
        if (keyboardWalk(lower)) return true
        if (decoratedWord(lower)) return true
        return repeatedChunk(codePoints)
    }

    /** 6 aufeinanderfolgende Zeichen einer Tastaturreihe (vorwärts oder rückwärts). */
    private fun keyboardWalk(lower: String): Boolean = KEYBOARD_ROWS.any { row ->
        listOf(row, row.reversed()).any { r -> (0..r.length - WALK).any { lower.contains(r.substring(it, it + WALK)) } }
    }

    /** Wort mit Schmuck: vorne und hinten nur Ziffern/Zeichen (keine Buchstaben), zusammen höchstens [DECORATION]. */
    private fun decoratedWord(lower: String): Boolean = WORDS.any { w ->
        var from = lower.indexOf(w)
        var hit = false
        while (from >= 0 && !hit) {
            val rest = lower.substring(0, from) + lower.substring(from + w.length)
            hit = rest.codePointCount(0, rest.length) <= DECORATION && rest.none { it.isLetter() }
            from = lower.indexOf(w, from + 1)
        }
        hit
    }

    /**
     * Fast nur ein Zeichen: höchstens 3 verschiedene Zeichen, mindestens 75 % dasselbe Zeichen, oder ohne
     * Ziffern/Zeichen (alles ausser Buchstaben) vorne und hinten bleibt ein einziges wiederholtes Zeichen.
     * Buchstabe = Unicode-Kategorie L* ([Character.isLetter], wie iOS `isLetterScalar`).
     */
    private fun mostlyOneChar(cp: IntArray): Boolean {
        if (cp.distinct().size <= 3) return true
        val most = cp.groupBy { it }.values.maxOf { it.size }
        if (most * 4 >= cp.size * 3) return true
        val first = cp.indexOfFirst { Character.isLetter(it) }
        if (first < 0) return false
        val last = cp.indexOfLast { Character.isLetter(it) }
        return (first..last).all { cp[it] == cp[first] }
    }

    /** Ganzes Passwort = ein Stück, mindestens zweimal wiederholt. */
    private fun repeatedChunk(cp: IntArray): Boolean {
        val n = cp.size
        for (p in 1..n / 2) {
            if (n % p != 0) continue
            if ((p until n).all { cp[it] == cp[it - p] }) return true
        }
        return false
    }

    /** Hinweis «zu leicht zu erraten» zeigen? Erst ab der Mindestlänge (vorher gilt «mindestens 12 Zeichen»). */
    fun showWeakHint(password: String): Boolean = BackupCrypto.isPasswordLongEnough(password) && isWeak(password)
}
