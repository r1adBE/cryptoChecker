package com.cryptochecker.app.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Alte Datenbank des Vorgängerprojekts nur einmal entfernen ([LegacyDatabaseCleanup]). */
class LegacyDatabaseCleanupTest {

    private fun tempFlag(): File = File(Files.createTempDirectory("legacy").toFile(), "sub/" + LegacyDatabaseCleanup.FLAG_FILE)

    @Test
    fun laeuftNurBeimErstenMal() {
        val flag = tempFlag()
        var deletes = 0
        assertTrue(LegacyDatabaseCleanup.runOnce(flag) { deletes++ })
        assertTrue(flag.exists())
        assertFalse(LegacyDatabaseCleanup.runOnce(flag) { deletes++ })
        assertEquals(1, deletes)
    }

    @Test
    fun fehlerSetztKeinenMerker() {
        val flag = tempFlag()
        assertFalse(LegacyDatabaseCleanup.runOnce(flag) { throw SecurityException("gesperrt") })
        assertFalse(flag.exists())
        // Nächster Start versucht es wieder
        assertTrue(LegacyDatabaseCleanup.runOnce(flag) { })
    }
}
