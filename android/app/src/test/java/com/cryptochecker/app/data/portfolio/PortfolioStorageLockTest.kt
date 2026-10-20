package com.cryptochecker.app.data.portfolio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [PortfolioStorageLock]: Schreiben sperren, solange das gespeicherte Portfolio nur leer im Arbeitsspeicher steht. */
class PortfolioStorageLockTest {

    @Test
    fun portfolioKeptBlocksWrites() {
        val kept = setOf("cryptochecker_portfolio.db")
        assertTrue(PortfolioStorageLock.isLocked(kept))
        assertTrue(PortfolioStorageLock.blocksWrites(kept))
    }

    @Test
    fun nothingKeptAllowsWrites() {
        assertFalse(PortfolioStorageLock.blocksWrites(emptySet()))
    }

    @Test
    fun onlyWatchlistKeptDoesNotBlockPortfolio() {
        assertFalse(PortfolioStorageLock.blocksWrites(setOf("cryptochecker.db")))
    }
}
