package com.cryptochecker.app.data

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Schreibweg des Nutzers bei vollem Speicher: kein Absturz, Hinweis, «nicht geschrieben» ([guardedWrite]).
 * Gegenstück iOS: `WatchlistWriteFailedTests.swift`.
 */
class GuardedWriteTest {

    @Test
    fun writeErrorGivesNoticeAndResultNotWritten() = runTest {
        var reported = 0
        val result = guardedWrite(false, report = { reported++ }) { throw IOException("Speicher voll") }
        assertFalse(result)
        assertEquals(1, reported)
        assertTrue(guardedWrite(false, report = { reported++ }) { true })
        assertEquals(1, reported)
    }

    @Test
    fun otherErrorsAndCancellationPassThrough() = runTest {
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
}
