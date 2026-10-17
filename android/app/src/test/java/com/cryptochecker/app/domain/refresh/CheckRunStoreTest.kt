package com.cryptochecker.app.domain.refresh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Datei von «Letzte Prüfungen»: schreiben, neu lesen, höchstens 20, kaputte Datei. */
class CheckRunStoreTest {

    private fun tempFile(): File = File(Files.createTempDirectory("runs").toFile(), CheckRunStore.FILE_NAME)

    @Test
    fun persistsAndReloads() {
        val file = tempFile()
        val store = CheckRunStore(file)
        assertTrue(store.runs.value.isEmpty())
        store.add(CheckRun(1, CheckTrigger.WORKER, CheckResult.OK, 900))
        store.add(CheckRun(2, CheckTrigger.EXACT, CheckResult.SKIPPED, 0))
        assertEquals(listOf(2L, 1L), store.runs.value.map { it.at })
        // Neuer Prozess: liest dieselbe Datei
        assertEquals(store.runs.value, CheckRunStore(file).runs.value)
        assertTrue(!File(file.path + ".tmp").exists())
    }

    @Test
    fun keepsNewestTwenty() {
        val file = tempFile()
        val store = CheckRunStore(file)
        for (i in 1..25) store.add(CheckRun(i.toLong(), CheckTrigger.WORKER, CheckResult.OK, 1))
        val reloaded = CheckRunStore(file).runs.value
        assertEquals(CheckRunLog.MAX, reloaded.size)
        assertEquals(25L, reloaded.first().at)
    }

    @Test
    fun unreadableFileStartsEmpty() {
        val file = tempFile()
        file.writeText("kaputt\n;;;")
        assertTrue(CheckRunStore(file).runs.value.isEmpty())
    }
}
