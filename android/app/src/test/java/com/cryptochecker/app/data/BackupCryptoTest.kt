package com.cryptochecker.app.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Base64

class BackupCryptoTest {

    // Prüfwert, auch für `BackupCrypto.swift` (selbstTest) — mit Python `cryptography` nachgerechnet:
    // hashlib.pbkdf2_hmac("sha256", pw.encode(), salt, 210000, 32) + AESGCM(key).encrypt(iv, plain, None)
    private val vectorPassword = "Kryptö-Test 2026!"
    private val vectorSalt = ByteArray(16) { it.toByte() }                  // 00..0f
    private val vectorIv = ByteArray(12) { (0xa0 + it).toByte() }           // a0..ab
    private val vectorPlain = """{"format":"cryptochecker-backup","version":1,"portfolio":[]}"""
    private val vectorData =
        "F8ZO22w17BWWRjDa0IBQavtrw3kOeap/pSDHBNMr2yCCLsZy82l3RqH6OxEDzba6sota8lpn/SClooHDYy0tm81k5gCByAiia3plAA=="

    private fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    @Test
    fun testVectorMatchesPython() {
        val env = BackupCrypto.encrypt(vectorPlain.toByteArray(Charsets.UTF_8), vectorPassword, vectorSalt, vectorIv)
        assertEquals("AAECAwQFBgcICQoLDA0ODw==", b64(env.salt))
        assertEquals("oKGio6Slpqeoqaqr", b64(env.iv))
        assertEquals(vectorData, b64(env.data))
        assertEquals(BackupCrypto.ITERATIONS, env.iterations)
    }

    @Test
    fun testVectorDecrypts() {
        val env = BackupCrypto.envelope(
            2, "AES-256-GCM", "PBKDF2-HMAC-SHA256", 210_000,
            "AAECAwQFBgcICQoLDA0ODw==", "oKGio6Slpqeoqaqr", vectorData
        )
        assertEquals(vectorPlain, BackupCrypto.decrypt(env, vectorPassword).toString(Charsets.UTF_8))
    }

    @Test
    fun decomposedPasswordEqualsComposed() {
        // «ö» als o + U+0308 (NFD) ergibt denselben Schlüssel wie das vorkomponierte «ö»
        val nfd = "Kryptö-Test 2026!"
        val env = BackupCrypto.encrypt(vectorPlain.toByteArray(Charsets.UTF_8), nfd, vectorSalt, vectorIv)
        assertEquals(vectorData, b64(env.data))
    }

    @Test
    fun roundTripWithRandomSaltAndIv() {
        val plain = "{\"format\":\"cryptochecker-backup\",\"version\":1,\"note\":\"Ünïcödé ✓\"}".toByteArray(Charsets.UTF_8)
        val a = BackupCrypto.encrypt(plain, "geheim123")
        val b = BackupCrypto.encrypt(plain, "geheim123")
        assertFalse(a.salt.contentEquals(b.salt))
        assertFalse(a.iv.contentEquals(b.iv))
        assertEquals(plain.size + 16, a.data.size)
        assertArrayEquals(plain, BackupCrypto.decrypt(a, "geheim123"))
        assertArrayEquals(plain, BackupCrypto.decrypt(b, "geheim123"))
    }

    @Test
    fun wrongPasswordIsReported() {
        val env = BackupCrypto.encrypt("{}".toByteArray(), "richtig-123")
        try {
            BackupCrypto.decrypt(env, "falsch-1234")
            fail("decrypted with wrong password")
        } catch (_: BackupCrypto.WrongPasswordException) {
        }
    }

