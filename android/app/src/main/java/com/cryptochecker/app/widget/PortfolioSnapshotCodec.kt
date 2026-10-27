package com.cryptochecker.app.widget

import com.cryptochecker.app.domain.portfolio.PortfolioChange
import com.cryptochecker.app.domain.portfolio.PortfolioDisplayCost
import com.cryptochecker.app.domain.portfolio.PortfolioDisplayFx
import com.cryptochecker.app.domain.portfolio.PortfolioHistoryPoint
import com.cryptochecker.app.domain.portfolio.PortfolioPosition
import com.cryptochecker.app.domain.portfolio.PortfolioValuePoint
import com.cryptochecker.app.domain.portfolio.PortfolioWidgetEntries
import com.cryptochecker.app.domain.portfolio.PortfolioWidgetEntry
import com.cryptochecker.app.domain.portfolio.PortfolioWidgetMath
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber

/**
 * JSON der Positionen, des Gewinn/Verlusts je Coin, des Tagesverlaufs und der übrigen Teile der Aufnahme
 * (reines Kotlin, getestet in PortfolioSnapshotCodecTest). Fehlende Felder älterer Aufnahmen laden als null bzw. leer.
 * Eigene Datei neben [PortfolioSnapshotStore]: der Speicher (versiegelte SharedPreferences) bleibt dort, hier nur
 * das Lesen und Schreiben der JSON-Teile.
 */
internal object PortfolioSnapshotCodec {

    /** Coin-Kürzel: ["BTC", …]; fehlt oder unlesbar = leer. */
    fun readCoins(json: String?): List<String> = runCatching {
        if (json == null) return emptyList()
        val array = JSONArray(json)
        (0 until array.length()).mapNotNull { i -> array.optString(i).takeIf { it.isNotBlank() } }
    }.onFailure { Timber.d(it, "Portfolio-Widget: Coins ohne «Heute» nicht lesbar") }.getOrDefault(emptyList())

    /** ± seit Kauf: [Betrag, Prozent?] in USDT; fehlt oder unlesbar = null. */
    fun readChange(json: String?): PortfolioChange? = runCatching {
        if (json == null) return null
        val row = JSONArray(json)
        val amount = row.optDouble(0).takeIf { it.isFinite() } ?: return null
        val percent = if (row.length() > 1) row.optDouble(1).takeIf { it.isFinite() } else null
        PortfolioChange(amount, percent)
    }.onFailure { Timber.d(it, "Portfolio-Widget: ± seit Kauf nicht lesbar") }.getOrNull()

    /** Tagesverlauf: [[Tag, Wert, Eröffnung?, Hoch?, Tief?], …]; fehlt oder unlesbar = leer. */
    fun readDaily(json: String?): List<PortfolioHistoryPoint> = runCatching {
        if (json == null) return emptyList()
        val array = JSONArray(json)
        (0 until array.length()).mapNotNull { i ->
            val row = array.optJSONArray(i) ?: return@mapNotNull null
            val value = row.optDouble(1).takeIf { it.isFinite() } ?: return@mapNotNull null
            fun opt(index: Int): Double? = if (row.length() > index) row.optDouble(index).takeIf { it.isFinite() } else null
            PortfolioHistoryPoint(row.optLong(0), value, opt(2), opt(3), opt(4))
        }.sortedBy { it.epochDay }
    }.onFailure { Timber.d(it, "Portfolio-Widget: Tagesverlauf nicht lesbar") }.getOrDefault(emptyList())

    /** Veränderung je Zeitraum: {"7": [Betrag, Prozent?], …}; fehlt oder unlesbar = leer. */
    fun readPeriodChanges(json: String?): Map<Int, PortfolioChange> = runCatching {
        if (json == null) return emptyMap()
        val root = JSONObject(json)
        val out = HashMap<Int, PortfolioChange>()
        root.keys().forEach { key ->
            val days = key.toIntOrNull() ?: return@forEach
            val row = root.optJSONArray(key) ?: return@forEach
            val amount = row.optDouble(0).takeIf { it.isFinite() } ?: return@forEach
            val percent = if (row.length() > 1) row.optDouble(1).takeIf { it.isFinite() } else null
            val skipped = row.optJSONArray(2)?.let { a -> (0 until a.length()).mapNotNull { a.optString(it).takeIf { s -> s.isNotBlank() } } }
            out[days] = PortfolioChange(amount, percent, skipped.orEmpty())
        }
        out
    }.onFailure { Timber.d(it, "Portfolio-Widget: Veränderung je Zeitraum nicht lesbar") }.getOrDefault(emptyMap())


