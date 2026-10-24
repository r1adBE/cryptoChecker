package com.cryptochecker.app.data

import com.cryptochecker.app.data.FakeWatchStore.Companion.BINANCE
import com.cryptochecker.app.data.FakeWatchStore.Companion.pair
import com.cryptochecker.app.data.local.AppDatabase
import com.cryptochecker.app.data.local.DatabaseEncryptionNotice
import com.cryptochecker.app.data.local.WatchlistNotSavedGate
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Rückfrage F-08 als Verhalten am Schreibweg des Repositorys statt nur am Quelltext: Merkliste «nicht gespeichert»
 * (Datenbank nur im Arbeitsspeicher, [DatabaseEncryptionNotice.markInMemory]) und — wo angegeben — eine sichtbare
 * Oberfläche, die den Dialog zeigen könnte (sammelt [WatchlistNotSavedGate.request]). Die Sicherung läuft echt
 * ([writeRefused] mit [WatchlistNotSavedGate.needsConfirmation]), der Ablauf wie [addWatches] ([collectBulkAdd]); nur
 * die Datenbank ist eine Merkliste im Speicher ([FakeWatchStore]).
 * - ohne vorherige Zustimmung abgelehnt: nichts geschrieben, keine Frage, kein Warten, kein Hinweis;
 * - nach «Trotzdem» geschrieben, nach «Abbrechen» bleibt die Sicherung;
 * - ein Durchlass ohne Oberfläche gilt nur für die Coroutine der Aktion samt Kindern, nicht für einen losgelösten
 *   Bereich;
 * - gesperrt (KEPT) lehnt trotz Zustimmung ab und meldet den Hinweis; Folge-Schreibwege (`notify = false`) lehnen nie
 *   ab.
 * Dass JEDER Aufrufer vorher fragt, prüft weiter der Wächter [WatchRepositoryCallGuardTest] über alle Dateien.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WatchlistWriteConsentTest {

    private val store = FakeWatchStore()
    private var hints = 0

    @Before
    fun setUp() {
        WatchlistNotSavedGate.resetForTest()
        DatabaseEncryptionNotice.markInMemory(AppDatabase.DB_NAME)
        assertTrue("Lage «nicht gespeichert» nicht gesetzt", WatchlistNotSavedGate.notSavedNow())
    }

    @After
    fun tearDown() {
        DatabaseEncryptionNotice.clearInMemoryForTest(AppDatabase.DB_NAME)
        WatchlistNotSavedGate.resetForTest()
    }

    /** Sicherung wie [userWriteRefused] nach dem Öffnen der Datenbank ([blocked] = gesperrt, KEPT). */
    private suspend fun refused(blocked: Boolean = false, notify: Boolean = true): Boolean =
        writeRefused(blocked, notify, report = { hints++ })

    /** Schreibweg «Paare anlegen» wie [addWatches]. */
    private suspend fun addPairs(vararg bases: String, blocked: Boolean = false): BulkAddResult =
        collectBulkAdd(bases.map { pair(it) }, refused = { refused(blocked) }, add = { store.add(BINANCE, it) }) {}

    /** Sichtbare Oberfläche: sammelt die offene Frage (wie `WatchlistNotSavedGateHost`), bis der Test endet. */
    private fun TestScope.showScreen() {
        backgroundScope.launch { WatchlistNotSavedGate.request.collect {} }
        runCurrent()
    }

    /** Aktion fragt vorher mit sichtbarer Oberfläche; [proceed] = Antwort im Dialog. */
    private suspend fun TestScope.askAndAnswer(proceed: Boolean): Boolean {
        val consent = async { WatchlistNotSavedGate.confirm() }
        runCurrent()
        assertNotNull("Dialog erwartet", WatchlistNotSavedGate.request.value)
        WatchlistNotSavedGate.answer(proceed)
        return consent.await()
    }

    @Test
    fun writeWithoutConsentIsRefusedWithoutAskingOrWaiting() = runTest {
        showScreen()
        val result = addPairs("BTC", "ETH")
        assertTrue(result.locked)
        assertEquals(0, result.added)
        assertEquals(emptyList<Long>(), result.addedIds)
        assertEquals("nichts geschrieben", emptyList<String>(), store.writes)
        // Der Schreibweg fragt nie selbst und wartet nicht (er kehrte ohne Antwort zurück): keine Frage, kein Hinweis
        assertNull(WatchlistNotSavedGate.request.value)
        assertEquals(0, hints)
    }

    @Test
    fun writeAfterProceedIsWritten() = runTest {
        showScreen()
        assertTrue(askAndAnswer(proceed = true))
        val result = addPairs("BTC", "ETH")
        assertFalse(result.locked)
        assertEquals(listOf(1L, 2L), result.addedIds)
        assertEquals(listOf("add 1", "add 2"), store.writes)
        // «Trotzdem» gilt für den Rest des Laufs: auch eine spätere Aktion schreibt ohne neue Frage
        assertEquals(1, addPairs("SOL").added)
        assertNull(WatchlistNotSavedGate.request.value)
    }

    @Test
    fun cancelWritesNothingAndKeepsTheGuard() = runTest {
        showScreen()
        assertFalse(askAndAnswer(proceed = false))
        // Ein Schreibweg trotz «Abbrechen» (Aufrufer beachtet die Antwort nicht) wird abgelehnt
        assertTrue(addPairs("BTC").locked)
        assertEquals(emptyList<String>(), store.writes)
    }

    /**
     * Review F-27, ARC7-02: Durchlass ohne Oberfläche (sie fehlte beim Bestätigen) nur für die Aktion. Danach ist die
     * Oberfläche da: Die Aktion, ihre Kind-Coroutine und ein `withContext` darin schreiben; eine Coroutine in einem
     * anderen Bereich (eigener Job, wie `appScope.launch`) wird abgelehnt; nach dem Ende der Aktion gilt die Sicherung.
     */
    @Test
    fun unaskedPassCoversOnlyTheActionAndItsChildren() = runTest {
        val detached = CoroutineScope(Job() + StandardTestDispatcher(testScheduler))
        showScreen()
        try {
            var detachedResult: BulkAddResult? = null
            val action = launch {
                // Beim Bestätigen fehlte die Oberfläche (canAsk = false, auch nach dem Warten nicht da)
                assertTrue(WatchlistNotSavedGate.confirm(canAsk = false, awaitAsker = { false }))
                assertEquals(1, addPairs("BTC").added)
                launch { assertEquals(1, addPairs("ETH").added) }.join()
                withContext(CoroutineName("Transaktion")) { assertEquals(1, addPairs("SOL").added) }
                detachedResult = detached.async { addPairs("XRP") }.await()
            }
            action.join()
            assertTrue("losgelöster Bereich gehört nicht zur Aktion", detachedResult!!.locked)
            assertFalse(store.contains(BINANCE, pair("XRP")))
            assertEquals(listOf("add 1", "add 2", "add 3"), store.writes)
            // Aktion beendet: ihr Durchlass ist verfallen
            assertTrue(addPairs("ADA").locked)
            assertNull(WatchlistNotSavedGate.request.value)
        } finally {
            detached.cancel()
        }
    }

    /** Ohne Oberfläche (Hintergrund, Empfänger) wird nie gefragt und nie abgelehnt. */
    @Test
    fun withoutScreenWritesWithoutAsking() = runTest {
        assertFalse(refused())
        assertEquals(1, addPairs("BTC").added)
        assertNull(WatchlistNotSavedGate.request.value)
    }

    /** Gesperrt (KEPT): trotz «Trotzdem» abgelehnt, mit Hinweis; still nur bei Folge-Schreibwegen. */
    @Test
    fun lockedWatchlistRefusesEvenAfterConsent() = runTest {
        showScreen()
        assertTrue(askAndAnswer(proceed = true))
        val result = addPairs("BTC", blocked = true)
        assertTrue(result.locked)
        assertEquals(emptyList<String>(), store.writes)
        assertEquals(1, hints)
        assertTrue(refused(blocked = true, notify = false))
        assertEquals("Folge-Schreibweg ohne eigenen Hinweis", 1, hints)
    }

    /** Folge-Schreibwege (Meldekurs, Prozent-Bezug) laufen nur nach einem bestätigten und lehnen nie ab. */
    @Test
    fun followUpWritesNeverRefuse() = runTest {
        showScreen()
        assertFalse(refused(notify = false))
        assertTrue(refused(notify = true))
        assertEquals(0, hints)
    }
}
