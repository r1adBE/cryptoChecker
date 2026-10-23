package com.cryptochecker.app.data.portfolio

import com.cryptochecker.app.domain.alarm.PortfolioAlarmKind
import com.cryptochecker.app.domain.alarm.PortfolioAlarmLogic
import com.cryptochecker.app.domain.alarm.PortfolioAlarmPause
import com.cryptochecker.app.domain.portfolio.PortfolioSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * «Kurs ungeprüft» (Binance-Kurs mit unbestätigtem Token) zählt für die Portfolio-Alarme wie ein alter
 * Kurs: wesentlich → keine Prüfung und Hinweis mit dem Coin, unwesentlich (unter 1 %) → Alarme prüfen. Wie
 * `PortfolioUnverifiedAlarmTests.swift`.
 */
class PortfolioUnverifiedAlarmTest {

    private fun snapshot(
        unverified: List<String> = emptyList(),
        unverifiedValue: Double = 0.0,
        stale: List<String> = emptyList(),
        staleValue: Double? = 0.0,
    ) = PortfolioSnapshot(
        total = 10_000.0, changeAmount = null, changePercent = 1.0, currency = "USDT", time = 1L, empty = false,
        totalUsdt = 10_000.0, stalePrices = stale.size, staleCoins = stale, staleValueUsdt = staleValue,
        unverifiedCoins = unverified, unverifiedValueUsdt = unverifiedValue,
    )

    @Test
    fun materialUnverifiedPausesAlarms() {
        val s = snapshot(unverified = listOf("BEAM"), unverifiedValue = 500.0)
        val reading = s.toAlarmReading()
        assertEquals(0, reading.stalePrices)
        assertEquals(listOf("BEAM"), reading.unverifiedCoins)
        assertEquals(500.0, reading.unverifiedValueUsdt, 1e-9)
        assertTrue(PortfolioAlarmLogic.staleMaterial(reading))
        assertTrue(PortfolioAlarmLogic.incomplete(PortfolioAlarmKind.VALUE_ABOVE, reading))
        // Eigener Hinweis «Kurs ungeprüft für BEAM»
        assertEquals(
            PortfolioAlarmPause.PriceUnverified(listOf("BEAM")),
            PortfolioAlarmLogic.pause(listOf(PortfolioAlarmKind.VALUE_ABOVE), reading, s.missingCoins, s.staleCoins),
        )
    }

    @Test
    fun immaterialUnverifiedKeepsAlarms() {
        val s = snapshot(unverified = listOf("BEAM"), unverifiedValue = 50.0)
        val reading = s.toAlarmReading()
        assertFalse(PortfolioAlarmLogic.staleMaterial(reading))
        assertNull(PortfolioAlarmLogic.pause(listOf(PortfolioAlarmKind.CHANGE_UP), reading, s.missingCoins, s.staleCoins))
    }

    @Test
    fun unverifiedAddsToStaleValueAndKeepsStaleWording() {
        // Je 60 USDT: einzeln unwesentlich, zusammen 1.2 % → wesentlich; Satz «kein aktueller Kurs» mit dem alten Coin
        val s = snapshot(unverified = listOf("BEAM"), unverifiedValue = 60.0, stale = listOf("PEPE"), staleValue = 60.0)
        val reading = s.toAlarmReading()
        assertTrue(PortfolioAlarmLogic.staleMaterial(reading))
        assertEquals(
            PortfolioAlarmPause.NoPrice(listOf("PEPE")),
            PortfolioAlarmLogic.pause(listOf(PortfolioAlarmKind.VALUE_BELOW), reading, s.missingCoins, s.staleCoins),
        )
    }
}
