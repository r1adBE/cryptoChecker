package com.cryptochecker.marketdata.watch

import org.json.JSONArray
import org.json.JSONObject

/**
 * Kurse vom Handy für die Uhr: Das Handy schickt für die Coins der Uhr (beide Listen) die Kurse, die es ohnehin schon
 * hat — samt 24 h, Börse und Zeit seines Abrufs, ohne eigene Abfrage für die Uhr. Eigene Liste (Kürzel): Paar der
 * Merkliste gegen [QUOTE_ASSET], Spot, nicht deaktiviert; «Wie Handy» ([WatchPair]-Schlüssel): genau das Paar des
 * Favoriten (Börse und Kontrakt, auch Futures/Perp und andere Börsen). Die Uhr übernimmt sie und fragt selbst nur, was das Handy nicht liefert ([selfFetch]).
 * Wie iOS `WatchPhoneQuotes` (Paritäts-Fixture `watch_link.json`).
 */
object WatchPhoneQuotes {
    const val QUOTE_ASSET = "USDT"

    /** Höchstzahl je Sendung: eigene Liste und Liste des Handys. */
    const val MAX = 2 * WatchCoins.MAX

    private val SYMBOL = Regex("[A-Z0-9]{2,12}")

    /**
     * Paar der Merkliste des Handys, nur was die Uhr braucht; [time] = letzter Abruf (Epoch-ms); [contract] = Marke wie
     * [WatchPair.contract] (leer = Spot).
     */
    data class Row(
        val base: String,
        val quote: String,
        val spot: Boolean,
        val disabled: Boolean,
        val price: Double?,
        val change24h: Double?,
        val exchange: String,
        val time: Long,
        val sortOrder: Int,
        val contract: String = "",
    )

    /**
     * Kurs vom Handy; [symbol] = Schlüssel der Uhr (Kürzel oder [WatchPair.key]); [time] = Abruf des Handys (Epoch-ms),
     * gilt auf der Uhr als Stand des Kurses.
     */
    data class Quote(val symbol: String, val price: Double, val change24h: Double?, val exchange: String, val time: Long)

    /**
     * Je Schlüssel aus [coins] (Reihenfolge bleibt) der neueste brauchbare Kurs; gleich alt: das Paar weiter oben. Kürzel:
     * jedes Spot-Paar gegen USDT; [WatchPair]-Schlüssel: nur genau dieses Paar (Basis, Gegenwährung, Börse, Kontrakt).
     */
    fun pick(rows: List<Row>, coins: List<String>): List<Quote> {
        val usable = rows.mapNotNull { r ->
            val price = r.price?.takeIf { it.isFinite() && it > 0.0 } ?: return@mapNotNull null
            if (r.disabled || r.time <= 0L) return@mapNotNull null
            val coin = r.base.trim().uppercase().takeIf { r.quote.trim().uppercase() == QUOTE_ASSET && r.spot }
            val pair = WatchPair.of(r.base, r.quote, r.exchange, r.contract)?.key
            Candidate(coin, pair, price, r.change24h?.takeIf { it.isFinite() }, r.exchange, r.time, r.sortOrder)
        }
        return coins.distinct().take(MAX).mapNotNull { key ->
            usable.filter { it.coin == key || it.pair == key }
                .minWithOrNull(compareByDescending<Candidate> { it.time }.thenBy { it.sortOrder })
                ?.let { Quote(key, it.price, it.change24h, it.exchange, it.time) }
        }
    }

    private class Candidate(
        val coin: String?,
        val pair: String?,
        val price: Double,
        val change24h: Double?,
        val exchange: String,
        val time: Long,
        val sortOrder: Int,
    )

    fun toJson(quotes: List<Quote>): String {
        val list = JSONArray()
        quotes.forEach { q ->
            val o = JSONObject().put("s", q.symbol).put("p", q.price).put("x", q.exchange).put("t", q.time)
            q.change24h?.let { o.put("c", it) }
            list.put(o)
        }
        return JSONObject().put("v", 1).put("q", list).toString()
    }

    /**
     * Liest [text]; Unlesbares → null, ungültige Einträge (Schlüssel weder Kürzel noch [WatchPair.key], Kurs ≤ 0, ohne
     * Zeit) fallen weg, höchstens [MAX].
     */
    fun fromJson(text: String?): List<Quote>? {
        val o = text?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return null
        val list = o.optJSONArray("q") ?: return null
        val out = ArrayList<Quote>()
        for (i in 0 until list.length()) {
            val q = list.optJSONObject(i) ?: continue
            val symbol = q.optString("s").takeIf { SYMBOL.matches(it) || WatchPair.parse(it) != null } ?: continue
            val price = q.optDouble("p").takeIf { it.isFinite() && it > 0.0 } ?: continue
            val time = q.optLong("t").takeIf { it > 0L } ?: continue
            if (out.any { it.symbol == symbol }) continue
            out += Quote(symbol, price, q.optDouble("c").takeIf { it.isFinite() }, q.optString("x"), time)
            if (out.size == MAX) break
        }
        return out
    }

    /**
     * Coins aus [shown], die die Uhr selbst abfragt. Ohne Kurs immer. Mit Kursen vom Handy ([usePhone]: Wahl «Kurse vom
     * Handy» und Handy verbunden) nie für einen Coin, den das Handy liefert ([phoneCoins]), solange dessen Kurs nicht
     * veraltet ist ([WatchFreshness]) — auch nicht auf «Aktualisieren». Sonst auf Wunsch ([force]) oder ab
     * [WatchInterval.refetchAfterMillis]. [times] = Stand je Coin auf der Uhr. Nie für ein Paar, das die Uhr nicht selbst
     * abfragen kann ([WatchPair.selfFetchable]: Pool, Laufzeit-Kontrakt) — das kommt nur vom Handy.
     */
    fun selfFetch(
        shown: List<String>,
        times: Map<String, Long>,
        phoneCoins: Set<String>,
        usePhone: Boolean,
        intervalMinutes: Int,
        now: Long,
        force: Boolean,
    ): List<String> = shown.filter { coin ->
        val time = times[coin] ?: 0L
        when {
            WatchPair.parse(coin)?.selfFetchable == false -> false
            time <= 0L -> true
            usePhone && coin in phoneCoins -> WatchFreshness.isOutdated(time, now)
            force -> true
            else -> now - time > WatchInterval.refetchAfterMillis(intervalMinutes)
        }
    }
}
