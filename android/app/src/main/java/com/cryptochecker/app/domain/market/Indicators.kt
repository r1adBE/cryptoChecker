package com.cryptochecker.app.domain.market

/** Kleine Kennzahlen-Helfer auf Schlusskursen (älteste zuerst). */
object Indicators {

    fun smaOfLast(values: List<Double>, n: Int): Double? =
        if (n > 0 && values.size >= n) values.takeLast(n).average() else null

    /** RSI nach Wilder über [period] Schritte; null bei zu wenig Daten. */
    fun rsi(closes: List<Double>, period: Int = 14): Double? {
        if (closes.size <= period) return null
        var gain = 0.0
        var loss = 0.0
        for (i in 1..period) {
            val d = closes[i] - closes[i - 1]
            if (d >= 0) gain += d else loss -= d
        }
        var avgGain = gain / period
        var avgLoss = loss / period
        for (i in period + 1 until closes.size) {
            val d = closes[i] - closes[i - 1]
            avgGain = (avgGain * (period - 1) + maxOf(d, 0.0)) / period
            avgLoss = (avgLoss * (period - 1) + maxOf(-d, 0.0)) / period
        }
        if (avgLoss == 0.0) return 100.0
        val rs = avgGain / avgLoss
        return 100.0 - 100.0 / (1.0 + rs)
    }
}
