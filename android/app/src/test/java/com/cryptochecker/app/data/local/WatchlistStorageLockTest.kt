package com.cryptochecker.app.data.local

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [WatchlistStorageLock]: Alarme sperren, solange die gespeicherte Merkliste nur leer im Arbeitsspeicher steht. */
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
}
