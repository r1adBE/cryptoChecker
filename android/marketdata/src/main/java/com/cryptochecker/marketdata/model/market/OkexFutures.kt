package com.cryptochecker.marketdata.model.market

import com.cryptochecker.marketdata.exceptions.MarketParseException
import com.cryptochecker.marketdata.model.PairQuery
import com.cryptochecker.marketdata.model.MarketPair
import com.cryptochecker.marketdata.model.FuturesContractType
import com.cryptochecker.marketdata.model.Ticker
import com.cryptochecker.marketdata.model.market.generic.SimpleMarket
import com.cryptochecker.marketdata.util.Change24h
import com.cryptochecker.marketdata.util.TradFi
import com.cryptochecker.marketdata.util.forEachJSONObject
import com.cryptochecker.marketdata.util.optDoubleOrMissing
import com.cryptochecker.marketdata.util.optText
import org.json.JSONObject

/** Alle SWAP-Ticker in einer Antwort (Sammelabfrage). */
private const val OKX_SWAP_TICKERS = "https://www.okx.com/api/v5/market/tickers?instType=SWAP"

/**
 * OKX Perpetual-Swaps. Die Paarliste kommt aus `/api/v5/public/instruments`, nicht aus den Tickern: Nur dort stehen
 * `instCategory` (Krypto, Aktie, Rohstoff …) und `state` — sonst liefen NVDA-, AAPL- oder XAU-Swaps als Krypto-Token.
 * Gegenstück: `OkexFutures.swift`.
 */
class OkexFutures : SimpleMarket(
    name = "OKX Futures",
    pairListUrl = "https://www.okx.com/api/v5/public/instruments?instType=SWAP",
    tickerUrl = "https://www.okx.com/api/v5/market/ticker?instId=%1\$s",
) {
    override val key = "OkexFutures"

    // ── Einzelkurs ──

    /** Fehler meldet OKX in code/msg statt in «data». */
    override fun fillTickerJson(call: Int, json: JSONObject, ticker: Ticker, info: PairQuery) {
        val complaint = json.optText("msg")
        if (complaint.isNotEmpty()) throw MarketParseException(complaint)
        copyRow(json.getJSONArray("data").getJSONObject(0), ticker)
    }

    // ── Sammelabfrage: eigener Endpunkt für alle SWAP-Ticker (nicht die Instrumentliste) ──

    override val bulkTickersNumOfRequests: Int
        get() = 1

    override fun getBulkTickersUrl(call: Int): String = OKX_SWAP_TICKERS

    override fun parseBulkTickers(
        call: Int,
        body: String,
        tickers: MutableMap<String, Ticker>,
        failed: MutableSet<String>,
    ) {
        JSONObject(body).getJSONArray("data").forEachJSONObject { row ->
            bulkRowIfActive(row.optString("instId"), row, "last", tickers, failed) { copyRow(row, it) }
        }
    }

    // ── Paarliste ──

    override fun fillPairListJson(call: Int, json: JSONObject, out: MutableList<MarketPair>) {
        json.getJSONArray("data").forEachJSONObject { inst ->
            val instId = inst.getString("instId")
            val legs = instId.split('-')
            if (!isLive(inst.optString("state")) || legs.size != 3 || legs[2] != "SWAP") return@forEachJSONObject
            out += MarketPair(
                legs[0],
                legs[1],
                instId,
                if (isInverse(inst, instId)) FuturesContractType.INVERSE_PERPETUAL else FuturesContractType.PERPETUAL,
                // instCategory ≠ 1: Aktie, Rohstoff, Devisen — kein Krypto-Token
                TradFi.okx(inst.optString("instCategory")),
            )
        }
    }

    // ── Lesen ──

    /** Je Paar dieselbe Struktur in Einzel- und Sammelabfrage. */
    private fun copyRow(row: JSONObject, ticker: Ticker) {
        val last = row.getDouble("last")
        ticker.last = last
        ticker.high = row.getDouble("high24h")
        ticker.low = row.getDouble("low24h")
        // SWAP: «vol24h» zählt Kontrakte, «volCcy24h» ist die Menge in der Basis. Einen Umsatz in der Kotierung
        // liefert OKX nicht — er wird auch nicht geschätzt
        ticker.vol = row.getDouble("volCcy24h")
        ticker.volQuote = Ticker.MISSING
        // Leeres Buch: "" für bidPx/askPx → ohne Geld-/Briefkurs
        ticker.bid = row.optDoubleOrMissing("bidPx")
        ticker.ask = row.optDoubleOrMissing("askPx")
        // «ts» = Erzeugung des Tickers, kein Handel. «open24h» = Kurs vor 24 h
        ticker.change24hPercent = Change24h.fromOpen(last, row.optDouble("open24h"))
    }

    /** Ohne «state» zählt das Instrument; sonst nur «live» (nicht preopen/suspend/test). */
    private fun isLive(state: String): Boolean = state.isEmpty() || state == "live"

    /** «ctType» entscheidet; fehlt es, die Kennung (z. B. BTC-USD-SWAP = in Coins abgerechnet). */
    private fun isInverse(inst: JSONObject, instId: String): Boolean =
        when (inst.optString("ctType")) {
            "inverse" -> true
            "linear" -> false
            else -> FuturesContractType.isInversePerpetualId(key, instId)
        }
}
