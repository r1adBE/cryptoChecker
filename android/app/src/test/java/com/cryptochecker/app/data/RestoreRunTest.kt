package com.cryptochecker.app.data

import com.cryptochecker.app.data.BackupManager.Step
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

/**
 * Wiederherstellen mit Journal und Zurückrollen ([RestoreRun], [RestoreRollback]) mit Fälschungen statt Datenbanken:
 * ohne Journal wird nichts geschrieben, jeder Zweig des Zurückrollens (Einstellungen, Favoriten,
 * Portfolio, Merkliste) und «Zurückrollen unvollständig → Journal bleibt».
 */
class RestoreRunTest {

    /** Die vier Teile als einfache Werte; [failOn] lässt das Schreiben eines Teils scheitern. */
    private class World {
        var watchlist = "alte Merkliste"
        var portfolio = "altes Portfolio"
        var favorites = "alte Favoriten"
        var settings = "alte Einstellungen"
        val writes = mutableListOf<String>()
        var failOn: String? = null
        var failRollBackOf: String? = null
        var portfolioWritable = true

        fun write(part: String, value: String, assign: (String) -> Unit) {
            writes += part
            if (failOn == part) throw IOException("$part scheitert")
            assign(value)
        }

        fun snapshot() = listOf(watchlist, portfolio, favorites, settings)
    }

    private class FakeJournal(private val writes: Boolean = true, private val throws: Boolean = false) : RestoreRun.Journal {
        var written = false
        var deleted = false
        override fun write(): Boolean {
            if (throws) throw IOException("Speicher voll")
            written = writes
            return writes
        }
        override fun delete() {
            deleted = true
        }
    }

    private val previous = World().snapshot()

    private fun writesOf(w: World) = listOf(
        RestoreRun.Write(Step.WATCHLIST, touchedBefore = false) { w.write("Merkliste", "neue Merkliste") { w.watchlist = it } },
        RestoreRun.Write(Step.PORTFOLIO, touchedBefore = false) { w.write("Portfolio", "neues Portfolio") { w.portfolio = it } },
        RestoreRun.Write(Step.PORTFOLIO, touchedBefore = true) { w.write("Bestand", "neues Portfolio+Bestand") { w.portfolio = it } },
        RestoreRun.Write(Step.FAVORITES, touchedBefore = true) { w.write("Favoriten", "neue Favoriten") { w.favorites = it } },
        RestoreRun.Write(Step.SETTINGS, touchedBefore = true) { w.write("Einstellungen", "neue Einstellungen") { w.settings = it } },
    )

    /** Zurückrollen wie `BackupManager.rollBack`: gemerkten Stand je berührtem Teil zurück. */
    private fun partsOf(w: World, rolledBack: MutableList<String>) = object : RestoreRollback.Parts {
        override fun mayWritePortfolio() = w.portfolioWritable
        override fun mayWriteWatchlist() = true
        private fun back(part: String, assign: () -> Unit) {
            rolledBack += part
            if (w.failRollBackOf == part) throw IOException("$part nicht zurück")
            assign()
        }
        override suspend fun settings() = back("Einstellungen") { w.settings = previous[3] }
        override suspend fun favorites() = back("Favoriten") { w.favorites = previous[2] }
        override suspend fun portfolio() = back("Portfolio") { w.portfolio = previous[1] }
        override suspend fun watchlist() = back("Merkliste") { w.watchlist = previous[0] }
    }

    private suspend fun run(w: World, journal: FakeJournal, rolledBack: MutableList<String> = mutableListOf()) =
        RestoreRun.execute(journal, writesOf(w)) { touched, cause -> RestoreRollback.run(touched, cause, partsOf(w, rolledBack)) }

    @Test
    fun ohneSchluesselFuerDasJournalWirdNichtsGeschrieben() = runTest {
        val w = World()
        try {
            run(w, FakeJournal(writes = false))
            fail("ohne Journal abgelehnt erwartet")
        } catch (e: BackupJournalUnavailableException) {
            // erwartet
        }
        assertTrue(w.writes.isEmpty())
        assertEquals(previous, w.snapshot())
    }

