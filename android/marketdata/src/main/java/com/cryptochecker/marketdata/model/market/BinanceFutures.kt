package com.cryptochecker.marketdata.model.market

import com.cryptochecker.marketdata.exceptions.MarketParseException
import com.cryptochecker.marketdata.model.*
import com.cryptochecker.marketdata.util.Change24h
import com.cryptochecker.marketdata.util.TradFi
import com.cryptochecker.marketdata.util.optStrings
import com.cryptochecker.marketdata.util.forEachJSONObject
import com.cryptochecker.marketdata.util.optDoubleNoData
import org.json.JSONArray
import org.json.JSONObject
import java.time.format.DateTimeFormatter
import java.util.*

class BinanceFutures : Market(NAME, TTS_NAME, null) {
    companion object {
        private const val NAME = "Binance Futures"
        private const val TTS_NAME = NAME

        private const val URL_USD_M = "https://fapi.binance.com/fapi/v1/ticker/24hr?symbol=%1\$s"
        private const val URL_CURRENCY_PAIRS_USD_M = "https://fapi.binance.com/fapi/v1/exchangeInfo"

        private const val URL_COIN_M = "https://dapi.binance.com/dapi/v1/ticker/24hr?symbol=%1\$s"

        // Ohne symbol-Parameter liefern beide Endpunkte alle Kontrakte auf einmal.
        private const val URL_ALL_TICKERS_USD_M = "https://fapi.binance.com/fapi/v1/ticker/24hr"
        private const val URL_ALL_TICKERS_COIN_M = "https://dapi.binance.com/dapi/v1/ticker/24hr"
        private const val URL_CURRENCY_PAIRS_COIN_M = "https://dapi.binance.com/dapi/v1/exchangeInfo"

        private const val COIN_M_PREFIX = "2:"

        private fun isCoinMPair(pairId: String) = pairId.startsWith(COIN_M_PREFIX)

        private val FUTURES_DATE_FORMAT = DateTimeFormatter.ofPattern("yyMMdd", Locale.ROOT)

        private fun parseTicker(jsonObject: JSONObject, ticker: Ticker) {
            // Bei unbekanntem Symbol antwortet Binance mit {"code":..,"msg":..}.
            // Ohne diese Abfrage endet das in "No value for volume".
            jsonObject.optString("msg").takeIf { it.isNotEmpty() }?.let {
                throw MarketParseException(it)
            }

            jsonObject.apply {
                ticker.vol = getDouble("volume")
                ticker.high = getDouble("highPrice")
                ticker.low = getDouble("lowPrice")
                ticker.last = getDouble("lastPrice")
                ticker.timestamp = getLong("closeTime")

                // Optional
                ticker.volQuote = optDoubleNoData("quoteVolume")
                // Gleitende 24 h, schon in Prozent
                ticker.change24hPercent = Change24h.percent(optDouble("priceChangePercent"))
            }
        }
    }

    override fun getUrl(requestId: Int, checkerInfo: CheckerInfo): String {
        val utlTemplate: String
        val pairId: String

        val currencyPairId = requirePairId(checkerInfo)
        if(isCoinMPair(currencyPairId)) {
            pairId = currencyPairId.substring(COIN_M_PREFIX.length)
            // String.format(URL_COIN_M, pairId)
            utlTemplate = URL_COIN_M
        }
        else {
//            String.format(URL_USD_M, checkerInfo.currencyPairId)
            pairId = currencyPairId
            utlTemplate = URL_USD_M
        }

        val deliveryDate = FuturesContractType.getDeliveryDate(checkerInfo.contractType) ?:
            return String.format(utlTemplate, pairId)

        val pairIdWithDeliveryDate = "${checkerInfo.currencyBase}${checkerInfo.currencyCounter}_${FUTURES_DATE_FORMAT.format(deliveryDate)}"
        return String.format(utlTemplate, pairIdWithDeliveryDate)
    }

    override fun parseTicker(
        requestId: Int,
        responseString: String,
        ticker: Ticker,
        checkerInfo: CheckerInfo
    ) {
        if(isCoinMPair(requirePairId(checkerInfo)))
            parseTicker(JSONArray(responseString).getJSONObject(0), ticker)
        else
            parseTicker(JSONObject(responseString), ticker)
    }

    override val bulkTickersNumOfRequests: Int
        get() = 2

    /** Binance liefert ohne Filter jedes gehandelte Paar. */
    override val bulkTickersComplete: Boolean
        get() = true

    override fun getBulkTickersUrl(requestId: Int): String =
        if (requestId == 0) URL_ALL_TICKERS_USD_M else URL_ALL_TICKERS_COIN_M

    override fun parseBulkTickers(
        requestId: Int,
        responseString: String,
        tickers: MutableMap<String, Ticker>,
    ) {
        JSONArray(responseString).forEachJSONObject { entry ->
            val symbol = entry.optString("symbol").ifEmpty { return@forEachJSONObject }

            val ticker = SimpleTicker()
            parseTicker(entry, ticker)

            // COIN-M-Paare tragen dasselbe Präfix wie beim Paar-Abgleich.
            tickers[if (requestId > 0) COIN_M_PREFIX + symbol else symbol] = ticker
        }
    }

    override val currencyPairsNumOfRequests: Int
        get() = 2

    override fun getCurrencyPairsUrl(requestId: Int): String =
        if(requestId == 0) URL_CURRENCY_PAIRS_USD_M else URL_CURRENCY_PAIRS_COIN_M

    @Throws(Exception::class)
    override fun parseCurrencyPairsFromJsonObject(requestId: Int, jsonObject: JSONObject, pairs: MutableList<CurrencyPairInfo>) {
        fun parseContractType(value: String): FuturesContractType? =
            when(value) {
                // TradFi-Perpetuals (Aktien, Rohstoffe, Devisen, Pre-IPO) sind technisch
                // gewöhnliche Perpetuals: gleiche Abfrage, gleiche Live-Kurse und Funding
                "PERPETUAL", TradFi.BINANCE_TRADFI_PERPETUAL -> FuturesContractType.PERPETUAL
                "CURRENT_QUARTER" -> FuturesContractType.QUARTERLY
                "NEXT_QUARTER" -> FuturesContractType.BIQUARTERLY
                else -> null
            }

        jsonObject.getJSONArray("symbols").forEachJSONObject { marketJsonObject ->
            val rawContractType = marketJsonObject.getString("contractType")
            val contractType = parseContractType(rawContractType) ?: return@forEachJSONObject

            val symbol = marketJsonObject.getString("symbol").let {
                if(requestId > 0) COIN_M_PREFIX + it else it
            }
            val baseAsset = marketJsonObject.getString("baseAsset")
            val quoteAsset = marketJsonObject.getString("quoteAsset")

            pairs.add(CurrencyPairInfo(
                baseAsset,
                quoteAsset,
                symbol,
                contractType,
                tradFi = TradFi.binance(rawContractType, marketJsonObject.optStrings("underlyingSubType"))))
        }
    }

    /** Paar-Kennung des Eintrags; ohne Kennung lässt sich kein Kurs abfragen. */
    private fun requirePairId(checkerInfo: CheckerInfo): String =
        checkerInfo.currencyPairId ?: throw MarketParseException("Missing pair id")
}
