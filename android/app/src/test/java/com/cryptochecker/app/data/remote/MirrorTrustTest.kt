package com.cryptochecker.app.data.remote

import com.cryptochecker.app.domain.mirror.MirrorManifest
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test

/** Spiegel ohne gültiges Manifest wird verworfen; ein Fehlschlag wird gemerkt (nicht jede Anfrage lädt neu). */
class MirrorTrustTest {

    @Before
    fun reset() = MirrorTrust.resetForTest()

    @After
    fun resetAfter() = MirrorTrust.resetForTest()

    @Test
    fun missingManifestRejectsAndIsRemembered() = runBlocking {
        val asked = mutableListOf<String>()
        val loader: MirrorLoader = { url, _ -> asked += url; null }
        assertFalse(MirrorTrust.verified("fng.txt", "CCDM1 1\n{}", loader))
        assertFalse(MirrorTrust.verified("onchain.txt", "CCDM1 1\n{}", loader))
        // Ohne Manifest wird die Signatur gar nicht erst geholt, und erst nach der Wartezeit wieder versucht
        assertEquals(listOf(MirrorManifest.URL), asked)
        assertEquals(MirrorManifest.Status.MISSING, MirrorTrust.lastStatus)
    }

    @Test
    fun invalidSignatureRejects() = runBlocking {
        val manifest = """{"created":${System.currentTimeMillis()},"files":{"fng.txt":"${MirrorManifest.sha256Hex("x")}"},"v":1}"""
        val loader: MirrorLoader = { url, max ->
            when (url) {
                MirrorManifest.URL -> manifest.also { assertEquals(MirrorManifest.MAX_BYTES, max) }
                MirrorManifest.SIGNATURE_URL -> "MEUCIQDf"
                else -> null
            }
        }
        assertFalse(MirrorTrust.verified("fng.txt", "x", loader))
        assertEquals(MirrorManifest.Status.INVALID, MirrorTrust.lastStatus)
    }
}
