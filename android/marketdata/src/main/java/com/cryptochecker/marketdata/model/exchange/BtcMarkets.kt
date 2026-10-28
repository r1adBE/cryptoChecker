package com.cryptochecker.marketdata.model.exchange

import com.cryptochecker.marketdata.model.PairQuery
import com.cryptochecker.marketdata.model.ExchangePair
import com.cryptochecker.marketdata.model.Ticker
import com.cryptochecker.marketdata.model.exchange.generic.SimpleExchange
import com.cryptochecker.marketdata.util.Change24h
import com.cryptochecker.marketdata.util.forEachJSONObject
import com.cryptochecker.marketdata.util.optDoubleOrMissing
import com.cryptochecker.marketdata.util.optText
import org.json.JSONArray
import org.json.JSONObject

private const val BTC_MARKETS_API = "https://api.btcmarkets.net/v3"

/** Sammelabfrage: hinter dem Präfix die Kennungen, je Markt ein eigener Parameter «marketId». */
private const val BTC_MARKETS_TICKERS_FOR = "$BTC_MARKETS_API/markets/tickers?marketId="

/** Trenner zwischen zwei Kennungen der Sammelabfrage. */
private const val BTC_MARKETS_NEXT_ID = "&marketId="

/** `status` eines normal handelbaren Markts; «Post Only», «Limit Only», «Cancel Only», «Offline» fallen weg. */
private const val BTC_MARKETS_STATUS_ONLINE = "Online"

/** Kennungen, die ohne Kodierung in der Adresse stehen dürfen («BTC-AUD»). */
private val BTC_MARKETS_SAFE_ID = Regex("[A-Za-z0-9-]+")

/**
 * BTC Markets (Australien, AUD-Paare), API v3: https://api.btcmarkets.net/doc/v3. Kennung = `marketId` («BTC-AUD»).
 * Die Sammelabfrage verlangt die Märkte als Auswahl; eine ungefilterte Form ist nicht belegt.
 */
class BtcMarkets : SimpleExchange(
    name = "BTC Markets",
    pairListUrl = "$BTC_MARKETS_API/markets",
    tickerUrl = "$BTC_MARKETS_API/markets/%1\$s/ticker",
    ttsName = "B T C Markets",
    errorField = "message",
) {
    override val key = "BtcMarkets"

    // ── Einzelkurs ──

    override fun pairId(info: PairQuery): String = info.pairId ?: (info.base + "-" + info.quote)

    override fun fillTickerJson(call: Int, json: JSONObject, ticker: Ticker, info: PairQuery) {
        copyRow(json, ticker)
    }

    // ── Sammelabfrage: nur gefiltert, alle beobachteten Märkte in einer Anfrage ──

    override val bulkTickersNumOfRequests: Int
        get() = 1

    /** Ob die Börse unbekannte Märkte ablehnt oder still weglässt, ist unbelegt: Fehlende prüft die Einzelabfrage. */
    override val bulkTickersComplete: Boolean
        get() = false

    /**
     * Sortiert und ohne Doppelte; Kennungen, die kodiert werden müssten, bleiben draussen (Einzelabfrage). Ohne
     * Kennung keine Sammelabfrage.
     */
    override fun getBulkTickersUrl(call: Int, pairIds: Collection<String>): String? {
        if (call != 0) return null
        val ids = pairIds.filter(BTC_MARKETS_SAFE_ID::matches).distinct().sorted()
        if (ids.isEmpty()) return null
        return ids.joinToString(separator = BTC_MARKETS_NEXT_ID, prefix = BTC_MARKETS_TICKERS_FOR)
    }

    /** Lehnt die Börse die Auswahl wegen eines unbekannten Markts ab: Paarliste holen und ohne ihn neu fragen. */
    override fun getBulkKnownPairsUrl(call: Int): String = pairListAddress(call)

    override fun parseBulkTickers(
        call: Int,
        body: String,
        tickers: MutableMap<String, Ticker>,
        failed: MutableSet<String>,
    ) {
        JSONArray(body).forEachJSONObject { row ->
            bulkRowIfActive(row.optText("marketId"), row, "lastPrice", tickers, failed) { copyRow(row, it) }
        }
    }

    // ── Paarliste ──

    /** Nur «Online»; ohne `status` (ältere Antworten) bleibt der Markt. */
    override fun fillPairList(call: Int, body: String, out: MutableList<ExchangePair>) {
        JSONArray(body).forEachJSONObject { market ->
            val status = market.optText("status")
            if (status.isNotEmpty() && status != BTC_MARKETS_STATUS_ONLINE) return@forEachJSONObject
            val base = market.optText("baseAssetName")
            out += ExchangePair(base, market.optText("quoteAssetName"), market.optText("marketId"))
        }
    }

    // ── Lesen ──

    /**
     * Ein Ticker. `timestamp` ist die Zeit der Momentaufnahme, kein Handel — Zeit der Börse bleibt unbekannt.
     */
    private fun copyRow(row: JSONObject, ticker: Ticker) {
        val last = row.getDouble("lastPrice")
        ticker.last = last
        ticker.bid = row.optDoubleOrMissing("bestBid")
        ticker.ask = row.optDoubleOrMissing("bestAsk")
        ticker.high = row.optDoubleOrMissing("high24h")
        ticker.low = row.optDoubleOrMissing("low24h")
        ticker.vol = row.optDoubleOrMissing("volume24h")
        ticker.volQuote = row.optDoubleOrMissing("volumeQte24h")
        // «price24h» = absolute Veränderung über 24 h (mehr Stellen); «pricePct24h» (Prozent) nur ersatzweise
        ticker.change24hPercent = Change24h.fromAbsolute(last, row.optDouble("price24h"))
            ?: Change24h.percent(row.optDouble("pricePct24h"))
    }
}
