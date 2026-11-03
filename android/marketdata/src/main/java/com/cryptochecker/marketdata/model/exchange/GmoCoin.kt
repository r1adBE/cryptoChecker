package com.cryptochecker.marketdata.model.exchange

import com.cryptochecker.marketdata.exceptions.ExchangeParseException
import com.cryptochecker.marketdata.model.ExchangePair
import com.cryptochecker.marketdata.model.PairQuery
import com.cryptochecker.marketdata.model.Ticker
import com.cryptochecker.marketdata.model.exchange.generic.SimpleExchange
import com.cryptochecker.marketdata.util.forEachJSONObject
import com.cryptochecker.marketdata.util.getText
import com.cryptochecker.marketdata.util.optDoubleOrMissing
import com.cryptochecker.marketdata.util.optText
import org.json.JSONObject

private const val GMO_API = "https://api.coin.z.com/public/v1"

/** Spot-Kurse gelten in Yen. */
private const val GMO_QUOTE = "JPY"

/**
 * GMO Coin (Japan), öffentliche API: https://api.coin.z.com/docs/ — nur Spot: Kennung = Kürzel («BTC», gegen JPY);
 * Symbole mit «_» («BTC_JPY») sind Margin-Handel und bleiben weg. Antworten `{"status": 0, "data": …}`; ohne
 * 24-h-Veränderung (die App rechnet mit Kerzen), `timestamp` ist die Momentaufnahme.
 */
class GmoCoin : SimpleExchange(
    name = "GMO Coin",
    pairListUrl = "$GMO_API/symbols",
    tickerUrl = "$GMO_API/ticker?symbol=%1\$s",
) {
    override val key = "GmoCoin"

    // ── Einzelkurs ──

    override fun pairId(info: PairQuery): String = info.pairId ?: info.base.uppercase()

    override fun fillTickerJson(call: Int, json: JSONObject, ticker: Ticker, info: PairQuery) {
        // Status ≠ 0 (Fehler, Wartung): kein «kein Eintrag» — den Text liefert errorMessageJson
        val status = json.optInt("status", -1)
        if (status != 0) throw ExchangeParseException("GMO status $status")
        val row = json.getJSONArray("data").optJSONObject(0)
            ?: throw ExchangeParseException(ExchangeParseException.NO_DATA)
        copyRow(row, ticker)
    }

    /** `status` ≠ 0 mit `messages[0].message_string` (z. B. «Not found», Wartung). */
    override fun errorMessageJson(call: Int, json: JSONObject, info: PairQuery): String? {
        if (json.optInt("status", 0) == 0) noErrorText()
        return json.optJSONArray("messages")?.optJSONObject(0)?.getText("message_string") ?: noErrorText()
    }

    // ── Sammelabfrage ──

    override val bulkTickersNumOfRequests: Int
        get() = 1

    /** Ohne `symbol` jedes Symbol (Aufzeichnung 03.11.2026: 33 von 33). */
    override val bulkTickersComplete: Boolean
        get() = true

    override fun getBulkTickersUrl(call: Int): String = "$GMO_API/ticker"

    /** Nur Spot (ohne «_»). */
    override fun parseBulkTickers(
        call: Int,
        body: String,
        tickers: MutableMap<String, Ticker>,
        failed: MutableSet<String>,
    ) {
        JSONObject(body).getJSONArray("data").forEachJSONObject { row ->
            val id = row.optText("symbol")
            if ('_' in id) return@forEachJSONObject
            bulkRowIfActive(id, row, "last", tickers, failed) { copyRow(row, it) }
        }
    }

    // ── Paarliste ──

    /** Spot-Symbole (ohne «_») gegen JPY. */
    override fun fillPairListJson(call: Int, json: JSONObject, out: MutableList<ExchangePair>) {
        json.getJSONArray("data").forEachJSONObject { row ->
            val id = row.getText("symbol")
            if (id.isEmpty() || '_' in id) return@forEachJSONObject
            out += ExchangePair(id.uppercase(), GMO_QUOTE, id)
        }
    }

    // ── Lesen ──

    /**
     * Kurs, Gebot/Angebot und Volumen (Basiswährung, 24 h). `high`/`low` bleiben weg: der Zeitraum ist in der Doku
     * nicht als gleitende 24 h belegt (wie bei Upbit der Handelstag). Keine Handelszeit.
     */
    private fun copyRow(row: JSONObject, ticker: Ticker) {
        ticker.last = row.getDouble("last")
        ticker.bid = row.optDoubleOrMissing("bid")
        ticker.ask = row.optDoubleOrMissing("ask")
        ticker.vol = row.optDoubleOrMissing("volume")
    }
}
