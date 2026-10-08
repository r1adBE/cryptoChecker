package com.cryptochecker.app.domain.model

import com.cryptochecker.marketdata.util.TradFi
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Kennzeichen «kein Krypto-Token» je Börse — Werte aus den öffentlichen Paarlisten (Oktober 2026).
 * Wie `TradFiTests.swift`.
 */
class TradFiTest {

    @Test
    fun binance() {
        assertTrue(TradFi.binance("TRADIFI_PERPETUAL", listOf("TradFi")))
        assertTrue(TradFi.binance("PERPETUAL", listOf("Pre-IPO", "TradFi")))
        assertFalse(TradFi.binance("PERPETUAL", listOf("Layer-1", "Crypto")))
        assertFalse(TradFi.binance("PERPETUAL", listOf("Index", "Crypto")))
        assertFalse(TradFi.binance("CURRENT_QUARTER", emptyList()))
    }

    @Test
    fun bybit() {
        listOf("stock", "ETF", "commodity", "forex").forEach { assertTrue(it, TradFi.bybit(it)) }
        listOf("", "innovation", "adventure").forEach { assertFalse(it, TradFi.bybit(it)) }
    }

    @Test
    fun okx() {
        assertTrue(TradFi.okx("3"))
        assertTrue(TradFi.okx("4"))
        assertFalse(TradFi.okx("1"))
        assertFalse(TradFi.okx(""))
    }

    @Test
    fun mexc() {
        // Aktie (Typ 2), Gold (Typ 1, Bereich «metals»/«tradfi»), Bitcoin
        assertTrue(TradFi.mexc(listOf("mc-trade-zone-Stock", "mc-trade-zone-0fees", "mc-trade-zone-tradfi"), 2))
        assertTrue(TradFi.mexc(listOf("mc-trade-zone-metals", "mc-trade-zone-tradfi"), 1))
        assertTrue(TradFi.mexc(emptyList(), 2))
        assertFalse(TradFi.mexc(listOf("mc-trade-zone-mainly", "mc-trade-zone-layer2", "mc-trade-zone-pow"), 1))
    }

    @Test
    fun bitget() {
        assertTrue(TradFi.bitget("YES"))
        assertFalse(TradFi.bitget("NO"))
        assertFalse(TradFi.bitget(""))
    }
}
