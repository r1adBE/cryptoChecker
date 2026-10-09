package com.cryptochecker.app.data

import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.text.Normalizer
import java.util.Base64
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
 *  "iter":210000,"salt":"<Base64, 16 Bytes>","iv":"<Base64, 12 Bytes>",
 *  "data":"<Base64: Chiffrat || 16-Byte-Tag>"}
 * ```
 * Klartext = die bisherige Sicherung (JSON, UTF-8). Schlüssel = PBKDF2-HMAC-SHA256 aus dem
 * Passwort (Unicode NFC, UTF-8) und dem Salt, 32 Bytes. Ohne Passwort ist die Datei nicht
 * wiederherzustellen; falsches Passwort und veränderte Datei sind nicht zu unterscheiden
 * (beides scheitert am GCM-Tag).
 *
 * Prüfwert (beide Seiten, mit Python `cryptography` nachgerechnet):
 * Passwort `Kryptö-Test 2026!`, iter 210000, salt `AAECAwQFBgcICQoLDA0ODw==` (00..0f),
 * iv `oKGio6Slpqeoqaqr` (a0..ab),
 * Klartext `{"format":"cryptochecker-backup","version":1,"portfolio":[]}` →
 * data `F8ZO22w17BWWRjDa0IBQavtrw3kOeap/pSDHBNMr2yCCLsZy82l3RqH6OxEDzba6sota8lpn/SClooHDYy0tm81k5gCByAiia3plAA==`
 */
object BackupCrypto {
    const val FORMAT = "cryptochecker-backup-enc"
    const val VERSION = 2
    const val ENC = "AES-256-GCM"
    const val KDF = "PBKDF2-HMAC-SHA256"
    const val ITERATIONS = 210_000
    const val SALT_BYTES = 16
    const val IV_BYTES = 12
    const val TAG_BITS = 128
    const val KEY_BITS = 256

    /** Mindestlänge des Passworts (Unicode-Zeichen). */
    const val MIN_PASSWORD_LENGTH = 8

    /** Grenzen für «iter» beim Lesen: Schutz vor unsinnigen oder absichtlich lähmenden Werten. */
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

    /** Formular «Mit Passwort schützen»: aus, oder lang genug und beide Felder gleich. */
    fun canExport(protect: Boolean, password: String, repeat: String): Boolean =
        !protect || (isPasswordLongEnough(password) && password == repeat)

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
