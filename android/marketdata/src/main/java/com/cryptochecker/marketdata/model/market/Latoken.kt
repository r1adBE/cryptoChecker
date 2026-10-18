package com.cryptochecker.marketdata.model.market

import com.cryptochecker.marketdata.model.PairQuery
import com.cryptochecker.marketdata.model.MarketPair
import com.cryptochecker.marketdata.model.Market
import com.cryptochecker.marketdata.model.Ticker
import com.cryptochecker.marketdata.util.Change24h
import com.cryptochecker.marketdata.util.forEachJSONObject
import com.cryptochecker.marketdata.util.getText
import com.cryptochecker.marketdata.util.optDoubleOrMissing
import com.cryptochecker.marketdata.util.optText
import org.json.JSONArray
import org.json.JSONObject

private const val LATOKEN_API = "https://api.latoken.com/v2"

/**
 * LATOKEN (API v2). Paare und Ticker kennen nur Währungs-IDs (UUID); die Kürzel liefert die Währungsliste —
 * darum zwei gemeinsam gelesene Paarlisten statt einer ([pairListCombined]) und kein `SimpleMarket`.
 * Paar-Kennung: «Basis-ID/Quote-ID». Vorsicht: «amount24h» = Menge im Coin, «volume24h» = Gegenwert.
 */
class Latoken : Market("LATOKEN", "La token") {
    override val key = "Latoken"

    // ── Einzelkurs ──

    override fun tickerAddress(call: Int, info: PairQuery): String {
        val pairId = info.pairId ?: idOf(info.base, info.quote)
        return "$LATOKEN_API/ticker/$pairId"
    }

    override fun fillTickerJson(call: Int, json: JSONObject, ticker: Ticker, info: PairQuery) {
        copyRow(json, ticker)
    }

    override fun errorMessageJson(call: Int, json: JSONObject, info: PairQuery): String? = json.getText("message")

    // ── Sammelabfrage ──

    override val bulkTickersNumOfRequests: Int
        get() = 1

    override fun getBulkTickersUrl(call: Int): String = "$LATOKEN_API/ticker"

    override fun parseBulkTickers(
        call: Int,
        body: String,
        tickers: MutableMap<String, Ticker>,
        failed: MutableSet<String>,
    ) {
        JSONArray(body).forEachJSONObject { row ->
            val baseId = row.optString("baseCurrency")
            val quoteId = row.optString("quoteCurrency")
            if (baseId.isEmpty() || quoteId.isEmpty()) return@forEachJSONObject
            bulkRowIfActive(idOf(baseId, quoteId), row, "lastPrice", tickers, failed) { copyRow(row, it) }
        }
    }

    // ── Paarliste: Währungen (0) und Paare (1) zusammen auswerten ──

    override val pairListCallCount: Int
        get() = 2

    override val pairListCombined: Boolean
        get() = true

    override fun pairListAddress(call: Int): String =
        if (call == 0) "$LATOKEN_API/currency" else "$LATOKEN_API/pair"

    override fun fillPairListCombined(bodies: List<String>, out: MutableList<MarketPair>) {
        val tagById = currencyTags(bodies[0])
        JSONArray(bodies[1]).forEachJSONObject { pair ->
            if (pair.optString("status") != "PAIR_STATUS_ACTIVE") return@forEachJSONObject
            val baseId = pair.optString("baseCurrency")
            val quoteId = pair.optString("quoteCurrency")
            // Unbekannte ID: Paar weglassen
            val baseTag = tagById[baseId] ?: return@forEachJSONObject
            val quoteTag = tagById[quoteId] ?: return@forEachJSONObject
            out += MarketPair(baseTag, quoteTag, idOf(baseId, quoteId))
        }
    }

    // ── Namen ──

    /** Volle Namen stehen in derselben Währungsliste (Kürzel «tag» → «name»). */
    override fun parseBaseNamesCombined(bodies: List<String>): Map<String, String> {
        val byTag = LinkedHashMap<String, String>()
        JSONArray(bodies[0]).forEachJSONObject { currency ->
            val tag = currency.optString("tag")
            val fullName = currency.optText("name")
            if (tag.isEmpty() || fullName.isEmpty()) return@forEachJSONObject
            byTag.putIfAbsent(tag, fullName)
        }
        return byTag
    }

    // ── Lesen ──

    private fun copyRow(row: JSONObject, ticker: Ticker) {
        ticker.last = row.getDouble("lastPrice")
        ticker.bid = row.optDoubleOrMissing("bestBid")
        ticker.ask = row.optDoubleOrMissing("bestAsk")
        ticker.vol = row.optDoubleOrMissing("amount24h")
        ticker.volQuote = row.optDoubleOrMissing("volume24h")
        // Zeit des letzten Updates laut Börse (nur Anzeige)
        ticker.timestamp = row.optLong("updateTimestamp")
        // change24h: Prozent über 24 h
        ticker.change24hPercent = Change24h.percent(row.optDouble("change24h"))
    }

    /** Währungs-ID → Kürzel; Einträge ohne ID oder Kürzel fallen weg. */
    private fun currencyTags(body: String): Map<String, String> {
        val tags = HashMap<String, String>()
        JSONArray(body).forEachJSONObject { currency ->
            val id = currency.optString("id")
            val tag = currency.optString("tag")
            if (id.isNotEmpty() && tag.isNotEmpty()) tags[id] = tag
        }
        return tags
    }

    private fun idOf(base: String, quote: String): String = "$base/$quote"
}
