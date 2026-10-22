package com.cryptochecker.app.data.local

import com.cryptochecker.app.parity.ParityJson
import com.cryptochecker.app.parity.list
import com.cryptochecker.app.parity.num
import com.cryptochecker.app.parity.obj
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Seit Build 104 (F-08): Frage vor der ersten Änderung je Lauf — gleiche Fälle wie `WatchlistNotSavedGateTests.swift`.
 * Seit Build 105: einmal je Aktion vorher, Sicherung im Schreibweg, Abbruch des Wartenden räumt die Frage weg.
 * Seit Build 106: ohne Oberfläche kurz auf sie warten, Durchlass ohne Oberfläche wird im Schreibweg nicht abgelehnt.
 */
class WatchlistNotSavedGateTest {

    private val data = ParityJson.load("watchlist_not_saved_gate.json")

    @Before
    fun setUp() = WatchlistNotSavedGate.resetForTest()

    @After
    fun tearDown() = WatchlistNotSavedGate.resetForTest()

    @Test
    fun sharedShouldAsk() {
        data["shouldAsk"].list().forEach { raw ->
            val c = raw.obj()
            assertEquals(
                c.toString(), c["ask"],
                WatchlistNotSavedGate.shouldAsk(
                    notSaved = c["notSaved"] as Boolean, userWrite = c["userWrite"] as Boolean,
                    confirmed = c["confirmed"] as Boolean, canAsk = c["canAsk"] as Boolean,
                ),
            )
        }
    }

    @Test
    fun sharedConfirmedAfter() {
        data["confirmedAfter"].list().forEach { raw ->
            val c = raw.obj()
            assertEquals(
                c.toString(), c["result"],
                WatchlistNotSavedGate.confirmedAfter(c["confirmed"] as Boolean, c["proceed"] as Boolean),
            )
        }
    }

    @Test
    fun sharedSequence() = runBlocking {
        data["sequence"].obj()["steps"].list().forEachIndexed { i, raw ->
            val step = raw.obj()
            val write = async(Dispatchers.Default) { WatchlistNotSavedGate.confirm(notSaved = true, canAsk = true) }
            val answer = step["answer"] as Boolean?
            if (answer != null) {
                withTimeout(5_000) { WatchlistNotSavedGate.request.filterNotNull().first() }
                WatchlistNotSavedGate.answer(answer)
            }
            assertEquals("Schritt $i", step["written"], withTimeout(5_000) { write.await() })
            assertNull("Schritt $i", WatchlistNotSavedGate.request.value)
        }
    }

    @Test
    fun withoutVisibleUiNeverAsks() = runBlocking {
        // Kein Beobachter (Hintergrund), auch nach dem Warten nicht: schreibt, ohne zu fragen
        assertEquals(true, WatchlistNotSavedGate.confirm(notSaved = true, canAsk = false, awaitAsker = { false }))
        assertNull(WatchlistNotSavedGate.request.value)
    }

    /**
     * Eine Nutzeraktion wie im Controller/ViewModel (seit Build 105): einmal [WatchlistNotSavedGate.confirm] vorher, danach je
     * Schreibweg nur die Sicherung des Repositorys ([WatchlistNotSavedGate.needsConfirmation], fragt nie), Folgen erst nach
     * allen Schreibwegen. Rückgabe: geschriebene Schreibwege und ob die Folgen liefen.
     */
    private suspend fun action(
        writes: Int, notSaved: Boolean, canAsk: Boolean, skipConfirm: Boolean,
        screenAppears: Boolean = false, canAskAtWrite: Boolean = canAsk,
    ): Pair<Int, Boolean> {
        if (!skipConfirm &&
            !WatchlistNotSavedGate.confirm(notSaved = notSaved, canAsk = canAsk, awaitAsker = { screenAppears })
        ) return 0 to false
        var written = 0
        repeat(writes) { if (!WatchlistNotSavedGate.needsConfirmation(notSaved = notSaved, canAsk = canAskAtWrite)) written++ }
        return written to (written == writes)
    }

