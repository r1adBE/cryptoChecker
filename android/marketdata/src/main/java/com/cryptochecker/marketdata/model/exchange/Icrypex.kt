package com.cryptochecker.marketdata.model.exchange

import com.cryptochecker.marketdata.exceptions.ExchangeParseException
import com.cryptochecker.marketdata.model.ExchangePair
import com.cryptochecker.marketdata.model.PairQuery
import com.cryptochecker.marketdata.model.Ticker
import com.cryptochecker.marketdata.model.exchange.generic.SimpleExchange
import com.cryptochecker.marketdata.util.forEachJSONObject
import com.cryptochecker.marketdata.util.getText
import com.cryptochecker.marketdata.util.optDoubleOrMissing
import com.cryptochecker.marketdata.util.optStrings
import com.cryptochecker.marketdata.util.optText
import org.json.JSONArray
import org.json.JSONObject

private const val ICRYPEX_API = "https://api.icrypex.com/v1"

/** Alle Ticker in einer Antwort; einen Einzelabruf je Paar gibt es nicht (`/v1/ticker?symbol=` antwortet 404). */
private const val ICRYPEX_TICKERS = "$ICRYPEX_API/tickers"

/** Nur Spot-Märkte; dieselbe Liste führt Perpetuals («BTCUSDT/P», `PERPETUAL`). */
private const val ICRYPEX_SPOT = "SPOT"

/** Handelbar; `CancelOnly` erlaubt nur noch das Löschen von Aufträgen und fällt weg. */
private const val ICRYPEX_RUNNING = "Running"

/**
 * Icrypex (Türkei), öffentliche API — Kennung = `symbol` ohne Trennzeichen («BTCUSDT»), im Ticker ebenso. Die
 * Paarliste kommt aus `exchange/info` (`pairs` mit `base`, `quote`, `status`, `marketTypes`): nur sie sagt, was Spot
 * ist und was gehandelt wird, und führt auch Gegenwährungen wie ICPX, die sich aus dem Symbol allein nicht sicher
 * abtrennen liessen. Perpetuals tragen «/P» im Symbol und werden nirgends übernommen.
 */
class Icrypex : SimpleExchange(
    name = "Icrypex",
    pairListUrl = "$ICRYPEX_API/exchange/info",
    tickerUrl = ICRYPEX_TICKERS,
) {
    override val key = "Icrypex"

    // ── Einzelkurs: aus der Gesamtliste das eigene Paar ──

    override fun pairId(info: PairQuery): String = info.pairId ?: (info.base + info.quote)

    /** Eine Spot-Kennung enthält nie «/», darum trifft der Vergleich nie die Perpetual-Zeile desselben Coins. */
    override fun fillTicker(call: Int, body: String, ticker: Ticker, info: PairQuery) {
        val id = pairId(info)
        var found: JSONObject? = null
        JSONArray(body).forEachJSONObject { row -> if (found == null && row.optText("symbol") == id) found = row }
        copyRow(found ?: throw ExchangeParseException(ExchangeParseException.NO_DATA), ticker)
    }

    // ── Sammelabfrage ──

    override val bulkTickersNumOfRequests: Int
        get() = 1

    /**
     * `tickers` führt jedes Spot-Paar (Aufzeichnung 19.10.2026: alle 211 Paare von `exchange/info`, auch
     * `CancelOnly`), ein fehlendes Paar ist nicht mehr gelistet.
     */
    override val bulkTickersComplete: Boolean
        get() = true

    override fun getBulkTickersUrl(call: Int): String = ICRYPEX_TICKERS

    override fun parseBulkTickers(
        call: Int,
        body: String,
        tickers: MutableMap<String, Ticker>,
        failed: MutableSet<String>,
    ) {
        JSONArray(body).forEachJSONObject { row ->
            val id = row.optText("symbol")
            // Perpetual («…/P»): keine Spot-Zeile, still übergehen statt als unlesbar zu zählen
            if (isPerpetual(id)) return@forEachJSONObject
            bulkRowIfActive(id, row, "last", tickers, failed) { copyRow(row, it) }
        }
    }

    // ── Paarliste ──

    /** Nur Spot-Märkte im Status `Running`; Basis und Gegenwährung stehen getrennt in der Antwort. */
    override fun fillPairListJson(call: Int, json: JSONObject, out: MutableList<ExchangePair>) {
        json.getJSONArray("pairs").forEachJSONObject { entry ->
            if (entry.optText("status") != ICRYPEX_RUNNING) return@forEachJSONObject
            if (ICRYPEX_SPOT !in entry.optStrings("marketTypes")) return@forEachJSONObject
            val id = entry.getText("symbol")
            if (isPerpetual(id)) return@forEachJSONObject
            out += ExchangePair(entry.getText("base").uppercase(), entry.getText("quote").uppercase(), id)
        }
    }

    // ── Lesen ──

    /**
     * Eine Ticker-Zeile, alle Zahlen als Text. `volume` zählt in der Basiswährung (BTCUSDT: «25.87»; als USDT wäre
     * das bei diesem Kurs unmöglich wenig). Nicht übernommen: `change` (Prozent, `qty` = Veränderung absolut) — der
     * Zeitraum ist nicht dokumentiert; Hoch/Tief/`avg` passen zwar zu 24 h, Zeilen ohne Eröffnungskurs melden aber
     * `change` 0 bei `qty` = `last`. Die App rechnet dann mit Kerzen. Eine Zeit der Börse liefert Icrypex nicht.
     */
    private fun copyRow(row: JSONObject, ticker: Ticker) {
        ticker.last = row.getDouble("last")
        ticker.bid = row.optDoubleOrMissing("bid")
        ticker.ask = row.optDoubleOrMissing("ask")
        ticker.high = row.optDoubleOrMissing("high")
        ticker.low = row.optDoubleOrMissing("low")
        ticker.vol = row.optDoubleOrMissing("volume")
    }

    private fun isPerpetual(id: String): Boolean = '/' in id
}
