package com.cryptochecker.app.widget

import com.cryptochecker.app.widget.PortfolioSnapshotSeal.ReadPlan
import com.cryptochecker.app.widget.PortfolioSnapshotSeal.WritePlan
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/** Versiegelte Widget-Aufnahme: Behälterformat, Manipulation, Lese-/Schreibregeln. */
class PortfolioSnapshotSealTest {

    private fun newKey(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    private val payload = """{"total":83385.24,"currency":"CHF","positions":"[{\"s\":\"BTC\",\"q\":\"1.3\"}]"}"""

    @Test
    fun roundTrip() {
        val key = newKey()
        assertEquals(payload, PortfolioSnapshotSeal.open(key, PortfolioSnapshotSeal.seal(key, payload)))
        assertEquals("", PortfolioSnapshotSeal.open(key, PortfolioSnapshotSeal.seal(key, "")))
    }

    @Test
    fun containerLayout() {
        val key = newKey()
        val bytes = Base64.getDecoder().decode(PortfolioSnapshotSeal.seal(key, payload))
        assertArrayEquals("CCWS".toByteArray(Charsets.US_ASCII), bytes.copyOfRange(0, 4))
        assertEquals(PortfolioSnapshotSeal.VERSION, bytes[4])
        assertEquals(5 + PortfolioSnapshotSeal.IV_BYTES + payload.toByteArray().size + PortfolioSnapshotSeal.TAG_BYTES, bytes.size)
        // Kein Klartext im Eintrag
        assertFalse(String(bytes, Charsets.ISO_8859_1).contains("BTC"))
        assertTrue(PortfolioSnapshotSeal.isContainer(bytes))
    }

    @Test
    fun freshIvPerSeal() {
        val key = newKey()
        val a = Base64.getDecoder().decode(PortfolioSnapshotSeal.seal(key, payload))
        val b = Base64.getDecoder().decode(PortfolioSnapshotSeal.seal(key, payload))
        assertNotEquals(a.copyOfRange(5, 17).toList(), b.copyOfRange(5, 17).toList())
    }

    @Test
    fun otherKeyOrTamperingOpensNothing() {
        val key = newKey()
        val sealed = PortfolioSnapshotSeal.seal(key, payload)
        assertNull(PortfolioSnapshotSeal.open(newKey(), sealed))
        val bytes = Base64.getDecoder().decode(sealed)
        fun tampered(index: Int) = Base64.getEncoder().encodeToString(bytes.copyOf().also { it[index] = (it[index] + 1).toByte() })
        assertNull(PortfolioSnapshotSeal.open(key, tampered(bytes.size - 1))) // Tag
        assertNull(PortfolioSnapshotSeal.open(key, tampered(20))) // Chiffrat
        assertNull(PortfolioSnapshotSeal.open(key, tampered(6))) // IV
        assertNull(PortfolioSnapshotSeal.open(key, tampered(4))) // Version (neuere App)
        assertNull(PortfolioSnapshotSeal.open(key, tampered(0))) // Magie
    }

    @Test
    fun foreignOrShortEntriesOpenNothing() {
        val key = newKey()
        assertNull(PortfolioSnapshotSeal.open(key, payload)) // Klartext-JSON, kein Base64
        assertNull(PortfolioSnapshotSeal.open(key, ""))
        val short = Base64.getDecoder().decode(PortfolioSnapshotSeal.seal(key, payload)).copyOfRange(0, 5 + 12 + 15)
        assertFalse(PortfolioSnapshotSeal.isContainer(short))
        assertNull(PortfolioSnapshotSeal.open(key, Base64.getEncoder().encodeToString(short)))
    }

    @Test
    fun readPlan() {
        // Ohne Schlüssel nie etwas anzeigen («App öffnen»)
        assertSame(ReadPlan.Empty, PortfolioSnapshotSeal.planRead("x", keyReady = false))
        assertSame(ReadPlan.Empty, PortfolioSnapshotSeal.planRead(null, keyReady = false))
        val open = PortfolioSnapshotSeal.planRead("x", keyReady = true)
        assertTrue(open is ReadPlan.Open && open.sealed == "x")
        assertSame(ReadPlan.Empty, PortfolioSnapshotSeal.planRead(null, keyReady = true))
    }

    @Test
    fun writePlanNeverPlaintext() {
        assertEquals(WritePlan.SEAL, PortfolioSnapshotSeal.planWrite(keyReady = true))
        assertEquals(WritePlan.KEEP_SEALED, PortfolioSnapshotSeal.planWrite(keyReady = false))
        assertEquals(setOf(WritePlan.SEAL, WritePlan.KEEP_SEALED), WritePlan.entries.toSet())
    }
}
