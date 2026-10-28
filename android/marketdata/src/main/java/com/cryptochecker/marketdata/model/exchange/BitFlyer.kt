package com.cryptochecker.marketdata.model.exchange

import com.cryptochecker.marketdata.exceptions.ExchangeParseException
import com.cryptochecker.marketdata.model.PairQuery
import com.cryptochecker.marketdata.model.ExchangePair
import com.cryptochecker.marketdata.model.Exchange
import com.cryptochecker.marketdata.model.Ticker
import com.cryptochecker.marketdata.util.forEachJSONObject
import com.cryptochecker.marketdata.util.optDoubleOrMissing
import org.json.JSONArray
import org.json.JSONObject

private const val BITFLYER_API = "https://api.bitflyer.com/v1"

/** Produktlisten der Regionen Japan, USA und EU. */
private val BITFLYER_REGION_LISTS = listOf("markets", "markets/usa", "markets/eu")

/**
 * bitFlyer — Spot aus drei Regionen (Japan, USA, EU), je eine Produktliste; darum kein `SimpleExchange`. Der Ticker
 * kennt weder 24-h-Hoch/-Tief noch ein Volumen in der Gegenwährung. https://lightning.bitflyer.com/docs
 */
class BitFlyer : Exchange("bitFlyer", "bit flyer") {
    override val key = "BitFlyer"

    // ── Einzelkurs ──

    override fun tickerAddress(call: Int, info: PairQuery): String {
        val code = info.pairId ?: (info.base + "_" + info.quote)
        return "$BITFLYER_API/ticker?product_code=$code"
    }

    /**
     * Ruht der Handel («state» nicht «RUNNING»: CLOSED, STARTING, PREOPEN, CIRCUIT BREAK …), gibt es keinen Kurs —
     * sonst stünde der letzte Handel vor dem Halt als aktuell da (wie Luno). Ohne Feld (Fehlerantwort) zählt das nicht.
     */
    override fun fillTickerJson(call: Int, json: JSONObject, ticker: Ticker, info: PairQuery) {
        if (!json.isNull("state") && json.optString("state") != "RUNNING") {
            throw ExchangeParseException(ExchangeParseException.NO_DATA)
        }
        ticker.last = json.getDouble("ltp")
        ticker.bid = json.optDoubleOrMissing("best_bid")
        ticker.ask = json.optDoubleOrMissing("best_ask")
        ticker.vol = json.optDoubleOrMissing("volume_by_product")
    }

    /** `getString` wie bisher: JSON null ergibt hier den Text «null». */
    override fun errorMessageJson(call: Int, json: JSONObject, info: PairQuery): String? =
        json.getString("error_message")

    // ── Paarliste ──

    override val pairListCallCount: Int
        get() = BITFLYER_REGION_LISTS.size

    override fun pairListAddress(call: Int): String = BITFLYER_API + "/" + BITFLYER_REGION_LISTS[call]

    override fun fillPairList(call: Int, body: String, out: MutableList<ExchangePair>) {
        JSONArray(body).forEachJSONObject { product ->
            val spot = product.optString("market_type").equals("Spot", ignoreCase = true)
            if (!spot) return@forEachJSONObject
            val code = product.optString("product_code")
            val sides = code.split('_')
            if (sides.size == 2) out += ExchangePair(sides[0], sides[1], code)
        }
    }
}
