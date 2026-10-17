package com.cryptochecker.app.domain.watch

import com.cryptochecker.app.domain.watch.TokenIdentity.Match
import com.cryptochecker.app.parity.ParityJson
import com.cryptochecker.app.parity.list
import com.cryptochecker.app.parity.obj
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Gleicher Token? (K-8): gemeinsame Fälle `testdata/parity/token_identity.json` (Gegenstück `Tests/TokenIdentityTests.swift`). */
class TokenIdentityTest {

    private val data = ParityJson.load("token_identity.json")

    private fun identity(raw: Any?): TokenIdentity.Identity? {
        val o = raw?.obj() ?: return null
        return TokenIdentity.Identity(o["name"] as String?, o["contracts"].list().map { it as String }.toSet())
    }

    @Test
    fun normalizeParity() {
        data["normalize"].list().forEach { raw ->
            val c = raw.obj()
            assertEquals(c["name"] as String, c["expected"] as String?, TokenIdentity.normalizeName(c["input"] as String?, c["symbol"] as String))
        }
    }

    @Test
    fun namesParity() {
        data["names"].list().forEach { raw ->
            val c = raw.obj()
            val symbol = c["symbol"] as String
            val expected = Match.valueOf(c["expected"] as String)
            assertEquals(c["name"] as String, expected, TokenIdentity.matchNames(symbol, c["a"] as String?, c["b"] as String?))
            // Symmetrisch
            assertEquals(c["name"] as String + " (b, a)", expected, TokenIdentity.matchNames(symbol, c["b"] as String?, c["a"] as String?))
        }
    }

    @Test
    fun identitiesParity() {
        data["identities"].list().forEach { raw ->
            val c = raw.obj()
            val symbol = c["symbol"] as String
            val expected = Match.valueOf(c["expected"] as String)
            assertEquals(c["name"] as String, expected, TokenIdentity.match(symbol, identity(c["a"]), identity(c["b"])))
            assertEquals(c["name"] as String + " (b, a)", expected, TokenIdentity.match(symbol, identity(c["b"]), identity(c["a"])))
        }
    }

    @Test
    fun acceptParity() {
        data["accept"].list().forEach { raw ->
            val c = raw.obj()
            assertEquals(c.toString(), c["expected"] as Boolean, TokenIdentity.accept(Match.valueOf(c["match"] as String), c["band"] as Boolean))
        }
    }

    @Test
    fun fallbackSeriesParity() {
        data["fallbackSeries"].list().forEach { raw ->
            val c = raw.obj()
            assertEquals(
                c["name"] as String, c["expected"] as Boolean,
                TokenIdentity.fallbackSeriesAllowed(
                    c["symbol"] as String, c["own"] as String?, c["provider"] as String?, c["binance"] as String?,
                    c["providerName"] as String?,
                ),
            )
        }
    }

    @Test
    fun chainSourcesParity() {
        data["chainSources"].list().forEach { raw ->
            val c = raw.obj()
            assertEquals(
                c["name"] as String, c["expected"] as Boolean,
                TokenIdentity.chainSourceAllowed(
                    c["symbol"] as String, c["own"] as String?, c["host"] as String, c["binance"] as String?,
                    c["providerName"] as String?,
                ),
            )
        }
    }

    @Test
    fun consensusParity() {
        data["consensus"].list().forEach { raw ->
            val c = raw.obj()
            val names = c["names"].list().map { it as String? }
            val symbol = c["symbol"] as String
            assertEquals(c["name"] as String, c["expected"] as String?, TokenIdentity.consensus(symbol, names))
            assertEquals(c["name"] as String, c["conflict"] as Boolean, TokenIdentity.conflicting(symbol, names))
        }
    }

    @Test
    fun compatibleOnlyRejectsClearMismatch() {
        assertTrue(TokenIdentity.compatible("CAT", "Simon's Cat", null))
        assertTrue(TokenIdentity.compatible("CAT", "Simon's Cat", "Simons Cat"))
        assertFalse(TokenIdentity.compatible("CAT", "Simon's Cat", "Catcoin"))
    }

    @Test
    fun contractKeyNormalizesEvmOnly() {
        assertEquals("ethereum:0xabc", TokenIdentity.contractKey(" Ethereum ", "0xABC"))
        assertEquals("solana:AbC", TokenIdentity.contractKey("SOLANA", "AbC"))
        assertNull(TokenIdentity.contractKey("", "0x1"))
        assertNull(TokenIdentity.contractKey("bsc", " "))
    }

    @Test
    fun nameIndexRoundTripAndCleaning() {
        val index = TokenIdentity.NameIndex(
            markets = mapOf("GateIo" to TokenIdentity.cleaned(mapOf("cat" to " Simon's Cat ", "X" to "", "Y" to "y".repeat(81)))),
            binance = mapOf("CAT" to "Simon's Cat"),
        )
        assertEquals(mapOf("CAT" to "Simon's Cat"), index.markets["GateIo"])
        val back = TokenIdentity.decode(TokenIdentity.encode(index))
        assertEquals(index, back)
        assertEquals("Simon's Cat", back.pairName("GateIo", " cat"))
        assertEquals("Simon's Cat", back.binanceName("Cat"))
        assertNull(back.pairName("Kraken", "CAT"))
        assertEquals(TokenIdentity.NameIndex(), TokenIdentity.decode("{not json"))
        assertEquals(TokenIdentity.NameIndex(), TokenIdentity.decode(null))
    }
}
