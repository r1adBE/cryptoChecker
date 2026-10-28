package com.cryptochecker.app.domain.market

import com.cryptochecker.marketdata.model.exchange.KrakenAssetCodes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Kraken-Kürzel aus `wsname` und zurück; wie `KrakenAssetCodesTests` (ExchangesTests.swift). */
class KrakenAssetCodesTest {

    @Test
    fun wsNameGivesBaseAndQuoteWithoutLegacyPrefixes() {
        assertEquals("BTC" to "USD", KrakenAssetCodes.fromWsName("XBT/USD"))
        assertEquals("ETH" to "EUR", KrakenAssetCodes.fromWsName("ETH/EUR"))
        assertEquals("DOGE" to "USD", KrakenAssetCodes.fromWsName("XDG/USD"))
        assertEquals("ETH" to "BTC", KrakenAssetCodes.fromWsName("ETH/XBT"))
        // Kürzel, die zufällig mit X oder Z beginnen, bleiben ganz
        assertEquals("XTZ" to "USD", KrakenAssetCodes.fromWsName("XTZ/USD"))
        assertEquals("ZEC" to "USD", KrakenAssetCodes.fromWsName("ZEC/USD"))
        assertEquals("ZRX" to "EUR", KrakenAssetCodes.fromWsName("ZRX/EUR"))
    }

    @Test
    fun malformedWsNameGivesNoPair() {
        assertNull(KrakenAssetCodes.fromWsName(""))
        assertNull(KrakenAssetCodes.fromWsName("XBTUSD"))
        assertNull(KrakenAssetCodes.fromWsName("XBT/"))
        assertNull(KrakenAssetCodes.fromWsName("/USD"))
        assertNull(KrakenAssetCodes.fromWsName("A/B/C"))
    }

    @Test
    fun onlyTheTwoKrakenAliasesAreMapped() {
        assertEquals("BTC", KrakenAssetCodes.common("XBT"))
        assertEquals("DOGE", KrakenAssetCodes.common("XDG"))
        // Frühere Einträge der alten Tabelle (XVN, ICN, NMC) gibt es bei Kraken nicht mehr
        assertEquals("XVN", KrakenAssetCodes.common("XVN"))
        assertEquals("XXBT", KrakenAssetCodes.common("XXBT"))
        assertEquals("USDT", KrakenAssetCodes.common("USDT"))
    }

    @Test
    fun krakenCodeIsTheReverseForPairIds() {
        assertEquals("XBT", KrakenAssetCodes.krakenCode("BTC"))
        assertEquals("XDG", KrakenAssetCodes.krakenCode("DOGE"))
        assertEquals("VEN", KrakenAssetCodes.krakenCode("VEN"))
        assertEquals("ETH", KrakenAssetCodes.krakenCode("ETH"))
    }
}
