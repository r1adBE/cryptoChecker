package com.cryptochecker.app.data

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
 * `App/Services/BackupCrypto.swift`). Ein Format für Android und iOS:
 *
 * ```
 * {"format":"cryptochecker-backup-enc","v":2,"enc":"AES-256-GCM","kdf":"PBKDF2-HMAC-SHA256",
 *  "iter":600000,"salt":"<Base64, 16 Bytes>","iv":"<Base64, 12 Bytes>",
 *  "data":"<Base64: Chiffrat || 16-Byte-Tag>"}
 * ```
 * Klartext = die bisherige Sicherung (JSON, UTF-8). Schlüssel = PBKDF2-HMAC-SHA256 aus dem
 * Passwort (Unicode NFC, UTF-8) und dem Salt, 32 Bytes. Ohne Passwort ist die Datei nicht
 * wiederherzustellen; falsches Passwort und veränderte Datei sind nicht zu unterscheiden
 * (beides scheitert am GCM-Tag).
 *
 * «iter»: Neue Sicherungen mit [ITERATIONS] = 600 000 (OWASP-Empfehlung für PBKDF2-HMAC-SHA256);
 * ältere Fassungen schrieben 210 000. Gelesen wird immer der Wert aus der Datei, ältere Sicherungen
 * bleiben also lesbar. 600 000 dauern auf älteren Geräten etwa 1 s — nur im Hintergrund aufrufen
 * (`BackupManager` läuft auf `Dispatchers.IO`).
 *
 * Prüfwerte (beide Seiten, mit Python `cryptography` nachgerechnet):
 * Passwort `Kryptö-Test 2026!`, salt `AAECAwQFBgcICQoLDA0ODw==` (00..0f),
 * iv `oKGio6Slpqeoqaqr` (a0..ab),
 * Klartext `{"format":"cryptochecker-backup","version":1,"portfolio":[]}` →
 * - iter 210000 (alte Datei): data `F8ZO22w17BWWRjDa0IBQavtrw3kOeap/pSDHBNMr2yCCLsZy82l3RqH6OxEDzba6sota8lpn/SClooHDYy0tm81k5gCByAiia3plAA==`
 * - iter 600000 (neu): data `0Ku8OwoZGZNMLB4nSmFxc+ftNLme+bH0q7YUsGipFoc6Edly6EQAyI3y2P5g9FVosu0L/CWRglvDAWzcW6/sO5/liBMAHn0IC/qYSg==`
 */
object BackupCrypto {
    const val FORMAT = "cryptochecker-backup-enc"
    const val VERSION = 2
    const val ENC = "AES-256-GCM"
    const val KDF = "PBKDF2-HMAC-SHA256"
    const val ITERATIONS = 600_000

    /** «iter» älterer Sicherungen (weiterhin lesbar). */
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

    /** Verschlüsselte Sicherung (Felder wie im JSON, Bytes schon dekodiert). */
    class Envelope(
        val iterations: Int,
        val salt: ByteArray,
        val iv: ByteArray,
        val data: ByteArray,
    )

    /** Passwort falsch oder Datei verändert (GCM-Tag passt nicht). */
    class WrongPasswordException : GeneralSecurityException("Wrong password or damaged file")

    /** Unbekannte Version, Verfahren oder ungültige Felder (z. B. aus einer neueren App). */
    class UnsupportedException(message: String) : GeneralSecurityException(message)

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

