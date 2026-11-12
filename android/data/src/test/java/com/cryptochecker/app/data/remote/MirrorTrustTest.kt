package com.cryptochecker.app.data.remote

import com.cryptochecker.app.domain.mirror.MirrorManifest
import com.cryptochecker.app.parity.ParityJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spiegel ohne gültiges Manifest wird verworfen; ein Fehlschlag wird gemerkt (nicht jede Anfrage lädt neu). Erfolgsweg
 * mit dem Test-Schlüssel aus `mirror_signature.json` (wie iOS `MirrorSignatureTests.swift`).
 */
class MirrorTrustTest {

    private val data = ParityJson.load("mirror_signature.json")

    @Suppress("UNCHECKED_CAST")
    private fun list(key: String) = (data[key] as List<Any?>).map { it as Map<String, Any?> }

    private val testKey = data["publicKey"] as String
    private val fixtureNow = (data["now"] as Number).toLong()
    private val valid = list("verify").first { it["name"] == "valid" }
    private val manifest = valid["manifest"] as String
    private val signature = valid["signature"] as String
    private val okText = list("files").first { it["name"] == "listed and same bytes" }["text"] as String
    private val changedText = list("files").first { it["name"] == "listed but changed" }["text"] as String

    private fun production() = MirrorTrust(MirrorManifest.PUBLIC_KEY, System::currentTimeMillis)

    @Test
    fun missingManifestRejectsAndIsRemembered() = runBlocking {
        val trust = production()
        val asked = mutableListOf<String>()
        val loader: MirrorLoader = { url, _ -> asked += url; null }
        assertFalse(trust.verified("fng.txt", "CCDM1 1\n{}", loader))
        assertFalse(trust.verified("onchain.txt", "CCDM1 1\n{}", loader))
        // Ohne Manifest wird die Signatur gar nicht erst geholt, und erst nach der Wartezeit wieder versucht
        assertEquals(listOf(MirrorManifest.URL), asked)
        assertEquals(MirrorManifest.Status.MISSING, trust.lastStatus)
    }

    @Test
    fun invalidSignatureRejects() = runBlocking {
        val trust = production()
        val manifest = """{"created":${System.currentTimeMillis()},"files":{"fng.txt":"${MirrorManifest.sha256Hex("x")}"},"v":1}"""
        val loader: MirrorLoader = { url, max ->
            when (url) {
                MirrorManifest.URL -> manifest.also { assertEquals(MirrorManifest.MAX_BYTES, max) }
                MirrorManifest.SIGNATURE_URL -> "MEUCIQDf"
                else -> null
            }
        }
        assertFalse(trust.verified("fng.txt", "x", loader))
        assertEquals(MirrorManifest.Status.INVALID, trust.lastStatus)
    }

    @Test
    fun validManifestWithTestKeyAccepts() = runBlocking {
        val trust = MirrorTrust(testKey) { fixtureNow }
        val asked = mutableListOf<String>()
        val loader: MirrorLoader = { url, max ->
            asked += "$url $max"
            when (url) {
                MirrorManifest.URL -> manifest
                MirrorManifest.SIGNATURE_URL -> signature
                else -> null
            }
        }
        assertTrue(trust.verified("fng.txt", okText, loader))
        assertEquals(MirrorManifest.Status.OK, trust.lastStatus)
        // Grenzen wie iOS: Manifest 64 KB, Signatur 1 KB
        assertEquals(listOf("${MirrorManifest.URL} 65536", "${MirrorManifest.SIGNATURE_URL} 1024"), asked)
        // Der eingebaute Schlüssel lehnt dasselbe Manifest ab
        assertFalse(MirrorTrust(MirrorManifest.PUBLIC_KEY) { fixtureNow }.verified("fng.txt", okText, loader))
    }

