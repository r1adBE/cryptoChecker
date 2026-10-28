package com.cryptochecker.app.ui.features.watchlist

import com.cryptochecker.app.data.local.model.WatchEntity
import com.cryptochecker.marketdata.model.FuturesContractType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Suche in der Merkliste (iOS: gleiche Regeln in `WatchlistSearch.matches`). */
class WatchSearchTest {
    private val btc = WatchEntity(exchangeKey = "Kraken", exchangeName = "Kraken", baseAsset = "BTC", quoteAsset = "EUR")
    private val nvda = WatchEntity(
        exchangeKey = "BinanceFutures", exchangeName = "Binance Futures", baseAsset = "NVDA", quoteAsset = "USDT",
        contractType = FuturesContractType.PERPETUAL,
    )
    private val dex = WatchEntity(exchangeKey = "DexScreener", exchangeName = "DEX", baseAsset = "BTC", quoteAsset = "SOL")

    @Test
    fun matchesPairExchangeAndName() {
        assertTrue(btc.matchesSearch("btc"))
        assertTrue(btc.matchesSearch("btc kraken"))
        assertFalse(btc.matchesSearch("bitcoin"))
        assertTrue(btc.matchesSearch("bitcoin", "Bitcoin"))
        assertTrue(btc.matchesSearch("BITCOIN eur", "Bitcoin"))
        assertTrue(btc.matchesSearch("coin", "Bitcoin"))
        assertFalse(btc.matchesSearch("bitcoin usdt", "Bitcoin"))
    }

    @Test
    fun ignoresCaseAndAccents() {
        val pepe = WatchEntity(exchangeKey = "Binance", exchangeName = "Binance", baseAsset = "PEPE", quoteAsset = "USDT")
        assertTrue(pepe.matchesSearch("pépé", "Pepe"))
        assertTrue(pepe.matchesSearch("PEPE", "Pépé"))
    }

    @Test
    fun searchNameUsesTradFiAndSkipsDex() {
        val names = mapOf("BTC" to "Bitcoin", "NVDA" to "Nvidia Token", "TRADFI:NVDA" to "NVIDIA")
        val tradFi = setOf("BinanceFutures|NVDA|USDT|PERPETUAL")
        assertEquals("Bitcoin", searchName(btc, names, tradFi))
        assertEquals("NVIDIA", searchName(nvda, names, tradFi))
        assertEquals("Nvidia Token", searchName(nvda, names, emptySet()))
        assertNull(searchName(dex, names, tradFi))
    }
}
