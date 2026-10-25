package com.cryptochecker.app.parity

import com.cryptochecker.app.domain.mirror.MirrorManifest
import com.cryptochecker.app.domain.mirror.MirrorRevocations
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Sperrliste des Spiegels (F-29), gemeinsame Testfälle `mirror_signature.json` → `revocation` (iOS:
 * `MirrorRevocationTests.swift`), signiert mit zwei Test-Schlüsseln.
 */
class MirrorRevocationParityTest {

    private val data = ParityJson.load("mirror_signature.json")
    private val now = (data["now"] as Number).toLong()

    @Suppress("UNCHECKED_CAST")
    private val keys = data["revocationKeys"] as Map<String, String>
    private val testKeys = listOf(keys.getValue("a"), keys.getValue("b"))

    @Suppress("UNCHECKED_CAST")
    private fun strings(value: Any?) = (value as List<Any?>).map { it as String }

    @Test
    fun keyIds() {
        data["keyIds"].list().map { it.obj() }.forEach { c ->
            assertEquals(c["id"], MirrorManifest.keyId(c["publicKey"] as String))
        }
        assertNull(MirrorManifest.keyId("not base64!"))
        // Eingebaute Schlüssel: verschiedene Ids
        val active = MirrorManifest.keyId(MirrorManifest.PUBLIC_KEY)
        assertNotEquals(active, MirrorManifest.keyId(MirrorManifest.RESERVE_PUBLIC_KEY))
    }

    @Test
    fun revocation() {
        val cases = data["revocation"].list().map { it.obj() }
        assertTrue(cases.size >= 10)
        cases.forEach { c ->
            val name = c["name"] as String
            val v = MirrorManifest.verify(
                c["manifest"] as String, c["signature"] as String, now, testKeys, strings(c["revokedBefore"]).toSet(),
            )
            assertEquals(name, c["expected"], v.status.name.lowercase())
            assertEquals(name, strings(c["revoke"]).toSet(), v.revoke)
        }
    }

    /** Gemerkte Sperren überdauern einen Neustart (neue Instanz auf derselben Datei) und werden nie entfernt. */
    @Test
    fun storeKeepsRevocations() {
        val dir = Files.createTempDirectory("revoked").toFile()
        try {
            val file = File(dir, MirrorRevocations.FILE_NAME)
            val a = MirrorManifest.keyId(keys.getValue("a"))!!
            val b = MirrorManifest.keyId(keys.getValue("b"))!!
            val first = MirrorRevocations(MirrorRevocations.FileStore(file))
            assertTrue(first.current().isEmpty())
            first.add(setOf(a, "kein Schlüssel"))
            assertEquals(setOf(a), MirrorRevocations(MirrorRevocations.FileStore(file)).current())
            first.add(setOf(b))
            first.add(emptySet())
            assertEquals(setOf(a, b), MirrorRevocations(MirrorRevocations.FileStore(file)).current())
            // Unlesbares bzw. Fremdes in der Datei zählt nicht
            assertEquals(setOf(a), MirrorRevocations.decode("$a\n\nXYZ\n${a.uppercase()}\n"))
        } finally {
            dir.deleteRecursively()
        }
    }
}