    @Test
    fun sharedActionsAskOnceBeforeTheAction() = runBlocking {
        data["actions"].obj()["cases"].list().forEach { raw ->
            val c = raw.obj()
            val name = c["name"] as String
            WatchlistNotSavedGate.resetForTest()
            if (c["confirmed"] == true) {
                val first = async(Dispatchers.Default) { WatchlistNotSavedGate.confirm(notSaved = true, canAsk = true) }
                withTimeout(5_000) { WatchlistNotSavedGate.request.filterNotNull().first() }
                WatchlistNotSavedGate.answer(true)
                assertTrue(name, first.await())
            }
            val run = async(Dispatchers.Default) {
                action(
                    writes = c["writes"].num()!!.toInt(), notSaved = c["notSaved"] as Boolean? ?: true,
                    canAsk = c["canAsk"] as Boolean? ?: true, skipConfirm = c["skipConfirm"] == true,
                )
            }
            var questions = 0
            (c["answer"] as Boolean?)?.let { answer ->
                withTimeout(5_000) { WatchlistNotSavedGate.request.filterNotNull().first() }
                questions++
                WatchlistNotSavedGate.answer(answer)
            }
            // Eine zweite Frage liesse die Aktion hängen: Zeitlimit statt Endlos-Warten
            val (written, effects) = withTimeout(5_000) { run.await() }
            assertEquals(name, c["questions"].num()!!.toInt(), questions)
            assertEquals(name, c["written"].num()!!.toInt(), written)
            assertEquals(name, c["effects"], effects)
            assertNull(name, WatchlistNotSavedGate.request.value)
        }
    }

    /** Seit Build 105 (ARC3-R02): Wartende Aktion abgebrochen (ViewModel weg) → keine offene Frage mehr, nichts bestätigt. */
    @Test
    fun sharedCancelledWaiterClearsTheQuestion() = runBlocking {
        val expected = data["cancelledWaiter"].obj()
        val waiting = async(Dispatchers.Default) { WatchlistNotSavedGate.confirm(notSaved = true, canAsk = true) }
        withTimeout(5_000) { WatchlistNotSavedGate.request.filterNotNull().first() }
        waiting.cancelAndJoin()
        assertEquals(expected["pendingAfterCancel"], WatchlistNotSavedGate.request.value != null)
        assertEquals(expected["confirmedAfterCancel"], !WatchlistNotSavedGate.needsConfirmation(notSaved = true, canAsk = true))
        // Die nächste Aktion fragt neu
        val next = async(Dispatchers.Default) { WatchlistNotSavedGate.confirm(notSaved = true, canAsk = true) }
        val asked = withTimeout(5_000) { WatchlistNotSavedGate.request.filterNotNull().first() }.isActive
        assertEquals(expected["nextActionAsks"], asked)
        WatchlistNotSavedGate.answer(false)
        assertFalse(withTimeout(5_000) { next.await() })
    }

    /** Zwei gleichzeitige Aktionen: eine Frage, beide bekommen dieselbe Antwort; einer abgebrochen → Frage bleibt für den anderen. */
    @Test
    fun concurrentActionsShareOneQuestion() = runBlocking {
        val a = async(Dispatchers.Default) { WatchlistNotSavedGate.confirm(notSaved = true, canAsk = true) }
        val question = withTimeout(5_000) { WatchlistNotSavedGate.request.filterNotNull().first() }
        val b = async(Dispatchers.Default) { WatchlistNotSavedGate.confirm(notSaved = true, canAsk = true) }
        // b hängt an derselben Frage (kurz warten, bis es wartet), dann a abbrechen: die Frage bleibt für b
        withTimeout(5_000) { while (!WatchlistNotSavedGate.hasWaitersForTest(2)) kotlinx.coroutines.delay(5) }
        a.cancelAndJoin()
        assertTrue(WatchlistNotSavedGate.request.value === question)
        WatchlistNotSavedGate.answer(true)
        assertTrue(withTimeout(5_000) { b.await() })
        assertFalse(WatchlistNotSavedGate.needsConfirmation(notSaved = true, canAsk = true))
    }

