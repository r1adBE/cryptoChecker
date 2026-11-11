package com.cryptochecker.app.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Logo-Dateien schreiben ([LogoFiles.write]): Erfolg nur mit Datei, Fehlschlag gemeldet (sonst zählte das Bild als neu
 * ohne Datei und wurde nie erneut versucht), keine Hilfsdateien liegen gelassen, gleichzeitiges Schreiben derselben
 * Datei ergibt immer ein ganzes Bild.
 */
class LogoFilesTest {

    @get:Rule val folder = TemporaryFolder()

    @Test
    fun writeReplacesTheFileAndLeavesNoTemp() {
        val dir = folder.newFolder("coin_logos")
        val file = File(dir, "FLOKI.png")
        assertTrue(LogoFiles.write(file, byteArrayOf(1, 2, 3)))
        assertTrue(LogoFiles.write(file, byteArrayOf(4, 5)))
        assertArrayEquals(byteArrayOf(4, 5), file.readBytes())
        assertEquals(listOf("FLOKI.png"), dir.list()?.toList())
    }

    @Test
    fun missingFolderReportsFailure() {
        val dir = File(folder.root, "weg")
        assertFalse(LogoFiles.write(File(dir, "0G.png"), byteArrayOf(1)))
        assertFalse(File(dir, "0G.png").exists())
    }

    @Test
    fun targetThatCannotBeReplacedReportsFailure() {
        val dir = folder.newFolder("coin_logos")
        // Ein Ordner mit dem Namen der Datei: Umbenennen scheitert
        File(dir, "MOG.png").mkdirs()
        assertFalse(LogoFiles.write(File(dir, "MOG.png"), byteArrayOf(1)))
        assertTrue(dir.list().orEmpty().none { it.endsWith(LogoFiles.TEMP_SUFFIX) })
    }

    @Test
    fun concurrentWritesOfTheSameFileStayWhole() {
        val dir = folder.newFolder("coin_logos")
        val file = File(dir, "FLOKI.png")
        val a = ByteArray(256 * 1024) { 1 }
        val b = ByteArray(128 * 1024) { 2 }
        val pool = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        val results = (0 until 32).map { i ->
            pool.submit<Boolean> {
                start.await()
                LogoFiles.write(file, if (i % 2 == 0) a else b)
            }
        }
        start.countDown()
        assertTrue(results.all { it.get(30, TimeUnit.SECONDS) })
        pool.shutdown()
        val bytes = file.readBytes()
        assertTrue("halbes oder gemischtes Bild", bytes.contentEquals(a) || bytes.contentEquals(b))
        assertEquals(listOf("FLOKI.png"), dir.list()?.toList())
    }
}
