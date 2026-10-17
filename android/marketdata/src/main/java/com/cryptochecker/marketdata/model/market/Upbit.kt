package com.cryptochecker.marketdata.model.market

import com.cryptochecker.marketdata.model.PairQuery
import com.cryptochecker.marketdata.model.MarketPair
import com.cryptochecker.marketdata.model.Ticker
import com.cryptochecker.marketdata.model.market.generic.SimpleMarket
import com.cryptochecker.marketdata.util.BulkPairChunks
import com.cryptochecker.marketdata.util.forEachJSONObject
import com.cryptochecker.marketdata.util.getText
import com.cryptochecker.marketdata.util.optDoubleOrMissing
import com.cryptochecker.marketdata.util.optText
import org.json.JSONArray
import org.json.JSONObject

/** Upbit (Südkorea). Kennung «QUOTE-BASE», z. B. KRW-BTC. Doku: docs.upbit.com */
class Upbit : UpbitStyleMarket(
    name = "Upbit",
    ttsName = "Up bit",
    apiRoot = "https://api.upbit.com/v1",
    detailsFlag = "is_details=false",
    unfilteredUrl = "https://api.upbit.com/v1/ticker/all?quote_currencies=KRW,BTC,USDT",
)

/** Bithumb (Südkorea), API v1 mit Upbits Aufbau. Doku: apidocs.bithumb.com */
class Bithumb : UpbitStyleMarket(
    name = "Bithumb",
    ttsName = "Bithumb",
    apiRoot = "https://api.bithumb.com/v1",
    detailsFlag = "isDetails=false",
    unfilteredUrl = null,
)

/**
 * Gemeinsamer Aufbau von Upbit und Bithumb: Paarliste `market/all`, Ticker `ticker?markets=…`, Kennung «QUOTE-BASE».
 * [unfilteredUrl]: Sammelabfrage ohne Auswahl, falls die Börse eine hat (Upbit), sonst null (Bithumb).
 */
abstract class UpbitStyleMarket(
    name: String,
    ttsName: String,
    apiRoot: String,
    detailsFlag: String,
    private val unfilteredUrl: String?,
) : SimpleMarket(
    name = name,
    pairListUrl = "$apiRoot/market/all?$detailsFlag",
    tickerUrl = "$apiRoot/ticker?markets=%1\$s",
    ttsName = ttsName,
) {

    /** Gefilterte Sammelabfrage: dahinter die Kennungen mit Komma. */
    private val selectionPrefix = "$apiRoot/ticker?markets="

    // ── Einzelkurs ──

    override fun pairId(info: PairQuery): String = info.pairId ?: "${info.quote}-${info.base}"

    override fun fillTicker(call: Int, body: String, ticker: Ticker, info: PairQuery) {
        copyRow(JSONArray(body).getJSONObject(0), ticker)
    }

    /** Fehlerform {"error": {"message": …}} oder {"message": …}. */
    override fun errorMessageJson(call: Int, json: JSONObject, info: PairQuery): String? {
        val nested = json.optJSONObject("error") ?: return json.getText("message")
        return nested.getText("message")
    }

    // ── Sammelabfrage ──

    override val bulkTickersNumOfRequests: Int
        get() = 1

    /**
     * Gefiltert liefert die Börse jedes angefragte Paar oder lehnt ab; ungefiltert (Upbit, alle Kotierungen) jedes
     * gehandelte. Was fehlt, wird nicht mehr gehandelt (Einzelabfrage bestätigt, NotTraded).
     */
    override val bulkTickersComplete: Boolean
        get() = true

    override fun getBulkTickersUrl(call: Int): String? = unfilteredUrl

    /**
     * Nur die beobachteten Paare. Kennt die Börse eines davon nicht, lehnt sie alles ab («Code not found», ohne
     * Paar): Upbit fällt auf die ungefilterte Abfrage zurück, Bithumb lädt die Paarliste, lässt Unbekannte weg und
     * fragt neu ([getBulkKnownPairsUrl]).
     */
    override fun getBulkTickersUrl(call: Int, pairIds: Collection<String>): String? {
        if (pairIds.isEmpty()) return unfilteredUrl
        // Lange Listen auf mehrere URLs verteilen (sonst HTTP 414) — nur mit ungefiltertem Ersatz (Upbit). Bei
        // Bithumb teilte die Wiederholung ohne Unbekannte die Liste neu auf und träfe andere Gruppen
        val groups = selectionGroups(pairIds)
            ?: return if (call == 0) selectionPrefix + pairIds.sorted().joinToString(",") else null
        val group = groups.getOrNull(call) ?: return null
        return BulkPairChunks.url(selectionPrefix, group)
    }

    override fun bulkTickersRequestCount(pairIds: Collection<String>): Int =
        selectionGroups(pairIds)?.size ?: bulkTickersNumOfRequests

    override fun getBulkKnownPairsUrl(call: Int): String? = if (unfilteredUrl == null) pairListAddress(call) else null

    override fun parseBulkTickers(
        call: Int,
        body: String,
        tickers: MutableMap<String, Ticker>,
        failed: MutableSet<String>,
    ) {
        JSONArray(body).forEachJSONObject { row ->
            bulkRowIfActive(row.optString("market"), row, "trade_price", tickers, failed) { copyRow(row, it) }
        }
    }

    // ── Paarliste ──

    override fun fillPairList(call: Int, body: String, out: MutableList<MarketPair>) {
        JSONArray(body).forEachJSONObject { entry ->
            val market = entry.optString("market")
            val (quote, base) = splitMarket(market) ?: return@forEachJSONObject
            out += MarketPair(base, quote, market)
        }
    }

    // ── Namen ──

    /** Volle Namen aus derselben Liste («english_name», KRW-BTC → Bitcoin). */
    override fun parseBaseNames(call: Int, body: String): Map<String, String> {
        val names = LinkedHashMap<String, String>()
        JSONArray(body).forEachJSONObject { entry ->
            val (_, base) = splitMarket(entry.optString("market")) ?: return@forEachJSONObject
            val english = entry.optText("english_name")
            if (base.isNotEmpty() && english.isNotEmpty()) names.putIfAbsent(base, english)
        }
        return names
    }

    // ── Lesen ──

    private fun copyRow(row: JSONObject, ticker: Ticker) {
        ticker.last = row.getDouble("trade_price")
        // high_price/low_price gelten seit Tagesbeginn der Börse, nicht über 24 h — darum nicht übernommen
        ticker.vol = row.optDoubleOrMissing("acc_trade_volume_24h")
        ticker.volQuote = row.optDoubleOrMissing("acc_trade_price_24h")
        // Zeit des letzten Handels (nur Anzeige); «timestamp» wäre bloss die Antwortzeit. «Veraltet» misst am Abruf
        ticker.timestamp = row.optLong("trade_timestamp")
        // signed_change_rate bezieht sich auf den Vortagesschluss (KST): keine 24-h-Veränderung
    }

    /** Gruppen der gefilterten Abfrage — nur mit ungefiltertem Ersatz; sonst oder nicht filterbar: null. */
    private fun selectionGroups(pairIds: Collection<String>): List<List<String>>? =
        if (unfilteredUrl == null) null else BulkPairChunks.chunks(selectionPrefix, pairIds)

    /** «KRW-BTC» → (KRW, BTC); andere Form → null. */
    private fun splitMarket(market: String): Pair<String, String>? {
        val parts = market.split('-')
        return if (parts.size == 2) parts[0] to parts[1] else null
    }
}
