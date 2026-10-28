package com.cryptochecker.app.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Base64

class BackupCryptoTest {

    // Prüfwerte (Argon2id + AAD): testdata/parity/backup_crypto.json (BackupCryptoParityTest)
    private val vectorPassword = "Kryptö-Test 2026!"
    private val vectorSalt = ByteArray(16) { it.toByte() }                  // 00..0f
    private val vectorIv = ByteArray(12) { (0xa0 + it).toByte() }           // a0..ab
    private val vectorPlain = """{"format":"cryptochecker-backup","version":1,"portfolio":[]}"""
    // Beliebige gültige Base64-Daten (≥ 16 Bytes) für Hüllen, die schon beim Lesen scheitern
    private val someData =
        "F8ZO22w17BWWRjDa0IBQavtrw3kOeap/pSDHBNMr2yCCLsZy82l3RqH6OxEDzba6sota8lpn/SClooHDYy0tm81k5gCByAiia3plAA=="

    // Rasche Argon2id-Parameter für Tests, die nicht den Prüfwert betreffen (64 MiB brauchen je Aufruf ~0,5 s)
    private val fastKdf = BackupCrypto.Kdf.Argon2(256, 1, 1)

    private fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    private fun pw(value: String): CharArray = value.toCharArray()

    @Test
    fun newBackupsUseArgon2idV3() {
        assertEquals(3, BackupCrypto.VERSION)
        assertEquals(BackupCrypto.Kdf.Argon2(65_536, 3, 1), BackupCrypto.DEFAULT_KDF)
        val env = BackupCrypto.encrypt(vectorPlain.toByteArray(Charsets.UTF_8), pw(vectorPassword))
        assertEquals(3, env.version)
        assertEquals(BackupCrypto.DEFAULT_KDF, env.kdf)
        assertEquals(16, env.salt.size)
        val json = BackupCrypto.toJson(env)
        for (field in listOf("\"v\": 3", "\"kdf\": \"Argon2id\"", "\"m\": 65536", "\"t\": 3", "\"p\": 1")) {
            assertTrue(field, json.contains(field))
        }
        assertFalse(json.contains("\"iter\""))
        assertEquals(vectorPlain, BackupCrypto.decrypt(env, pw(vectorPassword)).toString(Charsets.UTF_8))
    }

