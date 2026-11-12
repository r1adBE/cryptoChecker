package com.cryptochecker.app.data.remote

import com.cryptochecker.app.domain.mirror.MirrorManifest
import com.cryptochecker.app.domain.mirror.MirrorRevocations
import com.cryptochecker.app.parity.ParityJson
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sperre über das Manifest im Ablauf: Ein vom Reserve-Schlüssel signiertes Manifest sperrt den aktiven
 * Schlüssel; danach nimmt auch eine neue Prüfung (neuer Start, gleiche Sperrliste) kein nur damit signiertes
 * Manifest mehr an.
 * Test-Schlüssel aus `mirror_signature.json` (wie iOS `MirrorRevocationTests.swift`).
 */
class MirrorTrustRevocationTest {

    private val data = ParityJson.load("mirror_signature.json")
    private val now = (data["now"] as Number).toLong()

    @Suppress("UNCHECKED_CAST")
    private val keys = (data["revocationKeys"] as Map<String, String>)
        .let { listOf(it.getValue("a"), it.getValue("b")) }

    @Suppress("UNCHECKED_CAST")
    private fun case(name: String) =
        (data["revocation"] as List<Any?>).map { it as Map<String, Any?> }.first { it["name"] == name }

    private val okText = (data["files"] as List<*>).map { it as Map<*, *> }
        .first { it["name"] == "listed and same bytes" }["text"] as String

    private fun loader(c: Map<String, Any?>): MirrorLoader = { url, _ ->
        when (url) {
            MirrorManifest.URL -> c["manifest"] as String
            MirrorManifest.SIGNATURE_URL -> c["signature"] as String
            else -> null
        }
    }

    @Test
    fun revocationIsRememberedAcrossChecks() = runBlocking {
        val store = MirrorRevocations(null)
        val byActive = case("same manifest without stored revocation: ok")
        val byReserve = case("reserve key revokes the active key: ok, active key revoked from now on")

        // Vorher: mit dem aktiven Schlüssel signiert = gültig
        assertTrue(MirrorTrust(keys, { now }, { store }).verified("fng.txt", okText, loader(byActive)))
        // Reserve-Schlüssel sperrt den aktiven: gültig, Sperre gemerkt
        assertTrue(MirrorTrust(keys, { now }, { store }).verified("fng.txt", okText, loader(byReserve)))
        assertEquals(setOf(MirrorManifest.keyId(keys[0])), store.current())
        // Danach: nur mit dem gesperrten Schlüssel signiert = abgelehnt
        val after = MirrorTrust(keys, { now }, { store })
        assertFalse(after.verified("fng.txt", okText, loader(byActive)))
        assertEquals(MirrorManifest.Status.INVALID, after.lastStatus)
    }

    /** Gegenseitige Sperre: A sperrt B, danach sperrt B (schon gesperrt) A → beide gesperrt, Spiegel aus. */
    @Test
    fun mutualRevocationTurnsMirrorOff() = runBlocking {
        val store = MirrorRevocations(null)
        val byReserve = case("reserve key revokes the active key: ok, active key revoked from now on")
        val activeRevokesReserve = case("active key revokes the reserve key")
        // Aktiver Schlüssel sperrt den Reserve-Schlüssel (gültig)
        assertTrue(MirrorTrust(keys, { now }, { store }).verified("fng.txt", okText, loader(activeRevokesReserve)))
        assertEquals(setOf(MirrorManifest.keyId(keys[1])), store.current())
        // Gesperrter Reserve-Schlüssel sperrt den aktiven: abgelehnt, aber beide gesperrt
        assertFalse(MirrorTrust(keys, { now }, { store }).verified("fng.txt", okText, loader(byReserve)))
        assertEquals(keys.map { MirrorManifest.keyId(it) }.toSet(), store.current())
        // Danach nimmt die App nichts mehr an
        assertFalse(MirrorTrust(keys, { now }, { store }).verified("fng.txt", okText, loader(activeRevokesReserve)))
        assertFalse(MirrorTrust(keys, { now }, { store }).verified("fng.txt", okText, loader(byReserve)))
    }

    @Test
    fun selfRevocationChangesNothing() = runBlocking {
        val store = MirrorRevocations(null)
        val trust = MirrorTrust(keys, { now }, { store })
        assertTrue(trust.verified("fng.txt", okText, loader(case("self-revocation ignored"))))
        assertTrue(store.current().isEmpty())
    }
}
