package com.cryptochecker.marketdata.model.market

import com.cryptochecker.marketdata.model.CheckerInfo
import com.cryptochecker.marketdata.model.CurrencyPairInfo
import com.cryptochecker.marketdata.model.SimpleTicker
import com.cryptochecker.marketdata.model.Ticker
import com.cryptochecker.marketdata.model.market.generic.SimpleMarket
import com.cryptochecker.marketdata.util.forEachJSONObject
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

class Binance: BinanceBase("Binance", "com")
class BinanceUs: BinanceBase("Binance.US", "us")

open class BinanceBase(name: String, domain: String) : SimpleMarket(
    name,
    "https://api.binance.$domain/api/v3/exchangeInfo",
    "https://api.binance.$domain/api/v3/ticker/24hr?symbol=%1\$s",
    errorPropertyName = "msg"
) {
    private val allTickersUrl = "https://api.binance.$domain/api/v3/ticker/24hr"

    /** Ohne symbol-Parameter liefert derselbe Endpunkt alle Paare auf einmal. */
    override val bulkTickersNumOfRequests: Int
        get() = 1

    override fun getBulkTickersUrl(requestId: Int): String = allTickersUrl

    /** Binance liefert ohne Filter jedes gehandelte Paar. */
    override val bulkTickersComplete: Boolean
        get() = true

    /**
     * Ohne Filter liefert Binance alle ~3500 Paare (rund 2 MB JSON) — das war
     * der Hauptgrund für lange Aktualisierungen. Mit `symbols=[...]` kommen
     * nur die beobachteten Paare zurück, wenige Kilobyte.
     */
    override fun getBulkTickersUrl(requestId: Int, pairIds: Collection<String>): String {
        if (pairIds.isEmpty() || pairIds.size > MAX_SYMBOLS_PER_REQUEST) return allTickersUrl

        val symbols = pairIds.joinToString(separator = ",", prefix = "[", postfix = "]") { "\"$it\"" }
        return "$allTickersUrl?symbols=" + URLEncoder.encode(symbols, "UTF-8")
    }

    private companion object {
        /** Ab 100 Symbolen kostet die Auswahl bei Binance gleich viel wie alle. */
        const val MAX_SYMBOLS_PER_REQUEST = 100
    }

    @Throws(Exception::class)
    override fun parseBulkTickers(
        requestId: Int,
        responseString: String,
        tickers: MutableMap<String, Ticker>,
    ) {
        JSONArray(responseString).forEachJSONObject { entry ->
            val symbol = entry.optString("symbol").ifEmpty { return@forEachJSONObject }

            val ticker = SimpleTicker()
            readTicker(entry, ticker)
            tickers[symbol] = ticker
        }
    }

    @Throws(Exception::class)
    override fun parseTickerFromJsonObject(requestId: Int, jsonObject: JSONObject, ticker: Ticker, checkerInfo: CheckerInfo) {
        readTicker(jsonObject, ticker)
    }

    /** Einzelabruf und Massenabfrage liefern dieselbe Struktur je Paar. */
    @Throws(Exception::class)
    private fun readTicker(jsonObject: JSONObject, ticker: Ticker) {
        ticker.bid = jsonObject.getDouble("bidPrice")
        ticker.ask = jsonObject.getDouble("askPrice")

        ticker.vol = jsonObject.getDouble("volume")
        ticker.volQuote = jsonObject.getDouble("quoteVolume")

        ticker.high = jsonObject.getDouble("highPrice")
        ticker.low = jsonObject.getDouble("lowPrice")

        ticker.last = jsonObject.getDouble("lastPrice")
        ticker.timestamp = jsonObject.getLong("closeTime")
    }

    @Throws(Exception::class)
    override fun parseCurrencyPairsFromJsonObject(requestId: Int, jsonObject: JSONObject, pairs: MutableList<CurrencyPairInfo>) {
        jsonObject.getJSONArray("symbols").forEachJSONObject { marketJsonObject ->
            if (marketJsonObject.getString("status") != "TRADING") {
                return@forEachJSONObject
            }

            val symbol = marketJsonObject.getString("symbol")
            val baseAsset = marketJsonObject.getString("baseAsset")
            val quoteAsset = marketJsonObject.getString("quoteAsset")
            pairs.add(
                CurrencyPairInfo(
                    baseAsset,
                    quoteAsset,
                    symbol
                )
            )
        }
    }
}