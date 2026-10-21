package com.cryptochecker.app.ui.features.alarms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Coin-Liste im Hinweis «Portfolio-Alarme pausiert» (iOS: `PortfolioPauseTextTests`). */
class PortfolioPauseTextTest {

    @Test
    fun `hoechstens drei Coins, dann Auslassung`() {
        assertEquals("PEPE", PortfolioPauseText.coins(listOf("PEPE"), hideCoins = false))
        assertEquals("A, B, C", PortfolioPauseText.coins(listOf("A", "B", "C"), hideCoins = false))
        assertEquals("A, B, C …", PortfolioPauseText.coins(listOf("A", "B", "C", "D"), hideCoins = false))
    }

    @Test
    fun `gesperrt oder ohne Namen ohne Liste`() {
        // Gesperrtes Portfolio: die Zusammensetzung des Bestands bleibt verborgen
        assertNull(PortfolioPauseText.coins(listOf("PEPE"), hideCoins = true))
        assertNull(PortfolioPauseText.coins(emptyList(), hideCoins = false))
    }
}
