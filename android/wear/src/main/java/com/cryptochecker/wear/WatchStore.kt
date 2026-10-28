package com.cryptochecker.wear

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.cryptochecker.marketdata.watch.WatchSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Speicher der Uhr (eigene kleine Datei, nicht gesichert): Wahl der Liste und die zuletzt geladenen Kurse, je Coin mit
 * der Zeit seines Abrufs ([WatchCache]) — die Komplikation zeigt sie auch ohne Netz. Beides liegt zusätzlich als
 * [StateFlow] einmal im Prozess: Uhr-App, [WatchDataListener] (Handy) und Komplikation schreiben und lesen denselben
 * Stand, die offene Liste folgt also auch Änderungen vom Handy.
 */
class WatchStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val state = load(prefs)

    /** Wahl der Uhr («Wie Handy» oder «Eigene Liste»), mit dem Handy abgeglichen ([WatchDataLayer]). */
    val settingsFlow: StateFlow<WatchSettings> = state.settings.asStateFlow()

    /** Zuletzt geladene Kurse je Coin. */
    val quotesFlow: StateFlow<Map<String, WatchCached>> = state.quotes.asStateFlow()

    val settings: WatchSettings get() = state.settings.value

    /** Coins, die die Uhr gerade zeigt. */
    val shown: List<String> get() = settings.shown

    /** Wahl ändern ([change] bekommt den aktuellen Stand), speichern; Ergebnis = neuer Stand. */
    fun updateSettings(change: (WatchSettings) -> WatchSettings): WatchSettings = synchronized(LOCK) {
        val next = change(state.settings.value)
        if (next != state.settings.value) {
            prefs.edit { putString(KEY_SETTINGS, next.toJson()) }
            state.settings.value = next
        }
        next
    }

    fun quotes(): Map<String, WatchCached> = state.quotes.value

    /** Neue Kurse je Coin übernehmen ([WatchCache.merge], Abruf zur Zeit [time]) und speichern; Ergebnis = neuer Stand. */
    fun mergeQuotes(fresh: Map<String, WatchQuote>, time: Long): Map<String, WatchCached> = synchronized(LOCK) {
        val keep = state.settings.value.let { it.own + it.phone }
        val merged = WatchCache.merge(state.quotes.value, fresh, time, keep)
        val list = JSONArray()
        merged.values.forEach { c ->
            val o = JSONObject().put("s", c.quote.symbol).put("p", c.quote.price).put("x", c.quote.source).put("t", c.time)
            c.quote.change24h?.let { o.put("c", it) }
            list.put(o)
        }
        prefs.edit { putString(KEY_QUOTES, JSONObject().put("q", list).toString()) }
        state.quotes.value = merged
        merged
    }

    /** Stand im Prozess: einmal aus der Datei gelesen, danach nur noch über diese Klasse geändert. */
    private class State(val settings: MutableStateFlow<WatchSettings>, val quotes: MutableStateFlow<Map<String, WatchCached>>)

    private companion object {
        const val PREFS = "watch"
        const val KEY_SETTINGS = "settings"
        const val KEY_QUOTES = "quotes"
        val LOCK = Any()

        @Volatile
        var shared: State? = null

        fun load(prefs: SharedPreferences): State = shared ?: synchronized(LOCK) {
            shared ?: State(
                MutableStateFlow(WatchSettings.fromJson(prefs.getString(KEY_SETTINGS, null)) ?: WatchSettings()),
                MutableStateFlow(readQuotes(prefs.getString(KEY_QUOTES, null))),
            ).also { shared = it }
        }

        /** Gespeicherte Kurse; Einträge ohne gültigen Kurs oder ohne Zeit fallen weg. */
        fun readQuotes(text: String?): Map<String, WatchCached> {
            val o = text?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return emptyMap()
            val list = o.optJSONArray("q") ?: return emptyMap()
            val out = LinkedHashMap<String, WatchCached>()
            for (i in 0 until list.length()) {
                val q = list.optJSONObject(i) ?: continue
                val symbol = q.optString("s").takeIf { it.isNotEmpty() } ?: continue
                val price = q.optDouble("p").takeIf { it.isFinite() && it > 0 } ?: continue
                val time = q.optLong("t").takeIf { it > 0 } ?: continue
                out[symbol] = WatchCached(WatchQuote(symbol, price, q.optDouble("c").takeIf { it.isFinite() }, q.optString("x")), time)
            }
            return out
        }
    }
}
