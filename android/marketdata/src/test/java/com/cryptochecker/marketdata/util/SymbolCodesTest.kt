package com.cryptochecker.marketdata.util

import com.cryptochecker.marketdata.model.market.KrakenAssetCodes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Kürzel-Normalisierung: Krakens Eigennamen (KrakenAssetCodes) und Währungszeichen (CurrencyText). */
class SymbolCodesTest {

    @Test
    fun krakenWsNameWirdZuUeblichenKuerzeln() {
        assertEquals("BTC" to "USD", KrakenAssetCodes.fromWsName("XBT/USD"))
        assertEquals("DOGE" to "EUR", KrakenAssetCodes.fromWsName("XDG/EUR"))
        assertEquals("ETH" to "BTC", KrakenAssetCodes.fromWsName("ETH/XBT"))
    }

    @Test
    fun krakenWsNameOhneGenauZweiKuerzelFaelltWeg() {
        for (name in listOf("XBTUSD", "A/B/C", "/USD", "XBT/", "")) assertNull(name, KrakenAssetCodes.fromWsName(name))
    }

    @Test
    fun krakenKuerzelInBeideRichtungen() {
        assertEquals("BTC", KrakenAssetCodes.common("XBT"))
        assertEquals("ETH", KrakenAssetCodes.common("ETH"))
        assertEquals("XBT", KrakenAssetCodes.krakenCode("BTC"))
        assertEquals("XDG", KrakenAssetCodes.krakenCode("DOGE"))
        assertEquals("ETH", KrakenAssetCodes.krakenCode("ETH"))
    }

    @Test
    fun waehrungszeichen() {
        assertEquals("$", CurrencyText.symbol("USD"))
        assertEquals("€", CurrencyText.symbol("EUR"))
        assertEquals("¥", CurrencyText.symbol("JPY"))
        assertEquals("¥", CurrencyText.symbol("CNY"))
        assertEquals("R$", CurrencyText.symbol("BRL"))
        // Franken und Krypto behalten das Kürzel; Gross-/Kleinschreibung zählt
        assertEquals("CHF", CurrencyText.symbol("CHF"))
        assertEquals("BTC", CurrencyText.symbol("BTC"))
        assertEquals("usd", CurrencyText.symbol("usd"))
    }
}
