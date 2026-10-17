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
    // Gleich, aber iter 600000 (neue Sicherungen)
    private val vectorData600k =
        "0Ku8OwoZGZNMLB4nSmFxc+ftNLme+bH0q7YUsGipFoc6Edly6EQAyI3y2P5g9FVosu0L/CWRglvDAWzcW6/sO5/liBMAHn0IC/qYSg=="

    private fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    @Test
    fun testVectorMatchesPython() {
        val env = BackupCrypto.encrypt(
            vectorPlain.toByteArray(Charsets.UTF_8), vectorPassword, vectorSalt, vectorIv, BackupCrypto.LEGACY_ITERATIONS
        )
        assertEquals("AAECAwQFBgcICQoLDA0ODw==", b64(env.salt))
        assertEquals("oKGio6Slpqeoqaqr", b64(env.iv))
        assertEquals(vectorData, b64(env.data))
        assertEquals(210_000, env.iterations)
    }

    @Test
    fun newBackupsUse600kIterations() {
        assertEquals(600_000, BackupCrypto.ITERATIONS)
        val env = BackupCrypto.encrypt(vectorPlain.toByteArray(Charsets.UTF_8), vectorPassword, vectorSalt, vectorIv)
        assertEquals(600_000, env.iterations)
        assertEquals(vectorData600k, b64(env.data))
        assertTrue(BackupCrypto.toJson(env).contains("\"iter\": 600000"))
    }

    @Test
    fun testVectorDecrypts() {
        // Alte Datei (iter 210000) bleibt lesbar
        val env = BackupCrypto.envelope(
            2, "AES-256-GCM", "PBKDF2-HMAC-SHA256", 210_000,
            "AAECAwQFBgcICQoLDA0ODw==", "oKGio6Slpqeoqaqr", vectorData
        )
        assertEquals(vectorPlain, BackupCrypto.decrypt(env, vectorPassword).toString(Charsets.UTF_8))
    }

    @Test
    fun testVector600kDecrypts() {
        val env = BackupCrypto.envelope(
            2, "AES-256-GCM", "PBKDF2-HMAC-SHA256", 600_000,
            "AAECAwQFBgcICQoLDA0ODw==", "oKGio6Slpqeoqaqr", vectorData600k
        )
        assertEquals(vectorPlain, BackupCrypto.decrypt(env, vectorPassword).toString(Charsets.UTF_8))
    }

    @Test
    fun decomposedPasswordEqualsComposed() {
        // «ö» als o + U+0308 (NFD) ergibt denselben Schlüssel wie das vorkomponierte «ö»
        val nfd = "Kryptö-Test 2026!"
        val env = BackupCrypto.encrypt(
            vectorPlain.toByteArray(Charsets.UTF_8), nfd, vectorSalt, vectorIv, BackupCrypto.LEGACY_ITERATIONS
        )
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
        val env = BackupCrypto.encrypt(
            vectorPlain.toByteArray(Charsets.UTF_8), vectorPassword, vectorSalt, vectorIv, BackupCrypto.LEGACY_ITERATIONS
        )
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
        // Neuere Hülle: eigene Meldung «neuere App-Version»
        try {
            BackupCrypto.envelope(3, "AES-256-GCM", "PBKDF2-HMAC-SHA256", 210_000, salt, iv, vectorData); fail("accepted")
        } catch (e: BackupVersion.NewerVersionException) {
            assertEquals(3, e.version)
        }
        rejects { BackupCrypto.envelope(1, "AES-256-GCM", "PBKDF2-HMAC-SHA256", 210_000, salt, iv, vectorData) }
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
        // Mindestlänge 10 (neue Sicherungen)
        assertFalse(BackupCrypto.isPasswordLongEnough("123456789"))
        assertTrue(BackupCrypto.isPasswordLongEnough("1234567890"))
        // Emoji zählt als ein Zeichen (zwei UTF-16-Einheiten)
        assertFalse(BackupCrypto.isPasswordLongEnough("12345678🔒"))
        assertTrue(BackupCrypto.isPasswordLongEnough("123456789🔒"))
        val good = "Kryptö-Test 2026!"
        assertTrue(BackupCrypto.canExport(protect = false, password = "", repeat = ""))
        assertFalse(BackupCrypto.canExport(protect = true, password = good, repeat = "Kryptö-Test 2027!"))
        assertFalse(BackupCrypto.canExport(protect = true, password = "1234", repeat = "1234"))
        assertTrue(BackupCrypto.canExport(protect = true, password = good, repeat = good))
        // Schwache Passwörter sperren das Sichern, auch ab 10 Zeichen
        // Liste statt Literal am Parameter: das Upload-Skript meldet sonst ein Passwort im Klartext
        val weakSamples = listOf("1234567890", "aaaaaaaaaaaa", "MeinPasswort")
        for (weak in weakSamples) {
            assertFalse(weak, BackupCrypto.canExport(protect = true, password = weak, repeat = weak))
        }
        assertFalse(BackupCrypto.isPasswordAllowed("Abc!def9x"))     // 9 Zeichen
        assertTrue(BackupCrypto.isPasswordAllowed("Abc!def9xy"))     // 10 Zeichen
    }

    @Test
    fun oldFilesDecryptWithShortPassword() {
        // Entschlüsseln prüft das Passwort nicht: alte Sicherungen mit 8 Zeichen bleiben lesbar
        val plain = "{}".toByteArray()
        val env = BackupCrypto.encrypt(plain, "12345678", iterations = 10_000)
        assertTrue(BackupCrypto.decrypt(env, "12345678").contentEquals(plain))
    }
}