    fun encrypt(
        plain: ByteArray,
        password: String,
        salt: ByteArray = randomBytes(SALT_BYTES),
        iv: ByteArray = randomBytes(IV_BYTES),
        iterations: Int = ITERATIONS,
    ): Envelope {
        require(salt.size == SALT_BYTES && iv.size == IV_BYTES)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, deriveKey(password, salt, iterations), GCMParameterSpec(TAG_BITS, iv))
        // Ergebnis = Chiffrat || Tag (16 Bytes), wie CryptoKit ciphertext + tag
        return Envelope(iterations, salt.copyOf(), iv.copyOf(), cipher.doFinal(plain))
    }

    /** @throws WrongPasswordException bei falschem Passwort oder veränderter Datei. */
    fun decrypt(envelope: Envelope, password: String): ByteArray {
        validate(envelope)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            deriveKey(password, envelope.salt, envelope.iterations),
            GCMParameterSpec(TAG_BITS, envelope.iv)
        )
        return try {
            cipher.doFinal(envelope.data)
        } catch (_: AEADBadTagException) {
            throw WrongPasswordException()
        }
    }

    /**
     * Felder aus dem JSON prüfen und dekodieren.
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
    ): Envelope {
        // Neuere Hülle: klare Meldung «neuere App-Version» statt «fehlgeschlagen»
        if (version > VERSION) throw BackupVersion.NewerVersionException(version)
        if (version != VERSION) throw UnsupportedException("version $version")
        if (enc != ENC || kdf != KDF) throw UnsupportedException("$enc / $kdf")
        val decoder = Base64.getDecoder()
        fun bytes(value: String?, name: String): ByteArray = try {
            decoder.decode(value ?: throw UnsupportedException("missing $name"))
        } catch (_: IllegalArgumentException) {
            throw UnsupportedException("invalid $name")
        }
        return Envelope(iterations, bytes(salt, "salt"), bytes(iv, "iv"), bytes(data, "data")).also(::validate)
    }

    /** JSON der verschlüsselten Sicherung (feste Reihenfolge der Felder). */
    fun toJson(envelope: Envelope): String {
        val b64 = Base64.getEncoder()
        return buildString {
            append("{\n")
            append("  \"format\": \"").append(FORMAT).append("\",\n")
            append("  \"v\": ").append(VERSION).append(",\n")
            append("  \"enc\": \"").append(ENC).append("\",\n")
            append("  \"kdf\": \"").append(KDF).append("\",\n")
            append("  \"iter\": ").append(envelope.iterations).append(",\n")
            append("  \"salt\": \"").append(b64.encodeToString(envelope.salt)).append("\",\n")
            append("  \"iv\": \"").append(b64.encodeToString(envelope.iv)).append("\",\n")
            append("  \"data\": \"").append(b64.encodeToString(envelope.data)).append("\"\n")
            append("}")
        }
    }

    private fun validate(envelope: Envelope) {
        if (envelope.iterations !in MIN_ITERATIONS..MAX_ITERATIONS) {
            throw UnsupportedException("iter ${envelope.iterations}")
        }
        if (envelope.salt.size < 8 || envelope.iv.size != IV_BYTES || envelope.data.size < TAG_BITS / 8) {
            throw UnsupportedException("field sizes")
        }
    }

    private fun deriveKey(password: String, salt: ByteArray, iterations: Int): SecretKeySpec {
        val chars = normalize(password).toCharArray()
        val spec = PBEKeySpec(chars, salt, iterations, KEY_BITS)
        try {
            val key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            return SecretKeySpec(key, "AES")
        } finally {
            spec.clearPassword()
            chars.fill('\u0000')
        }
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
     *  - ein wiederholtes Stück («abcabcabcabc», «Test1Test1»).
     * Die frühere Regel «höchstens 2 Zeichenklassen und kürzer als 12 Zeichen» deckt seit Build 69 die
     * Mindestlänge 12 ab (Zeichenklassen spielen ab 12 Zeichen keine Rolle).
     */
    fun isWeak(password: String): Boolean {
        val normalized = Normalizer.normalize(password, Normalizer.Form.NFC)
        val codePoints = normalized.codePoints().toArray()
        if (codePoints.size < BackupCrypto.MIN_PASSWORD_LENGTH) return true
        if (codePoints.all { it in '0'.code..'9'.code }) return true
        if (codePoints.distinct().size == 1) return true
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
