package com.cryptochecker.app.widget

import com.cryptochecker.app.domain.watch.ChangeStamp
import androidx.core.content.edit
import android.content.Context
import com.cryptochecker.app.domain.portfolio.PortfolioChange
import com.cryptochecker.app.domain.portfolio.PortfolioHistoryPoint
import com.cryptochecker.app.domain.portfolio.PortfolioPosition
import com.cryptochecker.app.domain.portfolio.PortfolioSnapshot
import com.cryptochecker.app.domain.portfolio.PortfolioValuePoint
import com.cryptochecker.app.domain.portfolio.PortfolioWidgetMath
import com.cryptochecker.app.domain.portfolio.PriceSample
import com.cryptochecker.app.domain.portfolio.TimedPrice
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import javax.crypto.SecretKey
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Letzte Momentaufnahme des Portfolios für das Widget, die Kursaufnahmen der
 * letzten 48 Stunden (Vergleichsbasis, siehe PortfolioSnapshotMath) und die gemerkten
 * Stundenkurse je Coin (24-h-Wertverlauf, siehe PortfolioWidgetSeries).
 * Bewusst SharedPreferences wie [WidgetPrefs]: Das Widget liest synchron und ohne Netz.
 * Flüchtige Daten — von der Android-Sicherung ausgenommen (backup_rules.xml).
 *
 * Seit Build 60 versiegelt ([PortfolioSnapshotSeal]): alle Werte als ein JSON-Objekt in einem mit AES-256-GCM
 * verschlüsselten Eintrag ([KEY_SEALED]), Schlüssel im Android Keystore ([PortfolioSnapshotSeal.Keys]). Die Schlüssel
 * im JSON heissen wie die früheren Einzel-Einträge; Klartext älterer Versionen wird einmal gelesen und versiegelt
 * neu geschrieben. Ohne Schlüssel: keine Aufnahme (Leer-Zustand des Widgets), nie Klartext. Lesbar bleibt nur der
 * gemerkte Devisenkurs ([fxMemo], kein Bestand).
 *
 * Seit Version 2 zusätzlich: Gesamtwert in USDT, die grössten Positionen und der
 * Wertverlauf — unter eigenen Schlüsseln mit «_v2». Fehlen sie (Aufnahme einer älteren
 * Version) oder sind sie nicht lesbar, lädt die Aufnahme trotzdem, nur ohne diese Teile.
 */
