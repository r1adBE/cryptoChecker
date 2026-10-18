package com.cryptochecker.app.parity

import com.cryptochecker.app.data.Argon2id
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Argon2id mit zufälligen Parametern (Passwort-, Salt-, Taglänge, m ≤ 1 MiB, t, p, teils Secret/AD) aus
 * `argon2_random.json`, gerechnet mit Python `cryptography` (`tools/gen_bip32_vectors.py`; iOS: `Argon2RandomTests.swift`).
 * Ergänzt den einen RFC-Prüfwert in `Argon2idTest` um Spuren, Taglängen über 64 Bytes und ungerade Speichergrössen.
 */
class Argon2RandomParityTest {

    private fun unhex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }

    @Test
    fun zufaelligeParameterWieDieReferenz() {
        val cases = ParityJson.load("argon2_random.json")["cases"].list().map { it.obj() }
        assertTrue(cases.size >= 20)
        cases.forEachIndexed { n, c ->
            val tag = Argon2id.hash(
                password = unhex(c["password"] as String),
                salt = unhex(c["salt"] as String),
                memoryKiB = c["m"].num()!!.toInt(),
                iterations = c["t"].num()!!.toInt(),
                parallelism = c["p"].num()!!.toInt(),
                tagLength = c["tagLength"].num()!!.toInt(),
                secret = unhex(c["secret"] as String),
                ad = unhex(c["ad"] as String),
            )
            assertEquals("Fall $n (m=${c["m"]}, t=${c["t"]}, p=${c["p"]})", c["tag"], hex(tag))
        }
    }
}
