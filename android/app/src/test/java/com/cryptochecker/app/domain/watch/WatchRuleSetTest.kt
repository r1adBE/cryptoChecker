package com.cryptochecker.app.domain.watch

import com.cryptochecker.app.parity.ParityJson
import com.cryptochecker.app.parity.list
import com.cryptochecker.app.parity.obj
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Kleine Testklassen dieses Themas in einer Datei; jede Klasse unverändert.

// ---- aus WatchEditTest

class WatchEditTest {

    private val btc = WatchEdit.Key("Binance", "BTC", "USDT", "NONE")

    @Test
    fun `same selection is unchanged, even when the lookup finds the entry itself`() {
        assertEquals(WatchEdit.Outcome.UNCHANGED, WatchEdit.decide(7, btc, btc.copy(), 7))
        assertEquals(WatchEdit.Outcome.UNCHANGED, WatchEdit.decide(7, btc, btc, null))
    }

    @Test
    fun `other exchange, pair or contract is a change`() {
        assertEquals(WatchEdit.Outcome.CHANGED, WatchEdit.decide(7, btc, btc.copy(marketKey = "Kraken"), null))
        assertEquals(WatchEdit.Outcome.CHANGED, WatchEdit.decide(7, btc, btc.copy(quote = "USDC"), null))
        assertEquals(WatchEdit.Outcome.CHANGED, WatchEdit.decide(7, btc, btc.copy(contract = "PERPETUAL"), null))
        // Treffer ist der Eintrag selbst (kann bei einem Wettlauf vorkommen): kein Doppel
        assertEquals(WatchEdit.Outcome.CHANGED, WatchEdit.decide(7, btc, btc.copy(base = "ETH"), 7))
    }

    @Test
    fun `target already watched by another entry is a duplicate`() {
        assertEquals(WatchEdit.Outcome.DUPLICATE, WatchEdit.decide(7, btc, btc.copy(base = "ETH"), 3))
        assertEquals(WatchEdit.Outcome.DUPLICATE, WatchEdit.decide(7, btc, btc.copy(contract = "PERPETUAL"), 9))
    }

    @Test
    fun `alarm warning only for a different selection with alarms`() {
        assertTrue(WatchEdit.warnAlarms(btc, btc.copy(quote = "USDC"), 2))
        assertFalse(WatchEdit.warnAlarms(btc, btc.copy(quote = "USDC"), 0))
        assertFalse(WatchEdit.warnAlarms(btc, btc, 2))
        assertFalse(WatchEdit.warnAlarms(btc, null, 2))
    }
}

// ---- aus WatchIdentityTest

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

// ---- aus WatchJumpTest

class WatchJumpTest {

    @Test
    fun eligible_onlyAboveThirtyAndNotSorting() {
        assertFalse(WatchJump.eligible(30, sorting = false))
        assertTrue(WatchJump.eligible(31, sorting = false))
        assertFalse(WatchJump.eligible(100, sorting = true))
    }

    @Test
    fun pointsDown_inUpperHalf() {
        assertTrue(WatchJump.pointsDown(firstVisible = 0, lastVisible = 10, total = 50))
        assertTrue(WatchJump.pointsDown(firstVisible = 15, lastVisible = 25, total = 50))
        assertFalse(WatchJump.pointsDown(firstVisible = 20, lastVisible = 30, total = 50))
        assertFalse(WatchJump.pointsDown(firstVisible = 40, lastVisible = 49, total = 50))
    }

    @Test
    fun animate_onlyShortDistancesWithoutReduceMotion() {
        assertTrue(WatchJump.animate(-40, reduceMotion = false))
        assertFalse(WatchJump.animate(41, reduceMotion = false))
        assertFalse(WatchJump.animate(5, reduceMotion = true))
    }
}
