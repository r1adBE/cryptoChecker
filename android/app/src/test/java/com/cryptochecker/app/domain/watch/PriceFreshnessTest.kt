package com.cryptochecker.app.domain.watch

import com.cryptochecker.app.data.local.model.WatchEntity
import com.cryptochecker.app.parity.ParityJson
import com.cryptochecker.app.parity.list
import com.cryptochecker.app.parity.num
import com.cryptochecker.app.parity.obj
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Frische des Kurses: gemeinsame Fälle `testdata/parity/price_freshness.json`
 * (Gegenstück `Tests/PriceFreshnessTests.swift`) und die Regeln für ein Paar.
 */
class PriceFreshnessTest {

    private val data = ParityJson.load("price_freshness.json")
    private val now = data["now"].num()!!.toLong()

    @Test
    fun freshnessParity() {
        data["freshness"].list().forEach { raw ->
            val c = raw.obj()
            assertEquals(
                c["name"] as String,
                c["expected"].num()!!.toLong(),
                PriceFreshness.freshnessTime(c["lastUpdate"].num()!!.toLong(), c["exchangeTime"].num()?.toLong()),
            )
        }
    }

    @Test
    fun staleParity() {
        data["stale"].list().forEach { raw ->
            val c = raw.obj()
            val lastUpdate = c["lastUpdateAge"].num()?.let { now - it.toLong() } ?: 0L
            val exchangeTime = c["exchangeTimeAge"].num()?.let { now - it.toLong() }
            assertEquals(
                c["name"] as String,
                (c["expected"] as String?)?.let { PriceFreshness.StaleReason.valueOf(it) },
                PriceFreshness.staleReason(lastUpdate, exchangeTime, now, c["staleAfter"].num()!!.toLong()),
            )
        }
    }

    @Test
    fun unchangedParity() {
        data["unchanged"].list().forEach { raw ->
            val c = raw.obj()
            val changedAt = c["priceChangedAtAge"].num()?.let { now - it.toLong() } ?: 0L
            val lastUpdate = c["lastUpdateAge"].num()?.let { now - it.toLong() } ?: 0L
            assertEquals(
                c["name"] as String,
                c["expected"].num()?.let { now - it.toLong() },
                PriceFreshness.unchangedSince(
                    c["exchangeTime"].num()?.toLong(), changedAt, lastUpdate, now, c["outdatedAfter"].num()!!.toLong(),
                ),
            )
        }
    }

    @Test
    fun nextParity() {
        data["next"].list().forEach { raw ->
            val c = raw.obj()
            assertEquals(
                c["name"] as String,
                c["expected"].num()!!.toLong(),
                PriceFreshness.nextPriceChangedAt(
                    c["previousPrice"].num(), c["previousChangedAt"].num()!!.toLong(),
                    c["price"].num()!!, c["time"].num()!!.toLong(),
                ),
            )
        }
    }

    private fun watch(
        exchangeTime: Long? = null,
        changedAgo: Long = 2 * 3_600_000L,
        disabled: Boolean = false,
        error: String? = null,
        price: Double? = 62_000.0,
    ) = WatchEntity(
        marketKey = "Kraken", marketName = "Kraken", baseAsset = "BTC", quoteAsset = "USD",
        lastPrice = price, lastUpdate = now - 10_000, lastError = error, disabled = disabled,
        priceChangedAt = now - changedAgo, exchangeTime = exchangeTime,
    )

    @Test
    fun watchWithoutExchangeTimeShowsUnchanged() {
        assertEquals(now - 2 * 3_600_000L, PriceFreshness.unchangedSince(watch(), now, 120_000L))
        assertEquals(now - 10_000, PriceFreshness.freshnessTime(watch()))
    }

    @Test
    fun watchNeverUnchangedWhenDisabledFailedWithoutPriceOrWithExchangeTime() {
        assertNull(PriceFreshness.unchangedSince(watch(disabled = true), now, 120_000L))
        assertNull(PriceFreshness.unchangedSince(watch(error = "timeout"), now, 120_000L))
        assertNull(PriceFreshness.unchangedSince(watch(price = null), now, 120_000L))
        assertNull(PriceFreshness.unchangedSince(watch(exchangeTime = now - 5_000), now, 120_000L))
        assertEquals(now - 5_000, PriceFreshness.freshnessTime(watch(exchangeTime = now - 5_000)))
    }

    @Test
    fun staleFollowsTheAlarmBlock() {
        // Eben abgefragt, letzter Handel laut Börse 3 h her: veraltet wie die Alarmsperre
        val old = watch(exchangeTime = now - 3 * 3_600_000L)
        assertEquals(PriceFreshness.StaleReason.TRADE, PriceFreshness.staleReason(old, now, 120_000L))
        assertTrue(PriceFreshness.isStale(old, now, 120_000L))
        assertTrue(com.cryptochecker.app.domain.alarm.AlarmFreshness.blocksAlarms(old.exchangeTime, false, now))
        // Ruhiges Paar (Handel vor 1 h) bzw. ohne Zeit der Börse: frisch
        assertFalse(PriceFreshness.isStale(watch(exchangeTime = now - 3_600_000L), now, 120_000L))
        assertFalse(PriceFreshness.isStale(watch(), now, 120_000L))
        // Deaktiviert nie; noch nie abgefragt immer
        assertFalse(PriceFreshness.isStale(old.copy(disabled = true), now, 120_000L))
        assertTrue(PriceFreshness.isStale(watch().copy(lastUpdate = 0), now, 120_000L))
    }

    @Test
    fun limitIsAtLeastOneHourOrTheOutdatedLimit() {
        assertEquals(3_600_000L, PriceFreshness.unchangedAfterMillis(120_000L))
        assertEquals(3_600_000L, PriceFreshness.unchangedAfterMillis(2_700_000L))
        assertEquals(10_800_000L, PriceFreshness.unchangedAfterMillis(10_800_000L))
    }
}
