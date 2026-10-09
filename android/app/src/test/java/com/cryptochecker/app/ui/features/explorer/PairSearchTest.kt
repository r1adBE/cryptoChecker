package com.cryptochecker.app.ui.features.explorer

import com.cryptochecker.app.domain.model.MarketInfo
import com.cryptochecker.app.domain.model.MarketPairsInfo
import com.cryptochecker.marketdata.model.CurrencyPairInfo
import com.cryptochecker.marketdata.model.FuturesContractType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Suche wie an der Börse: «BTCUSDT», «BTCUSDT Qtly 1225», «BTCUSD_PERP». */
class PairSearchTest {

    private val spot = MarketInfo("Binance", "Binance")
    private val futures = MarketInfo("BinanceFutures", "Binance Futures")
    private val cache = mapOf(
        "Binance" to MarketPairsInfo(1, listOf(
            CurrencyPairInfo("BTC", "USDT", "BTCUSDT"),
            CurrencyPairInfo("BTC", "EUR", "BTCEUR"),
            CurrencyPairInfo("ETH", "USDT", "ETHUSDT"),
        )),
        "BinanceFutures" to MarketPairsInfo(1, listOf(
            CurrencyPairInfo("BTC", "USDT", "BTCUSDT_261225", FuturesContractType.QUARTERLY),
            CurrencyPairInfo("BTC", "USDT", "BTCUSDT_270326", FuturesContractType.BIQUARTERLY),
            CurrencyPairInfo("BTC", "USDT", "BTCUSDT", FuturesContractType.PERPETUAL),
            CurrencyPairInfo("BTC", "USD", "2:BTCUSD_PERP", FuturesContractType.INVERSE_PERPETUAL),
        )),
    )
    private val markets = listOf(spot, futures)

    private fun find(q: String) = PairSearch.find(q, markets, cache).map {
        "${it.market.key} ${it.pair.currencyBase}/${it.pair.currencyCounter} ${it.pair.contractType.name}"
    }

    @Test
    fun joinedSymbolFindsSpotPerpAndQuarters() {
        assertEquals(
            listOf(
                "Binance BTC/USDT NONE",
                "BinanceFutures BTC/USDT PERPETUAL",
                "BinanceFutures BTC/USDT QUARTERLY",
                "BinanceFutures BTC/USDT BIQUARTERLY",
            ),
            find("BTCUSDT"),
        )
    }

    @Test
    fun quarterWordAndDateNarrowDown() {
        assertEquals(listOf("BinanceFutures BTC/USDT QUARTERLY"), find("BTCUSDT Qtly 1225"))
        assertEquals(listOf("BinanceFutures BTC/USDT QUARTERLY"), find("BTCUSDT_261225"))
        assertEquals(listOf("BinanceFutures BTC/USDT BIQUARTERLY"), find("btc usdt 0326"))
        assertEquals(
            listOf("BinanceFutures BTC/USDT QUARTERLY", "BinanceFutures BTC/USDT BIQUARTERLY"),
            find("BTC USDT Quartal"),
        )
    }

    @Test
    fun perpWord() {
        assertEquals(listOf("BinanceFutures BTC/USD INVERSE_PERPETUAL"), find("BTCUSD_PERP"))
        assertEquals(
            listOf("BinanceFutures BTC/USDT PERPETUAL", "BinanceFutures BTC/USD INVERSE_PERPETUAL"),
            find("BTC PERP"),
        )
    }

    @Test
    fun classicQueriesUnchanged() {
        assertTrue(find("BTC").first().startsWith("Binance BTC/USDT NONE"))
        assertEquals(listOf("Binance ETH/USDT NONE"), find("eth/usdt"))
        assertEquals(emptyList<String>(), find("XYZ"))
    }

    @Test
    fun parseAndDeliveryCode() {
        val q = PairSearch.parse("BTCUSDT Qtly 1225")!!
        assertEquals("BTCUSDT", q.base)
        assertNull(q.quote)
        assertEquals("1225", q.date)
        // Zahl als erstes Wort bleibt ein Coin
        assertEquals("1000", PairSearch.parse("1000")!!.base)
        assertEquals("261225", PairSearch.deliveryCode(CurrencyPairInfo("BTC", "USDT", "BTCUSDT_261225", FuturesContractType.QUARTERLY)))
        assertNull(PairSearch.deliveryCode(CurrencyPairInfo("BTC", "USDT", "BTCUSDT", FuturesContractType.PERPETUAL)))
    }
}
