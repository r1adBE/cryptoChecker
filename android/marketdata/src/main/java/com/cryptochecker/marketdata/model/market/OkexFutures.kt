package com.cryptochecker.marketdata.model.market

import com.cryptochecker.marketdata.exceptions.MarketParseException
import com.cryptochecker.marketdata.model.CheckerInfo
import com.cryptochecker.marketdata.model.CurrencyPairInfo
import com.cryptochecker.marketdata.model.FuturesContractType
import com.cryptochecker.marketdata.model.SimpleTicker
import com.cryptochecker.marketdata.model.Ticker
import com.cryptochecker.marketdata.model.market.generic.SimpleMarket
import com.cryptochecker.marketdata.util.Change24h
import com.cryptochecker.marketdata.util.forEachJSONObject
import org.json.JSONObject

class OkexFutures : SimpleMarket(
    "OKX Futures",
    "https://www.okx.com/api/v5/market/tickers?instType=SWAP",
    "https://www.okx.com/api/v5/market/ticker?instId=%1\$s"
) {

    override fun parseCurrencyPairsFromJsonObject(
        requestId: Int,
        jsonObject: JSONObject,
        pairs: MutableList<CurrencyPairInfo>
    ) {
        jsonObject
            .getJSONArray("data")
            .forEachJSONObject {
                val pairId = it.getString("instId")
                val assets = pairId.split('-')

                if(assets.size == 3 && assets[2] == "SWAP") {
                    pairs.add(CurrencyPairInfo(
                        assets[0],
                        assets[1],
                        pairId,
                        FuturesContractType.PERPETUAL
                    ))
                }
            }
    }

    @Throws(Exception::class)
    override fun parseTickerFromJsonObject(requestId: Int, jsonObject: JSONObject, ticker: Ticker, checkerInfo: CheckerInfo) {
        // OKX verpackt Fehler in code/msg statt in "data".
        jsonObject.optString("msg").takeIf { it.isNotEmpty() }?.let {
            throw MarketParseException(it)
        }

        readTicker(jsonObject.getJSONArray("data").getJSONObject(0), ticker)
    }

    /** Einzelabruf und Massenabfrage liefern je Paar dieselbe Struktur. */
    @Throws(Exception::class)
    private fun readTicker(json: JSONObject, ticker: Ticker) {
        ticker.bid = json.getDouble("bidPx")
        ticker.ask = json.getDouble("askPx")

        ticker.vol = json.getDouble("vol24h")
        ticker.volQuote = json.getDouble("volCcy24h")

        ticker.high = json.getDouble("high24h")
        ticker.low = json.getDouble("low24h")

        ticker.last = json.getDouble("last")
        ticker.timestamp = json.getLong("ts")

        // open24h = Kurs vor 24 h (sodUtc0/sodUtc8 wären Tageswerte)
        ticker.change24hPercent = Change24h.fromOpen(ticker.last, json.optDouble("open24h"))
    }

    /** Derselbe Endpunkt wie für die Paarliste liefert alle Ticker mit. */
    override val bulkTickersNumOfRequests: Int
        get() = 1

    override fun getBulkTickersUrl(requestId: Int): String = getCurrencyPairsUrl(0)

    @Throws(Exception::class)
    override fun parseBulkTickers(
        requestId: Int,
        responseString: String,
        tickers: MutableMap<String, Ticker>,
    ) {
        JSONObject(responseString)
            .getJSONArray("data")
            .forEachJSONObject { entry ->
                val instId = entry.optString("instId").ifEmpty { return@forEachJSONObject }

                val ticker = SimpleTicker()
                readTicker(entry, ticker)
                tickers[instId] = ticker
            }
    }
}
