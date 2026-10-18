package com.cryptochecker.app.domain.refresh

import com.cryptochecker.app.data.pairIdentity
import com.cryptochecker.app.domain.live.LiveQuote
import com.cryptochecker.app.domain.refresh.RefreshFixture.Companion.above
import com.cryptochecker.app.domain.refresh.RefreshFixture.Companion.watch
import com.cryptochecker.marketdata.model.SimpleTicker
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Live-Kurse ([LivePriceApplier]) neben einer laufenden Aktualisierung — beide über dieselbe [RefreshLock] und
 * denselben letzten Schritt ([RefreshResultProcessor]), hier mit Fakes statt Datenbank: Ein Live-Takt gibt auf,
 * solange die Aktualisierung schreibt; das letzte Speichern wartet und schreibt danach (Alarm-Kreuzung gegenüber dem
 * eben gespeicherten Kurs); nie schreiben beide gleichzeitig.
 */
class LivePriceApplierLockTest {

    private fun quote(price: Double) = LiveQuote(price = price, change24h = 1.0, time = System.currentTimeMillis())

    /** Steht für refreshAll: Kurse unter der Sperre schreiben und dann (bis [release]) die Sperre halten. */
    private fun RefreshFixture.runningRefresh(price: Double, held: CompletableDeferred<Unit>, release: CompletableDeferred<Unit>) =
        CoroutineScope(Dispatchers.Default).launch {
            lock.withLock { proof ->
                val ticker = SimpleTicker().apply {
                    last = price
                    timestamp = System.currentTimeMillis()
                }
                processor.process(proof, store.getWatches(), mapOf(1L to Fetched(ticker, null)), settings.settings, dayStart = null)
                held.complete(Unit)
                release.await()
            }
        }

    @Test
    fun liveTickGivesUpWhileARefreshHoldsTheLock() = runBlocking {
        val f = RefreshFixture(listOf(watch(price = 99.0)))
        val held = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val refresh = f.runningRefresh(99.5, held, release)
        withTimeout(5_000) { held.await() }

        assertFalse(f.live.applyLive(mapOf(1L to quote(101.0)), redrawWidgets = false))
        assertEquals("nur die Aktualisierung hat geschrieben", 1, f.store.refreshWrites.size)

        release.complete(Unit)
        refresh.join()
        assertTrue(f.live.applyLive(mapOf(1L to quote(101.0)), redrawWidgets = false))
        assertEquals(2, f.store.refreshWrites.size)
        assertEquals(101.0, f.store.watches.getValue(1).lastPrice!!, 0.0)
        // Charts danach ausserhalb der Sperre
        assertEquals(1, f.charts.launches)
    }

    @Test
    fun finalSaveWaitsForTheRefreshAndCrossesFromItsPrice() = runBlocking {
        val f = RefreshFixture(listOf(watch(price = 99.0)), listOf(above(100.0)))
        val held = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        // Zwei Schreibvorgänge gleichzeitig wären ein Fehler der Sperre
        val writing = AtomicInteger(0)
        var overlap = false
        f.store.beforeApply = {
            if (writing.incrementAndGet() > 1) overlap = true
            delay(20)
            writing.decrementAndGet()
        }
        val refresh = f.runningRefresh(99.5, held, release)
        withTimeout(5_000) { held.await() }

        val final = async(Dispatchers.Default) {
            f.live.applyLiveFinal(mapOf(1L to quote(101.0)), redrawWidgets = true, timeoutMillis = 5_000)
        }
        delay(200)
        assertFalse("wartet auf die Sperre", final.isCompleted)
        assertEquals(1, f.store.refreshWrites.size)

        release.complete(Unit)
        assertTrue(withTimeout(5_000) { final.await() })
        refresh.join()

        // Reihenfolge: erst die Aktualisierung (99.5), dann der Live-Kurs (101)
        assertEquals(listOf(99.5, 101.0), f.store.refreshWrites.map { it.single().price })
        assertFalse(overlap)
        // Kreuzung gegenüber dem eben gespeicherten Kurs: einmal gemeldet
        assertEquals(1, f.notifier.alarmPosts.size)
        assertEquals(101.0, f.notifier.alarmPosts.single().second, 0.0)
        // Widgets nach Live-Kursen gezeichnet
        assertEquals(1, f.widgets.draws)
    }

    @Test
    fun finalSaveGivesUpWhenTheLockStaysBusy() = runBlocking {
        val f = RefreshFixture(listOf(watch(price = 99.0)))
        val held = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val refresh = f.runningRefresh(99.5, held, release)
        withTimeout(5_000) { held.await() }

        assertFalse(f.live.applyLiveFinal(mapOf(1L to quote(101.0)), redrawWidgets = false, timeoutMillis = 100))
        assertEquals(1, f.store.refreshWrites.size)

        release.complete(Unit)
        refresh.join()
        assertEquals(99.5, f.store.watches.getValue(1).lastPrice!!, 0.0)
    }

    @Test
    fun quotesForChangedOrDisabledPairsAreDropped() = runBlocking {
        val edited = watch(id = 1, price = 99.0)
        val disabled = watch(id = 2, price = 99.0).copy(disabled = true)
        val f = RefreshFixture(listOf(edited, disabled))
        // Abonniert war Paar 1 noch als ETH/USDT (inzwischen bearbeitet)
        val subscribed = mapOf(1L to edited.copy(baseAsset = "ETH").pairIdentity, 2L to disabled.pairIdentity)

        assertTrue(f.live.applyLive(mapOf(1L to quote(101.0), 2L to quote(101.0)), redrawWidgets = false, pairs = subscribed))

        assertTrue("nichts gespeichert", f.store.refreshWrites.isEmpty())
        assertEquals(99.0, f.store.watches.getValue(1).lastPrice!!, 0.0)
        assertEquals(99.0, f.store.watches.getValue(2).lastPrice!!, 0.0)
    }
}
