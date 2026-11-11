package com.cryptochecker.app.data.portfolio

import com.cryptochecker.app.domain.portfolio.AddressBalance
import com.cryptochecker.app.domain.portfolio.AddressChain
import com.cryptochecker.app.domain.portfolio.WatchedAddress
import com.cryptochecker.app.domain.portfolio.WatchedAddressRules
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Single-Flight der Adress-Aktualisierung ([singleFlightRefresh]): zwei gleichzeitige Vordergrund-Aufrufe laden jede
 * Adresse genau einmal (auch xpub); der Hintergrund kehrt sofort zurück, wenn schon eine Runde läuft. Während einer
 * Runde etwas dazugenommen («Tokens mitzählen» an, weiteres Netz — auch wenn der Abzug noch nie versucht war):
 * Ergebnis verworfen, der Wartende lädt neu. Abbruch und Fehler geben das Gate frei. Gegenstück: `AddressRefreshGateTests.swift`.
 */
class WatchedAddressSingleFlightTest {

    private val xpub = "xpub6ceT3jjUWNFQ4zxeZWGDSw8iM3MjHpxvZcKVZFLU4zbXnUGAnVSRraqr8LaZwCaUFT6TV7ZF6834RXLF5xzxa2ew6Jw63uPr91LryWJ8fiz"

