package com.cryptochecker.marketdata.model.market

import com.cryptochecker.marketdata.model.CheckerInfo
import com.cryptochecker.marketdata.model.CurrencyPairInfo
import com.cryptochecker.marketdata.model.FuturesContractType
import com.cryptochecker.marketdata.model.SimpleTicker
import com.cryptochecker.marketdata.model.Ticker
import com.cryptochecker.marketdata.model.market.generic.SimpleMarket
import com.cryptochecker.marketdata.util.forEachJSONObject
import com.cryptochecker.marketdata.util.optDoubleNoData
import org.json.JSONObject
import java.util.Locale

/** HTX (früher Huobi) Spot. */
class Htx : SimpleMarket(
    "HTX",
    "https://api.huobi.pro/v2/settings/common/symbols",
    "https://api.huobi.pro/market/detail/merged?symbol=%1\$s",
    errorPropertyName = "err-msg"
) {
    override fun parseCurrencyPairsFromJsonObject(requestId: Int, jsonObject: JSONObject, pairs: MutableList<CurrencyPairInfo>) {
        jsonObject.getJSONArray("data").forEachJSONObject { item ->
            if (item.optString("state") != "online") return@forEachJSONObject
            pairs.add(
                CurrencyPairInfo(
                    item.getString("bc").uppercase(Locale.ROOT),
                    item.getString("qc").uppercase(Locale.ROOT),
                    item.getString("sc")
                )
            )
        }
    }

    override fun parseTickerFromJsonObject(requestId: Int, jsonObject: JSONObject, ticker: Ticker, checkerInfo: CheckerInfo) {
        HtxTicker.read(jsonObject.getJSONObject("tick"), ticker)
        ticker.timestamp = jsonObject.optLong("ts")
    }

    override val bulkTickersNumOfRequests: Int get() = 1
    override fun getBulkTickersUrl(requestId: Int): String = "https://api.huobi.pro/market/tickers"
    override fun parseBulkTickers(requestId: Int, responseString: String, tickers: MutableMap<String, Ticker>) =
        HtxTicker.readAll(responseString, "data", "symbol", tickers)
    override val bulkTickersComplete: Boolean get() = true
}

/** HTX USDT-Perpetuals (linear swap). */
class HtxFutures : SimpleMarket(
    "HTX Futures",
    "https://api.hbdm.com/linear-swap-api/v1/swap_contract_info?business_type=swap",
    "https://api.hbdm.com/linear-swap-ex/market/detail/merged?contract_code=%1\$s",
    errorPropertyName = "err_msg"
) {
    override fun parseCurrencyPairsFromJsonObject(requestId: Int, jsonObject: JSONObject, pairs: MutableList<CurrencyPairInfo>) {
        jsonObject.getJSONArray("data").forEachJSONObject { item ->
            // contract_status 1 = gelistet / handelbar
            if (item.optInt("contract_status", -1) != 1) return@forEachJSONObject
            val code = item.getString("contract_code")          // z. B. BTC-USDT
            val parts = code.split('-')
            if (parts.size != 2) return@forEachJSONObject
            pairs.add(CurrencyPairInfo(parts[0], parts[1], code, FuturesContractType.PERPETUAL))
        }
    }

    override fun parseTickerFromJsonObject(requestId: Int, jsonObject: JSONObject, ticker: Ticker, checkerInfo: CheckerInfo) {
        HtxTicker.read(jsonObject.getJSONObject("tick"), ticker)
        ticker.volQuote = jsonObject.getJSONObject("tick").optDoubleNoData("trade_turnover")
        ticker.timestamp = jsonObject.optLong("ts")
    }

    override val bulkTickersNumOfRequests: Int get() = 1
    override fun getBulkTickersUrl(requestId: Int): String =
        "https://api.hbdm.com/linear-swap-ex/market/detail/batch_merged?business_type=swap"
    override fun parseBulkTickers(requestId: Int, responseString: String, tickers: MutableMap<String, Ticker>) =
        HtxTicker.readAll(responseString, "ticks", "contract_code", tickers)
    override val bulkTickersComplete: Boolean get() = true
}

/** Spot und Swap nutzen dieselben Feldnamen; bid/ask mal als Zahl, mal als [Preis, Menge]. */
internal object HtxTicker {
    fun read(json: JSONObject, ticker: Ticker) {
        // Kein 24-h-Wert: „open“ ist laut Doku der Massenabfrage die Eröffnung des
        // Kalendertags (Singapur-Zeit) — Einzel- und Massenabfrage wären uneinheitlich.
        ticker.last = json.getDouble("close")
        ticker.high = json.optDoubleNoData("high")
        ticker.low = json.optDoubleNoData("low")
        ticker.vol = json.optDoubleNoData("amount")
        ticker.volQuote = json.optDoubleNoData("trade_turnover").takeIf { it >= 0 } ?: json.optDoubleNoData("vol")
        ticker.bid = priceOf(json, "bid")
        ticker.ask = priceOf(json, "ask")
        json.optLong("ts").takeIf { it > 0 }?.let { ticker.timestamp = it }
    }

    private fun priceOf(json: JSONObject, name: String): Double =
        json.optJSONArray(name)?.optDouble(0, Ticker.NO_DATA.toDouble()) ?: json.optDoubleNoData(name)

    fun readAll(responseString: String, arrayName: String, idName: String, tickers: MutableMap<String, Ticker>) {
        val root = JSONObject(responseString)
        val time = root.optLong("ts")
        root.getJSONArray(arrayName).forEachJSONObject { item ->
            val id = item.optString(idName).ifEmpty { return@forEachJSONObject }
            val ticker = SimpleTicker()
            ticker.timestamp = time
            runCatching { read(item, ticker) }.onFailure { return@forEachJSONObject }
            tickers[id] = ticker
        }
    }
}