    /**
     * Seit Build 106 (Review ARC4-R02, Variante A): ohne Oberfläche beim Bestätigen erst warten; erscheint sie, wird gefragt,
     * sonst durchgelassen — und die Sicherung lehnt die Schreibwege dieser Aktion nicht still ab.
     */
    @Test
    fun sharedLateScreen() = runBlocking {
        val late = data["lateScreen"].obj()
        assertEquals(late["waitMillis"].num()!!.toLong(), WatchlistNotSavedGate.ASKER_WAIT_MILLIS)
        late["cases"].list().forEach { raw ->
            val c = raw.obj()
            val name = c["name"] as String
            WatchlistNotSavedGate.resetForTest()
            val run = async(Dispatchers.Default) {
                action(
                    writes = c["writes"].num()!!.toInt(), notSaved = true, canAsk = false, skipConfirm = false,
                    screenAppears = c["screenAppears"] == true, canAskAtWrite = c["canAskAtWrite"] as Boolean? ?: true,
                )
            }
            var questions = 0
            (c["answer"] as Boolean?)?.let { answer ->
                withTimeout(5_000) { WatchlistNotSavedGate.request.filterNotNull().first() }
                questions++
                WatchlistNotSavedGate.answer(answer)
            }
            val (written, effects) = withTimeout(5_000) { run.await() }
            assertEquals(name, c["questions"].num()!!.toInt(), questions)
            assertEquals(name, c["written"].num()!!.toInt(), written)
            assertEquals(name, c["effects"], effects)
            assertNull(name, WatchlistNotSavedGate.request.value)
        }
        // Nach dem Durchlass: die nächste Aktion mit Oberfläche fragt wieder, nach «Abbrechen» gilt die Sicherung wieder
        val after = late["afterUnaskedPass"].obj()
        WatchlistNotSavedGate.resetForTest()
        assertTrue(WatchlistNotSavedGate.confirm(notSaved = true, canAsk = false, awaitAsker = { false }))
        val next = async(Dispatchers.Default) { WatchlistNotSavedGate.confirm(notSaved = true, canAsk = true) }
        val asked = withTimeout(5_000) { WatchlistNotSavedGate.request.filterNotNull().first() }.isActive
        assertEquals(after["nextActionAsks"], asked)
        WatchlistNotSavedGate.answer(false)
        assertFalse(withTimeout(5_000) { next.await() })
        assertEquals(after["refusesAgainAfterCancel"], WatchlistNotSavedGate.needsConfirmation(notSaved = true, canAsk = true))
    }

    /** Seit Build 106: Die Oberfläche beginnt erst während des Wartens zu sammeln (Activity neu erstellt) → es wird gefragt. */
    @Test
    fun confirmWaitsForAReturningScreenThenAsks() = runBlocking {
        val write = async(Dispatchers.Default) { WatchlistNotSavedGate.confirm(notSaved = true, canAsk = false) }
        kotlinx.coroutines.delay(100)
        val screen = launch(Dispatchers.Default) { WatchlistNotSavedGate.request.collect {} }
        withTimeout(5_000) { WatchlistNotSavedGate.request.filterNotNull().first() }
        WatchlistNotSavedGate.answer(true)
        assertTrue(withTimeout(5_000) { write.await() })
        screen.cancelAndJoin()
    }

    /** Seit Build 106: ohne Oberfläche endet das Warten nach der Frist (false), ohne Frage. */
    @Test
    fun awaitSubscriberTimesOut() = runBlocking {
        assertFalse(WatchlistNotSavedGate.awaitSubscriber(50))
        val passed = WatchlistNotSavedGate.confirm(
            notSaved = true, canAsk = false, awaitAsker = { WatchlistNotSavedGate.awaitSubscriber(50) },
        )
        assertTrue(passed)
        assertNull(WatchlistNotSavedGate.request.value)
        // Durchgelassen: die Sicherung lehnt diese Aktion auch mit Oberfläche nicht ab
        assertFalse(WatchlistNotSavedGate.needsConfirmation(notSaved = true, canAsk = true))
    }
}
