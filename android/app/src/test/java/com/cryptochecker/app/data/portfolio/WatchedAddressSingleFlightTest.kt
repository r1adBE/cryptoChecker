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
 * Runde vom Nutzer fällig gemacht («Tokens mitzählen» an): Ergebnis verworfen, der Wartende lädt neu. Abbruch und
 * Fehler geben das Gate frei. Gegenstück: `AddressRefreshGateTests.swift`.
 */
class WatchedAddressSingleFlightTest {

    private val xpub = "xpub6ceT3jjUWNFQ4zxeZWGDSw8iM3MjHpxvZcKVZFLU4zbXnUGAnVSRraqr8LaZwCaUFT6TV7ZF6834RXLF5xzxa2ew6Jw63uPr91LryWJ8fiz"

    /**
     * Fake-Speicher und Fake-Loader mit Zähler je Adresse; der Loader wartet auf [release]. Ergebnis je Adresse mit dem
     * lastAttempt des Abzugs; Speichern wie das Repository ([AddressBalance.resetDuringFetch]).
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
                started.complete(Unit)
                release.await()
                due.map { it.id to it.lastAttempt }
            },
            save = { results ->
                stored = stored.map { a ->
                    val snapshot = results.firstOrNull { it.first == a.id }?.second ?: return@map a
                    if (AddressBalance.resetDuringFetch(a.lastAttempt, snapshot)) a else a.copy(lastAttempt = now, lastUpdated = now)
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
