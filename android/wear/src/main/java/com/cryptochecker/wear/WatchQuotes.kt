package com.cryptochecker.wear

import org.json.JSONArray
import org.json.JSONObject

/** Kurs eines Coins gegen USDT auf der Uhr: letzter Kurs, Änderung über 24 h in Prozent (null = unbekannt), Börse. */
data class WatchQuote(val symbol: String, val price: Double, val change24h: Double?, val source: String)

/**
 * Antworten der Börsen für die Uhr lesen: Binance `ticker/24hr`, Bybit `v5/market/tickers` (Spot),
 * OKX `v5/market/tickers` (Spot). Nur gewünschte Coins ([wanted], Kürzel ohne «USDT»), nur Kurse > 0.
 */
object WatchQuotes {
    const val BINANCE = "Binance"
    const val BYBIT = "Bybit"
    const val OKX = "OKX"

    /** Binance: Array von `{symbol:"BTCUSDT", lastPrice, priceChangePercent}`. */
    fun binance(body: String, wanted: Collection<String>): List<WatchQuote> {
        val array = JSONArray(body)
        val out = ArrayList<WatchQuote>()
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val coin = coinOf(o.optString("symbol"), "USDT", wanted) ?: continue
            val price = o.optString("lastPrice").toDoubleOrNull()?.takeIf { it > 0 && it.isFinite() } ?: continue
            out += WatchQuote(coin, price, o.optString("priceChangePercent").toDoubleOrNull()?.takeIf { it.isFinite() }, BINANCE)
        }
        return out
    }

    /** Bybit: `result.list` mit `{symbol:"BTCUSDT", lastPrice, price24hPcnt}` (Anteil, 0.0123 = 1.23 %). */
    fun bybit(body: String, wanted: Collection<String>): List<WatchQuote> {
        val list = JSONObject(body).optJSONObject("result")?.optJSONArray("list") ?: return emptyList()
        val out = ArrayList<WatchQuote>()
        for (i in 0 until list.length()) {
            val o = list.optJSONObject(i) ?: continue
            val coin = coinOf(o.optString("symbol"), "USDT", wanted) ?: continue
            val price = o.optString("lastPrice").toDoubleOrNull()?.takeIf { it > 0 && it.isFinite() } ?: continue
            val change = o.optString("price24hPcnt").toDoubleOrNull()?.takeIf { it.isFinite() }?.times(100)
            out += WatchQuote(coin, price, change, BYBIT)
        }
        return out
    }

    /** OKX: `data` mit `{instId:"BTC-USDT", last, open24h}`; Änderung = last gegen open24h. */
    fun okx(body: String, wanted: Collection<String>): List<WatchQuote> {
        val data = JSONObject(body).optJSONArray("data") ?: return emptyList()
        val out = ArrayList<WatchQuote>()
        for (i in 0 until data.length()) {
            val o = data.optJSONObject(i) ?: continue
            val coin = coinOf(o.optString("instId").replace("-", ""), "USDT", wanted) ?: continue
            val price = o.optString("last").toDoubleOrNull()?.takeIf { it > 0 && it.isFinite() } ?: continue
            val open = o.optString("open24h").toDoubleOrNull()?.takeIf { it > 0 && it.isFinite() }
            out += WatchQuote(coin, price, open?.let { (price / it - 1) * 100 }, OKX)
        }
        return out
    }

    /** «BTCUSDT» → «BTC», wenn gewünscht; sonst null. */
    private fun coinOf(symbol: String, quote: String, wanted: Collection<String>): String? {
        if (!symbol.endsWith(quote)) return null
        val coin = symbol.removeSuffix(quote)
        return coin.takeIf { it in wanted }
    }

    /** Ergebnisse mehrerer Börsen zusammenführen: je Coin die erste Börse, die ihn liefert (Reihenfolge = Vorrang). */
    fun merge(vararg sources: List<WatchQuote>): Map<String, WatchQuote> {
        val out = LinkedHashMap<String, WatchQuote>()
        sources.forEach { list -> list.forEach { q -> out.putIfAbsent(q.symbol, q) } }
        return out
    }
}
