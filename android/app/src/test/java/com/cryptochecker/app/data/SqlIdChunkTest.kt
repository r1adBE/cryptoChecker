package com.cryptochecker.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Eine Regel für alle `IN (:ids)`-Abfragen der Merkliste: Jeder Aufruf mit einer Id-Liste läuft in Blöcken von
 * `SQL_ID_CHUNK` (WatchRepositoryAdd.kt) — auch «alle auswählen › deaktivieren» mit bis zu 2000 Paaren. Room läuft nur
 * auf dem Gerät, darum am Quelltext wie [WatchRepositoryLockTest].
 */
class SqlIdChunkTest {

    private fun source(name: String): String {
        val path = "src/main/java/com/cryptochecker/app/data/$name"
        return listOf(File(path), File("app/$path"), File("../data/$path"), File("data/$path")).first { it.exists() }.readText()
    }

    @Test
    fun listQueriesRunInOneDocumentedChunkSize() {
        val repo = source("WatchRepository.kt")
        assertTrue(repo.contains("chunked(SQL_ID_CHUNK).forEach { watchDao.setDisabled(it, disabled) }"))
        assertTrue(repo.contains("chunked(SQL_ID_CHUNK).forEach { watchDao.clearChanges(it) }"))
        // Keine zweite, eigene Blockgrösse
        assertEquals(listOf<String>(), Regex("""chunked\(\d+\)""").findAll(repo).map { it.value }.toList())
        val add = source("WatchRepositoryAdd.kt")
        assertTrue(add.contains("internal const val SQL_ID_CHUNK = 500"))
        assertTrue(add.contains("unique.chunked(SQL_ID_CHUNK)"))
    }
}
