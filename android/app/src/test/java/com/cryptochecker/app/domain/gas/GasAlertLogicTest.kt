package com.cryptochecker.app.domain.gas

import com.cryptochecker.app.domain.market.GasFees
import org.junit.Assert.assertEquals
import org.junit.Test

/** Reine Logik ohne org.json (im JVM-Test nur als Android-Attrappe vorhanden). */
class GasAlertLogicTest {

    @Test
    fun firesOnceAndRearmsOnlyClearlyAbove() {
        var armed = true
        val fired = listOf(3.0, 1.9, 1.5, 2.1, 2.4, 2.6, 1.8).map { value ->
            val (fire, next) = GasAlertLogic.evaluate(value, 2.0, armed)
            armed = next
            fire
        }
        assertEquals(listOf(false, true, false, false, false, false, true), fired)
    }

    @Test
    fun offNeverFires() {
        assertEquals(false to true, GasAlertLogic.evaluate(0.1, 0.0, true))
    }

    @Test
    fun formatsGweiShortButNeverZero() {
        assertEquals(
            listOf("<0.001", "0.012", "0.5", "1", "2.3", "13"),
            listOf(0.0004, 0.0123, 0.5, 1.04, 2.25, 12.6).map(GasFees::formatGwei)
        )
    }

    @Test
    fun hexWeiToGwei() {
        assertEquals(1.0, GasFees.hexToGwei("0x3b9aca00"), 1e-12)
        assertEquals(1.0e11, GasFees.hexToGwei("0x56bc75e2d63100000"), 1e-3)
    }

    @Test
    fun transferCosts() {
        assertEquals(0.63, GasFees.evmTransferUsd(10.0, 3000.0)!!, 1e-9)
        assertEquals(0.252, GasFees.btcTransferUsd(3.0, 60000.0)!!, 1e-9)
        assertEquals("<\$0.01", GasFees.formatUsd(0.004))
        assertEquals("\$0.63", GasFees.formatUsd(0.63))
    }
}
