package com.cryptochecker.marketdata.model.market

import com.cryptochecker.marketdata.model.CheckerInfo
import com.cryptochecker.marketdata.model.CurrencyPairInfo
import com.cryptochecker.marketdata.model.FuturesContractType
import com.cryptochecker.marketdata.model.Market
import com.cryptochecker.marketdata.model.SimpleTicker
import com.cryptochecker.marketdata.model.Ticker
import com.cryptochecker.marketdata.util.Change24h
import com.cryptochecker.marketdata.util.forEachJSONObject
import com.cryptochecker.marketdata.util.optDoubleNoData
import org.json.JSONObject
import com.cryptochecker.marketdata.util.getText

/**
 * Deribit — nur Perpetuals: BTC-PERPETUAL/ETH-PERPETUAL (gegen USD, in Coins
 * abgerechnet) und die linearen USDC-Perpetuals (z. B. SOL_USDC-PERPETUAL).
 * API v2: https://docs.deribit.com
 */
class Deribit : Market("Deribit", "Deribit") {

    override val currencyPairsNumOfRequests: Int get() = CURRENCIES.size

    override fun getCurrencyPairsUrl(requestId: Int): String =
        "$BASE/public/get_instruments?currency=${CURRENCIES[requestId]}&kind=future&expired=false"

    override fun parseCurrencyPairsFromJsonObject(requestId: Int, jsonObject: JSONObject, pairs: MutableList<CurrencyPairInfo>) {
        jsonObject.getJSONArray("result").forEachJSONObject { item ->
            if (item.optString("settlement_period") != "perpetual") return@forEachJSONObject
            val state = item.optString("state")
            if (state.isNotEmpty() && state != "open") return@forEachJSONObject
            pairs.add(
                CurrencyPairInfo(
                    item.getString("base_currency"),
                    item.getString("quote_currency"),
                    item.getString("instrument_name"),
                    FuturesContractType.PERPETUAL,
                )
            )
        }
    }

    override fun getUrl(requestId: Int, checkerInfo: CheckerInfo): String =
        "$BASE/public/ticker?instrument_name=${checkerInfo.currencyPairId ?: "${checkerInfo.currencyBase}-PERPETUAL"}"

    override fun parseTickerFromJsonObject(requestId: Int, jsonObject: JSONObject, ticker: Ticker, checkerInfo: CheckerInfo) {
        val result = jsonObject.getJSONObject("result")
        ticker.last = result.getDouble("last_price")
        ticker.bid = result.optDoubleNoData("best_bid_price")
        ticker.ask = result.optDoubleNoData("best_ask_price")
        ticker.timestamp = result.optLong("timestamp")
        result.optJSONObject("stats")?.let { stats ->
            ticker.high = stats.optDoubleNoData("high")
            ticker.low = stats.optDoubleNoData("low")
            ticker.vol = stats.optDoubleNoData("volume")
            ticker.volQuote = stats.optDoubleNoData("volume_usd")
            // price_change = gleitende 24 h in Prozent
            ticker.change24hPercent = Change24h.percent(stats.optDouble("price_change"))
        }
    }

    override fun parseErrorFromJsonObject(requestId: Int, jsonObject: JSONObject, checkerInfo: CheckerInfo): String? =
        jsonObject.getJSONObject("error").getText("message")

    // Sammelabfrage je Abrechnungswährung
    override val bulkTickersNumOfRequests: Int get() = CURRENCIES.size

    override fun getBulkTickersUrl(requestId: Int): String =
        "$BASE/public/get_book_summary_by_currency?currency=${CURRENCIES[requestId]}&kind=future"

    override fun parseBulkTickers(requestId: Int, responseString: String, tickers: MutableMap<String, Ticker>) {
        JSONObject(responseString).getJSONArray("result").forEachJSONObject { item ->
            val name = item.optString("instrument_name")
            if (!name.endsWith("PERPETUAL")) return@forEachJSONObject
            val last = item.optDouble("last")
            if (last.isNaN()) return@forEachJSONObject
            tickers[name] = SimpleTicker().apply {
                this.last = last
                bid = item.optDoubleNoData("bid_price")
                ask = item.optDoubleNoData("ask_price")
                high = item.optDoubleNoData("high")
                low = item.optDoubleNoData("low")
                vol = item.optDoubleNoData("volume")
                volQuote = item.optDouble("volume_usd").takeUnless { it.isNaN() }
                    ?: item.optDoubleNoData("volume_notional")
                timestamp = item.optLong("creation_timestamp")
                change24hPercent = Change24h.percent(item.optDouble("price_change"))
            }
        }
    }

    override val bulkTickersComplete: Boolean get() = true

    private companion object {
        const val BASE = "https://www.deribit.com/api/v2"
        val CURRENCIES = listOf("BTC", "ETH", "USDC")
    }
}
