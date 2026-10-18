package com.cryptochecker.app.data.portfolio

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Beiseitegelegte Kopie der Systemsicherung: genau ein zweiter Versuch, danach gelöscht — nie dauerhaft Klartext.
 */
class PortfolioBackupMirrorRetryTest {

    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("mirror-retry").toFile()
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun setAsideCopiesAreRetriedOnceThenDeleted() {
        val mirror = File(dir, PortfolioBackupMirror.FILE_NAME).apply { writeText("{ kaputt") }
        val first = PortfolioBackupMirror.setAside(mirror)
        File(dir, PortfolioBackupMirror.FILE_NAME).writeText("{ auch kaputt")
        val second = PortfolioBackupMirror.setAside(File(dir, PortfolioBackupMirror.FILE_NAME))
        // Fremde Dateien bleiben unberührt
        val other = File(dir, "portfolio_system_backup.json.tmp").apply { writeText("x") }
        assertEquals(listOf(first, second).map { it!!.name }.sorted(), PortfolioBackupMirror.failedCopies(dir).map { it.name })

        val tried = mutableListOf<String>()
        val lost = PortfolioBackupMirror.retryFailed(dir) { file ->
            tried += file.name
            if (file.readText().contains("auch")) error("nicht lesbar")
        }

        assertEquals(2, tried.size)
        assertEquals(1, lost)
        assertTrue(PortfolioBackupMirror.failedCopies(dir).isEmpty())
        assertTrue(other.exists())
        // Ein weiterer Start versucht nichts mehr
        assertEquals(0, PortfolioBackupMirror.retryFailed(dir) { error("kein Versuch erwartet") })
    }

    @Test
    fun successfulRetryCountsNothingAsLost() {
        File(dir, PortfolioBackupMirror.FAILED_FILE_NAME).writeText(PortfolioBackupMirror.encode(emptyList(), emptyList()))
        assertEquals(0, PortfolioBackupMirror.retryFailed(dir) { PortfolioBackupMirror.parse(it.readText()) })
        assertFalse(File(dir, PortfolioBackupMirror.FAILED_FILE_NAME).exists())
        assertEquals(0, PortfolioBackupMirror.retryFailed(null) { error("ohne Ordner nichts") })
    }
}
