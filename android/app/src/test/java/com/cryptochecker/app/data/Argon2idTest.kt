package com.cryptochecker.app.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Test

/** Argon2id und BLAKE2b gegen die Prüfwerte der RFCs (gleiche Fälle wie `Argon2idTests.swift`). */
class Argon2idTest {

    private fun hex(value: String): ByteArray = ByteArray(value.length / 2) { value.substring(2 * it, 2 * it + 2).toInt(16).toByte() }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    @Test
    fun rfc9106Argon2idVector() {
        // RFC 9106 Abschnitt 5.3: Passwort 32 × 01, Salt 16 × 02, Secret 8 × 03, AD 12 × 04, t = 3, m = 32, p = 4
        val tag = Argon2id.hash(
            password = ByteArray(32) { 1 },
            salt = ByteArray(16) { 2 },
            memoryKiB = 32,
            iterations = 3,
            parallelism = 4,
            tagLength = 32,
            secret = ByteArray(8) { 3 },
            ad = ByteArray(12) { 4 },
        )
        assertEquals("0d640df58d78766c08c037a34a8b53c9d01ef0452d75b65eb52520e96b01e659", hex(tag))
    }

    @Test
    fun blake2bVectors() {
        // RFC 7693 Anhang A («abc») und leere Eingabe mit 32 Bytes (wie Python hashlib.blake2b)
        assertEquals(
            "ba80a53f981c4d0d6a2797b69f12f6e94c212f14685ac4b74b12bb6fdbffa2d1" +
                "7d87c5392aab792dc252d5de4533cc9518d38aa8dbf1925ab92386edd4009923",
            hex(Blake2b(64).apply { update("abc".toByteArray(Charsets.US_ASCII)) }.digest()),
        )
        assertEquals(
            "0e5751c026e543b2e8ab2eb06099daa1d1e5df47778f7787faab45cdf12fe3a8",
            hex(Blake2b(32).digest()),
        )
        // Genau ein Block (128 Bytes) und mehr als zwei Blöcke: Endmarke am richtigen Block
        assertEquals(
            "9bcba0ef17a9045ce7f060d2ec5f3616a53d2678dc6462cce9487f34b652c92b" +
                "90ebb8bfedf1bdfd4c94ccad95747ff767399ee51b21a530146c8ca283747890",
            hex(Blake2b(64).apply { update(ByteArray(128) { 1 }) }.digest()),
        )
        assertEquals(
            "d9cf5983dc6b34c0fa1f0226926855ad3eccd2bcdcd8f8053b9a80664d33b5af" +
                "cc32fd21c70ea14f4ef50ca97c3203c4d1803159f0e01bb6cb1d1c83db52b63c",
            hex(Blake2b(64).apply { update(ByteArray(300) { it.toByte() }) }.digest()),
        )
    }

    @Test
    fun variableLengthHash() {
        // H' (gleiche Werte wie Argon2idTests.swift, mit Python hashlib nachgerechnet)
        val abc = "abc".toByteArray(Charsets.US_ASCII)
        assertEquals("3e6c02ac", hex(Argon2id.hashLong(abc, 4)))
        assertEquals(
            "f32577a3172f56657d531faaa43077bb8c9726ada7bb04dd337ec5a65454abff" +
                "241ad6b87a72440e5127c6f9caa70327f2a699096e52d163eb52d9cd99620593",
            hex(Argon2id.hashLong(abc, 64)),
        )
        assertEquals(
            "4c9ba23bcafae5e571a5d41673bb8084a4a1de2688416ed390f669d33d364f3d" +
                "4d9bfa7fe762680c6b2362711c4ce5b2c60ddcd14c1277ec1369c79f44c28966" +
                "98a2b0773a3ce2e410532fa7c72f0bb61ccca0c24c362f337555cbf2998f2d36" +
                "01be70d1",
            hex(Argon2id.hashLong(abc, 100)),
        )
    }

    @Test
    fun longTagsUseVariableLengthHash() {
        // Tags über 64 Bytes laufen über H' in 32-Byte-Stücken; Präfix darf nicht dem kürzeren Tag gleichen
        val pw = ByteArray(8) { 7 }
        val salt = ByteArray(16) { 9 }
        val short = Argon2id.hash(pw, salt, 64, 1, 1, 64)
        val long = Argon2id.hash(pw, salt, 64, 1, 1, 100)
        assertEquals(100, long.size)
        assertFalse(short.contentEquals(long.copyOf(64)))
        assertArrayEquals(long, Argon2id.hash(pw, salt, 64, 1, 1, 100))
    }

    @Test
    fun invalidParametersAreRejected() {
        fun rejects(block: () -> Unit) = try {
            block(); fail("accepted")
        } catch (_: IllegalArgumentException) {
        }
        val pw = ByteArray(4)
        rejects { Argon2id.hash(pw, ByteArray(16), 15, 1, 2, 32) } // m < 8 · p
        rejects { Argon2id.hash(pw, ByteArray(16), 64, 0, 1, 32) } // t = 0
        rejects { Argon2id.hash(pw, ByteArray(16), 64, 1, 0, 32) } // p = 0
        rejects { Argon2id.hash(pw, ByteArray(7), 64, 1, 1, 32) } // Salt < 8 Bytes
    }
}
