package com.cryptochecker.marketdata.model.exchange

import com.cryptochecker.marketdata.exceptions.ExchangeParseException
import com.cryptochecker.marketdata.model.ExchangePair
import com.cryptochecker.marketdata.model.PairQuery
import com.cryptochecker.marketdata.model.Ticker
import com.cryptochecker.marketdata.model.exchange.generic.SimpleExchange
import com.cryptochecker.marketdata.util.Change24h
import com.cryptochecker.marketdata.util.forEachJSONObject
import com.cryptochecker.marketdata.util.getText
import com.cryptochecker.marketdata.util.optDoubleOrMissing
import com.cryptochecker.marketdata.util.optText
import org.json.JSONObject

private const val COINONE_API = "https://api.coinone.co.kr/public/v2"

/** Coinone handelt nur gegen KRW; Paarliste und Sammelabfrage gelten diesem Markt. */
private const val COINONE_QUOTE = "KRW"

/**
 * Coinone (Südkorea), öffentliche API v2: https://docs.coinone.co.kr/ — Kennung wie «BTC-KRW», in der Adresse
 * umgekehrt («…/ticker_new/KRW/BTC»). Antworten tragen `result` («success»/«error»); Kurse unter `tickers`.
 */
class Coinone : SimpleExchange(
    name = "Coinone",
    pairListUrl = "$COINONE_API/markets/$COINONE_QUOTE",
    tickerUrl = "$COINONE_API/ticker_new/%1\$s",
    errorField = "error_msg",
) {
    override val key = "Coinone"

    // ── Einzelkurs ──

    /** Kennung «BTC-KRW» → Pfad «KRW/BTC». */
    override fun pairId(info: PairQuery): String {
        val id = info.pairId ?: (info.base.uppercase() + "-" + info.quote.uppercase())
        val target = id.substringBefore('-')
        val quote = id.substringAfter('-', COINONE_QUOTE)
        return "$quote/$target"
    }

    /** Liste mit dem einen Paar; leer = Paar unbekannt. */
    override fun fillTickerJson(call: Int, json: JSONObject, ticker: Ticker, info: PairQuery) {
        val row = json.getJSONArray("tickers").optJSONObject(0)
            ?: throw ExchangeParseException(ExchangeParseException.NO_DATA)
        copyRow(row, ticker)
    }

    /** Nur `result` «error» trägt einen Fehlertext. */
    override fun errorMessageJson(call: Int, json: JSONObject, info: PairQuery): String? {
        if (json.optString("result") != "error") noErrorText()
        return super.errorMessageJson(call, json, info)
    }

    // ── Sammelabfrage ──

    override val bulkTickersNumOfRequests: Int
        get() = 1

    /** Alle KRW-Paare in einer Antwort (Aufzeichnung 03.11.2026: 357 von 357 der Paarliste). */
    override val bulkTickersComplete: Boolean
        get() = true

    override fun getBulkTickersUrl(call: Int): String = "$COINONE_API/ticker_new/$COINONE_QUOTE"

    override fun parseBulkTickers(
        call: Int,
        body: String,
        tickers: MutableMap<String, Ticker>,
        failed: MutableSet<String>,
    ) {
        JSONObject(body).getJSONArray("tickers").forEachJSONObject { row ->
            bulkRowIfActive(idOf(row), row, "last", tickers, failed) { copyRow(row, it) }
        }
    }

    // ── Paarliste ──

    /** Nur Märkte mit `trade_status` 1 (Handel offen). */
    override fun fillPairListJson(call: Int, json: JSONObject, out: MutableList<ExchangePair>) {
        json.getJSONArray("markets").forEachJSONObject { market ->
            if (market.optInt("trade_status", 0) != 1) return@forEachJSONObject
            val base = market.getText("target_currency").uppercase()
            val quote = market.getText("quote_currency").uppercase()
            out += ExchangePair(base, quote, "$base-$quote")
        }
    }

    // ── Lesen ──

    /**
     * Eine Ticker-Zeile (Zahlen als Text, Werte über 24 h): `first` = Kurs vor 24 h, Gebot/Angebot aus der ersten
     * Stufe von `best_bids`/`best_asks`, `target_volume` in der Basiswährung. `timestamp` ist die Zeit der
     * Momentaufnahme, kein Handel — die Zeit der Börse bleibt unbekannt.
     */
    private fun copyRow(row: JSONObject, ticker: Ticker) {
        val last = row.getDouble("last")
        ticker.last = last
        ticker.bid = row.optJSONArray("best_bids")?.optJSONObject(0)?.optDoubleOrMissing("price") ?: Ticker.MISSING
        ticker.ask = row.optJSONArray("best_asks")?.optJSONObject(0)?.optDoubleOrMissing("price") ?: Ticker.MISSING
        ticker.high = row.optDoubleOrMissing("high")
        ticker.low = row.optDoubleOrMissing("low")
        ticker.vol = row.optDoubleOrMissing("target_volume")
        ticker.volQuote = row.optDoubleOrMissing("quote_volume")
        ticker.change24hPercent = Change24h.fromOpen(last, row.optDouble("first"))
    }

    /** «btc»/«krw» der Zeile → Kennung «BTC-KRW»; ohne Angaben leer (Zeile wird übergangen). */
    private fun idOf(row: JSONObject): String {
        val base = row.optText("target_currency").uppercase()
        val quote = row.optText("quote_currency").uppercase()
        return if (base.isEmpty() || quote.isEmpty()) "" else "$base-$quote"
    }
}
