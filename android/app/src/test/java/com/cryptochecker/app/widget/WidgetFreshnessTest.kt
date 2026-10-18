package com.cryptochecker.app.widget

import com.cryptochecker.app.data.local.model.WatchEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Frische in den Widgets ([WidgetFreshness]); Gegenstück `Tests/WidgetFreshnessTests.swift`. */
class WidgetFreshnessTest {

    private val now = 1_760_000_000_000L
    private val hour = 3_600_000L
    private val after = WidgetFreshness.afterMillis(15) // 45 Min.

    private fun watch(
        lastUpdateAgo: Long = 60_000L,
        changedAgo: Long = 2 * hour,
        exchangeTime: Long? = null,
        disabled: Boolean = false,
        error: String? = null,
    ) = WatchEntity(
        marketKey = "kraken", marketName = "Kraken", baseAsset = "BTC", quoteAsset = "EUR",
        lastPrice = 62_000.0, lastUpdate = now - lastUpdateAgo, lastError = error, disabled = disabled,
        priceChangedAt = now - changedAgo, exchangeTime = exchangeTime,
    )

    @Test
    fun widgetsUseThePollingLimitNeverTheLiveLimit() {
        assertEquals(45 * 60_000L, WidgetFreshness.afterMillis(15))
        assertEquals(15 * 60_000L, WidgetFreshness.afterMillis(1))
        assertEquals(180 * 60_000L, WidgetFreshness.afterMillis(60))
    }

    @Test
    fun unchangedPriceShowsSinceAndItsTime() {
        assertEquals(now - 2 * hour, WidgetFreshness.unchangedSince(watch(), now, after))
        assertEquals(now - 2 * hour, WidgetFreshness.priceTime(watch(), now, after))
    }

    @Test
    fun recentChangeIsFreshWithFetchTime() {
        val w = watch(changedAgo = 30 * 60_000L)
        assertNull(WidgetFreshness.unchangedSince(w, now, after))
        assertEquals(now - 60_000L, WidgetFreshness.priceTime(w, now, after))
    }

    @Test
    fun exchangeTimeWinsAndNeverUnchanged() {
        val w = watch(exchangeTime = now - 5 * 60_000L)
        assertNull(WidgetFreshness.unchangedSince(w, now, after))
        assertEquals(now - 5 * 60_000L, WidgetFreshness.priceTime(w, now, after))
    }

    @Test
    fun outdatedDisabledOrFailedPairsShowNoUnchangedHint() {
        assertNull(WidgetFreshness.unchangedSince(watch(lastUpdateAgo = hour), now, after))
        assertNull(WidgetFreshness.unchangedSince(watch(disabled = true), now, after))
        assertNull(WidgetFreshness.unchangedSince(watch(error = "timeout"), now, after))
    }

    @Test
    fun outdatedLikeTheRowAndTheAlarmBlock() {
        // Abruf zu alt: Zeit des Abrufs
        assertEquals(now - hour, WidgetFreshness.outdatedTime(watch(lastUpdateAgo = hour), now, after))
        // Abruf frisch, letzter Handel 3 h her: Zeit des Handels; ruhiges Paar (1 h) nicht
        assertEquals(now - 3 * hour, WidgetFreshness.outdatedTime(watch(exchangeTime = now - 3 * hour), now, after))
        assertNull(WidgetFreshness.outdatedTime(watch(exchangeTime = now - hour), now, after))
        assertNull(WidgetFreshness.outdatedTime(watch(), now, after))
        assertNull(WidgetFreshness.outdatedTime(watch(lastUpdateAgo = hour, disabled = true), now, after))
        // Wecker: Handel so verschoben, dass er mit derselben Grenze genau nach 2 h wechselt
        val w = watch(exchangeTime = now - hour)
        assertEquals(listOf(w.lastUpdate, now - hour + 2 * hour - after), WidgetFreshness.outdatedTimes(w, after))
        assertEquals(now + hour + 1, com.cryptochecker.app.domain.refresh.OutdatedRule.nextChangeAt(
            listOf(now - hour + 2 * hour - after), now, after,
        ))
    }
}
