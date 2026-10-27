package com.cryptochecker.wear

import android.content.Context
import com.cryptochecker.marketdata.watch.WatchSettings
import org.json.JSONArray
import org.json.JSONObject

/**
 * Speicher der Uhr (eigene kleine Datei, nicht gesichert): Wahl der Liste und die zuletzt geladenen Kurse samt Zeit — die
 * Komplikation zeigt sie auch ohne Netz.
 */
class WatchStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Wahl der Uhr («Wie Handy» oder «Eigene Liste»), mit dem Handy abgeglichen ([WatchDataLayer]). */
    var settings: WatchSettings
        get() = WatchSettings.fromJson(prefs.getString(KEY_SETTINGS, null)) ?: WatchSettings()
        set(value) {
            prefs.edit().putString(KEY_SETTINGS, value.toJson()).apply()
        }

    /** Coins, die die Uhr gerade zeigt. */
    val shown: List<String> get() = settings.shown

    /** Zuletzt geladene Kurse und Zeit (Epoch-ms; 0 = nie). */
    fun quotes(): Pair<Map<String, WatchQuote>, Long> {
        val text = prefs.getString(KEY_QUOTES, null) ?: return emptyMap<String, WatchQuote>() to 0L
        val o = runCatching { JSONObject(text) }.getOrNull() ?: return emptyMap<String, WatchQuote>() to 0L
        val list = o.optJSONArray("q") ?: JSONArray()
        val out = LinkedHashMap<String, WatchQuote>()
        for (i in 0 until list.length()) {
            val q = list.optJSONObject(i) ?: continue
            val symbol = q.optString("s").takeIf { it.isNotEmpty() } ?: continue
            val price = q.optDouble("p").takeIf { it.isFinite() && it > 0 } ?: continue
            out[symbol] = WatchQuote(symbol, price, q.optDouble("c").takeIf { it.isFinite() }, q.optString("x"))
        }
        return out to o.optLong("t")
    }

    fun saveQuotes(quotes: Map<String, WatchQuote>, time: Long) {
        val list = JSONArray()
        quotes.values.forEach { q ->
            val o = JSONObject().put("s", q.symbol).put("p", q.price).put("x", q.source)
            q.change24h?.let { o.put("c", it) }
            list.put(o)
        }
        prefs.edit().putString(KEY_QUOTES, JSONObject().put("t", time).put("q", list).toString()).apply()
    }

    private companion object {
        const val PREFS = "watch"
        const val KEY_SETTINGS = "settings"
        const val KEY_QUOTES = "quotes"
    }
}
