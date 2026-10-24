package com.cryptochecker.app.data

import com.cryptochecker.app.data.FakeWatchStore.Companion.BINANCE
import com.cryptochecker.app.data.FakeWatchStore.Companion.pair
import com.cryptochecker.app.data.local.AppDatabase
import com.cryptochecker.app.data.local.DatabaseEncryptionNotice
import com.cryptochecker.app.data.local.WatchlistNotSavedGate
import com.cryptochecker.app.domain.model.MarketInfo
import com.cryptochecker.app.domain.watch.WatchlistLimits
import com.cryptochecker.app.ui.features.explorer.BulkAddResult as ShownResult
import com.cryptochecker.app.ui.features.explorer.BulkAddDisplay
import com.cryptochecker.marketdata.model.MarketPair
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Sammelaufnahme als Verhalten ([BulkAddRunner] mit dem echten Ablauf [collectBulkAdd] und der echten Sicherung
 * [writeRefused] gegen eine Merkliste im Speicher, [FakeWatchStore]; Gegenstück iOS `AddWatchesBehaviorTests`):
 * Fortschritt zählt je Paar hoch, vorhandene Paare werden übersprungen, über der Obergrenze abgelehnt (`refused`),
 * [BulkAddResult.addedIds] sind genau die neuen und «Rückgängig» entfernt nur diese. Dazu F-08: Der Runner holt die
 * Zustimmung in SEINER Coroutine (ein Durchlass des Explorers in dessen Coroutine reicht im App-Bereich nicht).
 * Deterministisch: App-Bereich auf dem Test-Takt (StandardTestDispatcher), kein echtes Warten.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BulkAddRunnerTest {

    /** Schreibwege wie [WatchRepository] (Zustimmung, Sicherung, Ablauf), nur die Datenbank im Speicher. */
    private class Writes(
        val store: FakeWatchStore,
        val confirmStep: suspend () -> Boolean = { WatchlistNotSavedGate.confirm() },
        /** Beim Anlegen dieses Coins scheitert die Datenbank (Ausnahme). */
        val failOn: String? = null,
    ) : BulkAddWrites {
        lateinit var runner: BulkAddRunner
        /** Stand von [BulkAddRunner.progress] nach jeder Meldung des Fortschritts. */
        val seen = mutableListOf<BulkAddRunner.Progress?>()
        var firstPairMarks = 0
        /** Paare, die inzwischen einen Alarm haben. */
        val alarmed = mutableSetOf<Long>()

        override suspend fun confirm(): Boolean = confirmStep()

        override suspend fun add(
            market: MarketInfo,
            pairs: List<MarketPair>,
            group: String?,
            onProgress: (Int) -> Unit,
        ) = collectBulkAdd(
            pairs,
            refused = { writeRefused(blocked = false, notify = true) },
            add = {
                check(it.base != failOn) { "Datenbank-Schlüssel nicht lesbar" }
                store.add(market, it)
            },
            onProgress = {
                onProgress(it)
                seen += runner.progress.value
            },
        )

        override suspend fun deleteWithoutAlarms(ids: List<Long>) {
            if (writeRefused(blocked = false, notify = true)) return
            store.delete(withoutAlarms(ids) { chunk -> chunk.filter { it in alarmed } })
        }

        override suspend fun markFirstPairAdded() {
            firstPairMarks++
        }
    }

    private lateinit var app: CoroutineScope
    /** Uhr des Runners (ms). */
    private var clock = 1_000_000L
    /** Ausnahmen, die der Runner im App-Bereich weiterwirft. */
    private val crashes = mutableListOf<Throwable>()

    @Before
    fun setUp() = WatchlistNotSavedGate.resetForTest()

    @After
    fun tearDown() {
        if (::app.isInitialized) app.cancel()
        DatabaseEncryptionNotice.clearInMemoryForTest(AppDatabase.DB_NAME)
        WatchlistNotSavedGate.resetForTest()
    }

    /** Runner im App-Bereich (eigener Job wie `appScope`, Test-Takt). */
    private fun TestScope.runner(writes: Writes): BulkAddRunner {
        val handler = CoroutineExceptionHandler { _, e -> crashes += e }
        app = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler) + handler)
        return BulkAddRunner(writes, app) { clock }.also { writes.runner = it }
    }

    @Test
    fun progressCountsUpDuplicatesAreSkippedAndUndoRemovesOnlyTheNewPairs() = runTest {
        val store = FakeWatchStore()
        store.add(BINANCE, pair("BTC"))
        store.add(BINANCE, pair("ETH"))
        store.writes.clear()
        val writes = Writes(store)
        val runner = runner(writes)

        assertTrue(runner.start(BINANCE, listOf(pair("ETH"), pair("SOL"), pair("BTC"), pair("XRP")), group = null))
        assertEquals(BulkAddRunner.Progress(0, 4), runner.progress.value)
        advanceUntilIdle()

        assertEquals((1..4).map { BulkAddRunner.Progress(it, 4) }, writes.seen)
        val result = checkNotNull(runner.progress.value?.result) { "kein Ergebnis" }
        assertEquals(BulkAddResult(added = 2, skipped = 2, refused = 0, addedIds = listOf(3L, 4L)), result)
        assertEquals(1, writes.firstPairMarks)

        runner.undo(result.addedIds)
        advanceUntilIdle()
        assertEquals(listOf("add 3", "add 4", "delete 3", "delete 4"), store.writes)
        assertEquals(setOf(1L, 2L), store.entries.keys)
        assertTrue(store.contains(BINANCE, pair("BTC")) && store.contains(BINANCE, pair("ETH")))
    }

    @Test
    fun fullWatchlistRefusesTheRest() = runTest {
        val store = FakeWatchStore(filler = WatchlistLimits.MAX_PAIRS - 1)
        val writes = Writes(store)
        val runner = runner(writes)

        runner.start(BINANCE, listOf(pair("A"), pair("B"), pair("C")), group = null)
        advanceUntilIdle()

        val result = checkNotNull(runner.progress.value?.result) { "kein Ergebnis" }
        assertEquals(1, result.added)
        assertEquals(2, result.refused)
        assertEquals(listOf(WatchlistLimits.MAX_PAIRS.toLong()), result.addedIds)
        assertEquals(WatchlistLimits.MAX_PAIRS, store.count)
        // Abgelehnte zählen im Fortschritt mit: der Balken läuft bis zum Ende
        assertEquals(listOf(1, 2, 3), writes.seen.map { it?.done })

        runner.undo(result.addedIds)
        advanceUntilIdle()
        assertEquals(WatchlistLimits.MAX_PAIRS - 1, store.count)
        assertFalse(store.contains(BINANCE, pair("A")))
    }

    @Test
    fun secondStartWhileRunningIsIgnoredAndResultStaysUntilCleared() = runTest {
        val runner = runner(Writes(FakeWatchStore()))
        assertTrue(runner.start(BINANCE, listOf(pair("BTC")), group = null))
        assertFalse(runner.start(BINANCE, listOf(pair("ETH")), group = null))
        advanceUntilIdle()
        assertEquals(1, runner.progress.value?.result?.added)
        runner.clearResult()
        assertNull(runner.progress.value)
    }

    /** F-08 «Abbrechen» im Dialog: eindeutiges Ende (kein Balken), nichts geschrieben, kein Erst-Moment. */
    @Test
    fun cancelledConsentWritesNothing() = runTest {
        DatabaseEncryptionNotice.markInMemory(AppDatabase.DB_NAME)
        backgroundScope.launch { WatchlistNotSavedGate.request.collect {} }
        val writes = Writes(FakeWatchStore())
        val runner = runner(writes)

        runner.start(BINANCE, listOf(pair("BTC"), pair("ETH")), group = null)
        runCurrent()
        assertNotNull("Dialog erwartet", WatchlistNotSavedGate.request.value)
        WatchlistNotSavedGate.answer(false)
        advanceUntilIdle()

        assertEquals(BulkAddRunner.Progress(0, 2, cancelled = true), runner.progress.value)
        assertFalse(runner.progress.value!!.running)
        assertEquals(emptyList<String>(), writes.store.writes)
        assertEquals(0, writes.firstPairMarks)
    }

    /**
     * F-08, ARC7-02: Die Oberfläche fehlte beim Bestätigen (Durchlass), ist beim Schreiben aber da. Der Runner
     * bestätigt in seiner eigenen Coroutine — dort gilt der Durchlass, die Paare werden angelegt; «Rückgängig» ebenso.
     */
    @Test
    fun runnerConsentCoversItsOwnCoroutine() = runTest {
        DatabaseEncryptionNotice.markInMemory(AppDatabase.DB_NAME)
        backgroundScope.launch { WatchlistNotSavedGate.request.collect {} }
        val unaskedPass = suspend { WatchlistNotSavedGate.confirm(canAsk = false, awaitAsker = { false }) }
        val writes = Writes(FakeWatchStore(), confirmStep = unaskedPass)
        val runner = runner(writes)

        runner.start(BINANCE, listOf(pair("BTC"), pair("ETH")), group = null)
        advanceUntilIdle()
        val result = checkNotNull(runner.progress.value?.result) { "kein Ergebnis" }
        assertEquals(listOf(1L, 2L), result.addedIds)

        runner.undo(result.addedIds)
        advanceUntilIdle()
        assertEquals(0, writes.store.count)
        assertNull("nie gefragt", WatchlistNotSavedGate.request.value)
    }

    /**
     * Gegenprobe: Verliesse sich der Runner auf die Zustimmung des Explorers (in dessen Coroutine durchgelassen), würde
     * die Sicherung im App-Bereich ablehnen — gesperrt gemeldet, nichts angelegt. Darum fragt [BulkAddRunner.start]
     * selbst.
     */
    @Test
    fun consentOfAnotherCoroutineDoesNotReachTheAppScope() = runTest {
        DatabaseEncryptionNotice.markInMemory(AppDatabase.DB_NAME)
        backgroundScope.launch { WatchlistNotSavedGate.request.collect {} }
        val writes = Writes(FakeWatchStore(), confirmStep = { true })
        val runner = runner(writes)

        // «Explorer-Aktion»: Durchlass ohne Oberfläche in der Test-Coroutine
        assertTrue(WatchlistNotSavedGate.confirm(canAsk = false, awaitAsker = { false }))
        runner.start(BINANCE, listOf(pair("BTC")), group = null)
        advanceUntilIdle()

        assertEquals(BulkAddRunner.Progress(1, 1, locked = true), runner.progress.value)
        assertEquals(emptyList<String>(), writes.store.writes)
        assertEquals(0, writes.firstPairMarks)
    }
    /**
     * Fehler mitten in der Aufnahme (Ausnahme): eindeutiges Ende «Fehler» statt eines ewigen Balkens; die Ausnahme
     * geht weiter. Der Explorer räumt ab (Knopf frei, kein Balken) und meldet den Fehler selbst, der Runner ist leer.
     */
    @Test
    fun failureEndsWithFailedAndExplorerClearsIt() = runTest {
        val writes = Writes(FakeWatchStore(), confirmStep = { true }, failOn = "ETH")
        val runner = runner(writes)
        val shown = MutableStateFlow<ShownResult?>(null)
        val display = BulkAddDisplay(runner, shown)

        runner.start(BINANCE, listOf(pair("BTC"), pair("ETH"), pair("SOL")), group = null)
        advanceUntilIdle()
        assertEquals(BulkAddRunner.Progress(1, 3, failed = true), runner.progress.value)
        assertEquals(1, crashes.size)

        display.apply(runner.progress.value)
        assertEquals(ShownResult(added = 0, skipped = 0, failed = true), shown.value)
        assertFalse(shown.value!!.running)
        assertNull("Ende gemeldet", runner.progress.value)
        // Neuer Versuch möglich
        assertTrue(runner.start(BINANCE, listOf(pair("SOL")), group = null))
    }

    /** «Abbrechen» der Rückfrage im Runner: der Explorer zeigt weder Balken noch Zählung, der Knopf ist wieder frei. */
    @Test
    fun cancelledConsentClearsTheExplorer() = runTest {
        val writes = Writes(FakeWatchStore(), confirmStep = { false })
        val runner = runner(writes)
        val shown = MutableStateFlow<ShownResult?>(null)
        val display = BulkAddDisplay(runner, shown)
        backgroundScope.launch { display.collect() }

        // Wie `addBulkPairs`: Explorer zeigt «läuft», bevor der Runner fragt
        shown.value = ShownResult(added = 0, skipped = 0, running = true)
        runner.start(BINANCE, listOf(pair("BTC")), group = null)
        advanceUntilIdle()

        assertNull(shown.value)
        assertNull(runner.progress.value)
        assertEquals(emptyList<String>(), writes.store.writes)
    }

    /**
     * Ergebnis nur einmal gemeldet: Beginnt der Explorer die Meldung ([BulkAddDisplay.claim]), ist der Runner sofort
     * leer (die Merkliste zeigt nichts mehr), die Anzeige des Explorers bleibt aber dieselbe (Snackbar läuft weiter).
     * Umgekehrt: Hat die Merkliste gemeldet ([BulkAddRunner.clearResult]), zeigt der Explorer es nicht mehr.
     */
    @Test
    fun resultIsReportedOnlyOnce() = runTest {
        val runner = runner(Writes(FakeWatchStore(), confirmStep = { true }))
        val shown = MutableStateFlow<ShownResult?>(null)
        val display = BulkAddDisplay(runner, shown)
        backgroundScope.launch { display.collect() }

        runner.start(BINANCE, listOf(pair("BTC")), group = null)
        advanceUntilIdle()
        val result = checkNotNull(shown.value) { "kein Ergebnis im Explorer" }
        assertEquals(1, result.added)

        display.claim()
        advanceUntilIdle()
        assertNull("Merkliste meldet nicht noch einmal", runner.progress.value)
        assertSame("Snackbar des Explorers läuft weiter", result, shown.value)
        display.clear()
        assertNull(shown.value)

        runner.start(BINANCE, listOf(pair("ETH")), group = null)
        advanceUntilIdle()
        assertEquals(1, shown.value?.added)
        runner.clearResult()
        advanceUntilIdle()
        assertNull("schon von der Merkliste gemeldet", shown.value)
    }

    /** «Rückgängig» nur kurz nach dem Ende: höchstens [BulkAddRunner.UNDO_WINDOW_MS] nach dem Ergebnis. */
    @Test
    fun undoIsOfferedOnlyShortlyAfterTheEnd() = runTest {
        val runner = runner(Writes(FakeWatchStore(), confirmStep = { true }))
        runner.start(BINANCE, listOf(pair("BTC")), group = null)
        advanceUntilIdle()
        val done = checkNotNull(runner.progress.value)
        assertEquals(clock, done.finishedAt)

        assertTrue(runner.undoOffered(done))
        clock += BulkAddRunner.UNDO_WINDOW_MS
        assertTrue(runner.undoOffered(done))
        clock += 1
        assertFalse("nach 60 s nur noch die Meldung", runner.undoOffered(done))
        // Kein Ergebnis bzw. Uhr rückwärts: nicht anbieten
        assertFalse(BulkAddRunner.undoOffered(finishedAt = 0, now = 5))
        assertFalse(BulkAddRunner.undoOffered(finishedAt = 10_000, now = 5_000))
    }

    /** «Rückgängig» lässt neu angelegte Paare stehen, die inzwischen einen Alarm haben; die übrigen gehen. */
    @Test
    fun undoKeepsPairsThatGotAnAlarm() = runTest {
        val writes = Writes(FakeWatchStore(), confirmStep = { true })
        val runner = runner(writes)
        runner.start(BINANCE, listOf(pair("BTC"), pair("ETH"), pair("SOL")), group = null)
        advanceUntilIdle()
        val ids = checkNotNull(runner.progress.value?.result).addedIds
        assertEquals(listOf(1L, 2L, 3L), ids)

        writes.alarmed += 2L
        runner.undo(ids)
        advanceUntilIdle()

        assertEquals(setOf(2L), writes.store.entries.keys)
        assertTrue(writes.store.contains(BINANCE, pair("ETH")))
    }

    @Test
    fun withoutAlarmsAsksInChunksAndKeepsOrder() = runTest {
        val asked = mutableListOf<Int>()
        val ids = (1L..1_200L).toList() + 5L
        val kept = withoutAlarms(ids) { chunk ->
            asked += chunk.size
            chunk.filter { it % 2 == 0L }
        }
        assertEquals(listOf(500, 500, 200), asked)
        assertEquals((1L..1_200L).filter { it % 2 == 1L }, kept)
    }
}
