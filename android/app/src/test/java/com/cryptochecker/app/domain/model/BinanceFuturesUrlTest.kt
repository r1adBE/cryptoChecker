package com.cryptochecker.app.domain.model

import com.cryptochecker.marketdata.model.PairQuery
import com.cryptochecker.marketdata.model.FuturesContractType
import com.cryptochecker.marketdata.model.exchange.BinanceFutures
import com.cryptochecker.marketdata.exceptions.ExchangeParseException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Binance Futures, Laufzeit-Kontrakte: Kursabfrage mit dem Symbol der Paarliste, nie aus dem Kalender
 * (der träfe nach der Abrechnung Freitag 08:00 UTC den abgelaufenen Kontrakt). Wie `BinanceFuturesUrlTests` (iOS).
 */
class BinanceFuturesUrlTest {

    @Test
    fun datedContractsUseTheExchangeSymbol() {
        val m = BinanceFutures()
        assertEquals("https://fapi.binance.com/fapi/v1/ticker/24hr?symbol=BTCUSDT_251226",
            m.tickerAddress(0, PairQuery("BTC", "USDT", "BTCUSDT_251226", FuturesContractType.QUARTERLY)))
        assertEquals("https://dapi.binance.com/dapi/v1/ticker/24hr?symbol=BTCUSD_260327",
            m.tickerAddress(0, PairQuery("BTC", "USD", "2:BTCUSD_260327", FuturesContractType.BIQUARTERLY)))
        assertEquals("https://fapi.binance.com/fapi/v1/ticker/24hr?symbol=BTCUSDT",
            m.tickerAddress(0, PairQuery("BTC", "USDT", "BTCUSDT", FuturesContractType.PERPETUAL)))
    }

    /** Ohne Paar-Kennung (fehlt oder leer) keine Anfrage, sondern ein Fehler. Wie iOS (`tickerQueryError`). */
    @Test
    fun missingPairIdIsAnErrorWithoutRequest() {
        val m = BinanceFutures()
        for (pairId in listOf(null, "", " ")) {
            val err = assertThrows(ExchangeParseException::class.java) {
                m.tickerAddress(0, PairQuery("BTC", "USDT", pairId, FuturesContractType.PERPETUAL))
            }
            assertEquals("Missing pair id", err.message)
        }
    }

    /** Nur «TRADING»-Kontrakte sind wählbar (USD-M «status», COIN-M «contractStatus»). Wie iOS `BinanceFuturesPairsTests`. */
    @Test
    fun onlyTradingContractsAreListed() {
        val usdM = """{"symbols": [
          {"symbol": "BTCUSDT", "baseAsset": "BTC", "quoteAsset": "USDT", "contractType": "PERPETUAL", "status": "TRADING"},
          {"symbol": "BTCUSDT_250926", "baseAsset": "BTC", "quoteAsset": "USDT", "contractType": "CURRENT_QUARTER", "status": "DELIVERING"},
          {"symbol": "ETHUSDT_250926", "baseAsset": "ETH", "quoteAsset": "USDT", "contractType": "CURRENT_QUARTER", "status": "SETTLING"},
          {"symbol": "XYZUSDT", "baseAsset": "XYZ", "quoteAsset": "USDT", "contractType": "PERPETUAL", "status": "CLOSE"},
          {"symbol": "OLDUSDT", "baseAsset": "OLD", "quoteAsset": "USDT", "contractType": "PERPETUAL"}
        ]}"""
        val pairs = ArrayList<com.cryptochecker.marketdata.model.ExchangePair>()
        BinanceFutures().readPairList(0, usdM, pairs)
        assertEquals(listOf("BTCUSDT", "OLDUSDT"), pairs.map { it.pairId })

        val coinM = """{"symbols": [
          {"symbol": "BTCUSD_PERP", "baseAsset": "BTC", "quoteAsset": "USD", "contractType": "PERPETUAL", "contractStatus": "TRADING"},
          {"symbol": "BTCUSD_250926", "baseAsset": "BTC", "quoteAsset": "USD", "contractType": "CURRENT_QUARTER", "contractStatus": "DELIVERED"}
        ]}"""
        val coinPairs = ArrayList<com.cryptochecker.marketdata.model.ExchangePair>()
        BinanceFutures().readPairList(1, coinM, coinPairs)
        assertEquals(listOf("2:BTCUSD_PERP"), coinPairs.map { it.pairId })
    }

    /** Eine unlesbare Zeile kippt nicht die ganze Massenabfrage: nur dieses Paar als Fehler. Wie iOS. */
    @Test
    fun malformedBulkRowIsSkipped() {
        val body = """[
          {"symbol": "BTCUSDT", "volume": "10", "highPrice": "101", "lowPrice": "99", "lastPrice": "100", "quoteVolume": "1000", "priceChangePercent": "1.5"},
          {"symbol": "BADUSDT", "volume": "10", "highPrice": "x", "lowPrice": "1", "lastPrice": "1"},
          42,
          {"volume": "1"},
          {"symbol": "ETHUSDT", "volume": "5", "highPrice": "11", "lowPrice": "9", "lastPrice": "10"}
        ]"""
        val parsed = BinanceFutures().parseBulkTickersDetailed(0, body)
        assertEquals(setOf("BTCUSDT", "ETHUSDT"), parsed.tickers.keys)
        assertEquals(setOf("BADUSDT"), parsed.failed)
        assertEquals(100.0, parsed.tickers.getValue("BTCUSDT").last, 0.0)
    }

    @Test
    fun deliveryCodeOfPairId() {
        assertEquals("251226", FuturesContractType.deliveryCodeOf("BTCUSDT_251226"))
        assertEquals("260327", FuturesContractType.deliveryCodeOf("2:BTCUSD_260327"))
        assertNull(FuturesContractType.deliveryCodeOf("2:BTCUSD_PERP"))
        assertNull(FuturesContractType.deliveryCodeOf("BTCUSDT"))
        assertNull(FuturesContractType.deliveryCodeOf("ETHUSDT_X"))
        assertNull(FuturesContractType.deliveryCodeOf(null))
    }
}
