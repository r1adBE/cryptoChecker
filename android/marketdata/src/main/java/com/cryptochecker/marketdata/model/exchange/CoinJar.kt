package com.cryptochecker.marketdata.model.exchange

import com.cryptochecker.marketdata.model.ExchangePair
import com.cryptochecker.marketdata.model.PairQuery
import com.cryptochecker.marketdata.model.Ticker
import com.cryptochecker.marketdata.model.exchange.generic.SimpleExchange
import com.cryptochecker.marketdata.util.Change24h
import com.cryptochecker.marketdata.util.forEachJSONObject
import com.cryptochecker.marketdata.util.getText
import com.cryptochecker.marketdata.util.optDoubleOrMissing
import org.json.JSONArray
import org.json.JSONObject

private const val COINJAR_DATA = "https://data.exchange.coinjar.com"

/**
 * CoinJar Exchange (Australien/UK), öffentliche API: https://docs.exchange.coinjar.com/ — Kennung = Produkt-ID, teils
 * «BTCAUD», teils «BTC-USDC» (wie die Börse sie führt). `change_24h` ist ein Anteil über gleitende 24 h (−0.0152 =
 * −1,52 %; er wandert mit der Zeit und passt zum ersten Wert von `sparklines_24h`).
 */
class CoinJar : SimpleExchange(
    name = "CoinJar",
    pairListUrl = "https://api.exchange.coinjar.com/products",
    tickerUrl = "$COINJAR_DATA/products/%1\$s/ticker",
) {
    override val key = "CoinJar"

    // ── Einzelkurs ──

    override fun pairId(info: PairQuery): String = info.pairId ?: (info.base + info.quote).uppercase()

    override fun fillTickerJson(call: Int, json: JSONObject, ticker: Ticker, info: PairQuery) {
        copyRow(json, ticker)
    }

    /** `{"error_type", "error_messages": [...]}`: die erste Meldung. */
    override fun errorMessageJson(call: Int, json: JSONObject, info: PairQuery): String? {
        val text = json.optJSONArray("error_messages")?.optString(0).orEmpty()
        return text.ifBlank { noErrorText() }
    }

    // ── Sammelabfrage ──

    override val bulkTickersNumOfRequests: Int
        get() = 1

    /**
     * Ein Objekt je Produkt-ID (Aufzeichnung 03.11.2026: 334 von 334 der Paarliste). Noch nie gehandelte Produkte
     * führen `last` als `false` (36 davon): sie fallen still weg; die Einzelabfrage liefert dann keinen Kurs.
     */
    override val bulkTickersComplete: Boolean
        get() = true

    override fun getBulkTickersUrl(call: Int): String = "$COINJAR_DATA/tickers"

    override fun parseBulkTickers(
        call: Int,
        body: String,
        tickers: MutableMap<String, Ticker>,
        failed: MutableSet<String>,
    ) {
        val all = JSONObject(body)
        for (id in all.keys()) {
            val row = all.optJSONObject(id) ?: continue
            if (row.opt("last") is Boolean) continue
            bulkRowIfActive(id, row, "last", tickers, failed) { copyRow(row, it) }
        }
    }

    // ── Paarliste ──

    override fun fillPairList(call: Int, body: String, out: MutableList<ExchangePair>) {
        JSONArray(body).forEachJSONObject { product ->
            out += ExchangePair(
                product.getJSONObject("base_currency").getText("iso_code").uppercase(),
                product.getJSONObject("counter_currency").getText("iso_code").uppercase(),
                product.getText("id"),
            )
        }
    }

    // ── Lesen ──

    /**
     * Kurs, Gebot/Angebot, `volume_24h` in der Basiswährung, `change_24h` als Anteil. Nicht übernommen: `prev_close`
     * (Schluss einer Handelssitzung, nicht vor 24 h) und `volume` (ganze Sitzung); `current_time` ist keine Handelszeit.
     */
    private fun copyRow(row: JSONObject, ticker: Ticker) {
        ticker.last = row.getDouble("last")
        ticker.bid = row.optDoubleOrMissing("bid")
        ticker.ask = row.optDoubleOrMissing("ask")
        ticker.vol = row.optDoubleOrMissing("volume_24h")
        ticker.change24hPercent = Change24h.fraction(row.optDouble("change_24h"))
    }
}
