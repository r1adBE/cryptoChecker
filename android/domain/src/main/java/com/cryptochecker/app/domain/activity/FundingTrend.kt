package com.cryptochecker.app.domain.activity

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

/** Eine abgerechnete Funding Rate: Zeitpunkt (Epoch-ms) und Satz in % je Periode. */
data class FundingPoint(val time: Long, val ratePercent: Double)

/** Abgerechnete Funding Rates (älteste zuerst) und ihre Quelle («Binance Futures», «Bybit», «OKX»). */
data class FundingHistory(val points: List<FundingPoint>, val source: String)

/** Richtung der Funding Rate. */
enum class FundingTrendDirection { RISING, FALLING, STABLE }

/** Verlauf der Funding Rate über [hours] Stunden. */
data class FundingTrend(val direction: FundingTrendDirection, val hours: Int)

/**
 * Verlauf der Funding Rate (reines Kotlin, Swift-Spiegel `FundingTrendRules`): aus den letzten
 * abgerechneten Sätzen (je Börse höchstens 4, beim Öffnen von «Warum?» geladen) plus dem
 * aktuellen Satz, wenn er von derselben Quelle stammt. Nur Punkte der letzten [WINDOW_HOURS]
 * Stunden; mindestens [MIN_POINTS] Punkte über mindestens [MIN_SPAN_HOURS] Stunden.
 *
 *  - stabil: alle Sätze liegen innerhalb von [MIN_DELTA_PERCENT] (Prozentpunkte)
 *  - steigend/fallend: Unterschied erster → letzter Punkt mindestens [MIN_DELTA_PERCENT] und
 *    kein Schritt geht um mehr als [MIN_DELTA_PERCENT] in die Gegenrichtung
 *  - sonst (hin und her): kein Verlauf
 *
 * Verglichen wird auf 8 Stunden umgerechnet ([ActivityAnalyzer.fundingPer8h]): stündliche Sätze
 * (Hyperliquid) zählen achtfach, damit die Mindestveränderung überall dasselbe bedeutet.
 */
object FundingTrendRules {

    /** So weit zurück zählen Punkte. */
    const val WINDOW_HOURS = 25

    const val MIN_POINTS = 3
    const val MIN_SPAN_HOURS = 2

    /** Kleinste Veränderung in Prozentpunkten, die zählt (0,005 = halber Grundsatz von 0,01 %). */
    const val MIN_DELTA_PERCENT = 0.005

    /** So viele abgerechnete Sätze werden je Börse geladen. */
    const val LIMIT = 4

    private const val HOUR_MILLIS = ActivityAnalyzer.HOUR_MILLIS
    private const val FUTURE_TOLERANCE_MILLIS = 60_000L

    fun trend(
        history: FundingHistory?,
        current: Double?,
        currentSource: String?,
        now: Long,
        /** Intervall der aktuellen Rate laut Börse; gilt auch für den Verlauf derselben Quelle. */
        currentIntervalHours: Int? = null,
    ): FundingTrend? {
        if (history == null) return null
        // Intervall: das gemeldete der aktuellen Rate (gleiche Quelle = gleicher Kontrakt), sonst das der Quelle
        val reported = currentIntervalHours.takeIf { currentSource == history.source }
        val interval = ActivityAnalyzer.fundingIntervalHours(history.source, reported)
        val points = history.points
            .filter { it.ratePercent.isFinite() && it.time - now <= FUTURE_TOLERANCE_MILLIS && now - it.time <= WINDOW_HOURS * HOUR_MILLIS }
            .distinctBy { it.time }
            .sortedBy { it.time }
            .map { it.copy(ratePercent = ActivityAnalyzer.fundingPer8h(it.ratePercent, interval)) }
            .toMutableList()
        // Aktueller Satz nur von derselben Börse (sonst vergliche man zwei Märkte)
        if (current != null && current.isFinite() && currentSource != null && currentSource == history.source) {
            points += FundingPoint(now, ActivityAnalyzer.fundingPer8h(current, interval))
        }
        if (points.size < MIN_POINTS) return null
        val spanMillis = points.last().time - points.first().time
        if (spanMillis < MIN_SPAN_HOURS * HOUR_MILLIS) return null
        val hours = (spanMillis.toDouble() / HOUR_MILLIS).roundToInt()
        val rates = points.map { it.ratePercent }
        val max = rates.maxOrNull() ?: return null
        val min = rates.minOrNull() ?: return null
        if (max - min < MIN_DELTA_PERCENT - EPS) return FundingTrend(FundingTrendDirection.STABLE, hours)
        val delta = rates.last() - rates.first()
        val steps = rates.zipWithNext { a, b -> b - a }
        return when {
            delta >= MIN_DELTA_PERCENT - EPS && steps.none { it < -MIN_DELTA_PERCENT } ->
                FundingTrend(FundingTrendDirection.RISING, hours)
            delta <= -MIN_DELTA_PERCENT + EPS && steps.none { it > MIN_DELTA_PERCENT } ->
                FundingTrend(FundingTrendDirection.FALLING, hours)
            else -> null
        }
    }

    /** Rundungsreserve, damit genau 0,005 Prozentpunkte (aus Strings mal 100) zählen. */
    private const val EPS = 1e-9

    // ---------------- Antworten der Börsen ----------------

    /** Binance `/fapi/v1/fundingRate`: `[{"fundingTime":…,"fundingRate":"0.0001"},…]`. */
    fun parseBinance(json: String): List<FundingPoint> {
        val array = JSONArray(json)
        return (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            point(o.optLong("fundingTime"), o.optString("fundingRate"))
        }.sortedBy { it.time }
    }

    /** Bybit `/v5/market/funding/history`: `{"result":{"list":[{"fundingRate","fundingRateTimestamp"}]}}`, neueste zuerst. */
    fun parseBybit(json: String): List<FundingPoint> {
        val list = JSONObject(json).getJSONObject("result").getJSONArray("list")
        return (0 until list.length()).mapNotNull { i ->
            val o = list.optJSONObject(i) ?: return@mapNotNull null
            point(o.optString("fundingRateTimestamp").toLongOrNull() ?: 0L, o.optString("fundingRate"))
        }.sortedBy { it.time }
    }

    /** OKX `/api/v5/public/funding-rate-history`: `{"code":"0","data":[{"fundingRate","fundingTime"}]}`, neueste zuerst. */
    fun parseOkx(json: String): List<FundingPoint> {
        val root = JSONObject(json)
        require(root.optString("code") == "0") { "OKX-Fehler: ${root.optString("msg")}" }
        val data = root.getJSONArray("data")
        return (0 until data.length()).mapNotNull { i ->
            val o = data.optJSONObject(i) ?: return@mapNotNull null
            // «realizedRate» ist der tatsächlich abgerechnete Satz; sonst «fundingRate»
            val rate = o.optString("realizedRate").takeIf { it.isNotBlank() } ?: o.optString("fundingRate")
            point(o.optString("fundingTime").toLongOrNull() ?: 0L, rate)
        }.sortedBy { it.time }
    }

    private fun point(time: Long, rate: String?): FundingPoint? {
        val value = rate?.trim()?.toDoubleOrNull()?.times(100.0) ?: return null
        if (time <= 0L || !value.isFinite()) return null
        return FundingPoint(time, value)
    }
}
