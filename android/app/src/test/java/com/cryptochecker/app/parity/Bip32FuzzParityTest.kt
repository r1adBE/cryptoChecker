package com.cryptochecker.app.parity

import com.cryptochecker.app.domain.portfolio.Bip32
import com.cryptochecker.app.domain.portfolio.XpubDeriver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Referenz-Fuzz der xpub-Ableitung aus `bip32_fuzz.json` (erzeugt von `tools/gen_bip32_vectors.py`, Python-Referenz
 * mit OpenSSL gegengeprüft; iOS: `Bip32FuzzTests.swift`). Abweichungen werden gesammelt gemeldet, damit ein Fehler in
 * der Kurvenrechnung nicht hinter dem ersten Fall verschwindet.
 */
class Bip32FuzzParityTest {

    private val data = ParityJson.load("bip32_fuzz.json")

    private fun hex(bytes: ByteArray?) = bytes?.joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun unhex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private val scripts = listOf(Bip32.Script.P2PKH, Bip32.Script.P2SH_P2WPKH, Bip32.Script.P2WPKH, Bip32.Script.P2TR)

    private fun assertNoMismatch(what: String, mismatches: List<String>) =
        assertTrue("$what: ${mismatches.size} Abweichungen, z. B.\n" + mismatches.take(5).joinToString("\n"), mismatches.isEmpty())

    @Test
    fun zufaelligeSchluesselLeitenWieDieReferenzAb() {
        val cases = data["cases"].list().map { it.obj() }
        assertTrue(cases.size >= 1000)
        val mismatches = mutableListOf<String>()
        cases.forEachIndexed { n, c ->
            val xpub = c["xpub"] as String
            val chain = c["chain"].num()!!.toLong()
            val index = c["index"].num()!!.toLong()
            val root = Bip32.parse(xpub)
            if (root == null) {
                mismatches += "#$n parse null"
                return@forEachIndexed
            }
            if (root.script.name != c["script"]) mismatches += "#$n script ${root.script}"
            if (Bip32.serialize(root) != xpub) mismatches += "#$n serialize"
            val leaf = Bip32.child(root, chain)?.let { Bip32.child(it, index) }
            if (leaf == null) {
                mismatches += "#$n child null"
                return@forEachIndexed
            }
            if (hex(leaf.publicKey) != c["pubkey"]) mismatches += "#$n pubkey $chain/$index"
            if (Bip32.serialize(leaf) != c["leaf"]) mismatches += "#$n leaf $chain/$index"
            val expected = c["addresses"].list()
            scripts.forEachIndexed { i, script ->
                val got = Bip32.address(script, leaf.publicKey)
                if (got != expected[i]) mismatches += "#$n $script $chain/$index: $got ≠ ${expected[i]}"
            }
            // x-only: der Tweak hängt nicht von der Parität des Schlüssels ab
            val x = leaf.publicKey.copyOfRange(1, 33)
            if (!Bip32.taprootOutputKey(byteArrayOf(2) + x).contentEquals(Bip32.taprootOutputKey(byteArrayOf(3) + x))) {
                mismatches += "#$n tweak parity"
            }
            // Ableiter (Kettenschlüssel zwischengespeichert) stichprobenweise, sonst doppelte Kurvenrechnung
            if (n % 10 == 0) {
                val got = XpubDeriver(xpub).address(chain.toInt(), index.toInt())
                if (got != expected[scripts.indexOf(root.script)]) mismatches += "#$n deriver $got"
            }
        }
        assertNoMismatch("Ableitung", mismatches)
    }

    @Test
    fun punktrechnungAnRandfaellen() {
        val mismatches = mutableListOf<String>()
        data["pointOps"].list().map { it.obj() }.forEach { op ->
            val got = hex(Bip32.multiplyAddG(unhex(op["k"] as String), unhex(op["q"] as String)))
            if (got != op["result"]) mismatches += "${op["note"]}: $got ≠ ${op["result"]}"
        }
        assertNoMismatch("k·G + Q", mismatches)
    }

    @Test
    fun gebastelteSchluesselWerdenGeprueft() {
        data["keyChecks"].list().map { it.obj() }.forEach { c ->
            val note = c["note"] as String
            val xpub = c["xpub"] as String
            assertEquals(note, c["problem"], Bip32.check(xpub)?.name)
            val key = Bip32.parse(xpub)
            if (c["problem"] == null) {
                assertEquals(note, xpub, Bip32.serialize(key!!))
                assertEquals(note, c["child0"], hex(Bip32.child(key, 0)!!.publicKey))
            } else {
                assertNull(note, key)
            }
        }
    }

    @Test
    fun indexGrenzen() {
        val key = Bip32.parse(data["cases"].list()[0].obj()["xpub"] as String)!!
        data["indexLimits"].list().map { it.obj() }.forEach { c ->
            val index = c["index"].num()!!.toLong()
            assertEquals("Index $index", c["valid"], Bip32.child(key, index) != null)
        }
    }
}
