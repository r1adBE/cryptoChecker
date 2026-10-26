package com.cryptochecker.marketdata.model.market

import com.cryptochecker.marketdata.model.MarketCategory
import com.cryptochecker.marketdata.model.PairQuery
import com.cryptochecker.marketdata.model.MarketPair
import com.cryptochecker.marketdata.model.FuturesContractType
import com.cryptochecker.marketdata.model.Market
import com.cryptochecker.marketdata.model.Ticker
import com.cryptochecker.marketdata.util.Change24h
import com.cryptochecker.marketdata.util.forEachJSONObject
import com.cryptochecker.marketdata.util.getText
import com.cryptochecker.marketdata.util.optDoubleOrMissing
import org.json.JSONObject

private const val DERIBIT_API = "https://www.deribit.com/api/v2"

/** Abrechnungswährungen mit Perpetuals; je eine Anfrage für Paarliste und Sammelabfrage. */
private val DERIBIT_SETTLE_CURRENCIES = listOf("BTC", "ETH", "USDC")

/**
 * Deribit, nur Perpetuals: BTC-/ETH-PERPETUAL (gegen USD, in Coins abgerechnet = invers) und die linearen
 * USDC-Perpetuals (z. B. SOL_USDC-PERPETUAL). Eine Paarliste je Abrechnungswährung, darum kein `SimpleMarket`.
 * API v2: https://docs.deribit.com
 */
class Deribit : Market("Deribit", "Deribit") {
    override val key = "Deribit"
    override val category = MarketCategory.FUTURES

    // ── Einzelkurs ──

    override fun tickerAddress(call: Int, info: PairQuery): String {
        val instrument = info.pairId ?: (info.base + "-PERPETUAL")
        return "$DERIBIT_API/public/ticker?instrument_name=$instrument"
    }

    /** `timestamp` ist der Schnappschuss (≈ Abruf), kein Handel: Zeit der Börse unbekannt. */
    override fun fillTickerJson(call: Int, json: JSONObject, ticker: Ticker, info: PairQuery) {
        val result = json.getJSONObject("result")
        ticker.last = result.getDouble("last_price")
        ticker.bid = result.optDoubleOrMissing("best_bid_price")
        ticker.ask = result.optDoubleOrMissing("best_ask_price")
        val stats = result.optJSONObject("stats") ?: return
        ticker.high = stats.optDoubleOrMissing("high")
        ticker.low = stats.optDoubleOrMissing("low")
        ticker.vol = stats.optDoubleOrMissing("volume")
        ticker.volQuote = stats.optDoubleOrMissing("volume_usd")
        // «price_change»: gleitende 24 h in Prozent
        ticker.change24hPercent = Change24h.percent(stats.optDouble("price_change"))
    }

    override fun errorMessageJson(call: Int, json: JSONObject, info: PairQuery): String? =
        json.getJSONObject("error").getText("message")

    // ── Sammelabfrage: Übersicht je Abrechnungswährung ──

    override val bulkTickersNumOfRequests: Int
        get() = DERIBIT_SETTLE_CURRENCIES.size

    override val bulkTickersComplete: Boolean
        get() = true

    override fun getBulkTickersUrl(call: Int): String =
        "$DERIBIT_API/public/get_book_summary_by_currency?currency=${DERIBIT_SETTLE_CURRENCIES[call]}&kind=future"

    /** Ohne Handel ist `last` null (ruhend); ein unlesbarer Kurs (Text statt Zahl) trifft nur sein Paar. */
    override fun parseBulkTickers(
        call: Int,
        body: String,
        tickers: MutableMap<String, Ticker>,
        failed: MutableSet<String>,
    ) {
        JSONObject(body).getJSONArray("result").forEachJSONObject { row ->
            val instrument = row.optString("instrument_name")
            if (!instrument.endsWith("PERPETUAL")) return@forEachJSONObject
            bulkRowIfActive(instrument, row, "last", tickers, failed) { copyRow(row, it) }
        }
    }

    // ── Paarliste: eine Anfrage je Abrechnungswährung ──

    override val pairListCallCount: Int
        get() = DERIBIT_SETTLE_CURRENCIES.size

    override fun pairListAddress(call: Int): String =
        "$DERIBIT_API/public/get_instruments?currency=${DERIBIT_SETTLE_CURRENCIES[call]}&kind=future&expired=false"

    override fun fillPairListJson(call: Int, json: JSONObject, out: MutableList<MarketPair>) {
        json.getJSONArray("result").forEachJSONObject { entry ->
            if (entry.optString("settlement_period") != "perpetual") return@forEachJSONObject
            val state = entry.optString("state")
            if (state.isNotEmpty() && state != "open") return@forEachJSONObject
            val instrument = entry.getString("instrument_name")
            out += MarketPair(
                entry.getString("base_currency"),
                entry.getString("quote_currency"),
                instrument,
                if (isInverse(entry, instrument)) FuturesContractType.INVERSE_PERPETUAL else FuturesContractType.PERPETUAL,
            )
        }
    }

    // ── Lesen ──

    /** Eine Zeile der Übersicht; `creation_timestamp` ist deren Erstellung, keine Handelszeit. */
    private fun copyRow(row: JSONObject, ticker: Ticker) {
        ticker.last = row.getDouble("last")
        ticker.bid = row.optDoubleOrMissing("bid_price")
        ticker.ask = row.optDoubleOrMissing("ask_price")
        ticker.high = row.optDoubleOrMissing("high")
        ticker.low = row.optDoubleOrMissing("low")
        ticker.vol = row.optDoubleOrMissing("volume")
        val usd = row.optDouble("volume_usd")
        ticker.volQuote = if (usd.isNaN()) row.optDoubleOrMissing("volume_notional") else usd
        ticker.change24hPercent = Change24h.percent(row.optDouble("price_change"))
    }

    /** «reversed» = invers, «linear» = linear; ohne Angabe entscheidet die Kennung. */
    private fun isInverse(entry: JSONObject, instrument: String): Boolean = when (entry.optString("instrument_type")) {
        "reversed" -> true
        "linear" -> false
        else -> FuturesContractType.isInversePerpetualId(key, instrument)
    }
}
