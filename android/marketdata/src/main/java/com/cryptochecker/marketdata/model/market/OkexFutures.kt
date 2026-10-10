package com.cryptochecker.marketdata.model.market

import com.cryptochecker.marketdata.exceptions.MarketParseException
import com.cryptochecker.marketdata.model.CheckerInfo
import com.cryptochecker.marketdata.model.CurrencyPairInfo
import com.cryptochecker.marketdata.model.FuturesContractType
import com.cryptochecker.marketdata.model.SimpleTicker
import com.cryptochecker.marketdata.model.Ticker
import com.cryptochecker.marketdata.model.market.generic.SimpleMarket
import com.cryptochecker.marketdata.util.Change24h
import com.cryptochecker.marketdata.util.TradFi
import com.cryptochecker.marketdata.util.forEachJSONObject
import com.cryptochecker.marketdata.util.optDoubleNoData
import org.json.JSONObject
import com.cryptochecker.marketdata.util.optText

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
                        FuturesContractType.PERPETUAL,
                        // Aktien (instCategory 3), Rohstoffe, Devisen: kein Krypto-Token
                        tradFi = TradFi.okx(it.optString("instCategory")),
                    ))
                }
            }
    }

    @Throws(Exception::class)
    override fun parseTickerFromJsonObject(requestId: Int, jsonObject: JSONObject, ticker: Ticker, checkerInfo: CheckerInfo) {
        // OKX verpackt Fehler in code/msg statt in "data".
        jsonObject.optText("msg").takeIf { it.isNotEmpty() }?.let {
            throw MarketParseException(it)
        }

        readTicker(jsonObject.getJSONArray("data").getJSONObject(0), ticker)
    }

    /** Einzelabruf und Massenabfrage liefern je Paar dieselbe Struktur. */
    @Throws(Exception::class)
    private fun readTicker(json: JSONObject, ticker: Ticker) {
        // Ohne Orders im Buch sendet OKX "" für bidPx/askPx: dann kein Geld-/Briefkurs statt Fehler
        ticker.bid = json.optDoubleNoData("bidPx")
        ticker.ask = json.optDoubleNoData("askPx")

        // Bei SWAP zählt vol24h Kontrakte, volCcy24h ist die Menge in der Basiswährung.
        // Ein Volumen in der Kotierungswährung liefert OKX für Swaps nicht – es wird nicht geschätzt.
        ticker.vol = json.getDouble("volCcy24h")
        ticker.volQuote = Ticker.NO_DATA.toDouble()

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

                // Ein unlesbarer Eintrag lässt nur dieses Paar aus, nicht die ganze Abfrage
                val ticker = SimpleTicker()
                runCatching { readTicker(entry, ticker) }.onFailure { return@forEachJSONObject }
                tickers[instId] = ticker
            }
    }
}
