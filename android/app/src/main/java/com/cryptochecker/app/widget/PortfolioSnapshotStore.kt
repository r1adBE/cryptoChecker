package com.cryptochecker.app.widget

import androidx.core.content.edit
import android.content.Context
import com.cryptochecker.app.domain.portfolio.PortfolioPosition
import com.cryptochecker.app.domain.portfolio.PortfolioSnapshot
import com.cryptochecker.app.domain.portfolio.PortfolioValuePoint
import com.cryptochecker.app.domain.portfolio.PortfolioWidgetMath
import com.cryptochecker.app.domain.portfolio.PriceSample
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Letzte Momentaufnahme des Portfolios für das Widget und die Kursaufnahmen der
 * letzten 48 Stunden (Vergleichsbasis für «heute», siehe PortfolioSnapshotMath).
 * Bewusst SharedPreferences wie [WidgetPrefs]: Das Widget liest synchron und ohne Netz.
 * Flüchtige Daten — von der Android-Sicherung ausgenommen (backup_rules.xml).
 *
 * Seit Version 2 zusätzlich: Gesamtwert in USDT, die grössten Positionen und der
 * Wertverlauf — unter eigenen Schlüsseln mit «_v2». Fehlen sie (Aufnahme einer älteren
 * Version) oder sind sie nicht lesbar, lädt die Aufnahme trotzdem, nur ohne diese Teile.
 */
@Singleton
class PortfolioSnapshotStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private val prefs get() = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    /** null = noch nie berechnet. */
    fun snapshot(): PortfolioSnapshot? {
        val p = prefs
        val time = p.getLong(KEY_TIME, 0L)
        if (time <= 0L) return null
        return PortfolioSnapshot(
            total = p.getDouble(KEY_TOTAL) ?: 0.0,
            changeAmount = p.getDouble(KEY_CHANGE),
            changePercent = p.getDouble(KEY_CHANGE_PERCENT),
            currency = p.getString(KEY_CURRENCY, null) ?: "USD",
            time = time,
            empty = p.getBoolean(KEY_EMPTY, true),
            totalUsdt = p.getDouble(KEY_TOTAL_USDT_V2),
            positions = readPositions(p.getString(KEY_POSITIONS_V2, null)),
            otherPositions = p.getInt(KEY_OTHER_POSITIONS_V2, 0).coerceAtLeast(0),
            history = readValueHistory(p.getString(KEY_VALUE_HISTORY_V2, null)),
        )
    }

    private fun readPositions(json: String?): List<PortfolioPosition> = runCatching {
        if (json == null) return emptyList()
        val array = JSONArray(json)
        (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            val symbol = o.optString("s").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val value = o.optDouble("v").takeIf { it.isFinite() } ?: return@mapNotNull null
            PortfolioPosition(
                symbol = symbol,
                value = value,
                sharePercent = o.optDouble("p").takeIf { it.isFinite() } ?: 0.0,
                change24hPercent = if (o.has("c")) o.optDouble("c").takeIf { it.isFinite() } else null,
            )
        }.take(PortfolioWidgetMath.MAX_POSITIONS)
    }.onFailure { Timber.d(it, "Portfolio-Widget: Positionen nicht lesbar") }.getOrDefault(emptyList())

    private fun readValueHistory(json: String?): List<PortfolioValuePoint> = runCatching {
        if (json == null) return emptyList()
        val array = JSONArray(json)
        (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            val time = o.optLong("t", 0L).takeIf { it > 0L } ?: return@mapNotNull null
            val value = o.optDouble("v").takeIf { it.isFinite() } ?: return@mapNotNull null
            PortfolioValuePoint(time, value)
        }.sortedBy { it.time }
    }.onFailure { Timber.d(it, "Portfolio-Widget: Wertverlauf nicht lesbar") }.getOrDefault(emptyList())

    fun history(): List<PriceSample> = runCatching {
        val array = JSONArray(prefs.getString(KEY_HISTORY, null) ?: return emptyList())
        (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            val time = o.optLong("t", 0L).takeIf { it > 0L } ?: return@mapNotNull null
            val pricesJson = o.optJSONObject("p") ?: return@mapNotNull null
            val prices = HashMap<String, Double>()
            pricesJson.keys().forEach { coin ->
                val price = pricesJson.optDouble(coin)
                if (!price.isNaN() && price > 0.0) prices[coin] = price
            }
            PriceSample(time, prices)
        }
    }.onFailure { Timber.d(it, "Portfolio-Widget: Kursaufnahmen nicht lesbar") }.getOrDefault(emptyList())

    fun save(snapshot: PortfolioSnapshot, history: List<PriceSample>) {
        val array = JSONArray()
        history.forEach { sample ->
            val prices = JSONObject()
            sample.prices.forEach { (coin, price) -> prices.put(coin, price) }
            array.put(JSONObject().put("t", sample.time).put("p", prices))
        }
        val positions = JSONArray()
        snapshot.positions.forEach { p ->
            if (!p.value.isFinite()) return@forEach
            val o = JSONObject().put("s", p.symbol).put("v", p.value)
            if (p.sharePercent.isFinite()) o.put("p", p.sharePercent)
            p.change24hPercent?.takeIf { it.isFinite() }?.let { o.put("c", it) }
            positions.put(o)
        }
        val valueHistory = JSONArray()
        snapshot.history.forEach { point ->
            if (point.value.isFinite()) valueHistory.put(JSONObject().put("t", point.time).put("v", point.value))
        }
        prefs.edit {
            putDouble(KEY_TOTAL, snapshot.total)
            putDouble(KEY_CHANGE, snapshot.changeAmount)
            putDouble(KEY_CHANGE_PERCENT, snapshot.changePercent)
            putString(KEY_CURRENCY, snapshot.currency)
            putLong(KEY_TIME, snapshot.time)
            putBoolean(KEY_EMPTY, snapshot.empty)
            putString(KEY_HISTORY, array.toString())
            putDouble(KEY_TOTAL_USDT_V2, snapshot.totalUsdt)
            putString(KEY_POSITIONS_V2, positions.toString())
            putInt(KEY_OTHER_POSITIONS_V2, snapshot.otherPositions.coerceAtLeast(0))
            putString(KEY_VALUE_HISTORY_V2, valueHistory.toString())
        }
    }

    private fun android.content.SharedPreferences.getDouble(key: String): Double? =
        if (contains(key)) Double.fromBits(getLong(key, 0L)).takeIf { !it.isNaN() } else null

    private fun android.content.SharedPreferences.Editor.putDouble(
        key: String,
        value: Double?,
    ): android.content.SharedPreferences.Editor =
        if (value == null || value.isNaN() || value.isInfinite()) remove(key)
        else putLong(key, value.toRawBits())

    private companion object {
        const val FILE_NAME = "portfolio_widget"
        const val KEY_TOTAL = "total"
        const val KEY_CHANGE = "change"
        const val KEY_CHANGE_PERCENT = "change_percent"
        const val KEY_CURRENCY = "currency"
        const val KEY_TIME = "time"
        const val KEY_EMPTY = "empty"
        const val KEY_HISTORY = "history"
        // Version 2 (grosses Widget): eigene Schlüssel, ältere Aufnahmen laden ohne sie
        const val KEY_TOTAL_USDT_V2 = "total_usdt_v2"
        const val KEY_POSITIONS_V2 = "positions_v2"
        const val KEY_OTHER_POSITIONS_V2 = "other_positions_v2"
        const val KEY_VALUE_HISTORY_V2 = "value_history_v2"
    }
}
