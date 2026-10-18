@file:Suppress("UNCHECKED_CAST")

package com.cryptochecker.app.parity

import com.cryptochecker.app.domain.portfolio.AddressChain
import com.cryptochecker.app.domain.portfolio.AddressValidation
import com.cryptochecker.app.domain.portfolio.Bip32
import com.cryptochecker.app.domain.portfolio.WatchedAddress
import com.cryptochecker.app.domain.portfolio.WatchedAddressRules
import com.cryptochecker.app.domain.portfolio.XpubDeriver
import com.cryptochecker.app.domain.portfolio.XpubScan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Taproot: BIP86-Ableitung, Deskriptoren (BIP380/389) und Bech32m (BIP350) aus `taproot.json` (iOS: `TaprootTests.swift`). */
class TaprootParityTest {

    private val data = ParityJson.load("taproot.json")

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun unhex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    @Test
    fun bip86Addresses() {
        val b = data["bip86"].obj()
        val normalized = b["normalized"] as String
        val key = Bip32.parse(normalized)!!
        assertTrue(key.taproot)
        assertEquals(Bip32.Script.P2TR, key.script)
        assertFalse(key.detectsScript)
        // Gleicher Schlüssel wie der einfache xpub, nur der Adresstyp unterscheidet sich
        assertEquals(b["xpub"], Bip32.serialize(key))
        assertFalse(Bip32.parse(b["xpub"] as String)!!.taproot)
        val deriver = XpubDeriver(normalized)
        assertEquals(Bip32.Script.P2TR, deriver.script)
        b["addresses"].list().map { it.obj() }.forEach { c ->
            val chain = c["chain"].num()!!.toInt()
            val index = c["index"].num()!!.toInt()
            val leaf = Bip32.child(Bip32.child(key, chain.toLong())!!, index.toLong())!!
            assertEquals("pubkey $chain/$index", c["pubkey"], hex(leaf.publicKey))
            assertEquals("output $chain/$index", c["outputKey"], hex(Bip32.taprootOutputKey(leaf.publicKey)!!))
            assertEquals("address $chain/$index", c["address"], Bip32.address(Bip32.Script.P2TR, leaf.publicKey))
            assertEquals(c["address"], deriver.address(chain, index))
            val check = AddressValidation.check(c["address"] as String)
            assertEquals(AddressValidation.Kind.P2TR, check.kind)
            assertEquals(AddressChain.BTC, check.chain)
        }
        // Ein einfacher xpub bleibt bei der bisherigen Suche (kein Taproot ohne Deskriptor)
        assertEquals(
            listOf(Bip32.Script.P2PKH, Bip32.Script.P2SH_P2WPKH, Bip32.Script.P2WPKH),
            XpubScan.scripts(Bip32.parse(b["xpub"] as String)!!, null),
        )
        assertEquals(listOf(Bip32.Script.P2TR), XpubScan.scripts(key, null))
        assertNull(XpubScan.pickScript(key, listOf(Bip32.Script.P2TR to 3)))
        // Privater Schlüssel: nie, auch nicht im Deskriptor
        assertEquals(AddressValidation.Problem.SECRET, AddressValidation.check(b["xprv"] as String).problem)
        assertNull(Bip32.parse("tr(" + b["xprv"] + "/<0;1>/*)"))
    }

    @Test
    fun taggedHash() {
        val t = data["taggedHash"].obj()
        assertEquals(t["hash"], hex(Bip32.taggedHash(t["tag"] as String, unhex(t["data"] as String))))
    }

    @Test
    fun descriptors() {
        data["descriptors"].list().map { it.obj() }.forEach { c ->
            val check = AddressValidation.check(c["input"] as String)
            val note = c["note"] as String
            if (c["normalized"] != null) {
                assertNull(note, check.problem)
                assertEquals(note, c["normalized"], check.normalized)
                assertEquals(note, AddressValidation.Kind.TR_XPUB, check.kind)
                assertEquals(note, AddressChain.BTC, check.chain)
                // Normalform prüft sich wieder zu sich selbst (Sicherung zurückspielen)
                assertEquals(note, check.normalized, AddressValidation.check(check.normalized).normalized)
                assertTrue(note, AddressValidation.isExtendedKey(check.normalized!!))
                assertEquals(note, AddressValidation.Problem.WRONG_CHAIN, AddressValidation.check(c["input"] as String, AddressChain.ETH).problem)
            } else {
                assertEquals(note, c["problem"], check.problem?.name)
            }
        }
    }

    @Test
    fun descriptorChecksums() {
        data["checksums"].list().map { it.obj() }.forEach { c ->
            assertEquals(c["descriptor"] as String, c["checksum"], AddressValidation.descriptorChecksum(c["descriptor"] as String))
        }
    }

    @Test
    fun bech32m() {
        val b = data["bech32m"].obj()
        b["encode"].list().map { it.obj() }.forEach { c ->
            assertEquals(
                c["address"],
                Bip32.segwitAddress(c["hrp"] as String, c["version"].num()!!.toInt(), unhex(c["program"] as String)),
            )
        }
        b["checks"].list().map { it.obj() }.forEach { c ->
            val check = AddressValidation.check(c["input"] as String)
            assertEquals("problem ${c["input"]}", c["problem"], check.problem?.name)
            if (c["problem"] == null) {
                assertEquals(c["kind"], check.kind?.name)
                assertEquals(c["normalized"], check.normalized)
            }
        }
    }

    @Test
    fun watchedAccount() {
        val b = data["bip86"].obj()
        val normalized = b["normalized"] as String
        assertEquals(b["short"], WatchedAddressRules.shortAddress(normalized))
        assertEquals(normalized, Bip32.taprootAccount(normalized)?.let { Bip32.taprootDescriptor(it) })
        assertNull(Bip32.taprootAccount(b["xpub"] as String))
        val a = WatchedAddress(chain = AddressChain.BTC, address = normalized)
        assertTrue(a.isXpub)
    }
}