    fun readValueHistory(json: String?): List<PortfolioValuePoint> = runCatching {
        if (json == null) return emptyList()
        val array = JSONArray(json)
        (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            val time = o.optLong("t", 0L).takeIf { it > 0L } ?: return@mapNotNull null
            val value = o.optDouble("v").takeIf { it.isFinite() } ?: return@mapNotNull null
            PortfolioValuePoint(time, value)
        }.sortedBy { it.time }
    }.onFailure { Timber.d(it, "Portfolio-Widget: Wertverlauf nicht lesbar") }.getOrDefault(emptyList())

    /** Tagesverlauf als [[Tag, Wert, Eröffnung?, Hoch?, Tief?], …] (Gegenstück zu [readDaily]). */
    fun dailyJson(points: List<PortfolioHistoryPoint>): JSONArray {
        val out = JSONArray()
        points.forEach { p ->
            if (!p.value.isFinite()) return@forEach
            val row = JSONArray().put(p.epochDay).put(p.value)
            val o = p.open
            val h = p.high
            val l = p.low
            if (o != null && h != null && l != null && o.isFinite() && h.isFinite() && l.isFinite()) row.put(o).put(h).put(l)
            out.put(row)
        }
        return out
    }

    /** Positionen: [{"s","v","p","c"?,"q"?,"pa"?,"pr"?}, …] — «pa»/«pr» Gewinn/Verlust 24 h (Anzeigewährung, Prozent). */
    fun positionsJson(positions: List<PortfolioPosition>): JSONArray {
        val out = JSONArray()
        positions.forEach { p ->
            if (!p.value.isFinite()) return@forEach
            val o = JSONObject().put("s", p.symbol).put("v", p.value)
            if (p.sharePercent.isFinite()) o.put("p", p.sharePercent)
            p.change24hPercent?.takeIf { it.isFinite() }?.let { o.put("c", it) }
            p.quantity?.takeIf { it.isNotBlank() }?.let { o.put("q", it) }
            p.pnlAmount?.takeIf { it.isFinite() }?.let { o.put("pa", it) }
            p.pnlPercent?.takeIf { it.isFinite() }?.let { o.put("pr", it) }
            // Buchungen für die Liste: [{"t","q","v"?,"n"?}, …] (fehlen in älteren Aufnahmen)
            p.entries?.let { entries ->
                val list = JSONArray()
                entries.forEach { e ->
                    if (!e.quantity.isFinite()) return@forEach
                    val item = JSONObject().put("t", e.time).put("q", e.quantity)
                    e.value?.takeIf { it.isFinite() }?.let { item.put("v", it) }
                    e.note?.let { item.put("n", it) }
                    list.put(item)
                }
                o.put("e", list)
            }
            out.put(o)
        }
        return out
    }

