package com.cryptochecker.app.domain.portfolio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

/** «Adresse entfernen» mit «Rückgängig» ([WatchedAddressUndo]). Gegenstück: `WatchedAddressUndoTests.swift`. */
class WatchedAddressUndoTest {

    private fun address(id: Long, addr: String = "bc1qaddress$id") = WatchedAddress(id = id, chain = AddressChain.BTC, address = addr)

    private val lot = AddressCostLot(id = 1, coin = "BTC", time = 1_000L, amount = BigDecimal("0.1"))
    private val obs = AddressObservation(1_000L, BigDecimal("0.1"))

    @Test
    fun rueckfrageNenntWasMitgeht() {
        assertTrue(WatchedAddressUndo.removal(address(1)).isEmpty)
        assertEquals(WatchedAddressUndo.Removal(2, false), WatchedAddressUndo.removal(address(1).copy(costLots = listOf(lot, lot.copy(id = 2)))))
        assertEquals(WatchedAddressUndo.Removal(0, true), WatchedAddressUndo.removal(address(1).copy(observations = listOf(obs))))
        // Token-Reihen zählen als Verlauf
        assertEquals(WatchedAddressUndo.Removal(1, true),
            WatchedAddressUndo.removal(address(1).copy(costLots = listOf(lot), tokenObservations = mapOf("USDC" to listOf(obs)))))
        assertTrue(WatchedAddressUndo.removal(address(1).copy(tokenObservations = mapOf("USDC" to emptyList()))).isEmpty)
    }

    @Test
    fun zurueckNurMitFreierIdUndOhneDoppel() {
        val removed = address(5).copy(costLots = listOf(lot), observations = listOf(obs))
        assertTrue(WatchedAddressUndo.canRestore(listOf(address(1), address(2)), removed))
        // Id inzwischen belegt bzw. dieselbe Adresse neu hinzugefügt
        assertFalse(WatchedAddressUndo.canRestore(listOf(address(5, "bc1qother")), removed))
        assertFalse(WatchedAddressUndo.canRestore(listOf(address(9, removed.address)), removed))
        // Liste voll
        val full = (100L until 100L + WatchedAddressRules.MAX_ADDRESSES).map { address(it) }
        assertFalse(WatchedAddressUndo.canRestore(full, removed))
        assertFalse(WatchedAddressUndo.canRestore(emptyList(), removed.copy(id = 0)))
    }
}
