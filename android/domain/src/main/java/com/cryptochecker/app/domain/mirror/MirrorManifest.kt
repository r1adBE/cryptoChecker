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
 * `manifest.json` (`{"created":<ms>,"files":{"<Datei>":"<SHA-256 hex>"…},"revoked":[…],"v":1}`) und
 * `manifest.json.sig` (Base64
 * der DER-Signatur ECDSA P-256 mit SHA-256 über die genauen Bytes des Manifests, erzeugt mit dem Secret
 * MIRROR_SIGNING_KEY in `mirror_data.py --manifest`). Die App nimmt eine Spiegel-Datei nur, wenn die Signatur zum
 * eingebauten Schlüssel [PUBLIC_KEY] passt, das Manifest höchstens [MAX_AGE_MILLIS] alt ist (Schutz gegen alte,
 * wieder eingespielte Daten) und der SHA-256 der Datei stimmt — sonst fragt sie die Anbieter direkt, wie bei einem
 * Ausfall des Spiegels. Sperrliste «revoked» (fehlt = leer): Schlüssel-Ids ([keyId]) gesperrter Schlüssel — gilt nur
 * in einem gültigen Manifest, das mit dem ANDEREN, nicht gesperrten eingebauten Schlüssel signiert ist; die eigene Id
 * (Selbstsperre) und unbekannte Ids zählen nicht. Die App merkt sich gesperrte Schlüssel dauerhaft
 * ([MirrorRevocations]) und nimmt danach kein Manifest mehr an, das nur damit signiert ist. Gegenseitige Sperre (A
 * sperrt B, B sperrt A, je in einem frischen, gültig signierten Manifest, Reihenfolge egal): beide gesperrt, Spiegel
 * aus bis zu einem App-Update mit neuen Schlüsseln.
 * Reines Kotlin (Plattform-Krypto, ab API 26), gleich in iOS `MirrorManifest.swift`,
 * gemeinsame Testfälle `mirror_signature.json` (mit eigenen Test-Schlüsselpaaren).
 */
object MirrorManifest {
    const val FILE = "manifest.json"
    const val SIGNATURE_FILE = "manifest.json.sig"
    const val URL = DataMirror.BASE + FILE
    const val SIGNATURE_URL = DataMirror.BASE + SIGNATURE_FILE

    /** Öffentlicher Schlüssel zu MIRROR_SIGNING_KEY (SubjectPublicKeyInfo, DER, Base64); Wechsel: DEVELOPMENT.md (Repository-Hauptordner). */
    const val PUBLIC_KEY = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEnyWpdnpqDJUM1yHZQxQKPAcaXyGpbk8HvfNVmnR1" +
        "MAuShndvEGub711SBOXLJ0aottnrndttNuskZV2BdiG/Pw=="

    /**
     * Reserve-Schlüssel: privater Teil nur offline beim Inhaber. Geht MIRROR_SIGNING_KEY verloren, signiert die
     * Action mit dem Reserve-Schlüssel weiter, ohne dass die Apps ein Update brauchen. Wird MIRROR_SIGNING_KEY bekannt
     * (Leck), signiert die Action mit dem Reserve-Schlüssel und sperrt den alten über «revoked»: Jede App, die dieses
     * Manifest einmal gesehen hat, nimmt nichts mehr an, was nur mit dem alten Schlüssel signiert ist.
     */
    const val RESERVE_PUBLIC_KEY = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE2QmggFHrIqdCNnz8a03ZyaQoet4YwwLdBwy9MP4k" +
        "lEv3ChD2aFAhLUyH2gs3vk7c4TUuihar2eMy/wx6He+Ltw=="

    /** Alle Schlüssel, mit denen ein Manifest gültig ist (aktiver und Reserve-Schlüssel). */
    val PUBLIC_KEYS: List<String> = listOf(PUBLIC_KEY, RESERVE_PUBLIC_KEY)

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

    /** Geprüftes Manifest: Erstellzeit (ms), SHA-256 (hex, klein) je Datei und die Sperrliste (Schlüssel-Ids). */
    data class Manifest(val created: Long, val files: Map<String, String>, val revoked: List<String> = emptyList())

    /**
     * Ergebnis von [verify]; [manifest] nur bei [Status.OK]. [revoke]: Schlüssel-Ids, die ab jetzt dauerhaft gesperrt
     * sind — aus einem frischen, gültig signierten Manifest; bei [Status.INVALID] nur die gegenseitige Sperre (Manifest
     * eines schon gesperrten Schlüssels sperrt den anderen). Der Aufrufer merkt sie sich ([MirrorRevocations]).
     */
    data class Verification(val status: Status, val manifest: Manifest?, val revoke: Set<String> = emptySet())