    @Test
    fun hashMismatchReloadsOnceAfterOneMinute() = runBlocking {
        var now = fixtureNow
        val trust = MirrorTrust(testKey) { now }
        val asked = mutableListOf<String>()
        val loader: MirrorLoader = { url, _ ->
            asked += url
            when (url) {
                MirrorManifest.URL -> manifest
                MirrorManifest.SIGNATURE_URL -> signature
                else -> null
            }
        }
        assertTrue(trust.verified("fng.txt", okText, loader))
        assertEquals(2, asked.size)
        // Abweichender Hash innerhalb einer Minute: kein Neuladen
        now = fixtureNow + 59_000
        assertFalse(trust.verified("fng.txt", changedText, loader))
        assertEquals(2, asked.size)
        // Nach 60 s: genau ein Neuladen (Manifest und Signatur), danach wieder gemerkt
        now = fixtureNow + 60_000
        assertFalse(trust.verified("fng.txt", changedText, loader))
        assertFalse(trust.verified("fng.txt", changedText, loader))
        assertEquals(
            listOf(MirrorManifest.URL, MirrorManifest.SIGNATURE_URL, MirrorManifest.URL, MirrorManifest.SIGNATURE_URL),
            asked,
        )
        assertTrue(trust.verified("fng.txt", okText, loader))
    }

    @Test
    fun oversizedManifestOrSignatureCountsAsMissing() = runBlocking {
        // Lader hält die Grenze ein wie `request(…, maxBytes)` (zu gross → Fehler → null)
        fun loader(manifestText: String, signatureText: String): MirrorLoader = { url, max ->
            val text = when (url) {
                MirrorManifest.URL -> manifestText
                MirrorManifest.SIGNATURE_URL -> signatureText
                else -> ""
            }
            text.takeIf { it.toByteArray(Charsets.UTF_8).size <= max }
        }
        val big = MirrorTrust(testKey) { fixtureNow }
        val padded = manifest + " ".repeat(MirrorManifest.MAX_BYTES.toInt())
        assertFalse(big.verified("fng.txt", okText, loader(padded, signature)))
        assertEquals(MirrorManifest.Status.MISSING, big.lastStatus)
        val bigSignature = MirrorTrust(testKey) { fixtureNow }
        val longSignature = signature + "\n".repeat(MirrorManifest.SIGNATURE_MAX_BYTES.toInt())
        assertFalse(bigSignature.verified("fng.txt", okText, loader(manifest, longSignature)))
        assertEquals(MirrorManifest.Status.MISSING, bigSignature.lastStatus)
    }

    // ---- Abbruch eines Aufrufers

    @Test
    fun cancelledCallerDoesNotRememberMissingForWaitingCaller() = cancelWhileLoading(swallowCancel = false)

    /** Auch ein Lader, der den Abbruch schluckt und null liefert (Netz meldet «abgebrochen» als Fehler). */
    @Test
    fun cancelledCallerWithSwallowingLoaderDoesNotRememberMissing() = cancelWhileLoading(swallowCancel = true)

    private fun cancelWhileLoading(swallowCancel: Boolean) = runBlocking {
        val trust = MirrorTrust(testKey) { fixtureNow }
        val firstLoading = CompletableDeferred<Unit>()
        val never = CompletableDeferred<String>()
        var manifestCalls = 0
        val loader: MirrorLoader = { url, _ ->
            when (url) {
                MirrorManifest.URL -> {
                    manifestCalls++
                    if (manifestCalls == 1) {
                        firstLoading.complete(Unit)
                        try {
                            never.await()
                        } catch (cancel: CancellationException) {
                            if (!swallowCancel) throw cancel
                            null
                        }
                    } else {
                        manifest
                    }
                }
                MirrorManifest.SIGNATURE_URL -> signature
                else -> null
            }
        }
        val first = async { trust.verified("fng.txt", okText, loader) }
        firstLoading.await()
        // Zweiter Aufrufer wartet auf die Sperre, während der erste lädt
        val second = async { trust.verified("fng.txt", okText, loader) }
        yield()
        first.cancel()
        assertTrue(second.await())
        assertTrue(first.isCancelled)
        // Der zweite hat selbst geladen statt ein gemerktes «fehlt» zu übernehmen
        assertEquals(2, manifestCalls)
        assertEquals(MirrorManifest.Status.OK, trust.lastStatus)
    }
}