    @Test
    fun tamperedDataIsRejected() {
        val env = BackupCrypto.encrypt("{\"a\":1}".toByteArray(), "richtig-123")
        val bad = env.data.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
        try {
            BackupCrypto.decrypt(BackupCrypto.Envelope(env.iterations, env.salt, env.iv, bad), "richtig-123")
            fail("decrypted tampered data")
        } catch (_: BackupCrypto.WrongPasswordException) {
        }
        val badTag = env.data.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 0x80).toByte() }
        try {
            BackupCrypto.decrypt(BackupCrypto.Envelope(env.iterations, env.salt, env.iv, badTag), "richtig-123")
            fail("decrypted with tampered tag")
        } catch (_: BackupCrypto.WrongPasswordException) {
        }
    }

    @Test
    fun envelopeJsonRoundTrip() {
        val env = BackupCrypto.encrypt(vectorPlain.toByteArray(Charsets.UTF_8), vectorPassword, vectorSalt, vectorIv)
        val json = BackupCrypto.toJson(env)
        assertTrue(json.contains("\"format\": \"cryptochecker-backup-enc\""))
        assertTrue(json.contains("\"v\": 2"))
        assertTrue(json.contains("\"enc\": \"AES-256-GCM\""))
        assertTrue(json.contains("\"kdf\": \"PBKDF2-HMAC-SHA256\""))
        assertTrue(json.contains("\"iter\": 210000"))
        assertTrue(json.contains("\"salt\": \"AAECAwQFBgcICQoLDA0ODw==\""))
        assertTrue(json.contains("\"iv\": \"oKGio6Slpqeoqaqr\""))
        assertTrue(json.contains("\"data\": \"$vectorData\""))
    }

    @Test
    fun unsupportedEnvelopesAreRejected() {
        fun rejects(block: () -> Unit) = try {
            block(); fail("accepted")
        } catch (_: BackupCrypto.UnsupportedException) {
        }
        val salt = "AAECAwQFBgcICQoLDA0ODw=="
        val iv = "oKGio6Slpqeoqaqr"
        rejects { BackupCrypto.envelope(3, "AES-256-GCM", "PBKDF2-HMAC-SHA256", 210_000, salt, iv, vectorData) }
        rejects { BackupCrypto.envelope(2, "ChaCha20", "PBKDF2-HMAC-SHA256", 210_000, salt, iv, vectorData) }
        rejects { BackupCrypto.envelope(2, "AES-256-GCM", "scrypt", 210_000, salt, iv, vectorData) }
        rejects { BackupCrypto.envelope(2, "AES-256-GCM", "PBKDF2-HMAC-SHA256", 1, salt, iv, vectorData) }
        rejects { BackupCrypto.envelope(2, "AES-256-GCM", "PBKDF2-HMAC-SHA256", Int.MAX_VALUE, salt, iv, vectorData) }
        rejects { BackupCrypto.envelope(2, "AES-256-GCM", "PBKDF2-HMAC-SHA256", 210_000, salt, "AAEC", vectorData) }
        rejects { BackupCrypto.envelope(2, "AES-256-GCM", "PBKDF2-HMAC-SHA256", 210_000, salt, iv, "%%%") }
        rejects { BackupCrypto.envelope(2, "AES-256-GCM", "PBKDF2-HMAC-SHA256", 210_000, null, iv, vectorData) }
        rejects { BackupCrypto.envelope(2, "AES-256-GCM", "PBKDF2-HMAC-SHA256", 210_000, salt, iv, "AAEC") }
    }

    @Test
    fun envelopeDetection() {
        assertTrue(BackupCrypto.isEnvelope("cryptochecker-backup-enc", "AES-256-GCM"))
        assertFalse(BackupCrypto.isEnvelope("cryptochecker-backup-enc", null))
        assertFalse(BackupCrypto.isEnvelope("cryptochecker-backup-enc", ""))
        assertFalse(BackupCrypto.isEnvelope("other", "AES-256-GCM"))
        // Lesbare Sicherung (älteres «format») ist nie eine Hülle — ältere Apps lehnen die Hülle ab
        assertFalse(BackupCrypto.isEnvelope("cryptochecker-backup", "AES-256-GCM"))
    }

    @Test
    fun passwordRules() {
        assertFalse(BackupCrypto.isPasswordLongEnough("1234567"))
        val eight = "12345678"
        assertTrue(BackupCrypto.isPasswordLongEnough("12345678"))
        // Emoji zählt als ein Zeichen (zwei UTF-16-Einheiten)
        assertFalse(BackupCrypto.isPasswordLongEnough("123456🔒"))
        assertTrue(BackupCrypto.canExport(protect = false, password = "", repeat = ""))
        assertFalse(BackupCrypto.canExport(protect = true, password = eight, repeat = "12345679"))
        assertFalse(BackupCrypto.canExport(protect = true, password = "1234", repeat = "1234"))
        assertTrue(BackupCrypto.canExport(protect = true, password = eight, repeat = "12345678"))
    }
}
