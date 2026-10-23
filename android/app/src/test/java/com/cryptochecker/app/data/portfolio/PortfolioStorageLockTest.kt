package com.cryptochecker.app.data.portfolio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

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

    @Test
    fun ensureWritableThrowsOnlyWhileLocked() {
        assertThrows(PortfolioStorageLockedException::class.java) { ensurePortfolioWritable(setOf("cryptochecker_portfolio.db")) }
        ensurePortfolioWritable(emptySet())
        ensurePortfolioWritable(setOf("cryptochecker.db"))
    }

    /**
     * Adress-Schreibwege des Nutzers prüfen die Sperre (in der Transaktion), Guthaben-Abrufe und automatische
     * Umstellungen nicht — sie dürfen im Hintergrund weiterlaufen. Prüft die Quelle, weil das Repository Room braucht.
     */
    @Test
    fun addressUserWritesAreLockedBackgroundUpdatesAreNot() {
        val source = repositorySource()
        val locked = listOf(
            "addWatchedAddress", "removeWatchedAddress", "setWatchedAddressLabel", "setWatchedAddressCostLots",
            "replaceWatchedAddresses", "mergeWatchedAddresses", "changeWatched",
        )
        for (name in locked) assertTrue("$name prüft die Sperre nicht", body(source, name).contains("ensureWritable()"))
        assertFalse(body(source, "updateWatchedAddressBalance").contains("ensureWritable()"))
        // Nutzer-Änderungen über changeWatched (Standard userEdit = true), automatische ausdrücklich ohne Sperre
        assertTrue(body(source, "setWatchedAddressOverlapShowOnly").contains("userEdit = false"))
        assertTrue(body(source, "migrateWatchedAddressOverlap").contains("userEdit = false"))
        for (name in listOf("setWatchedAddressCountTokens", "setWatchedAddressNetworks", "setWatchedAddressCountInPortfolio")) {
            assertFalse("$name darf die Sperre nicht umgehen", body(source, name).contains("userEdit = false"))
        }
        // «Rückgängig» nach «Adresse entfernen» ist ebenfalls ein Schreibweg des Nutzers
        assertTrue(source("PortfolioRepositoryUndo.kt").contains("ensurePortfolioWritable()"))
    }

    /** Text von `fun name(` bis zur nächsten Funktion. */
    private fun body(source: String, name: String): String {
        val start = source.indexOf("fun $name(")
        assertTrue("fun $name nicht gefunden", start >= 0)
        val next = source.indexOf("\n    suspend fun ", start + 1).let { if (it < 0) source.length else it }
        val nextPrivate = source.indexOf("\n    private ", start + 1).let { if (it < 0) source.length else it }
        return source.substring(start, minOf(next, nextPrivate))
    }

    private fun repositorySource(): String = source("PortfolioRepository.kt")

    private fun source(name: String): String {
        val path = "src/main/java/com/cryptochecker/app/data/portfolio/$name"
        val file = listOf("", "app/", "android/app/").map { File(it + path) }.firstOrNull { it.isFile }
        assertNotNull("$name nicht gefunden (Arbeitsverzeichnis?)", file)
        return file!!.readText()
    }
}
