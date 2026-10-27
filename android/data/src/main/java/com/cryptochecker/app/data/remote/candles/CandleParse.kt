// Gemeinsames der eigenen Kerzen je Börse (Teil von `ExchangeCandleSourcesMajor.kt`, gespiegelt in `CandleParse.swift`).
package com.cryptochecker.app.data.remote.candles

import com.cryptochecker.app.data.remote.CandleInterval
import com.cryptochecker.app.domain.activity.HourCandle
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/** Gemeinsames für die Quellen: Zahlen lesen, Kennungen prüfen, Reihe abschliessen. */
object CandleParse {

    /** Zahl aus String oder Number; NaN, wenn keine. */
    fun num(v: Any?): Double = when (v) {
        null, JSONObject.NULL -> Double.NaN
        is Number -> v.toDouble()
        else -> v.toString().trim().toDoubleOrNull() ?: Double.NaN
    }

    fun JSONArray.numAt(i: Int): Double = if (i < length()) num(opt(i)) else Double.NaN

    /** Kennung nur aus Buchstaben, Ziffern, «-» und «_» (wird unverändert in die Adresse gesetzt). */
    fun safeId(s: String?): String? {
        val t = s?.trim().orEmpty()
        return t.takeIf { it.isNotEmpty() && it.all { c -> c.isLetterOrDigit() && c.code < 128 || c == '-' || c == '_' } }
    }

    /**
     * Kennung des Paars ([pairId]); fehlt sie, gebildet mit [derive] (wie der Adapter). Eine vorhandene,
     * aber unsichere Kennung gilt nicht (nie aus den Kürzeln geraten).
     */
    inline fun id(pairId: String?, derive: () -> String?): String? =
        if (pairId.isNullOrBlank()) derive() else safeId(pairId)

    /** Kürzel nur aus Buchstaben und Ziffern, gross. */
    fun asset(s: String): String? = s.trim().uppercase().takeIf { it.isNotEmpty() && it.all { c -> c.isLetterOrDigit() && c.code < 128 } }

    /** Gültige Kerzen (Kurse > 0), je Startzeit eine, aufsteigend, die jüngsten [limit]. */
    fun finish(candles: List<HourCandle>, limit: Int): List<HourCandle> =
        candles.filter {
            it.open.isFinite() && it.high.isFinite() && it.low.isFinite() && it.close.isFinite() &&
                it.open > 0.0 && it.high > 0.0 && it.low > 0.0 && it.close > 0.0 && it.openTime > 0L
        }
            .map { if (it.volume.isFinite() && it.volume >= 0.0) it else it.copy(volume = 0.0) }
            .distinctBy { it.openTime }
            .sortedBy { it.openTime }
            .takeLast(limit)

    /** Quote-Umsatz nur, wenn gültig (sonst nähert das Blatt Menge × Schluss). */
    fun quoteVolume(v: Double): Double? = v.takeIf { it.isFinite() && it >= 0.0 }

    /**
     * Binance-Kline: [openTime, open, high, low, close, volume, closeTime, quoteVolume, …].
     * COIN-M ([coinM]): Feld 5 zählt Kontrakte, Feld 7 ist die Menge in der Basis — dann
     * Menge = Feld 7, Quote-Umsatz unbekannt.
     */
    fun binance(json: String, coinM: Boolean): List<HourCandle> = binance(JSONArray(json), coinM)

    /** Wie oben, aus dem schon gelesenen Array (Binance Alpha: `data` der Antwort). */
    fun binance(array: JSONArray, coinM: Boolean): List<HourCandle> {
        return (0 until array.length()).mapNotNull { i ->
            val k = array.optJSONArray(i) ?: return@mapNotNull null
            HourCandle(
                openTime = k.numAt(0).toLong(),
                open = k.numAt(1), high = k.numAt(2), low = k.numAt(3), close = k.numAt(4),
                volume = if (coinM) k.numAt(7) else k.numAt(5),
                quoteVolume = if (coinM) null else quoteVolume(k.numAt(7)),
            )
        }
    }

    fun binanceInterval(interval: CandleInterval): String? = when (interval) {
        CandleInterval.H1 -> "1h"
        CandleInterval.H4 -> "4h"
        CandleInterval.D1 -> "1d"
        CandleInterval.W1 -> null
    }

    /** Coinbase-Zeit für `start`/`end`: ISO 8601 in UTC, z. B. «2025-10-07T00:00:00Z». */
    fun iso(millis: Long): String = Instant.ofEpochMilli(millis).toString()
}