    /**
     * Fake-Speicher und Fake-Loader mit Zähler je Adresse; der Loader wartet auf [release]. Ergebnis je Adresse mit den
     * Einstellungen des Abzugs; Speichern wie das Repository ([AddressBalance.discardFetched]).
     */
    private inner class Fixture {
        val gate = Mutex()
        val now = System.currentTimeMillis()
        var stored = listOf(
            WatchedAddress(id = 1, chain = AddressChain.BTC, address = "bc1qdjgwenuvhz447xe3udlc0yh6ng8lnxd9v666m8"),
            WatchedAddress(id = 2, chain = AddressChain.BTC, address = xpub),
            // Schon früher versucht (vor einer Stunde), Token aus
            WatchedAddress(
                id = 3, chain = AddressChain.ETH, address = "0x0000000000000000000000000000000000000001",
                lastAttempt = now - 3_600_000, countTokens = false,
            ),
        )
        val loads = mutableMapOf<Long, Int>()
        /** «Tokens mitzählen» der Adresse 3 je Abruf. */
        val ethCountTokens = mutableListOf<Boolean>()
        /** Gewählte Netze je Abruf der Adresse 4 (nur in Tests, die sie anlegen). */
        val netsOf4 = mutableListOf<List<String>?>()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var failRead = false

        suspend fun run(background: Boolean = false): Boolean = singleFlightRefresh(
            gate, background,
            read = { check(!failRead) { "nicht lesbar" }; stored },
            load = { addresses ->
                val due = AddressBalance.dueOrder(addresses, now, force = false, background = background)
                due.forEach { loads[it.id] = (loads[it.id] ?: 0) + 1 }
                due.filter { it.id == 3L }.forEach { ethCountTokens += it.countTokens }
                due.filter { it.id == 4L }.forEach { netsOf4 += it.networks }
                started.complete(Unit)
                release.await()
                due
            },
            save = { results ->
                stored = stored.map { a ->
                    val snap = results.firstOrNull { it.id == a.id } ?: return@map a
                    val discard = AddressBalance.discardFetched(a.countTokens, a.networks, snap.countTokens, snap.networks)
                    if (discard) a else a.copy(lastAttempt = now, lastUpdated = now)
                }
            },
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

    @Test
    fun userResetDuringRoundIsKeptAndWaiterLoadsAgain() = runBlocking {
        val f = Fixture()
        val first = async { f.run() }
        f.started.await()
        // Während Runde 1 läuft: «Tokens mitzählen» an (wie das Repository über changeWatched)
        f.stored = f.stored.map { if (it.id == 3L) WatchedAddressRules.withCountTokens(it, true) else it }
        assertEquals(0L, f.stored[2].lastAttempt)
        val second = async { f.run() }
        f.release.complete(Unit)
        assertTrue(first.await())
        // Ergebnis von Runde 1 (alte Einstellung) verworfen: Adresse 3 bleibt fällig, der Wartende lädt sie mit Token
        assertTrue(second.await())
        assertEquals(mapOf(1L to 1, 2L to 1, 3L to 2), f.loads)
        assertEquals(listOf(false, true), f.ethCountTokens)
        assertEquals(f.now, f.stored[2].lastAttempt)
        assertFalse(f.gate.isLocked)
    }

    /** Netz A angetippt (fällig, Runde 1 lädt mit A), während Runde 1 Netz B: B wird gleich geladen, nicht erst nach 10 min. */
    @Test
    fun secondNetworkDuringRoundIsLoadedByWaiter() = runBlocking {
        val f = Fixture()
        val evm = WatchedAddress(
            id = 4, chain = AddressChain.ETH, address = "0x0000000000000000000000000000000000000004",
            lastAttempt = f.now - 3_600_000, networks = listOf("BSC"),
        )
        f.stored = f.stored + WatchedAddressRules.withNetworks(evm, listOf("BSC", "POLYGON"))
        assertEquals(0L, f.stored[3].lastAttempt)
        val first = async { f.run() }
        f.started.await()
        f.stored = f.stored.map { if (it.id == 4L) WatchedAddressRules.withNetworks(it, listOf("BSC", "POLYGON", "ARBITRUM")) else it }
        val second = async { f.run() }
        f.release.complete(Unit)
        assertTrue(first.await())
        assertTrue(second.await())
        assertEquals(2, f.loads[4L])
        assertEquals(listOf(listOf("BSC", "POLYGON"), listOf("BSC", "POLYGON", "ARBITRUM")), f.netsOf4)
        assertEquals(f.now, f.stored[3].lastAttempt)
    }

    /** Neue Adresse (nie versucht), «Tokens mitzählen» während der ersten Runde: verworfen, der Wartende lädt mit Token. */
    @Test
    fun tokensOnDuringFirstRoundOfNewAddressIsLoaded() = runBlocking {
        val f = Fixture()
        f.stored = f.stored.map { if (it.id == 3L) it.copy(lastAttempt = 0) else it }
        val first = async { f.run() }
        f.started.await()
        f.stored = f.stored.map { if (it.id == 3L) WatchedAddressRules.withCountTokens(it, true) else it }
        val second = async { f.run() }
        f.release.complete(Unit)
        assertTrue(first.await())
        assertTrue(second.await())
        assertEquals(listOf(false, true), f.ethCountTokens)
        assertEquals(f.now, f.stored[2].lastAttempt)
    }

    /** Erste Suche fragt alle Netze: eine Wahl während der Runde ist darin enthalten — gespeichert, nicht doppelt geladen. */
    @Test
    fun networkChoiceDuringFirstSearchIsSaved() = runBlocking {
        val f = Fixture()
        f.stored = f.stored + WatchedAddress(id = 4, chain = AddressChain.ETH, address = "0x0000000000000000000000000000000000000004")
        val first = async { f.run() }
        f.started.await()
        f.stored = f.stored.map { if (it.id == 4L) WatchedAddressRules.withNetworks(it, listOf("BSC")) else it }
        val second = async { f.run() }
        f.release.complete(Unit)
        assertTrue(first.await())
        assertFalse(second.await())
        assertEquals(listOf<List<String>?>(null), f.netsOf4)
        assertEquals(f.now, f.stored[3].lastAttempt)
    }

    @Test
    fun cancelledHolderReleasesGateAndWaiterRuns() = runBlocking {
        val f = Fixture()
        val first = async { f.run() }
        f.started.await()
        val second = async { f.run() }
        yield()
        first.cancel()
        f.release.complete(Unit)
        assertTrue(second.await())
        assertTrue(first.isCancelled)
        // Runde 1 speicherte nichts: der Wartende lädt alle
        assertEquals(mapOf(1L to 2, 2L to 2, 3L to 2), f.loads)
        assertFalse(f.gate.isLocked)
    }

    @Test
    fun readFailureReleasesGateAndReturnsFalse() = runBlocking {
        val f = Fixture()
        f.failRead = true
        f.release.complete(Unit)
        assertFalse(f.run())
        assertFalse(f.gate.isLocked)
        assertTrue(f.loads.isEmpty())
        f.failRead = false
        assertTrue(f.run())
    }
}