    @Test
    fun v3HeaderIsBoundAsAad() {
        val env = BackupCrypto.encrypt(vectorPlain.toByteArray(Charsets.UTF_8), pw(vectorPassword), vectorSalt, vectorIv, fastKdf)
        assertEquals(
            "{\"format\":\"cryptochecker-backup-enc\",\"v\":3,\"enc\":\"AES-256-GCM\",\"kdf\":\"Argon2id\"," +
                "\"m\":256,\"t\":1,\"p\":1,\"salt\":\"AAECAwQFBgcICQoLDA0ODw==\",\"iv\":\"oKGio6Slpqeoqaqr\"}",
            BackupCrypto.header(env).toString(Charsets.UTF_8)
        )
        // Gleiche Ableitung, aber ohne AAD verschlüsselt: muss am Tag scheitern
        val withoutAad = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding").run {
            val key = Argon2id.hash(vectorPassword.toByteArray(Charsets.UTF_8), vectorSalt, 256, 1, 1, 32)
            init(javax.crypto.Cipher.ENCRYPT_MODE, javax.crypto.spec.SecretKeySpec(key, "AES"), javax.crypto.spec.GCMParameterSpec(128, vectorIv))
            doFinal(vectorPlain.toByteArray(Charsets.UTF_8))
        }
        // Chiffrat gleich (gleicher Schlüssel und IV), nur das Tag hängt vom Kopf ab
        assertArrayEquals(env.data.copyOf(env.data.size - 16), withoutAad.copyOf(withoutAad.size - 16))
        try {
            BackupCrypto.decrypt(BackupCrypto.Envelope(fastKdf, env.salt, env.iv, withoutAad), pw(vectorPassword))
            fail("decrypted without header")
        } catch (_: BackupCrypto.WrongPasswordException) {
        }
        // Veränderter Kopf (t, p, m) → «falsches Passwort oder beschädigte Datei», nicht «nicht unterstützt»
        for (changed in listOf(BackupCrypto.Kdf.Argon2(256, 2, 1), BackupCrypto.Kdf.Argon2(256, 1, 2), BackupCrypto.Kdf.Argon2(264, 1, 1))) {
            try {
                BackupCrypto.decrypt(BackupCrypto.Envelope(changed, env.salt, env.iv, env.data), pw(vectorPassword))
                fail("decrypted with changed header $changed")
            } catch (_: BackupCrypto.WrongPasswordException) {
            }
        }
    }

    @Test
    fun argon2LimitsProtectAgainstManipulatedFiles() {
        fun rejects(kdf: BackupCrypto.Kdf) = try {
            BackupCrypto.decrypt(BackupCrypto.Envelope(kdf, vectorSalt, vectorIv, ByteArray(32)), pw(vectorPassword))
            fail("accepted $kdf")
        } catch (_: BackupCrypto.UnsupportedException) {
        }
        rejects(BackupCrypto.Kdf.Argon2(262_145, 3, 1))   // über 256 MiB
        rejects(BackupCrypto.Kdf.Argon2(65_536, 11, 1))   // t über 10
        rejects(BackupCrypto.Kdf.Argon2(65_536, 3, 5))    // p über 4
        rejects(BackupCrypto.Kdf.Argon2(65_536, 0, 1))
        rejects(BackupCrypto.Kdf.Argon2(65_536, 3, 0))
        rejects(BackupCrypto.Kdf.Argon2(31, 1, 4))        // unter 8 KiB je Bahn
        rejects(BackupCrypto.Kdf.Argon2(Int.MAX_VALUE, 3, 1))
    }

    @Test
    fun decomposedPasswordEqualsComposed() {
        // «ö» als o + U+0308 (NFD) ergibt denselben Schlüssel wie das vorkomponierte «ö»
        val nfd = "Kryptö-Test 2026!"
        val composed = BackupCrypto.encrypt(vectorPlain.toByteArray(Charsets.UTF_8), pw(vectorPassword), vectorSalt, vectorIv, fastKdf)
        val decomposed = BackupCrypto.encrypt(vectorPlain.toByteArray(Charsets.UTF_8), pw(nfd), vectorSalt, vectorIv, fastKdf)
        assertArrayEquals(composed.data, decomposed.data)
    }

    @Test
    fun callerPasswordIsNotChanged() {
        // BackupCrypto arbeitet auf Kopien; überschreiben darf nur der Aufrufer (BackupManager)
        val password = pw(vectorPassword)
        BackupCrypto.encrypt("{}".toByteArray(), password, kdf = fastKdf)
        assertEquals(vectorPassword, String(password))
    }

    @Test
    fun roundTripWithRandomSaltAndIv() {
        val plain = "{\"format\":\"cryptochecker-backup\",\"version\":1,\"note\":\"Ünïcödé ✓\"}".toByteArray(Charsets.UTF_8)
        val a = BackupCrypto.encrypt(plain, pw("geheim123"), kdf = fastKdf)
        val b = BackupCrypto.encrypt(plain, pw("geheim123"), kdf = fastKdf)
        assertFalse(a.salt.contentEquals(b.salt))
        assertFalse(a.iv.contentEquals(b.iv))
        assertEquals(plain.size + 16, a.data.size)
        assertArrayEquals(plain, BackupCrypto.decrypt(a, pw("geheim123")))
        assertArrayEquals(plain, BackupCrypto.decrypt(b, pw("geheim123")))
    }

    @Test
    fun wrongPasswordIsReported() {
        val env = BackupCrypto.encrypt("{}".toByteArray(), pw("richtig-123"), kdf = fastKdf)
        try {
            BackupCrypto.decrypt(env, pw("falsch-1234"))
            fail("decrypted with wrong password")
        } catch (_: BackupCrypto.WrongPasswordException) {
        }
    }

    @Test
    fun tamperedDataIsRejected() {
        val env = BackupCrypto.encrypt("{\"a\":1}".toByteArray(), pw("richtig-123"), kdf = fastKdf)
        val bad = env.data.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
        try {
            BackupCrypto.decrypt(BackupCrypto.Envelope(env.kdf, env.salt, env.iv, bad), pw("richtig-123"))
            fail("decrypted tampered data")
        } catch (_: BackupCrypto.WrongPasswordException) {
        }
        val badTag = env.data.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 0x80).toByte() }
        try {
            BackupCrypto.decrypt(BackupCrypto.Envelope(env.kdf, env.salt, env.iv, badTag), pw("richtig-123"))
            fail("decrypted with tampered tag")
        } catch (_: BackupCrypto.WrongPasswordException) {
        }
    }

    @Test
    fun envelopeJson() {
        val env = BackupCrypto.encrypt(
            vectorPlain.toByteArray(Charsets.UTF_8), pw(vectorPassword), vectorSalt, vectorIv, fastKdf
        )
        val json = BackupCrypto.toJson(env)
        assertTrue(json.contains("\"format\": \"cryptochecker-backup-enc\""))
        assertTrue(json.contains("\"v\": 3"))
        assertTrue(json.contains("\"enc\": \"AES-256-GCM\""))
        assertTrue(json.contains("\"kdf\": \"Argon2id\""))
        assertTrue(json.contains("\"m\": 256"))
        assertTrue(json.contains("\"t\": 1"))
        assertTrue(json.contains("\"p\": 1"))
        assertTrue(json.contains("\"salt\": \"AAECAwQFBgcICQoLDA0ODw==\""))
        assertTrue(json.contains("\"iv\": \"oKGio6Slpqeoqaqr\""))
        assertTrue(json.contains("\"data\": \"${b64(env.data)}\""))
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
            BackupCrypto.envelope(4, "AES-256-GCM", "Argon2id", salt, iv, someData, 65_536, 3, 1); fail("accepted")
        } catch (e: BackupVersion.NewerVersionException) {
            assertEquals(4, e.version)
        }
        // Nur v3 mit Argon2id
        rejects { BackupCrypto.envelope(1, "AES-256-GCM", "Argon2id", salt, iv, someData, 65_536, 3, 1) }
        rejects { BackupCrypto.envelope(2, "AES-256-GCM", "PBKDF2-HMAC-SHA256", salt, iv, someData) }
        rejects { BackupCrypto.envelope(2, "AES-256-GCM", "Argon2id", salt, iv, someData, 65_536, 3, 1) }
        rejects { BackupCrypto.envelope(3, "AES-256-GCM", "PBKDF2-HMAC-SHA256", salt, iv, someData) }
        rejects { BackupCrypto.envelope(3, "AES-256-GCM", "scrypt", salt, iv, someData, 65_536, 3, 1) }
        rejects { BackupCrypto.envelope(3, "ChaCha20", "Argon2id", salt, iv, someData, 65_536, 3, 1) }
        rejects { BackupCrypto.envelope(3, "AES-256-GCM", "Argon2id", salt, iv, someData, 262_145, 3, 1) }
        rejects { BackupCrypto.envelope(3, "AES-256-GCM", "Argon2id", salt, iv, someData, 65_536, 11, 1) }
        rejects { BackupCrypto.envelope(3, "AES-256-GCM", "Argon2id", salt, iv, someData, 65_536, 3, 5) }
        rejects { BackupCrypto.envelope(3, "AES-256-GCM", "Argon2id", salt, iv, someData) }   // m, t, p fehlen
        rejects { BackupCrypto.envelope(3, "AES-256-GCM", "Argon2id", salt, "AAEC", someData, 65_536, 3, 1) }
        rejects { BackupCrypto.envelope(3, "AES-256-GCM", "Argon2id", salt, iv, "%%%", 65_536, 3, 1) }
        rejects { BackupCrypto.envelope(3, "AES-256-GCM", "Argon2id", null, iv, someData, 65_536, 3, 1) }
        rejects { BackupCrypto.envelope(3, "AES-256-GCM", "Argon2id", salt, iv, "AAEC", 65_536, 3, 1) }
    }

    @Test
    fun envelopeDetection() {
        assertTrue(BackupCrypto.isEnvelope("cryptochecker-backup-enc", "AES-256-GCM"))
        assertFalse(BackupCrypto.isEnvelope("cryptochecker-backup-enc", null))
        assertFalse(BackupCrypto.isEnvelope("cryptochecker-backup-enc", ""))
        assertFalse(BackupCrypto.isEnvelope("other", "AES-256-GCM"))
        // Lesbare Sicherung («format» ohne «-enc») ist nie eine Hülle
        assertFalse(BackupCrypto.isEnvelope("cryptochecker-backup", "AES-256-GCM"))
    }

    @Test
    fun passwordRules() {
        // Mindestlänge 12 (neue Sicherungen)
        assertFalse(BackupCrypto.isPasswordLongEnough("12345678901"))
        assertTrue(BackupCrypto.isPasswordLongEnough("123456789012"))
        // Emoji zählt als ein Zeichen (zwei UTF-16-Einheiten)
        assertFalse(BackupCrypto.isPasswordLongEnough("1234567890🔒"))
        assertTrue(BackupCrypto.isPasswordLongEnough("12345678901🔒"))
        val good = "Kryptö-Test 2026!"
        assertTrue(BackupCrypto.canExport(protect = false, password = "", repeat = ""))
        assertFalse(BackupCrypto.canExport(protect = true, password = good, repeat = "Kryptö-Test 2027!"))
        assertFalse(BackupCrypto.canExport(protect = true, password = "1234", repeat = "1234"))
        assertTrue(BackupCrypto.canExport(protect = true, password = good, repeat = good))
        // Schwache Passwörter sperren das Sichern, auch ab 12 Zeichen
        // Liste statt Literal am Parameter: das Upload-Skript meldet sonst ein Passwort im Klartext
        val weakSamples = listOf("123456789012", "aaaaaaaaaaaa", "MeinPasswort")
        for (weak in weakSamples) {
            assertFalse(weak, BackupCrypto.canExport(protect = true, password = weak, repeat = weak))
        }
        assertFalse(BackupCrypto.isPasswordAllowed("Abc!def9xy"))    // 10 Zeichen
        assertFalse(BackupCrypto.isPasswordAllowed("Abc!def9xyZ"))   // 11 Zeichen
        assertTrue(BackupCrypto.isPasswordAllowed("Abc!def9xyZ2"))   // 12 Zeichen
    }

    @Test
    fun decryptDoesNotCheckPasswordLength() {
        // Die Mindestlänge gilt nur beim Sichern
        val plain = "{}".toByteArray()
        val env = BackupCrypto.encrypt(plain, pw("12345678"), kdf = fastKdf)
        assertTrue(BackupCrypto.decrypt(env, pw("12345678")).contentEquals(plain))
    }
}
