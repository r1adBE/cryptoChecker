package com.cryptochecker.app.domain.watch

import com.cryptochecker.app.parity.ParityJson
import com.cryptochecker.app.parity.list
import com.cryptochecker.app.parity.obj
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** [WatchIdentity] an den gemeinsamen Fällen `testdata/parity/watch_identity.json` (wie `WatchIdentityTests.swift`). */
class WatchIdentityTest {

    private fun key(spec: Map<String, Any?>) = WatchIdentity.key(
        spec["marketKey"] as String,
        spec["base"] as String,
        spec["quote"] as String,
        spec["contract"] as String,
        spec["pairId"] as String?,
    )

    @Test
    fun sharedCases() {
        val cases = ParityJson.load("watch_identity.json")["cases"].list()
        for (raw in cases) {
            val c = raw.obj()
            val name = c["name"] as String
            val a = key(c["a"].obj())
            val b = key(c["b"].obj())
            assertEquals(name, c["pool"] as String?, a.pool)
            assertEquals(name, c["same"] as Boolean, a == b)
        }
    }

    @Test
    fun editKeyFollowsTheSameRule() {
        val first = WatchEdit.Key.of("DexScreener", "PEPE", "USD (ethereum)", "NONE", "ethereum/0xaaa")
        val second = WatchEdit.Key.of("DexScreener", "PEPE", "USD (ethereum)", "NONE", "ethereum/0xbbb")
        assertNotEquals(first, second)
        assertEquals(WatchEdit.Key("Binance", "BTC", "USDT", "NONE"), WatchEdit.Key.of("Binance", "BTC", "USDT", "NONE", "BTCUSDT"))
        assertNull(WatchIdentity.pool("Binance", "BTCUSDT"))
    }

    @Test
    fun restoreCleanupKeepsTwoPoolsButMergesTheSamePool() {
        data class W(val id: Long, val pairId: String)
        data class A(val id: Long, val watchId: Long)
        val (watches, alarms) = com.cryptochecker.app.data.WatchlistRestoreCleanup.clean(
            watches = listOf(W(1, "ethereum/0xaaa"), W(2, "ethereum/0xbbb"), W(3, "ethereum/0xaaa")),
            alarms = listOf(A(10, 3)),
            watchId = { it.id },
            pairKey = { WatchIdentity.key("DexScreener", "PEPE", "USD (ethereum)", "NONE", it.pairId) },
            alarmId = { it.id },
            alarmWatchId = { it.watchId },
            withWatchId = { a, id -> a.copy(watchId = id) },
        )
        assertEquals(listOf(1L, 2L), watches.map { it.id })
        // Alarm des weggelassenen Doppels hängt am behaltenen Pool
        assertEquals(listOf(A(10, 1)), alarms)
    }
}
