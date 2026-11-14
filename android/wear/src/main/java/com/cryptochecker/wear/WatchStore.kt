package com.cryptochecker.wear

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.cryptochecker.marketdata.watch.WatchPhoneQuotes
import com.cryptochecker.marketdata.watch.WatchPortfolio
import com.cryptochecker.marketdata.watch.WatchSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Speicher der Uhr (eigene kleine Datei, nicht gesichert): Wahl der Liste, die zuletzt geladenen Kurse, je Coin mit
 * dem Start seines Abrufs ([WatchCached.time]) — die Komplikation zeigt sie auch ohne Netz —, die Coins der letzten
 * Kurse vom Handy ([phoneCoins]) und der Portfolio-Wert vom Handy ([portfolioFlow]). Wahl, Kurse und Wert liegen als
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

    /** Portfolio-Wert vom Handy; null = keiner (Schalter auf dem Handy aus oder nie gesendet). */
    val portfolioFlow: StateFlow<WatchPortfolio?> = state.portfolio.asStateFlow()

    /** Schlüssel (Coins bzw. Paare) der letzten Kurse vom Handy ([WatchPhoneQuotes.selfFetch]). */
    val phoneCoins: Set<String> get() = prefs.getStringSet(KEY_PHONE_COINS, null).orEmpty()

    /** Stand der letzten Kurse vom Handy ([WatchPhoneQuotes.deliveryTime]); 0 = keine ([WatchPhoneQuotes.usePhone]). */
    val phoneTime: Long get() = prefs.getLong(KEY_PHONE_TIME, 0L)

    val settings: WatchSettings get() = state.settings.value

    /** Schlüssel (Coins bzw. Paare des Handys), die die Uhr gerade zeigt. */
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

    /**
     * Neue Kurse eines Abrufs, der zur Zeit [startedAt] begann, je Coin übernehmen und speichern; Ergebnis = neuer
     * Stand. Lesen, Zusammenführen und Schreiben laufen unter einer Sperre (Uhr-App, Komplikation und [WatchDataListener]
     * teilen sich den Prozess); [WatchCache.merge] lässt einen Coin stehen, den ein später gestarteter Abruf schon
     * geschrieben hat.
     */
    fun mergeQuotes(fresh: Map<String, WatchQuote>, startedAt: Long): Map<String, WatchCached> = synchronized(LOCK) {
        val keep = state.settings.value.kept
        writeQuotes(WatchCache.merge(state.quotes.value, fresh, startedAt, keep), null)
    }

    /**
     * Kurse vom Handy übernehmen ([WatchCache.mergeTimed], je Coin mit der Zeit des Handys) und die gelieferten Coins
     * als [phoneCoins], den Stand der Lieferung als [phoneTime] merken; Ergebnis = neuer Stand.
     */
    fun takePhoneQuotes(quotes: List<WatchPhoneQuotes.Quote>): Map<String, WatchCached> = synchronized(LOCK) {
        val keep = state.settings.value.kept
        val fresh = quotes.associate { it.symbol to WatchCached(WatchQuote(it.symbol, it.price, it.change24h, it.exchange), it.time) }
        val merged = writeQuotes(WatchCache.mergeTimed(state.quotes.value, fresh, keep), quotes.map { it.symbol }.toSet())
        prefs.edit { putLong(KEY_PHONE_TIME, WatchPhoneQuotes.deliveryTime(quotes)) }
        merged
    }

    /**
     * Kurse kommen nicht mehr vom Handy (sein Eintrag ist gelöscht): [phoneCoins] und [phoneTime] vergessen, die Uhr holt
     * dann selbst ([WatchPhoneQuotes.selfFetch]); die Kurse selbst bleiben mit ihrer Zeit (werden veraltet angezeigt).
     * true = etwas geändert.
     */
    fun forgetPhoneQuotes(): Boolean = synchronized(LOCK) {
        if (!prefs.contains(KEY_PHONE_COINS) && !prefs.contains(KEY_PHONE_TIME)) return@synchronized false
        prefs.edit {
            remove(KEY_PHONE_COINS)
            remove(KEY_PHONE_TIME)
        }
        true
    }

    /** Portfolio-Wert vom Handy speichern bzw. bei [WatchPortfolio.Message.Clear] entfernen. */
    fun takePortfolio(message: WatchPortfolio.Message) {
        synchronized(LOCK) {
            val value = (message as? WatchPortfolio.Message.Value)?.portfolio
            prefs.edit { if (value == null) remove(KEY_PORTFOLIO) else putString(KEY_PORTFOLIO, WatchPortfolio.toJson(message)) }
            state.portfolio.value = value
        }
    }

    /** Kurse speichern (und mit [phone] die Coins vom Handy); nur unter [LOCK]. */
    private fun writeQuotes(merged: Map<String, WatchCached>, phone: Set<String>?): Map<String, WatchCached> {
        val list = JSONArray()
        merged.values.forEach { c ->
            val o = JSONObject().put("s", c.quote.symbol).put("p", c.quote.price).put("x", c.quote.source).put("t", c.time)
            c.quote.change24h?.let { o.put("c", it) }
            list.put(o)
        }
        prefs.edit {
            putString(KEY_QUOTES, JSONObject().put("q", list).toString())
            phone?.let { putStringSet(KEY_PHONE_COINS, it) }
        }
        state.quotes.value = merged
        return merged
    }

    /** Stand im Prozess: einmal aus der Datei gelesen, danach nur noch über diese Klasse geändert. */
    private class State(
        val settings: MutableStateFlow<WatchSettings>,
        val quotes: MutableStateFlow<Map<String, WatchCached>>,
        val portfolio: MutableStateFlow<WatchPortfolio?>,
    )

    private companion object {
        const val PREFS = "watch"
        const val KEY_SETTINGS = "settings"
        const val KEY_QUOTES = "quotes"
        const val KEY_PHONE_COINS = "phone_coins"
        const val KEY_PHONE_TIME = "phone_time"
        const val KEY_PORTFOLIO = "portfolio"
        val LOCK = Any()

        @Volatile
        var shared: State? = null

        fun load(prefs: SharedPreferences): State = shared ?: synchronized(LOCK) {
            shared ?: State(
                MutableStateFlow(WatchSettings.fromJson(prefs.getString(KEY_SETTINGS, null)) ?: WatchSettings()),
                MutableStateFlow(readQuotes(prefs.getString(KEY_QUOTES, null))),
                MutableStateFlow(
                    (WatchPortfolio.fromJson(prefs.getString(KEY_PORTFOLIO, null)) as? WatchPortfolio.Message.Value)?.portfolio
                ),
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
