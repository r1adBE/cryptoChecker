package com.cryptochecker.app.domain.refresh

import com.cryptochecker.app.domain.live.LiveQuote
import com.cryptochecker.app.domain.refresh.RefreshFixture.Companion.above
import com.cryptochecker.app.domain.refresh.RefreshFixture.Companion.watch
import com.cryptochecker.marketdata.model.SimpleTicker
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Kurs-Meldungen nach Widgets und Bericht ([RefreshResultProcessor.process] mit `deferPrices`, danach
 * [RefreshResultProcessor.postPrices] als Nacharbeit unter der weitergereichten Sperre): gleiche Meldungen in gleicher
 * Reihenfolge, jede genau einmal, Meldekurs nur für tatsächlich gezeigte — wie bisher in einem Schritt. Alarm-Meldungen
 * kommen sofort, die Aktualisierung (hier ein Live-Takt) endet vor den Kurs-Meldungen.
 */
class PriceNotificationTailTest {

    private val ids = (1L..8L).toList()

    private fun fixture(): RefreshFixture = RefreshFixture(
        ids.map { watch(id = it, price = 100.0).copy(notificationEnabled = true) },
        listOf(above(105.0, id = 70, watchId = 3)),
    ).apply {
        // Paar 5 gilt als «von Android verworfen»: kein Meldekurs
        notifier.priceShown = { it != 5L }
    }

    private fun ticker(price: Double) = SimpleTicker().apply {
        last = price
        timestamp = System.currentTimeMillis()
    }

    private fun fetched(): Map<Long, Fetched> = ids.associateWith { Fetched(ticker(110.0 + it), null) }

    @Test
    fun deferredPricesMatchTheInlineWay() = runBlocking {
        val inline = fixture()
        val inlineProcessed = inline.lock.withLock { proof ->
            val watches = inline.store.getWatches()
            inline.processor.process(proof, watches, fetched(), inline.settings.settings, dayStart = null)
        }

        val deferred = fixture()
        val (processed, shown) = deferred.lock.withLock { proof ->
            val p = deferred.processor.process(
                proof, deferred.store.getWatches(), fetched(), deferred.settings.settings, dayStart = null,
                deferPrices = true,
            )
            // Alarm sofort, Kurs-Meldungen noch nicht
            assertEquals(listOf(70L), deferred.notifier.alarmPosts.map { it.first })
            assertTrue(deferred.notifier.pricePosts.isEmpty())
            assertTrue(deferred.store.notifiedPrices.isEmpty())
            val pending = p.pendingPrices
            assertNotNull(pending)
            p to deferred.processor.postPrices(proof, pending!!)
        }

        assertEquals(0, processed.notificationsShown)
        assertEquals(inlineProcessed.notificationsShown, shown)
        assertEquals(7, shown)
        // Reihenfolge und jede Meldung genau einmal
        assertEquals(ids, deferred.notifier.pricePosts.toList())
        assertEquals(inline.notifier.pricePosts.toList(), deferred.notifier.pricePosts.toList())
        // Meldekurs nur für gezeigte, gleich wie bisher
        assertEquals(inline.store.notifiedPrices.toList(), deferred.store.notifiedPrices.toList())
        assertTrue(deferred.store.notifiedPrices.none { it.first == 5L })
        assertEquals(inline.notifier.cancelledPrices.toList(), deferred.notifier.cancelledPrices.toList())
        assertEquals(inline.notifier.alarmPosts.toList(), deferred.notifier.alarmPosts.toList())
        assertEquals(inlineProcessed.alarms, processed.alarms)
    }

    @Test
    fun withoutPricePostsNothingIsPending() = runBlocking {
        val f = RefreshFixture(listOf(watch(price = 100.0)))
        val processed = f.lock.withLock { proof ->
            f.processor.process(
                proof, f.store.getWatches(), mapOf(1L to Fetched(ticker(110.0), null)), f.settings.settings,
                dayStart = null, deferPrices = true,
            )
        }
        assertNull(processed.pendingPrices)
        assertNull(f.processor.pricesTail(processed))
    }

    @Test
    fun liveTickEndsBeforeThePriceNotifications() = runBlocking {
        val f = fixture()
        val entered = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        f.notifier.beforeShowPrices = {
            entered.complete(Unit)
            gate.await()
        }
        val now = System.currentTimeMillis()
        val quotes = ids.associateWith { LiveQuote(price = 110.0 + it, change24h = 1.0, time = now) }

        assertTrue(withTimeout(5_000) { f.live.applyLive(quotes, redrawWidgets = true) })
        // Fertig: Kurse gespeichert, Alarm gemeldet, Zeit und Widgets — Kurs-Meldungen noch nicht
        assertEquals(1, f.store.refreshWrites.size)
        assertEquals(listOf(70L), f.notifier.alarmPosts.map { it.first })
        assertEquals(1, f.stats.lastRefreshCalls)
        assertEquals(1, f.widgets.draws)
        withTimeout(5_000) { entered.await() }
        assertTrue(f.notifier.pricePosts.isEmpty())
        // Die Sperre bleibt bis nach den Kurs-Meldungen gehalten: der nächste Takt gibt auf
        assertTrue(f.lock.isLocked)
        assertFalse(f.live.applyLive(quotes, redrawWidgets = true))

        gate.complete(Unit)
        withTimeout(5_000) { while (f.lock.isLocked) delay(5) }
        assertEquals(ids, f.notifier.pricePosts.toList())
        assertEquals(ids.filter { it != 5L }, f.store.notifiedPrices.map { it.first })
        assertEquals(1, f.store.refreshWrites.size)
    }

    @Test
    fun priceNotificationsSurviveAnErrorAfterTheSave() = runBlocking {
        val f = fixture()
        f.widgets.failure = IllegalStateException("Zeichnen gescheitert")
        val now = System.currentTimeMillis()
        val quotes = ids.associateWith { LiveQuote(price = 110.0 + it, change24h = 1.0, time = now) }

        val result = runCatching { withTimeout(5_000) { f.live.applyLive(quotes, redrawWidgets = true) } }
        // Der Fehler beim Zeichnen geht weiter, die Kurse sind aber gespeichert
        assertEquals("Zeichnen gescheitert", result.exceptionOrNull()?.message)
        assertEquals(1, f.store.refreshWrites.size)
        // Die vorgemerkten Kurs-Meldungen kommen trotzdem, jede genau einmal, Meldekurs nur für gezeigte
        withTimeout(5_000) { while (f.lock.isLocked || f.notifier.pricePosts.size < ids.size) delay(5) }
        assertEquals(ids, f.notifier.pricePosts.toList())
        assertEquals(ids.filter { it != 5L }, f.store.notifiedPrices.map { it.first })
    }
}
