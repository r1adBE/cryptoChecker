package com.cryptochecker.app.data.portfolio

import com.cryptochecker.app.domain.portfolio.AddressBalance
import com.cryptochecker.app.domain.portfolio.AddressChain
import com.cryptochecker.app.domain.portfolio.WatchedAddress
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Single-Flight der Adress-Aktualisierung ([singleFlightRefresh]): zwei gleichzeitige Vordergrund-Aufrufe laden jede
 * Adresse genau einmal (auch xpub); der Hintergrund kehrt sofort zurück, wenn schon eine Runde läuft.
 */
class WatchedAddressSingleFlightTest {

    private val xpub = "xpub6ceT3jjUWNFQ4zxeZWGDSw8iM3MjHpxvZcKVZFLU4zbXnUGAnVSRraqr8LaZwCaUFT6TV7ZF6834RXLF5xzxa2ew6Jw63uPr91LryWJ8fiz"

    /** Fake-Speicher und Fake-Loader mit Zähler je Adresse; der Loader wartet auf [release]. */
    private inner class Fixture {
        val gate = Mutex()
        var stored = listOf(
            WatchedAddress(id = 1, chain = AddressChain.BTC, address = "bc1qdjgwenuvhz447xe3udlc0yh6ng8lnxd9v666m8"),
            WatchedAddress(id = 2, chain = AddressChain.BTC, address = xpub),
            WatchedAddress(id = 3, chain = AddressChain.ETH, address = "0x0000000000000000000000000000000000000001"),
        )
        val loads = mutableMapOf<Long, Int>()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val now = System.currentTimeMillis()

        suspend fun run(background: Boolean = false): Boolean = singleFlightRefresh(
            gate, background,
            read = { stored },
            load = { addresses ->
                val due = AddressBalance.dueOrder(addresses, now, force = false, background = background)
                due.forEach { loads[it.id] = (loads[it.id] ?: 0) + 1 }
                started.complete(Unit)
                release.await()
                due.map { it.id }
            },
            save = { ids -> stored = stored.map { if (it.id in ids) it.copy(lastAttempt = now, lastUpdated = now) else it } },
        )
    }

    @Test
    fun twoForegroundRefreshesLoadEachAddressOnce() = runBlocking {
        val f = Fixture()
        val first = async { f.run() }
        f.started.await()
        val second = async { f.run() }
        f.release.complete(Unit)
        val results = listOf(first, second).awaitAll()
        assertEquals(mapOf(1L to 1, 2L to 1, 3L to 1), f.loads)
        assertTrue(results[0])
        // Zweiter Aufruf liest nach der ersten Runde frisch: nichts mehr fällig
        assertFalse(results[1])
        assertTrue(f.stored[1].isXpub)
    }

    @Test
    fun backgroundReturnsImmediatelyWhileForegroundRuns() = runBlocking {
        val f = Fixture()
        val first = async { f.run() }
        f.started.await()
        assertFalse(f.run(background = true))
        f.release.complete(Unit)
        assertTrue(first.await())
        assertEquals(mapOf(1L to 1, 2L to 1, 3L to 1), f.loads)
        assertFalse(f.gate.isLocked)
    }

    @Test
    fun withoutAddressesNothingLoads() = runBlocking {
        val f = Fixture()
        f.stored = emptyList()
        f.release.complete(Unit)
        assertFalse(f.run())
        assertTrue(f.loads.isEmpty())
    }
}