    /** Spiegel-Datei, die gegen das Manifest geprüft wird (alles unter [DataMirror.BASE] ausser Manifest und Signatur). */
    fun isSignedFile(url: String): Boolean =
        url.startsWith(DataMirror.BASE) && url != URL && url != SIGNATURE_URL

    /**
     * Manifest und Signatur prüfen. Fehlt eines (null/leer) → [Status.MISSING]; Signatur ungültig, Inhalt unbekannt
     * oder Erstellzeit mehr als 5 Minuten in der Zukunft → [Status.INVALID]; [MAX_AGE_MILLIS] oder älter →
     * [Status.TOO_OLD]. Gültig, wenn die Signatur zu einem der [publicKeys] passt, der nicht in [revoked] (Schlüssel-Ids)
     * steht; andere Schlüssel nur für Tests.
     */
    fun verify(manifestText: String?, signatureText: String?, now: Long, publicKey: String): Verification =
        verify(manifestText, signatureText, now, listOf(publicKey))

    fun verify(
        manifestText: String?,
        signatureText: String?,
        now: Long,
        publicKeys: List<String> = PUBLIC_KEYS,
        revoked: Set<String> = emptySet(),
    ): Verification {
        val signature = signatureText?.trim().orEmpty()
        if (manifestText.isNullOrBlank() || signature.isEmpty()) return Verification(Status.MISSING, null)
        val der = decodeBase64(signature)
        val bytes = manifestText.toByteArray(Charsets.UTF_8)
        // Signiert mit einem eingebauten Schlüssel; gesperrte zählen für die Annahme nicht mehr
        val signer = der?.let { d -> publicKeys.firstOrNull { signatureValid(bytes, d, it) } }
            ?: return Verification(Status.INVALID, null)
        val manifest = parse(manifestText) ?: return Verification(Status.INVALID, null)
        if (manifest.created > now + CLOCK_SKEW_MILLIS) return Verification(Status.INVALID, null)
        if (now - manifest.created >= MAX_AGE_MILLIS) return Verification(Status.TOO_OLD, null)
        val revoke = newlyRevoked(manifest.revoked, signer, publicKeys, revoked)
        // Gesperrter Schlüssel: Manifest abgelehnt; sperrt er seinerseits den anderen (gegenseitige Sperre), sind
        // danach beide gesperrt und der Spiegel ist aus, bis ein App-Update neue Schlüssel bringt
        if (keyId(signer) in revoked) return Verification(Status.INVALID, null, revoke)
        return Verification(Status.OK, manifest, revoke)
    }

    /**
     * Schlüssel-Ids aus der Sperrliste [listed] eines gültigen, mit [signer] signierten Manifests, die ab jetzt
     * gesperrt sind: nur andere eingebaute Schlüssel ([publicKeys]) — die eigene Id (Selbstsperre) und unbekannte Ids
     * zählen nicht; schon gesperrte ([revoked]) kommen nicht nochmals.
     */
    fun newlyRevoked(
        listed: List<String>,
        signer: String,
        publicKeys: List<String>,
        revoked: Set<String>,
    ): Set<String> {
        val own = keyId(signer)
        val known = publicKeys.mapNotNullTo(HashSet()) { keyId(it) }
        return listed.filterTo(LinkedHashSet()) { it in known && it != own && it !in revoked }
    }

    /** Schlüssel-Id: SHA-256 (hex, klein) der DER-Bytes des öffentlichen Schlüssels [publicKey]; sonst null. */
    fun keyId(publicKey: String): String? =
        try {
            hex(MessageDigest.getInstance("SHA-256").digest(Base64.getDecoder().decode(publicKey)))
        } catch (e: IllegalArgumentException) {
            null
        }

    /** Passt [text] (als UTF-8-Bytes) zum Eintrag von [file] im geprüften Manifest? */
    fun fileStatus(manifest: Manifest, file: String, text: String): FileStatus {
        val expected = manifest.files[file] ?: return FileStatus.NOT_LISTED
        return if (sha256Hex(text) == expected) FileStatus.OK else FileStatus.HASH_MISMATCH
    }

    fun sha256Hex(text: String): String =
        hex(MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)))

    private fun hex(bytes: ByteArray): String =
        bytes.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

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
        // Sperrliste: fehlt = leer; sonst nur Schlüssel-Ids (wie ein SHA-256), alles andere macht das Manifest ungültig
        val revoked = when (val r = o["revoked"]) {
            null -> if (o.containsKey("revoked")) return null else emptyList()
            is List<*> -> r.map { id -> (id as? String)?.takeIf { validHash(it) } ?: return null }
            else -> return null
        }
        return Manifest(created, out, revoked)
    }

    private fun validName(name: String) = name.length in 1..64 && name.all { it in FILE_NAME_CHARS }

    private fun validHash(hash: String) = hash.length == 64 && hash.all { it in HEX_CHARS }
}
