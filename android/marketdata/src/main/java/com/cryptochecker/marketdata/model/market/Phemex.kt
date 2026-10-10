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
import com.cryptochecker.marketdata.util.optDoubleNoData
import org.json.JSONArray
import org.json.JSONObject
import com.cryptochecker.marketdata.util.optText

/**
 * Phemex Spot. Symbole mit „s“ davor (sBTCUSDT). Kurse sind ganze Zahlen,
 * skaliert mit 10^8 (Ep/Ev). https://github.com/phemex/phemex-api-docs
 */
class Phemex : PhemexBase(
    "Phemex",
    "https://api.phemex.com/md/spot/ticker/24hr?symbol=%1\$s",
    FuturesContractType.NONE,
) {
    override fun getPairId(checkerInfo: CheckerInfo): String =
        checkerInfo.currencyPairId ?: "s${checkerInfo.currencyBase}${checkerInfo.currencyCounter}"

    override fun read(json: JSONObject, ticker: Ticker) {
        ticker.last = json.getLong("lastEp") / SCALE
        ticker.bid = json.scaled("bidEp")
        ticker.ask = json.scaled("askEp")
        ticker.high = json.scaled("highEp")
        ticker.low = json.scaled("lowEp")
        ticker.vol = json.scaled("volumeEv")
        ticker.volQuote = json.scaled("turnoverEv")
        ticker.timestamp = nanosToMillis(json)
        // 24-h-Ticker: openEp = Kurs vor 24 h
        ticker.change24hPercent = Change24h.fromOpen(ticker.last, json.scaled("openEp"))
    }

    override fun getBulkTickersUrl(requestId: Int): String = "https://api.phemex.com/md/spot/ticker/24hr/all"

    private fun JSONObject.scaled(name: String): Double =
        if (has(name) && !isNull(name)) optLong(name) / SCALE else Ticker.NO_DATA.toDouble()

    private companion object {
        const val SCALE = 100_000_000.0
    }
}

/** Phemex USDT-Perpetuals. Werte als Dezimal-Strings (Rp/Rq/Rv), ohne Geld-/Briefkurs. */
class PhemexFutures : PhemexBase(
    "Phemex Futures",
    "https://api.phemex.com/md/v2/ticker/24hr?symbol=%1\$s",
    FuturesContractType.PERPETUAL,
) {
    override fun getPairId(checkerInfo: CheckerInfo): String =
        checkerInfo.currencyPairId ?: "${checkerInfo.currencyBase}${checkerInfo.currencyCounter}"

    override fun read(json: JSONObject, ticker: Ticker) {
        ticker.last = json.getDouble("closeRp")
        ticker.high = json.optDoubleNoData("highRp")
        ticker.low = json.optDoubleNoData("lowRp")
        ticker.vol = json.optDoubleNoData("volumeRq")
        ticker.volQuote = json.optDoubleNoData("turnoverRv")
        ticker.timestamp = nanosToMillis(json)
        // 24-h-Ticker: openRp = Kurs vor 24 h
        ticker.change24hPercent = Change24h.fromOpen(ticker.last, json.optDouble("openRp"))
    }

    override fun getBulkTickersUrl(requestId: Int): String = "https://api.phemex.com/md/v2/ticker/24hr/all"
}

abstract class PhemexBase(
    name: String,
    tickerUrl: String,
    private val contractType: FuturesContractType,
) : SimpleMarket(name, "https://api.phemex.com/public/products", tickerUrl, "Phemex") {

    override fun parseCurrencyPairsFromJsonObject(requestId: Int, jsonObject: JSONObject, pairs: MutableList<CurrencyPairInfo>) {
        val data = jsonObject.getJSONObject("data")
        if (contractType == FuturesContractType.NONE) {
            data.getJSONArray("products").forEachJSONObject { item ->
                if (item.optString("type") != "Spot" || item.optString("status") != "Listed") return@forEachJSONObject
                pairs.add(CurrencyPairInfo(item.getString("baseCurrency"), item.getString("quoteCurrency"), item.getString("symbol")))
            }
        } else {
            data.optJSONArray("perpProductsV2")?.forEachJSONObject { item ->
                if (item.optString("status") != "Listed") return@forEachJSONObject
                val type = item.optString("type")
                if (type.isNotEmpty() && !type.contains("Perpetual", ignoreCase = true)) return@forEachJSONObject
                pairs.add(
                    CurrencyPairInfo(
                        item.getString("baseCurrency"),
                        item.getString("quoteCurrency"),
                        item.getString("symbol"),
                        contractType,
                    )
                )
            }
        }
    }

    protected abstract fun read(json: JSONObject, ticker: Ticker)

    override fun parseTickerFromJsonObject(requestId: Int, jsonObject: JSONObject, ticker: Ticker, checkerInfo: CheckerInfo) =
        read(jsonObject.getJSONObject("result"), ticker)

    /** Phemex meldet Fehler als {"error":{…}} oder {"code":…,"msg":"…"}. */
    override fun parseErrorFromJsonObject(requestId: Int, jsonObject: JSONObject, checkerInfo: CheckerInfo): String? {
        jsonObject.optJSONObject("error")?.let { return it.optText("message").ifEmpty { it.toString() } }
        return jsonObject.optText("msg").ifEmpty { throw Exception("Kein Fehlertext") }
    }

    override val bulkTickersNumOfRequests: Int get() = 1

    override fun parseBulkTickers(requestId: Int, responseString: String, tickers: MutableMap<String, Ticker>) {
        val json = JSONObject(responseString)
        // Fehlerantwort ({"code":…,"msg":…} oder {"error":{…}}) statt Liste: Abfrage scheitert mit dem
        // Text der Börse – nicht stillschweigend leer. Wie iOS.
        val list = json.optJSONArray("result") ?: throw MarketParseException(
            json.optJSONObject("error")?.optText("message")?.ifEmpty { null }
                ?: json.optText("msg").ifEmpty { "No result" }
        )
        list.forEachJSONObject { item ->
            val symbol = item.optString("symbol").ifEmpty { return@forEachJSONObject }
            val ticker = SimpleTicker()
            runCatching { read(item, ticker) }.onFailure { return@forEachJSONObject }
            tickers[symbol] = ticker
        }
    }

    private companion object {
        val LONG_MAX: java.math.BigDecimal = java.math.BigDecimal.valueOf(Long.MAX_VALUE)
        val LONG_MIN: java.math.BigDecimal = java.math.BigDecimal.valueOf(Long.MIN_VALUE)
    }

    protected fun nanosToMillis(json: JSONObject): Long {
        // Zeitstempel in Nanosekunden (teils als String)
        val raw = json.optString("timestamp")
        val millis = raw.toBigDecimalOrNull()?.movePointLeft(6) ?: return 0L
        // BigDecimal.toLong() schnitte riesige Werte ab (Überlauf) – stattdessen begrenzen, wie iOS
        return when {
            millis > LONG_MAX -> Long.MAX_VALUE
            millis < LONG_MIN -> Long.MIN_VALUE
            else -> millis.toLong()
        }
    }
}