    fun readPositions(json: String?): List<PortfolioPosition> = runCatching {
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
                // Menge als Text (fehlt in älteren Aufnahmen)
                quantity = if (o.has("q")) o.optString("q").takeIf { it.isNotBlank() } else null,
                // Gewinn/Verlust 24 h je Position (fehlt in älteren Aufnahmen)
                pnlAmount = if (o.has("pa")) o.optDouble("pa").takeIf { it.isFinite() } else null,
                pnlPercent = if (o.has("pr")) o.optDouble("pr").takeIf { it.isFinite() } else null,
                entries = o.optJSONArray("e")?.let(::readEntries),
            )
        }.take(PortfolioWidgetMath.MAX_POSITIONS)
    }.onFailure { Timber.d(it, "Portfolio-Widget: Positionen nicht lesbar") }.getOrDefault(emptyList())

    /** Buchungen einer Position ([positionsJson] «e»); unlesbare Einträge entfallen. */
    private fun readEntries(array: JSONArray): List<PortfolioWidgetEntry> = (0 until array.length()).mapNotNull { i ->
        val e = array.optJSONObject(i) ?: return@mapNotNull null
        val quantity = e.optDouble("q").takeIf { it.isFinite() } ?: return@mapNotNull null
        PortfolioWidgetEntry(
            time = e.optLong("t"),
            quantity = quantity,
            note = if (e.has("n")) e.optString("n").takeIf { it.isNotBlank() } else null,
            value = if (e.has("v")) e.optDouble("v").takeIf { it.isFinite() } else null,
        )
    }.take(PortfolioWidgetEntries.MAX_PER_COIN)

    /** Gewinn/Verlust je Coin: {"BTC": [Betrag, Prozent?], …} in USDT. */
    fun coinPnlJson(coinPnl: Map<String, PortfolioChange>): String {
        val root = JSONObject()
        coinPnl.forEach { (coin, c) ->
            if (coin.isBlank() || !c.amount.isFinite()) return@forEach
            val row = JSONArray().put(c.amount)
            c.percent?.takeIf { it.isFinite() }?.let { row.put(it) }
            root.put(coin, row)
        }
        return root.toString()
    }

    /** Betrag in der Anzeigewährung: [Betrag, Prozent|null, [fehlende Coins]?]; null = nichts zu speichern. */
    fun displayChangeJson(change: PortfolioChange?): String? {
        val c = change?.takeIf { it.amount.isFinite() } ?: return null
        val row = JSONArray().put(c.amount).put(c.percent?.takeIf { it.isFinite() } ?: JSONObject.NULL)
        val skipped = c.skipped.filter { it.isNotBlank() }
        if (skipped.isNotEmpty()) row.put(JSONArray(skipped))
        return row.toString()
    }

    /** Gegenstück zu [displayChangeJson]; fehlt oder unlesbar = null. */
    fun readDisplayChange(json: String?): PortfolioChange? = runCatching {
        if (json == null) return null
        val row = JSONArray(json)
        val amount = row.optDouble(0).takeIf { it.isFinite() } ?: return null
        val percent = if (row.length() > 1 && !row.isNull(1)) row.optDouble(1).takeIf { it.isFinite() } else null
        val skipped = row.optJSONArray(2)?.let { a -> (0 until a.length()).mapNotNull { a.optString(it).takeIf { s -> s.isNotBlank() } } }
        PortfolioChange(amount, percent, skipped.orEmpty())
    }.onFailure { Timber.d(it, "Portfolio-Widget: Betrag in der Anzeigewährung nicht lesbar") }.getOrNull()

    /** Merkzettel: {"c": Währung, "d": Tag, "k": {"BTC": [USDT, Anzeigewährung]}, "f": {"Tag": Faktor}}. */
    fun displayFxJson(fx: PortfolioDisplayFx): String {
        val costs = JSONObject()
        fx.costs.forEach { (coin, c) ->
            if (coin.isNotBlank() && c.usdt.isFinite() && c.target.isFinite()) costs.put(coin, JSONArray().put(c.usdt).put(c.target))
        }
        val factors = JSONObject()
        fx.dayFactors.forEach { (day, f) -> if (f.isFinite() && f > 0.0) factors.put(day.toString(), f) }
        return JSONObject().put("c", fx.currency).put("d", fx.day).put("k", costs).put("f", factors).toString()
    }

    /** Gegenstück zu [displayFxJson]; fehlt oder unlesbar = null. */
    fun readDisplayFx(json: String?): PortfolioDisplayFx? = runCatching {
        if (json == null) return null
        val root = JSONObject(json)
        val currency = root.optString("c").takeIf { it.isNotBlank() } ?: return null
        val costs = HashMap<String, PortfolioDisplayCost>()
        root.optJSONObject("k")?.let { k ->
            k.keys().forEach { coin ->
                val row = k.optJSONArray(coin) ?: return@forEach
                val usdt = row.optDouble(0).takeIf { it.isFinite() } ?: return@forEach
                val target = row.optDouble(1).takeIf { it.isFinite() } ?: return@forEach
                costs[coin] = PortfolioDisplayCost(usdt, target)
            }
        }
        val factors = HashMap<Long, Double>()
        root.optJSONObject("f")?.let { f ->
            f.keys().forEach { key ->
                val day = key.toLongOrNull() ?: return@forEach
                f.optDouble(key).takeIf { it.isFinite() && it > 0.0 }?.let { factors[day] = it }
            }
        }
        PortfolioDisplayFx(currency, costs, factors, root.optLong("d", 0L))
    }.onFailure { Timber.d(it, "Portfolio-Widget: Merkzettel Anzeigewährung nicht lesbar") }.getOrNull()

    /** Gegenstück zu [coinPnlJson]; fehlt oder unlesbar = leer. */
    fun readCoinPnl(json: String?): Map<String, PortfolioChange> = runCatching {
        if (json == null) return emptyMap()
        val root = JSONObject(json)
        val out = HashMap<String, PortfolioChange>()
        root.keys().forEach { coin ->
            val row = root.optJSONArray(coin) ?: return@forEach
            val amount = row.optDouble(0).takeIf { it.isFinite() } ?: return@forEach
            val percent = if (row.length() > 1) row.optDouble(1).takeIf { it.isFinite() } else null
            out[coin] = PortfolioChange(amount, percent)
        }
        out
    }.onFailure { Timber.d(it, "Portfolio-Widget: Gewinn/Verlust je Coin nicht lesbar") }.getOrDefault(emptyMap())
}
