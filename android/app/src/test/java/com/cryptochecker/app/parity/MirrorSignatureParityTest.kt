package com.cryptochecker.app.parity

import com.cryptochecker.app.domain.mirror.DataMirror
import com.cryptochecker.app.domain.mirror.MirrorManifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Gemeinsame Testfälle `mirror_signature.json` (iOS: `MirrorSignatureTests.swift`), signiert mit einem Test-Schlüssel. */
class MirrorSignatureParityTest {

    private val data = ParityJson.load("mirror_signature.json")

    @Suppress("UNCHECKED_CAST")
    private fun list(key: String) = (data[key] as List<Any?>).map { it as Map<String, Any?> }

    private val testKey = data["publicKey"] as String
    private val now = (data["now"] as Number).toLong()

    @Test
    fun constants() {
        assertEquals(data["productionPublicKey"], MirrorManifest.PUBLIC_KEY)
        assertEquals((data["maxAgeMillis"] as Number).toLong(), MirrorManifest.MAX_AGE_MILLIS)
        assertEquals(data["manifestFile"], MirrorManifest.FILE)
        assertEquals(data["signatureFile"], MirrorManifest.SIGNATURE_FILE)
        assertEquals(DataMirror.BASE + MirrorManifest.FILE, MirrorManifest.URL)
        assertNotEquals(testKey, MirrorManifest.PUBLIC_KEY)
        // Reserve-Schlüssel eingebaut, verschieden vom aktiven und vom Test-Schlüssel
        assertEquals(data["productionReservePublicKey"], MirrorManifest.RESERVE_PUBLIC_KEY)
        assertEquals(listOf(MirrorManifest.PUBLIC_KEY, MirrorManifest.RESERVE_PUBLIC_KEY), MirrorManifest.PUBLIC_KEYS)
        assertNotEquals(MirrorManifest.PUBLIC_KEY, MirrorManifest.RESERVE_PUBLIC_KEY)
        assertNotEquals(testKey, MirrorManifest.RESERVE_PUBLIC_KEY)
    }

    @Test
    fun verify() {
        val cases = list("verify")
        assertTrue(cases.size >= 20)
        cases.forEach { c ->
            @Suppress("UNCHECKED_CAST")
            val keys = c["publicKeys"] as List<String>? ?: listOf(c["publicKey"] as String? ?: testKey)
            val v = MirrorManifest.verify(c["manifest"] as String?, c["signature"] as String?, now, keys)
            assertEquals("${c["name"]}", c["expected"], v.status.name.lowercase())
            if (v.status == MirrorManifest.Status.OK) {
                val m = assertNotNullAndGet(v.manifest, c)
                (c["created"] as Number?)?.let { assertEquals("${c["name"]}", it.toLong(), m.created) }
                (c["fileCount"] as Number?)?.let { assertEquals("${c["name"]}", it.toInt(), m.files.size) }
            } else {
                assertNull("${c["name"]}", v.manifest)
            }
        }
    }

    private fun assertNotNullAndGet(m: MirrorManifest.Manifest?, c: Map<String, Any?>): MirrorManifest.Manifest {
        assertNotNull("${c["name"]}", m)
        return m!!
    }

    /** Der echte Schlüssel lässt sich lesen und lehnt Test-Signaturen ab (kein Absturz). */
    @Test
    fun productionKeyRejectsTestSignature() {
        val valid = list("verify").first { it["expected"] == "ok" }
        val v = MirrorManifest.verify(valid["manifest"] as String, valid["signature"] as String, now)
        assertEquals(MirrorManifest.Status.INVALID, v.status)
    }

    @Test
    fun files() {
        @Suppress("UNCHECKED_CAST")
        val files = (data["manifestFiles"] as Map<String, Any?>).mapValues { it.value as String }
        val manifest = MirrorManifest.Manifest(now, files)
        list("files").forEach { c ->
            val status = MirrorManifest.fileStatus(manifest, c["file"] as String, c["text"] as String)
            assertEquals("${c["name"]}", c["expected"], status.name.lowercase())
        }
    }

    @Test
    fun signedFiles() {
        assertTrue(MirrorManifest.isSignedFile(DataMirror.ALT_SEASON.url))
        assertTrue(MirrorManifest.isSignedFile(DataMirror.BASE + "coin_meta.json"))
        assertFalse(MirrorManifest.isSignedFile(MirrorManifest.URL))
        assertFalse(MirrorManifest.isSignedFile(MirrorManifest.SIGNATURE_URL))
        assertFalse(MirrorManifest.isSignedFile("https://api.alternative.me/fng/?limit=31"))
    }
}
