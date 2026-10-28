package com.cryptochecker.app.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [WatchlistStorageLock]: Schreibwege sperren, solange die gespeicherte Merkliste nur leer im Arbeitsspeicher steht. */
class WatchlistStorageLockTest {

    @Test
    fun watchlistKeptBlocksAlarms() {
        val kept = setOf("cryptochecker.db")
        assertTrue(WatchlistStorageLock.isLocked(kept))
        assertTrue(WatchlistStorageLock.blocksAlarmSave(kept))
    }

    @Test
    fun nothingKeptAllowsAlarms() {
        assertFalse(WatchlistStorageLock.isLocked(emptySet()))
        assertFalse(WatchlistStorageLock.blocksAlarmSave(emptySet()))
    }

    @Test
    fun onlyPortfolioKeptDoesNotBlockWatchlistAlarms() {
        // Portfolio-Datei gesperrt, Merkliste lesbar: Alarme der Merkliste werden gespeichert
        val kept = setOf("cryptochecker_portfolio.db")
        assertFalse(WatchlistStorageLock.isLocked(kept))
        assertFalse(WatchlistStorageLock.blocksAlarmSave(kept))
    }

    /** Rückfrage «nicht gespeichert»: gesperrt = nur lesbar wie das Portfolio — alle Schreibwege des Nutzers, nicht nur Alarme. */
    @Test
    fun keptWatchlistBlocksAllUserWrites() {
        assertTrue(WatchlistStorageLock.blocksWrites(setOf("cryptochecker.db")))
        assertTrue(WatchlistStorageLock.blocksWrites(setOf("cryptochecker.db", "cryptochecker_portfolio.db")))
    }

    @Test
    fun inMemoryWithoutStoredStateDoesNotBlockWrites() {
        // Kein Schlüssel, keine Datei (NOT_SAVED): nichts Gespeichertes zu verlieren, Merkliste bleibt nutzbar
        assertFalse(WatchlistStorageLock.blocksWrites(emptySet()))
        // Nur das Portfolio gesperrt: die Merkliste nicht
        assertFalse(WatchlistStorageLock.blocksWrites(setOf("cryptochecker_portfolio.db")))
    }

    @Test
    fun noticeKeptWinsOverNotSaved() {
        // storedKept ist Teilmenge von inMemory: gespeicherter Stand da → «gesperrt», nicht «wird nicht gespeichert»
        val db = setOf("cryptochecker.db")
        assertEquals(WatchlistStorageLock.Notice.KEPT, WatchlistStorageLock.notice(db, db))
    }

    @Test
    fun noticeNotSavedWithoutKeyAndWithoutFile() {
        // Kein Schlüssel, keine Datei (Decision.Refused): nur im Arbeitsspeicher, Hinweis, aber Alarme nicht gesperrt
        val inMemory = setOf("cryptochecker.db")
        assertEquals(WatchlistStorageLock.Notice.NOT_SAVED, WatchlistStorageLock.notice(inMemory, emptySet()))
        assertFalse(WatchlistStorageLock.blocksAlarmSave(emptySet()))
    }

    @Test
    fun noNoticeForPortfolioOnlyOrNothing() {
        assertNull(WatchlistStorageLock.notice(emptySet(), emptySet()))
        val portfolio = setOf("cryptochecker_portfolio.db")
        assertNull(WatchlistStorageLock.notice(portfolio, portfolio))
    }
}
