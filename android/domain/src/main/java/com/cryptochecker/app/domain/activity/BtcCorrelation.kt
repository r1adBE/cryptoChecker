package com.cryptochecker.app.domain.activity

import kotlin.math.ln
import kotlin.math.sqrt

/** Wie eng ein Coin gerade mit Bitcoin läuft — ein optionaler Satz im «Warum?»-Blatt. */
enum class BtcLink {
    /** «SOL läuft derzeit eng mit Bitcoin.» */
    TIGHT,

    /** «SOL bewegt sich derzeit unabhängig von Bitcoin.» */
    INDEPENDENT,
}

/**
 * Korrelation der stündlichen Log-Renditen eines Paars mit Bitcoin (Pearson) über die letzten
 * 24–72 Stunden — aus den Kerzen, die «Warum?» ohnehin lädt (Paar und BTCUSDT als Markt-
 * Vergleich), also ohne zusätzliche Abfrage. Nur abgeschlossene Stunden, nur Stunden, die in
 * beiden Reihen vorkommen, nur Renditen über genau eine Stunde. Swift-Spiegel: BtcCorrelation.swift.
 */
object BtcCorrelation {

    /** Mindestens so viele gemeinsame Stunden-Renditen, sonst kein Satz. */
    const val MIN_POINTS = 24

    /** Höchstens so viele (die jüngsten). */
    const val MAX_POINTS = 72

    /** Ab hier «läuft eng mit Bitcoin». */
    const val TIGHT = 0.8

    /** Bis hier «bewegt sich unabhängig von Bitcoin». */
    const val INDEPENDENT = 0.3

    private const val HOUR_MILLIS = 3_600_000L

    /**
     * Pearson-Korrelation der Stunden-Log-Renditen; null für Bitcoin selbst, ohne eine der
     * Reihen oder mit weniger als [MIN_POINTS] gemeinsamen Renditen bzw. ohne Schwankung.
     * Die letzte Kerze jeder Reihe gilt als laufende Stunde und bleibt weg.
     */
    fun correlation(baseAsset: String, candles: List<HourCandle>?, btc: List<HourCandle>?): Double? {
        if (baseAsset.trim().equals("BTC", ignoreCase = true)) return null
        if (candles == null || btc == null) return null
        val coin = returns(candles)
        val ref = returns(btc)
        val times = coin.keys.filter { it in ref }.sorted().takeLast(MAX_POINTS)
        if (times.size < MIN_POINTS) return null
        return pearson(times.map { coin.getValue(it) }, times.map { ref.getValue(it) })
    }

    /** Einordnung: ≥ [TIGHT] eng, ≤ [INDEPENDENT] unabhängig, dazwischen (oder null) kein Satz. */
    fun link(correlation: Double?): BtcLink? = when {
        correlation == null || !correlation.isFinite() -> null
        correlation >= TIGHT -> BtcLink.TIGHT
        correlation <= INDEPENDENT -> BtcLink.INDEPENDENT
        else -> null
    }

    fun link(baseAsset: String, candles: List<HourCandle>?, btc: List<HourCandle>?): BtcLink? =
        link(correlation(baseAsset, candles, btc))

    /** Startzeit der Stunde → ln(Schluss / Schluss der Stunde davor); ohne die laufende Kerze. */
    private fun returns(candles: List<HourCandle>): Map<Long, Double> {
        val closed = candles.dropLast(1)
        val out = HashMap<Long, Double>(closed.size)
        for (i in 1 until closed.size) {
            val previous = closed[i - 1]
            val current = closed[i]
            if (current.openTime - previous.openTime != HOUR_MILLIS) continue
            if (!(previous.close > 0.0) || !(current.close > 0.0)) continue
            val r = ln(current.close / previous.close)
            if (r.isFinite()) out[current.openTime] = r
        }
        return out
    }

    /** Pearson; null bei weniger als zwei Werten oder ohne Streuung in einer der Reihen. */
    fun pearson(xs: List<Double>, ys: List<Double>): Double? {
        val n = minOf(xs.size, ys.size)
        if (n < 2) return null
        val mx = xs.take(n).average()
        val my = ys.take(n).average()
        var sxy = 0.0
        var sxx = 0.0
        var syy = 0.0
        for (i in 0 until n) {
            val dx = xs[i] - mx
            val dy = ys[i] - my
            sxy += dx * dy
            sxx += dx * dx
            syy += dy * dy
        }
        if (sxx <= 0.0 || syy <= 0.0) return null
        val r = sxy / sqrt(sxx * syy)
        return if (r.isFinite()) r.coerceIn(-1.0, 1.0) else null
    }
}
