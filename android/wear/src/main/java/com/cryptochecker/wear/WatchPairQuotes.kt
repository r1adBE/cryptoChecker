package com.cryptochecker.wear

import com.cryptochecker.marketdata.watch.WatchPair
import org.json.JSONArray
import org.json.JSONObject

/**
 * Selbst abgefragter Kurs eines Favoriten des Handys ([WatchPair], «Wie Handy»): zuerst bei derselben Börse und
 * Kontraktart wie auf dem Handy, andere Börsen nur, wenn diese nicht antwortet oder das Paar dort fehlt — Spot bei
 * Binance, Bybit, OKX; Perpetuals bei Binance USDⓈ-M (`fapi`), Bybit linear, OKX Swap. Die Börse im Ergebnis ist die,
 * die wirklich geliefert hat. Andere Kontrakte und Pools fragt die Uhr nicht selbst ab ([WatchPair.selfFetchable]).
 * Wie iOS `WatchPairQuotes` (Paritäts-Fixture `watch_quotes.json`, Abschnitte «pairVenues», «pairUrls», «pairFetch»).
 */
object WatchPairQuotes {

    /** Börse und Kontraktart, die die Uhr selbst abfragen kann; [label] = Name wie auf dem Handy. */
    enum class Venue(val label: String, val perp: Boolean) {
        BINANCE("Binance", false),
        BYBIT("Bybit", false),
        OKX("OKX", false),
        BINANCE_FUTURES("Binance Futures", true),
        BYBIT_FUTURES("Bybit Futures", true),
        OKX_FUTURES("OKX Futures", true),
    }

    /** Reihenfolge der Abfrage: Börse des Handys zuerst (falls die Uhr sie kennt), dann die anderen gleicher Art. */
    fun venues(pair: WatchPair): List<Venue> {
        if (!pair.selfFetchable) return emptyList()
        val kind = Venue.entries.filter { it.perp == pair.perp }
        val own = kind.firstOrNull { it.label.equals(pair.exchange, ignoreCase = true) }
        return listOfNotNull(own) + (kind - setOfNotNull(own))
    }

    /**
     * Fragt die Uhr die Börse des Paars selbst ab (erste in [venues])? Nein bei Börsen, die sie nicht kennt (Kraken-Spot-Paar:
     * nur Binance, Bybit, OKX) und bei Paaren, die sie gar nicht selbst lädt — dann sagt «—» nicht «Kraken nicht
     * erreichbar» (`missingReason`).
     */
    fun asksOwnExchange(pair: WatchPair): Boolean =
        venues(pair).firstOrNull()?.label?.equals(pair.exchange, ignoreCase = true) == true

    /** Abfrage eines Paars bei [venue] (Kürzel nur `[A-Z0-9]`, siehe [WatchPair.of]). */
    fun url(venue: Venue, pair: WatchPair): String {
        val symbol = pair.base + pair.quote
        return when (venue) {
            Venue.BINANCE -> "https://api.binance.com/api/v3/ticker/24hr?symbol=$symbol"
            Venue.BINANCE_FUTURES -> "https://fapi.binance.com/fapi/v1/ticker/24hr?symbol=$symbol"
            Venue.BYBIT -> "https://api.bybit.com/v5/market/tickers?category=spot&symbol=$symbol"
            Venue.BYBIT_FUTURES -> "https://api.bybit.com/v5/market/tickers?category=linear&symbol=$symbol"
            Venue.OKX -> "https://www.okx.com/api/v5/market/ticker?instId=${pair.base}-${pair.quote}"
            Venue.OKX_FUTURES -> "https://www.okx.com/api/v5/market/ticker?instId=${pair.base}-${pair.quote}-SWAP"
        }
    }

    /** Antwort von [venue] für [pair]: Kurs > 0 unter dem Schlüssel des Paars, Börse = [Venue.label]; sonst null. */
    fun parse(venue: Venue, body: String, pair: WatchPair): WatchQuote? {
        val symbol = pair.base + pair.quote
        return when (venue) {
            Venue.BINANCE, Venue.BINANCE_FUTURES -> {
                // Ein Paar: Objekt; zur Sicherheit auch ein Array
                val text = body.trim()
                val rows = if (text.startsWith("[")) objects(JSONArray(text)) else listOf(JSONObject(text))
                rows.firstOrNull { it.optString("symbol") == symbol }?.let { o ->
                    quote(pair, venue, o.optString("lastPrice"), o.optString("priceChangePercent").toDoubleOrNull())
                }
            }
            Venue.BYBIT, Venue.BYBIT_FUTURES -> {
                val list = JSONObject(body).optJSONObject("result")?.optJSONArray("list") ?: return null
                objects(list).firstOrNull { it.optString("symbol") == symbol }?.let { o ->
                    quote(pair, venue, o.optString("lastPrice"), o.optString("price24hPcnt").toDoubleOrNull()?.times(100))
                }
            }
            Venue.OKX, Venue.OKX_FUTURES -> {
                val inst = "${pair.base}-${pair.quote}" + if (venue == Venue.OKX_FUTURES) "-SWAP" else ""
                val data = JSONObject(body).optJSONArray("data") ?: return null
                objects(data).firstOrNull { it.optString("instId") == inst }?.let { o ->
                    val last = o.optString("last").toDoubleOrNull()
                    val open = o.optString("open24h").toDoubleOrNull()?.takeIf { it > 0 && it.isFinite() }
                    quote(pair, venue, o.optString("last"), if (last != null && open != null) (last / open - 1) * 100 else null)
                }
            }
        }
    }

    private fun quote(pair: WatchPair, venue: Venue, price: String, change: Double?): WatchQuote? {
        val p = price.toDoubleOrNull()?.takeIf { it > 0 && it.isFinite() } ?: return null
        return WatchQuote(pair.key, p, change?.takeIf { it.isFinite() }, venue.label)
    }

    private fun objects(a: JSONArray): List<JSONObject> = (0 until a.length()).mapNotNull { a.optJSONObject(it) }
}