@Singleton
class PortfolioSnapshotStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val keys: PortfolioSnapshotSeal.Keys,
) {
    private val prefs get() = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    /** Zuletzt geöffneter Eintrag samt Inhalt: Widget und Aktualisierung lesen mehrmals hintereinander. */
    @Volatile
    private var opened: Pair<String, JSONObject>? = null

    /** null = noch nie berechnet, oder nicht lesbar (Schlüssel nicht verfügbar, Eintrag kaputt). */
    fun snapshot(): PortfolioSnapshot? {
        val p = payload() ?: return null
        val time = p.optLong(KEY_TIME, 0L)
        if (time <= 0L) return null
        return PortfolioSnapshot(
            total = p.number(KEY_TOTAL) ?: 0.0,
            changeAmount = p.number(KEY_CHANGE),
            changePercent = p.number(KEY_CHANGE_PERCENT),
            currency = p.text(KEY_CURRENCY) ?: "USD",
            time = time,
            empty = p.optBoolean(KEY_EMPTY, true),
            totalUsdt = p.number(KEY_TOTAL_USDT_V2),
            positions = readPositions(p.text(KEY_POSITIONS_V2)),
            otherPositions = p.optInt(KEY_OTHER_POSITIONS_V2, 0).coerceAtLeast(0),
            history = readValueHistory(p.text(KEY_VALUE_HISTORY_V2)),
            stamp = ChangeStamp.decode(p.text(KEY_CHANGE_STAMP)),
            // fehlt bei älteren Aufnahmen → 0 (kein Hinweis)
            missingPrices = p.optInt(KEY_MISSING_PRICES, 0).coerceAtLeast(0),
            stalePrices = p.optInt(KEY_STALE_PRICES, 0).coerceAtLeast(0),
            daily = readDaily(p.text(KEY_DAILY_V4)),
            periodChanges = readPeriodChanges(p.text(KEY_PERIOD_CHANGES_V4)),
            // fehlt bei älteren Aufnahmen → null («Gesamt» der Übersicht «—»)
            unrealized = readChange(p.text(KEY_UNREALIZED_V5)),
            // fehlt bei älteren Aufnahmen → leer (kein Hinweis «Heute ohne …»)
            todaySkipped = readCoins(p.text(KEY_TODAY_SKIPPED_V6)),
            // fehlt bei älteren Aufnahmen → leer (Zeilen im Portfolio-Tab «—» bis zur nächsten Aufnahme)
            coinPnl = PortfolioSnapshotCodec.readCoinPnl(p.text(KEY_COIN_PNL_V7)),
        )
    }

    // ── Versiegelter Eintrag ──

    /** Schlüssel der Aufnahme; null = gerade nicht verfügbar. */
    private fun currentKey(): SecretKey? =
        runCatching { (keys.lookup() as? PortfolioSnapshotSeal.KeyLookup.Found)?.key }
            .onFailure { Timber.d(it, "Portfolio-Widget: Schlüsselsuche fehlgeschlagen") }
            .getOrNull()

    /** Inhalt der Aufnahme als JSON; null = keine oder nicht lesbar. Wirft nie (das Widget liest synchron). */
    private fun payload(): JSONObject? = runCatching {
        val p = prefs
        val sealed = p.getString(KEY_SEALED, null)
        val key = currentKey()
        when (val plan = PortfolioSnapshotSeal.planRead(sealed, PLAIN_KEYS.any(p::contains), key != null)) {
            is PortfolioSnapshotSeal.ReadPlan.Open -> {
                opened?.takeIf { it.first == plan.sealed }?.second
                    ?: PortfolioSnapshotSeal.open(key ?: return null, plan.sealed)
                        ?.let(::JSONObject)
                        ?.also { opened = plan.sealed to it }
            }
            PortfolioSnapshotSeal.ReadPlan.MigratePlain -> migratePlain(p, key ?: return null)
            PortfolioSnapshotSeal.ReadPlan.Empty -> null
        }
    }.onFailure { Timber.d(it, "Portfolio-Widget: Aufnahme nicht lesbar") }.getOrNull()

    /** Klartext einer älteren Version einmal lesen, versiegelt speichern, Klartext entfernen. */
    private fun migratePlain(p: android.content.SharedPreferences, key: SecretKey): JSONObject? {
        val payload = JSONObject()
        if (p.contains(KEY_TIME)) payload.put(KEY_TIME, p.getLong(KEY_TIME, 0L))
        if (p.contains(KEY_EMPTY)) payload.put(KEY_EMPTY, p.getBoolean(KEY_EMPTY, true))
        for (k in PLAIN_DOUBLE_KEYS) p.getDouble(k)?.let { payload.putNumber(k, it) }
        for (k in PLAIN_INT_KEYS) if (p.contains(k)) payload.put(k, p.getInt(k, 0))
        for (k in PLAIN_STRING_KEYS) p.getString(k, null)?.let { payload.put(k, it) }
        // Schlägt das Versiegeln fehl, ist der Klartext trotzdem weg (Zwischenspeicher: die nächste Aktualisierung
        // berechnet neu) und angezeigt wird nichts — lieber kurz keine Werte als Bestände im Klartext
        if (!writeSealed(payload, key)) return null
        return payload
    }

    /**
     * Versiegelt speichern und Klartext älterer Versionen entfernen; false = nicht versiegelt (Schlüssel fehlt oder
     * Fehler): dann nichts geschrieben, ein vorhandener versiegelter Eintrag bleibt.
     */
    private fun writeSealed(payload: JSONObject, key: SecretKey? = currentKey()): Boolean {
        val sealed = when (PortfolioSnapshotSeal.planWrite(key != null)) {
            PortfolioSnapshotSeal.WritePlan.SEAL -> runCatching { PortfolioSnapshotSeal.seal(key ?: return false, payload.toString()) }
                .onFailure {
                    Timber.d(it, "Portfolio-Widget: Aufnahme nicht versiegelt")
                    keys.discardIfBroken(it)
                }
                .getOrNull()
            PortfolioSnapshotSeal.WritePlan.KEEP_SEALED -> null
        }
        prefs.edit {
            // Nie Klartext schreiben: ohne Versiegelung bleibt ein versiegelter Eintrag, wie er ist
            if (sealed != null) putString(KEY_SEALED, sealed)
            PLAIN_KEYS.forEach { remove(it) }
        }
        if (sealed != null) opened = sealed to payload
        return sealed != null
    }

    /** Coin-Kürzel: ["BTC", …]; fehlt oder unlesbar = leer. */
    private fun readCoins(json: String?): List<String> = runCatching {
        if (json == null) return emptyList()
        val array = JSONArray(json)
        (0 until array.length()).mapNotNull { i -> array.optString(i).takeIf { it.isNotBlank() } }
    }.onFailure { Timber.d(it, "Portfolio-Widget: Coins ohne «Heute» nicht lesbar") }.getOrDefault(emptyList())

    /** ± seit Kauf: [Betrag, Prozent?] in USDT; fehlt oder unlesbar = null. */
    private fun readChange(json: String?): PortfolioChange? = runCatching {
        if (json == null) return null
        val row = JSONArray(json)
        val amount = row.optDouble(0).takeIf { it.isFinite() } ?: return null
        val percent = if (row.length() > 1) row.optDouble(1).takeIf { it.isFinite() } else null
        PortfolioChange(amount, percent)
    }.onFailure { Timber.d(it, "Portfolio-Widget: ± seit Kauf nicht lesbar") }.getOrNull()

    /** Tagesverlauf: [[Tag, Wert, Eröffnung?, Hoch?, Tief?], …]; fehlt oder unlesbar = leer. */
    private fun readDaily(json: String?): List<PortfolioHistoryPoint> = runCatching {
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
    private fun readPeriodChanges(json: String?): Map<Int, PortfolioChange> = runCatching {
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

    private fun readPositions(json: String?): List<PortfolioPosition> = PortfolioSnapshotCodec.readPositions(json)

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
        val array = JSONArray(payload()?.text(KEY_HISTORY) ?: return emptyList())
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

    /**
     * Gemerkte Stundenkurse je Coin für den 24-h-Wertverlauf ([PortfolioWidgetSeries.merge]);
     * leer, wenn noch nichts gemerkt oder nicht lesbar.
     */
    fun hourlyPrices(): Map<String, List<TimedPrice>> = runCatching {
        val root = JSONObject(payload()?.text(KEY_HOURLY_PRICES_V3) ?: return emptyMap())
        val out = HashMap<String, List<TimedPrice>>()
        root.keys().forEach { coin ->
            val array = root.optJSONArray(coin) ?: return@forEach
            val list = (0 until array.length()).mapNotNull { i ->
                val pair = array.optJSONArray(i) ?: return@mapNotNull null
                val time = pair.optLong(0, 0L).takeIf { it > 0L } ?: return@mapNotNull null
                val price = pair.optDouble(1).takeIf { it.isFinite() && it > 0.0 } ?: return@mapNotNull null
                TimedPrice(time, price)
            }
            if (list.isNotEmpty()) out[coin] = list
        }
        out
    }.onFailure { Timber.d(it, "Portfolio-Widget: Stundenkurse nicht lesbar") }.getOrDefault(emptyMap())

    /** Gemerkter Devisenkurs ([PortfolioWidgetChart.fxRateFor]); null = keiner oder nicht lesbar. */
    fun fxMemo(): PortfolioFxMemo? {
        val p = prefs
        val currency = p.getString(KEY_FX_CURRENCY, null)?.takeIf { it.isNotBlank() } ?: return null
        val rate = p.getDouble(KEY_FX_RATE)?.takeIf { it > 0.0 && it.isFinite() } ?: return null
        val at = p.getLong(KEY_FX_AT, 0L).takeIf { it > 0L } ?: return null
        return PortfolioFxMemo(currency, rate, at)
    }

    fun saveFxMemo(memo: PortfolioFxMemo) {
        prefs.edit {
            putString(KEY_FX_CURRENCY, memo.currency)
            putDouble(KEY_FX_RATE, memo.rate)
            putLong(KEY_FX_AT, memo.fetchedAt)
        }
    }

    fun save(snapshot: PortfolioSnapshot, history: List<PriceSample>, hourlyPrices: Map<String, List<TimedPrice>>) {
        val array = JSONArray()
        history.forEach { sample ->
            val prices = JSONObject()
            sample.prices.forEach { (coin, price) -> prices.put(coin, price) }
            array.put(JSONObject().put("t", sample.time).put("p", prices))
        }
        val positions = PortfolioSnapshotCodec.positionsJson(snapshot.positions)
        val valueHistory = JSONArray()
        snapshot.history.forEach { point ->
            if (point.value.isFinite()) valueHistory.put(JSONObject().put("t", point.time).put("v", point.value))
        }
        val daily = dailyJson(snapshot.daily)
        val periodChanges = JSONObject()
        snapshot.periodChanges.forEach { (days, change) ->
            if (!change.amount.isFinite()) return@forEach
            val row = JSONArray().put(change.amount)
            val percent = change.percent?.takeIf { it.isFinite() }
            val skipped = change.skipped.filter { it.isNotBlank() }
            // [Betrag, Prozent?, [fehlende Coins]?]; ohne Prozent, aber mit Coins: null an zweiter Stelle (ältere lesen NaN)
            if (percent != null) row.put(percent) else if (skipped.isNotEmpty()) row.put(JSONObject.NULL)
            if (skipped.isNotEmpty()) row.put(JSONArray(skipped))
            periodChanges.put(days.toString(), row)
        }
        val hourly = JSONObject()
        hourlyPrices.forEach { (coin, prices) ->
            val array = JSONArray()
            prices.forEach { if (it.price.isFinite()) array.put(JSONArray().put(it.time).put(it.price)) }
            if (array.length() > 0) hourly.put(coin, array)
        }
        val payload = JSONObject()
            .putNumber(KEY_TOTAL, snapshot.total)
            .putNumber(KEY_CHANGE, snapshot.changeAmount)
            .putNumber(KEY_CHANGE_PERCENT, snapshot.changePercent)
            .put(KEY_CURRENCY, snapshot.currency)
            .put(KEY_TIME, snapshot.time)
            .put(KEY_EMPTY, snapshot.empty)
            .put(KEY_HISTORY, array.toString())
            .putNumber(KEY_TOTAL_USDT_V2, snapshot.totalUsdt)
            .put(KEY_POSITIONS_V2, positions.toString())
            .put(KEY_OTHER_POSITIONS_V2, snapshot.otherPositions.coerceAtLeast(0))
            .put(KEY_VALUE_HISTORY_V2, valueHistory.toString())
            .put(KEY_HOURLY_PRICES_V3, hourly.toString())
            .put(KEY_MISSING_PRICES, snapshot.missingPrices.coerceAtLeast(0))
            .put(KEY_STALE_PRICES, snapshot.stalePrices.coerceAtLeast(0))
            .put(KEY_DAILY_V4, daily.toString())
            .put(KEY_PERIOD_CHANGES_V4, periodChanges.toString())
            .put(KEY_COIN_PNL_V7, PortfolioSnapshotCodec.coinPnlJson(snapshot.coinPnl))
        snapshot.stamp?.let { payload.put(KEY_CHANGE_STAMP, it.encode()) }
        snapshot.unrealized?.takeIf { it.amount.isFinite() }?.let { unrealized ->
            val row = JSONArray().put(unrealized.amount)
            unrealized.percent?.takeIf { it.isFinite() }?.let { row.put(it) }
            payload.put(KEY_UNREALIZED_V5, row.toString())
        }
        val skipped = snapshot.todaySkipped.filter { it.isNotBlank() }
        if (skipped.isNotEmpty()) payload.put(KEY_TODAY_SKIPPED_V6, JSONArray().apply { skipped.forEach { put(it) } }.toString())
        writeSealed(payload)
    }

    /** Tagesverlauf als [[Tag, Wert, Eröffnung?, Hoch?, Tief?], …] (Gegenstück zu [readDaily]). */
    private fun dailyJson(points: List<PortfolioHistoryPoint>): JSONArray {
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

    /** Klartext-Zahl (Bits als Long, wie bis Build 59 und im Devisenkurs); null = fehlt oder NaN. */
    private fun android.content.SharedPreferences.getDouble(key: String): Double? =
        if (contains(key)) Double.fromBits(getLong(key, 0L)).takeIf { !it.isNaN() } else null

    private fun android.content.SharedPreferences.Editor.putDouble(
        key: String,
        value: Double?,
    ): android.content.SharedPreferences.Editor =
        if (value == null || value.isNaN() || value.isInfinite()) remove(key)
        else putLong(key, value.toRawBits())

    /** Zahl im versiegelten JSON; nicht endlich oder null = weggelassen (wie bisher `putDouble`). */
    private fun JSONObject.putNumber(key: String, value: Double?): JSONObject =
        if (value == null || !value.isFinite()) this else put(key, value)

    private fun JSONObject.number(key: String): Double? = if (has(key)) optDouble(key).takeIf { it.isFinite() } else null

    private fun JSONObject.text(key: String): String? = if (has(key) && !isNull(key)) optString(key) else null

    private companion object {
        const val FILE_NAME = "portfolio_widget"
        // Build 60: die ganze Aufnahme versiegelt ([PortfolioSnapshotSeal]); die Schlüssel unten gelten im JSON
        const val KEY_SEALED = "sealed_snapshot"
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
        // Version 3: Stundenkurse je Coin für den 24-h-Wertverlauf
        const val KEY_HOURLY_PRICES_V3 = "hourly_prices_v3"
        // %-Basis der Veränderung (fehlt = rollende 24 Stunden)
        const val KEY_CHANGE_STAMP = "change_stamp"
        // Offene Coins ohne Kurs (fehlt = 0, ältere Aufnahme)
        const val KEY_MISSING_PRICES = "missing_prices"
        const val KEY_STALE_PRICES = "stale_prices"
        // Version 4: Tagesverlauf (USDT) und Veränderung je Zeitraum für 7 T / 30 T / 1 J
        const val KEY_DAILY_V4 = "daily_v4"
        const val KEY_PERIOD_CHANGES_V4 = "period_changes_v4"
        // Version 5: ± seit Kauf (USDT) für «Gesamt» der Übersicht; bis zu 12 Positionen für die Liste
        const val KEY_UNREALIZED_V5 = "unrealized_v5"
        // Version 6: Coins, die in «Heute» fehlen (Hinweis «Heute ohne …» wie die Gesamtkarte)
        const val KEY_TODAY_SKIPPED_V6 = "today_skipped_v6"
        // Version 7: MEIN Gewinn/Verlust je Coin (USDT) für die Zeilen des Portfolio-Tabs (wie iOS `coinPnl`);
        // je Position steht er in den Positionen («pa»/«pr»), das Total in «change»
        const val KEY_COIN_PNL_V7 = "coin_pnl_v7"
        // Zuletzt bekannter Devisenkurs USD → Umrechnungswährung (Hintergrund und Widget ohne Netz)
        const val KEY_FX_CURRENCY = "fx_currency"
        const val KEY_FX_RATE = "fx_rate"
        const val KEY_FX_AT = "fx_at"

        // Klartext-Einträge bis Build 59 nach Typ (Überführung [migratePlain]); der Devisenkurs bleibt Klartext
        val PLAIN_DOUBLE_KEYS = listOf(KEY_TOTAL, KEY_CHANGE, KEY_CHANGE_PERCENT, KEY_TOTAL_USDT_V2)
        val PLAIN_INT_KEYS = listOf(KEY_OTHER_POSITIONS_V2, KEY_MISSING_PRICES, KEY_STALE_PRICES)
        val PLAIN_STRING_KEYS = listOf(
            KEY_CURRENCY, KEY_HISTORY, KEY_POSITIONS_V2, KEY_VALUE_HISTORY_V2, KEY_HOURLY_PRICES_V3, KEY_CHANGE_STAMP,
            KEY_DAILY_V4, KEY_PERIOD_CHANGES_V4, KEY_UNREALIZED_V5, KEY_TODAY_SKIPPED_V6, KEY_COIN_PNL_V7,
        )
        val PLAIN_KEYS = listOf(KEY_TIME, KEY_EMPTY) + PLAIN_DOUBLE_KEYS + PLAIN_INT_KEYS + PLAIN_STRING_KEYS
    }
}

/**
 * JSON der Positionen und des Gewinn/Verlusts je Coin (reines Kotlin, getestet in PortfolioSnapshotCodecTest).
 * Fehlende Felder älterer Aufnahmen laden als null bzw. leer.
 */
internal object PortfolioSnapshotCodec {

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
            )
        }.take(PortfolioWidgetMath.MAX_POSITIONS)
    }.onFailure { Timber.d(it, "Portfolio-Widget: Positionen nicht lesbar") }.getOrDefault(emptyList())

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
