package com.cryptochecker.app.domain.portfolio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Gleiche Fälle wie `PortfolioOffTests.swift`. */
class PortfolioOffTest {

    @Test
    fun `nur eine neue Transaktion schaltet das ausgeschaltete Portfolio ein`() {
        assertTrue(PortfolioOff.enableOnSave(isNew = true, portfolioEnabled = false))
        assertFalse(PortfolioOff.enableOnSave(isNew = true, portfolioEnabled = true))
        assertFalse(PortfolioOff.enableOnSave(isNew = false, portfolioEnabled = false))
        assertFalse(PortfolioOff.enableOnSave(isNew = false, portfolioEnabled = true))
    }

    @Test
    fun `nur die erste neue Transaktion bei Portfolio aus fragt nach dem Tab`() {
        assertTrue(PortfolioOff.askEnableOnSave(isNew = true, portfolioEnabled = false, hadTransactions = false))
        assertFalse(PortfolioOff.askEnableOnSave(isNew = true, portfolioEnabled = false, hadTransactions = true))
        assertFalse(PortfolioOff.askEnableOnSave(isNew = true, portfolioEnabled = true, hadTransactions = false))
        assertFalse(PortfolioOff.askEnableOnSave(isNew = false, portfolioEnabled = false, hadTransactions = false))
    }

    @Test
    fun `hinzufuegen wird immer angeboten ausser in der Vorschau`() {
        assertTrue(PortfolioOff.offerAddToPortfolio(preview = false))
        assertFalse(PortfolioOff.offerAddToPortfolio(preview = true))
    }

    @Test
    fun `widget aus geht vor Sperre und leer`() {
        assertEquals(PortfolioOff.WidgetState.OFF, PortfolioOff.widgetState(portfolioEnabled = false, locked = true, empty = true))
        assertEquals(PortfolioOff.WidgetState.OFF, PortfolioOff.widgetState(portfolioEnabled = false, locked = false, empty = false))
        assertEquals(PortfolioOff.WidgetState.LOCKED, PortfolioOff.widgetState(portfolioEnabled = true, locked = true, empty = true))
        assertEquals(PortfolioOff.WidgetState.EMPTY, PortfolioOff.widgetState(portfolioEnabled = true, locked = false, empty = true))
        assertEquals(PortfolioOff.WidgetState.VALUES, PortfolioOff.widgetState(portfolioEnabled = true, locked = false, empty = false))
    }

    @Test
    fun `widget oeffnet Tab oder Einstellungen`() {
        assertEquals(PortfolioOff.WidgetTarget.PORTFOLIO_TAB, PortfolioOff.widgetTarget(portfolioEnabled = true))
        assertEquals(PortfolioOff.WidgetTarget.PORTFOLIO_SETTINGS, PortfolioOff.widgetTarget(portfolioEnabled = false))
    }
}
