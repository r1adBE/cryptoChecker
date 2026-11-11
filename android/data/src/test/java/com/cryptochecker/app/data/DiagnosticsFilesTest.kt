package com.cryptochecker.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Aufräumen der Diagnose-Datei im Cache ([DiagnosticsFiles]). */
class DiagnosticsFilesTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun loeschtBerichtImOrdnerUndLaesstAnderesStehen() {
        val cache = temp.newFolder("cache")
        val dir = DiagnosticsFiles.dir(cache).apply { mkdirs() }
        File(dir, "cryptochecker-diagnostics.txt").writeText("Bericht")
        File(dir, "alt.txt").writeText("alt")
        val other = File(cache, "logos.bin").apply { writeText("x") }

        assertEquals(2, DiagnosticsFiles.clear(cache))
        assertTrue(dir.listFiles().orEmpty().isEmpty())
        assertTrue(other.exists())
    }

    @Test
    fun ohneOrdnerNichtsZuTun() {
        val cache = temp.newFolder("leer")
        assertEquals(0, DiagnosticsFiles.clear(cache))
        assertFalse(DiagnosticsFiles.dir(cache).exists())
    }
}
