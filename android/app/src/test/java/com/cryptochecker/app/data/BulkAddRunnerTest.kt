package com.cryptochecker.app.data

import com.cryptochecker.app.data.FakeWatchStore.Companion.BINANCE
import com.cryptochecker.app.data.FakeWatchStore.Companion.pair
import com.cryptochecker.app.data.local.AppDatabase
import com.cryptochecker.app.data.local.DatabaseEncryptionNotice
import com.cryptochecker.app.data.local.WatchlistNotSavedGate
import com.cryptochecker.app.domain.model.MarketInfo
import com.cryptochecker.app.domain.watch.WatchlistLimits
import com.cryptochecker.marketdata.model.MarketPair
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
    ) : BulkAddWrites {
        lateinit var runner: BulkAddRunner
        /** Stand von [BulkAddRunner.progress] nach jeder Meldung des Fortschritts. */
        val seen = mutableListOf<BulkAddRunner.Progress?>()
        var firstPairMarks = 0

        override suspend fun confirm(): Boolean = confirmStep()

        override suspend fun add(
            market: MarketInfo,
            pairs: List<MarketPair>,
            group: String?,
            onProgress: (Int) -> Unit,
        ) = collectBulkAdd(
            pairs,
            refused = { writeRefused(blocked = false, notify = true) },
            add = { store.add(market, it) },
            onProgress = {
                onProgress(it)
                seen += runner.progress.value
            },
        )

        override suspend fun delete(ids: List<Long>) {
            if (!writeRefused(blocked = false, notify = true)) store.delete(ids)
        }

        override suspend fun markFirstPairAdded() {
            firstPairMarks++
        }
    }

    private lateinit var app: CoroutineScope

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
        app = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        return BulkAddRunner(writes, app).also { writes.runner = it }
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

    /** F-08 «Abbrechen» im Dialog: kein Balken, nichts geschrieben, kein Erst-Moment. */
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

        assertNull(runner.progress.value)
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
}
