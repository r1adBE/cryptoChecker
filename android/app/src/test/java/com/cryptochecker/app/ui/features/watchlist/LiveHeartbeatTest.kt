package com.cryptochecker.app.ui.features.watchlist

import com.cryptochecker.app.domain.model.WatchEntity
import com.cryptochecker.app.domain.watch.LiveStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ruhiges Paar an einem gesunden Live-Strom: Frische am letzten Takt, nicht am letzten Kurswechsel.
 * Wie LiveHeartbeatTests.swift.
 */
class LiveHeartbeatTest {

    private val min = 60_000L
    private val now = 1_000 * min
    private val polling = LiveStatus.pollingStaleAfterMillis(15)

    /** Kurs seit 10 Min. unverändert (Binance schickt jede Sekunde denselben Kurs). */
    private fun quiet(id: Long = 1) = WatchEntity(
        id = id, exchangeKey = "Binance", exchangeName = "Binance", baseAsset = "BTC", quoteAsset = "USDT",
        lastPrice = 50_000.0, lastUpdate = now - 10 * min,
    )

    @Test
    fun quietPairOnHealthyStreamIsNotStale() {
        assertFalse(WatchlistStatus.isStale(quiet(), now, polling, liveIds = setOf(1L)))
        val status = WatchlistStatus.of(listOf(quiet()), now, polling, online = true, liveIds = setOf(1L))
        assertEquals(0, status.stale)
        // «Live» bleibt in der Pille
        assertTrue(status.allLive)
    }

    @Test
    fun rowAndPageMeasureAtTheLastTick() {
        val shown = LiveOverlay.atHeartbeat(quiet(), onLiveSocket = true, now = now)
        assertEquals(LiveStatus.freshnessTime(now - 10 * min, onLiveSocket = true, now = now), shown.lastUpdate)
        assertFalse(
            com.cryptochecker.app.domain.watch.PriceFreshness.isStale(
                shown, now, LiveStatus.staleAfterMillis(onLiveSocket = true, pollingAfter = polling),
            ),
        )
    }

    @Test
    fun withoutStreamTheStoredTimeCounts() {
        val watch = quiet()
        assertSame(watch, LiveOverlay.atHeartbeat(watch, onLiveSocket = false, now = now))
        // Ohne Strom: Abfrage-Schwelle (45 Min.) ab dem letzten Abruf — 10 Min. sind frisch, 50 Min. veraltet
        assertFalse(WatchlistStatus.isStale(watch, now, polling))
        assertTrue(WatchlistStatus.isStale(watch.copy(lastUpdate = now - 50 * min), now, polling))
    }

    @Test
    fun disabledPairIsLeftAlone() {
        val off = quiet().copy(disabled = true)
        assertSame(off, LiveOverlay.atHeartbeat(off, onLiveSocket = true, now = now))
    }
}
