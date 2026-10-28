package com.cryptochecker.marketdata.model.exchange

import com.cryptochecker.marketdata.model.PairQuery
import com.cryptochecker.marketdata.model.ExchangePair
import com.cryptochecker.marketdata.model.Ticker
import com.cryptochecker.marketdata.model.exchange.generic.SimpleExchange
import com.cryptochecker.marketdata.util.forEachJSONObject
import com.cryptochecker.marketdata.util.optDoubleOrMissing
import com.cryptochecker.marketdata.util.optText
import org.json.JSONObject

private const val COINCHECK_API = "https://coincheck.com/api"

/** Status eines Paars, das gerade nicht handelbar ist; «available» und «itayose» (Auktion) bleiben. */
private const val COINCHECK_STATUS_STOP = "stop"

/**
 * Coincheck (Japan, JPY-Paare): https://coincheck.com/documents/exchange/api. Eine eigene Paarliste gibt es nicht;
 * `exchange_status` nennt aber jedes Paar mit seinem Zustand und dient darum als Liste (keine feste Tabelle).
 * Kennung «btc_jpy». Keine Sammelabfrage, keine 24-h-Veränderung im Ticker.
 */
class Coincheck : SimpleExchange(
    name = "Coincheck",
    pairListUrl = "$COINCHECK_API/exchange_status",
    tickerUrl = "$COINCHECK_API/ticker?pair=%1\$s",
    ttsName = "Coin check",
    errorField = "error",
) {
    override val key = "Coincheck"

    // ── Einzelkurs ──

    override fun pairId(info: PairQuery): String = info.pairId ?: (info.baseLower + "_" + info.quoteLower)

    /**
     * `timestamp` ist laut Doku die aktuelle Zeit (Antwort), kein Handel — Zeit der Börse bleibt unbekannt.
     * `high`/`low`/`volume` gelten über 24 Stunden.
     */
    override fun fillTickerJson(call: Int, json: JSONObject, ticker: Ticker, info: PairQuery) {
        ticker.last = json.getDouble("last")
        ticker.bid = json.optDoubleOrMissing("bid")
        ticker.ask = json.optDoubleOrMissing("ask")
        ticker.high = json.optDoubleOrMissing("high")
        ticker.low = json.optDoubleOrMissing("low")
        ticker.vol = json.optDoubleOrMissing("volume")
    }

    // ── Paarliste ──

    override fun fillPairListJson(call: Int, json: JSONObject, out: MutableList<ExchangePair>) {
        json.getJSONArray("exchange_status").forEachJSONObject { entry ->
            if (entry.optText("status") == COINCHECK_STATUS_STOP) return@forEachJSONObject
            val pair = entry.optText("pair")
            val sides = pair.split('_')
            if (sides.size == 2) out += ExchangePair(sides[0].uppercase(), sides[1].uppercase(), pair)
        }
    }
}
