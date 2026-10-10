package com.cryptochecker.marketdata.model.market

import com.cryptochecker.marketdata.exceptions.MarketParseException
import com.cryptochecker.marketdata.model.CheckerInfo
import com.cryptochecker.marketdata.model.CurrencyPairInfo
import com.cryptochecker.marketdata.model.Market
import com.cryptochecker.marketdata.model.Ticker
import com.cryptochecker.marketdata.model.currency.CurrencyPairsMap
import com.cryptochecker.marketdata.util.Change24h
import com.cryptochecker.marketdata.util.forEachJSONObject
import org.json.JSONArray
import org.json.JSONObject
import com.cryptochecker.marketdata.util.optText
import com.cryptochecker.marketdata.util.getText

// API Reference: https://docs.cloud.coinbase.com/exchange/reference/
class Coinbase : Market(NAME, TTS_NAME, CURRENCY_PAIRS) {
    companion object {
        private const val NAME = "Coinbase"
        private const val TTS_NAME = NAME
        private const val URL_STATS = "https://api.exchange.coinbase.com/products/%1\$s/stats"
        private const val URL_CURRENCY_PAIRS = "https://api.exchange.coinbase.com/products"

        private val CURRENCY_PAIRS: CurrencyPairsMap = CurrencyPairsMap()
    }

    init {
        CURRENCY_PAIRS["1INCH"] = arrayOf("BTC", "EUR", "GBP", "USD")
        CURRENCY_PAIRS["AAVE"] = arrayOf("BTC", "EUR", "GBP", "USD")
        CURRENCY_PAIRS["ADA"] = arrayOf("BTC", "ETH", "EUR", "GBP", "USD", "USDC")
        CURRENCY_PAIRS["ATOM"] = arrayOf("BTC", "USD")
        CURRENCY_PAIRS["BAT"] = arrayOf("BTC", "ETH", "EUR", "USD", "USDC")
        CURRENCY_PAIRS["BTC"] = arrayOf("EUR", "GBP", "USDC", "USD", "USDT")
        CURRENCY_PAIRS["DAI"] = arrayOf("USD", "USDC")
        CURRENCY_PAIRS["DASH"] = arrayOf("BTC", "USD")
        CURRENCY_PAIRS["DOGE"] = arrayOf("BTC", "EUR", "GBP", "USD", "USDT")
        CURRENCY_PAIRS["DOT"] = arrayOf("BTC", "EUR", "GBP", "USD", "USDT")
        CURRENCY_PAIRS["EOS"] = arrayOf("BTC", "EUR", "USD")
        CURRENCY_PAIRS["ETC"] = arrayOf("BTC", "EUR", "GBP", "USD")
        CURRENCY_PAIRS["ETH"] = arrayOf("BTC", "DAI", "EUR", "GBP", "USD", "USDT", "USDC")
        CURRENCY_PAIRS["FIL"] = arrayOf("BTC", "EUR", "GBP", "USD")
        CURRENCY_PAIRS["LINK"] = arrayOf("BTC", "ETH", "EUR", "GBP", "USD")
        CURRENCY_PAIRS["LTC"] = arrayOf("BTC", "EUR", "GBP", "USD")
        CURRENCY_PAIRS["OMG"] = arrayOf("BTC", "EUR", "GBP", "USD")
        CURRENCY_PAIRS["STORJ"] = arrayOf("BTC", "USD")
        CURRENCY_PAIRS["SUSHI"] = arrayOf("BTC", "ETH", "EUR", "GBP", "USD")
        CURRENCY_PAIRS["USDC"] = arrayOf("EUR", "GBP")
        CURRENCY_PAIRS["USDT"] = arrayOf("EUR", "GBP", "USD", "USDC")
        CURRENCY_PAIRS["XLM"] = arrayOf("BTC", "EUR", "USD")
        CURRENCY_PAIRS["ZEC"] = arrayOf("BTC", "USD", "USDC")
    }

    /**
     * Eine Anfrage je Paar: /stats liefert gleitend über 24 h open, high, low,
     * last und volume — alles, was Merkliste, Alarme und Widgets brauchen.
     * /ticker kam nur für Bid/Ask und den Zeitstempel dazu; Bid/Ask zeigt die
     * App nirgends dauerhaft an (nur die Vorschau beim Hinzufügen, die sie bei
     * fehlenden Werten ausblendet), der Zeitstempel ist dann die Abrufzeit.
     */
    override fun getNumOfRequests(checkerInfo: CheckerInfo?): Int {
        return 1
    }

    override fun getUrl(requestId: Int, checkerInfo: CheckerInfo): String {
        val pairId = checkerInfo.currencyPairId ?: "${checkerInfo.currencyBase}-${checkerInfo.currencyCounter}"
        return String.format(URL_STATS, pairId)
    }

    @Throws(Exception::class)
    override fun parseTickerFromJsonObject(requestId: Int, jsonObject: JSONObject, ticker: Ticker, checkerInfo: CheckerInfo) {
        // Unbekannte oder stillgelegte Produkte liefern statt der Kursdaten
        // ein Objekt mit "message". Ohne diese Abfrage stolpert der Parser
        // darüber und meldet nur "No value for volume".
        jsonObject.optText("message").takeIf { it.isNotEmpty() }?.let {
            throw MarketParseException(it)
        }

        ticker.vol = jsonObject.getDouble("volume").also {
            if(it <= 0)
                throw MarketParseException("No trading volume")
        }
        ticker.last = jsonObject.getDouble("last")
        ticker.high = jsonObject.getDouble("high")
        ticker.low = jsonObject.getDouble("low")
        // „open“ = Kurs vor 24 h (gleitend)
        ticker.change24hPercent = Change24h.fromOpen(ticker.last, jsonObject.optDouble("open"))
    }

    override fun getCurrencyPairsUrl(requestId: Int): String {
        return URL_CURRENCY_PAIRS
    }

    @Throws(Exception::class)
    override fun parseCurrencyPairs(requestId: Int, responseString: String, pairs: MutableList<CurrencyPairInfo>) {
        JSONArray(responseString).forEachJSONObject { pairJsonObject ->
            if(pairJsonObject.optString("status") != "delisted") {
                pairs.add(
                    CurrencyPairInfo(
                        pairJsonObject.getString("base_currency"),
                        pairJsonObject.getString("quote_currency"),
                        pairJsonObject.getString("id")
                    )
                )
            }
        }
    }

    override fun parseErrorFromJsonObject(
        requestId: Int,
        jsonObject: JSONObject,
        checkerInfo: CheckerInfo
    ): String? {
        return jsonObject.getText("message")
    }
}