package com.cryptochecker.app.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.io.IOException

/**
 * Schreibweg des Nutzers bei vollem Speicher (N3-20): kein Absturz, Hinweis, «nicht geschrieben» ([guardedWrite]).
 * Gegenstück iOS: `WatchlistWriteFailedTests.swift`.
 */
class GuardedWriteTest {

    @Test
    fun schreibfehlerGibtHinweisUndErgebnisNichtGeschrieben() = runTest {
        var reported = 0
        val result = guardedWrite(false, report = { reported++ }) { throw IOException("Speicher voll") }
        assertFalse(result)
        assertEquals(1, reported)
        assertTrue(guardedWrite(false, report = { reported++ }) { true })
        assertEquals(1, reported)
    }

    @Test
    fun andereFehlerUndAbbruchGehenWeiter() = runTest {
        for (error in listOf(IllegalStateException("Logik"), CancellationException("weg"))) {
            try {
                guardedWrite(Unit, report = { fail("kein Hinweis") }) { throw error }
                fail("nicht weitergereicht: $error")
            } catch (e: Exception) {
                assertEquals(error, e)
            }
        }
        assertFalse(isWriteFailure(IllegalArgumentException()))
        assertTrue(isWriteFailure(IOException()))
    }

    /** Jeder Schreibweg des Nutzers in Merkliste und Alarme läuft über [guardedWrite]. */
    @Test
    fun alleSchreibwegeGeschuetzt() {
        fun source(name: String) = listOf(
            File("src/main/java/com/cryptochecker/app/data/$name"), File("data/src/main/java/com/cryptochecker/app/data/$name"),
        ).first { it.exists() }.readText()
        val repo = source("WatchRepository.kt")
        val guarded = listOf(
            "suspend fun changePair(", "suspend fun setMissingPercentReferences(", "suspend fun deleteWatch(id: Long)",
            "suspend fun deleteWatches(", "suspend fun deleteAllWatches()", "suspend fun setNotificationEnabled(",
            "suspend fun setFavorite(", "private suspend fun saveOrder(", "suspend fun setNote(", "suspend fun setGroup(",
            "suspend fun applyGroupEdit(", "suspend fun clearGroup(", "suspend fun setTtsEnabled(", "suspend fun setDisabled(",
            "suspend fun setNotifiedPrice(", "suspend fun deleteAlarm(", "suspend fun setAlarmEnabled(",
        )
        for (signature in guarded) {
            assertTrue(signature, repo.substringAfter(signature).substringBefore("\n    /**").contains("guardedWrite("))
        }
        val add = source("WatchRepositoryAdd.kt")
        for (signature in listOf("fun WatchRepository.tryAddWatch(", "fun WatchRepository.restore(", "fun WatchRepository.deleteWatchesWithoutAlarms(")) {
            assertTrue(signature, add.substringAfter(signature).substringBefore("\n}\n").contains("guardedWrite"))
        }
        val save = source("WatchRepositoryAlarmSave.kt")
        for (signature in listOf("fun WatchRepository.trySaveAlarm(", "fun WatchRepository.trySaveAlarmMerged(")) {
            assertTrue(signature, save.substringAfter(signature).substringBefore("\n\n").contains("guardedWrite<AlarmSaveResult>("))
        }
    }
}
