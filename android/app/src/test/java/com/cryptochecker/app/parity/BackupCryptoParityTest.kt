package com.cryptochecker.app.parity

import com.cryptochecker.app.data.Argon2id
import com.cryptochecker.app.data.BackupCrypto
import com.cryptochecker.app.data.BackupVersion
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.util.Base64

/**
 * Verschlüsselte Sicherungen v3 (Argon2id + AAD) und v2 (PBKDF2) mit festen Prüfwerten
 * (`testdata/parity/backup_crypto.json`, mit Python `cryptography` nachgerechnet); Gegenstück:
 * `BackupCryptoParityTests.swift`. Gleiche Bytes auf beiden Seiten = Sicherungen zwischen Android und iPhone austauschbar.
 */
class BackupCryptoParityTest {

    private val root = JSONObject(File(ParityJson.dir(), "backup_crypto.json").readText(Charsets.UTF_8))
    private val plain = root.getString("plain")

    private fun password(): CharArray = root.getString("password").toCharArray()

    private fun b64(value: String): ByteArray = Base64.getDecoder().decode(value)

    private fun hex(value: String): ByteArray = ByteArray(value.length / 2) { value.substring(2 * it, 2 * it + 2).toInt(16).toByte() }

    /** Wie `BackupManager.read`: Felder mit optInt/optString lesen. */
    private fun envelope(f: JSONObject): BackupCrypto.Envelope = BackupCrypto.envelope(
        version = f.optInt("v"),
        enc = f.optString("enc"),
        kdf = f.optString("kdf"),
        iterations = f.optInt("iter"),
        salt = f.optString("salt"),
        iv = f.optString("iv"),
        data = f.optString("data"),
        memoryKiB = f.optInt("m"),
        time = f.optInt("t"),
        parallelism = f.optInt("p"),
    )

    @Test
    fun v3FilesDecryptAndHeaderIsCanonical() {
        val files = root.getJSONArray("v3")
        assertEquals(3, files.length())
        for (i in 0 until files.length()) {
            val f = files.getJSONObject(i)
            val env = envelope(f)
            assertEquals(3, env.version)
            assertEquals(f.getString("header"), BackupCrypto.header(env).toString(Charsets.UTF_8))
            val pw = password()
            assertEquals(plain, BackupCrypto.decrypt(env, pw).toString(Charsets.UTF_8))
            pw.fill('\u0000')
        }
    }

    @Test
    fun v3EncryptionMatchesFixture() {
        val files = root.getJSONArray("v3")
        for (i in 0 until files.length()) {
            val f = files.getJSONObject(i)
            val kdf = BackupCrypto.Kdf.Argon2(f.getInt("m"), f.getInt("t"), f.getInt("p"))
            val env = BackupCrypto.encrypt(plain.toByteArray(Charsets.UTF_8), password(), b64(f.getString("salt")), b64(f.getString("iv")), kdf)
            assertEquals(f.getString("data"), Base64.getEncoder().encodeToString(env.data))
            // Geschriebenes JSON trägt dieselben Felder wie die Prüfdatei
            val written = JSONObject(BackupCrypto.toJson(env))
            for (key in listOf("format", "v", "enc", "kdf", "m", "t", "p", "salt", "iv", "data")) {
                assertEquals(key, f.get(key), written.get(key))
            }
        }
    }

    @Test
    fun newBackupsMatchTheProductionVector() {
        // Erster v3-Fall = Parameter neuer Sicherungen (64 MiB, t = 3, p = 1)
        val f = root.getJSONArray("v3").getJSONObject(0)
        assertEquals(BackupCrypto.DEFAULT_KDF, BackupCrypto.Kdf.Argon2(f.getInt("m"), f.getInt("t"), f.getInt("p")))
        val env = BackupCrypto.encrypt(plain.toByteArray(Charsets.UTF_8), password(), b64(f.getString("salt")), b64(f.getString("iv")))
        assertEquals(f.getString("data"), Base64.getEncoder().encodeToString(env.data))
    }

    @Test
    fun v2FilesStayReadable() {
        val files = root.getJSONArray("v2")
        assertEquals(2, files.length())
        for (i in 0 until files.length()) {
            val f = files.getJSONObject(i)
            val env = envelope(f)
            assertEquals(2, env.version)
            assertEquals(plain, BackupCrypto.decrypt(env, password()).toString(Charsets.UTF_8))
        }
    }

    @Test
    fun manipulatedFilesAreRejected() {
        val cases = root.getJSONArray("reject")
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val name = c.getString("name")
            val result = try {
                BackupCrypto.decrypt(envelope(c.getJSONObject("file")), password())
                "accepted"
            } catch (_: BackupCrypto.WrongPasswordException) {
                "wrongPassword"
            } catch (_: BackupCrypto.UnsupportedException) {
                "unsupported"
            } catch (_: BackupVersion.NewerVersionException) {
                "newer"
            }
            assertEquals(name, c.getString("result"), result)
        }
    }

    @Test
    fun argon2idMatchesRfc9106() {
        val v = root.getJSONObject("rfc9106")
        val tag = Argon2id.hash(
            password = hex(v.getString("password")),
            salt = hex(v.getString("salt")),
            memoryKiB = v.getInt("m"),
            iterations = v.getInt("t"),
            parallelism = v.getInt("p"),
            tagLength = v.getString("tag").length / 2,
            secret = hex(v.getString("secret")),
            ad = hex(v.getString("ad")),
        )
        assertArrayEquals(hex(v.getString("tag")), tag)
    }

    @Test
    fun fixtureIsComplete() {
        if (root.getJSONArray("reject").length() < 20) fail("reject cases missing")
    }
}
