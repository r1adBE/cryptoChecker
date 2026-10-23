package com.cryptochecker.app.domain.mirror

import com.cryptochecker.app.domain.macro.MiniJson
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * Signiertes Manifest des Daten-Spiegels ([DataMirror]). Die Action «Market data» legt neben die Spiegel-Dateien
 * `manifest.json` (`{"created":<ms>,"files":{"<Datei>":"<SHA-256 hex>"…},"v":1}`) und `manifest.json.sig` (Base64
 * der DER-Signatur ECDSA P-256 mit SHA-256 über die genauen Bytes des Manifests, erzeugt mit dem Secret
 * MIRROR_SIGNING_KEY in `mirror_data.py --manifest`). Die App nimmt eine Spiegel-Datei nur, wenn die Signatur zum
 * eingebauten Schlüssel [PUBLIC_KEY] passt, das Manifest höchstens [MAX_AGE_MILLIS] alt ist (Schutz gegen alte,
 * wieder eingespielte Daten) und der SHA-256 der Datei stimmt — sonst fragt sie die Anbieter direkt, wie bei einem
 * Ausfall des Spiegels. Reines Kotlin (Plattform-Krypto, ab API 26), gleich in iOS `MirrorManifest.swift`,
 * gemeinsame Testfälle `mirror_signature.json` (mit eigenem Test-Schlüsselpaar).
 */
object MirrorManifest {
    const val FILE = "manifest.json"
    const val SIGNATURE_FILE = "manifest.json.sig"
    const val URL = DataMirror.BASE + FILE
    const val SIGNATURE_URL = DataMirror.BASE + SIGNATURE_FILE

    /** Öffentlicher Schlüssel zu MIRROR_SIGNING_KEY (SubjectPublicKeyInfo, DER, Base64); Wechsel: DEVELOPMENT.md. */
    const val PUBLIC_KEY = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEnyWpdnpqDJUM1yHZQxQKPAcaXyGpbk8HvfNVmnR1" +
        "MAuShndvEGub711SBOXLJ0aottnrndttNuskZV2BdiG/Pw=="

    /** Älter → verworfen (die Action läuft stündlich). */
    const val MAX_AGE_MILLIS = 7L * 24 * 60 * 60 * 1000

    /** Höchstgrösse von Manifest und Signatur beim Laden. */
    const val MAX_BYTES = 64L * 1024
    const val SIGNATURE_MAX_BYTES = 1024L

    /** Zeitpunkt in der Zukunft bis zu so viel gilt noch (Uhren gehen nicht genau gleich). */
    private const val CLOCK_SKEW_MILLIS = 5L * 60 * 1000

    /** Grösste ganze Zahl, die ein JSON-Double genau hält (2^53). */
    private const val MAX_SAFE_INTEGER = 9_007_199_254_740_992.0
    private const val FILE_NAME_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789._-"
    private const val HEX_CHARS = "0123456789abcdef"

    enum class Status { OK, MISSING, INVALID, TOO_OLD }

    enum class FileStatus { OK, HASH_MISMATCH, NOT_LISTED }

    /** Geprüftes Manifest: Erstellzeit (ms) und SHA-256 (hex, klein) je Datei. */
    data class Manifest(val created: Long, val files: Map<String, String>)

    /** Ergebnis von [verify]; [manifest] nur bei [Status.OK]. */
    data class Verification(val status: Status, val manifest: Manifest?)

    /** Spiegel-Datei, die gegen das Manifest geprüft wird (alles unter [DataMirror.BASE] ausser Manifest und Signatur). */
    fun isSignedFile(url: String): Boolean =
        url.startsWith(DataMirror.BASE) && url != URL && url != SIGNATURE_URL

    /**
     * Manifest und Signatur prüfen. Fehlt eines (null/leer) → [Status.MISSING]; Signatur ungültig, Inhalt unbekannt
     * oder Erstellzeit mehr als 5 Minuten in der Zukunft → [Status.INVALID]; [MAX_AGE_MILLIS] oder älter →
     * [Status.TOO_OLD]. [publicKey] nur für Tests anders.
     */
    fun verify(manifestText: String?, signatureText: String?, now: Long, publicKey: String = PUBLIC_KEY): Verification {
        val signature = signatureText?.trim().orEmpty()
        if (manifestText.isNullOrBlank() || signature.isEmpty()) return Verification(Status.MISSING, null)
        val der = decodeBase64(signature)
        if (der == null || !signatureValid(manifestText.toByteArray(Charsets.UTF_8), der, publicKey)) {
            return Verification(Status.INVALID, null)
        }
        val manifest = parse(manifestText) ?: return Verification(Status.INVALID, null)
        if (manifest.created > now + CLOCK_SKEW_MILLIS) return Verification(Status.INVALID, null)
        if (now - manifest.created >= MAX_AGE_MILLIS) return Verification(Status.TOO_OLD, null)
        return Verification(Status.OK, manifest)
    }

    /** Passt [text] (als UTF-8-Bytes) zum Eintrag von [file] im geprüften Manifest? */
    fun fileStatus(manifest: Manifest, file: String, text: String): FileStatus {
        val expected = manifest.files[file] ?: return FileStatus.NOT_LISTED
        return if (sha256Hex(text) == expected) FileStatus.OK else FileStatus.HASH_MISMATCH
    }

    fun sha256Hex(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    /** Streng: Länge ein Vielfaches von 4, nur das Base64-Alphabet (wie iOS `Data(base64Encoded:)`). */
    private fun decodeBase64(text: String): ByteArray? {
        if (text.length % 4 != 0) return null
        return try {
            Base64.getDecoder().decode(text)
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    private fun signatureValid(data: ByteArray, der: ByteArray, publicKey: String): Boolean =
        try {
            val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(publicKey)))
            Signature.getInstance("SHA256withECDSA").run {
                initVerify(key)
                update(data)
                verify(der)
            }
        } catch (e: GeneralSecurityException) {
            false
        } catch (e: IllegalArgumentException) {
            false
        }

    private fun parse(text: String): Manifest? {
        val o = try {
            MiniJson.parse(text) as? Map<*, *>
        } catch (e: IllegalArgumentException) {
            null
        } ?: return null
        if (o["v"] != 1.0) return null
        val created = (o["created"] as? Double)
            ?.takeIf { it > 0 && it == Math.floor(it) && it <= MAX_SAFE_INTEGER }?.toLong() ?: return null
        val files = o["files"] as? Map<*, *> ?: return null
        val out = LinkedHashMap<String, String>()
        for ((name, hash) in files) {
            if (name !is String || hash !is String || !validName(name) || !validHash(hash)) return null
            out[name] = hash
        }
        return Manifest(created, out)
    }

    private fun validName(name: String) = name.length in 1..64 && name.all { it in FILE_NAME_CHARS }

    private fun validHash(hash: String) = hash.length == 64 && hash.all { it in HEX_CHARS }
}