    @Test
    fun speicherfehlerBeimJournalLehntAbOhneZuSchreiben() = runTest {
        val w = World()
        try {
            run(w, FakeJournal(throws = true))
            fail("ohne Journal abgelehnt erwartet")
        } catch (e: BackupJournalUnavailableException) {
            assertTrue(e.cause is IOException)
        }
        assertTrue(w.writes.isEmpty())
    }

    @Test
    fun erfolgSchreibtAllesUndLoeschtDasJournal() = runTest {
        val w = World()
        val journal = FakeJournal()
        run(w, journal)
        assertEquals(listOf("neue Merkliste", "neues Portfolio+Bestand", "neue Favoriten", "neue Einstellungen"), w.snapshot())
        assertTrue(journal.written)
        assertTrue(journal.deleted)
    }

    @Test
    fun abbruchNachDemPortfolioSchrittRolltAlleVierTeileZurueck() = runTest {
        // Sicherung mit Portfolio, Alarmen und Favoriten; Favoriten scheitern halb (vor dem Schreiben schon berührt)
        val w = World().apply { failOn = "Favoriten" }
        val journal = FakeJournal()
        val rolledBack = mutableListOf<String>()
        try {
            run(w, journal, rolledBack)
            fail("Fehler erwartet")
        } catch (e: IOException) {
            assertEquals("Favoriten scheitert", e.message)
        }
        assertEquals(listOf("Favoriten", "Portfolio", "Merkliste"), rolledBack)
        assertEquals(previous, w.snapshot())
        assertTrue(journal.deleted)
    }

    @Test
    fun einstellungenScheiternHalbUndWerdenZurueckgeschrieben() = runTest {
        val w = World().apply { failOn = "Einstellungen" }
        val rolledBack = mutableListOf<String>()
        runCatching { run(w, FakeJournal(), rolledBack) }
        assertEquals(listOf("Einstellungen", "Favoriten", "Portfolio", "Merkliste"), rolledBack)
        assertEquals(previous, w.snapshot())
    }

    @Test
    fun gescheiterteTransaktionGiltNichtAlsBeruehrt() = runTest {
        // Portfolio-Transaktion scheitert: nur die Merkliste ist zurückzuschreiben
        val w = World().apply { failOn = "Portfolio" }
        val rolledBack = mutableListOf<String>()
        runCatching { run(w, FakeJournal(), rolledBack) }
        assertEquals(listOf("Merkliste"), rolledBack)
        assertEquals(previous, w.snapshot())
    }

    @Test
    fun unvollstaendigesZurueckrollenBehaeltDasJournal() = runTest {
        val w = World().apply {
            failOn = "Einstellungen"
            failRollBackOf = "Portfolio"
        }
        val journal = FakeJournal()
        val rolledBack = mutableListOf<String>()
        val error = runCatching { run(w, journal, rolledBack) }.exceptionOrNull()
        // Die übrigen Teile laufen trotzdem; der Fehler des Zurückrollens hängt an der Ursache
        assertEquals(listOf("Einstellungen", "Favoriten", "Portfolio", "Merkliste"), rolledBack)
        assertEquals("nicht zurück", error!!.suppressed.single().message!!.substringAfter("Portfolio "))
        assertFalse(journal.deleted)
        assertEquals("alte Merkliste", w.watchlist)
    }

    @Test
    fun portfolioNurImArbeitsspeicherIstUnvollstaendig() = runTest {
        val w = World().apply {
            failOn = "Favoriten"
            portfolioWritable = false
        }
        val journal = FakeJournal()
        val rolledBack = mutableListOf<String>()
        runCatching { run(w, journal, rolledBack) }
        assertEquals(listOf("Favoriten", "Merkliste"), rolledBack)
        assertFalse(journal.deleted)
    }

    @Test
    fun zurueckrollenBeimStartSchreibtAlleTeile() = runTest {
        // Abgebrochener Lauf (Journal beim Start): welche Schritte liefen, ist unbekannt — alle zurück
        val w = World().apply {
            watchlist = "halb"
            portfolio = "halb"
        }
        val rolledBack = mutableListOf<String>()
        val cause = IllegalStateException("Wiederherstellen abgebrochen")
        assertTrue(RestoreRollback.run(Step.entries.toSet(), cause, partsOf(w, rolledBack)))
        assertEquals(previous, w.snapshot())
        assertEquals(0, cause.suppressed.size)
    }
}
