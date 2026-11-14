package com.cryptochecker.app.domain.market

/** Kleine Kennzahlen-Helfer auf Schlusskursen (älteste zuerst). */
object Indicators {

    /** Tagesschluss am Tag [day] (Tage seit 1970-01-01, UTC). */
    data class DayClose(val day: Long, val close: Double)

    /** Grösste Lücke, die ein Schluss «am oder vor» einem Tag überbrücken darf (Wochenende, fehlende Kerzen einer Quelle). */
    const val MAX_GAP_DAYS = 3

    /**
     * Schluss am Tag [day] oder am letzten Tag davor, höchstens [MAX_GAP_DAYS] früher; sonst (oder nicht positiv) null.
     * [series] älteste zuerst. Kennzahlen «vor n Tagen» zählen so Tage, nicht Kerzen: fehlt einer Quelle ein Tag, rückt
     * der Vergleich nicht auf ein anderes Datum.
     */
    fun closeOnOrBefore(series: List<DayClose>, day: Long): Double? {
        val hit = series.lastOrNull { it.day <= day } ?: return null
        return hit.close.takeIf { day - hit.day <= MAX_GAP_DAYS && it.isFinite() && it > 0.0 }
    }

    /** Veränderung (Faktor − 1) vom Schluss [days] Tage vor dem letzten Tag der Reihe bis zu ihm, nach Datum; sonst null. */
    fun changeOverDays(series: List<DayClose>, days: Int): Double? {
        val last = series.lastOrNull()?.takeIf { it.close.isFinite() && it.close > 0.0 } ?: return null
        val first = closeOnOrBefore(series, last.day - days) ?: return null
        return last.close / first - 1.0
    }

    /**
     * Stärke gegen eine Vergleichsreihe in % über [days] Tage: ((Coin heute / Coin damals) / (BTC heute / BTC damals) − 1)
     * × 100, beide am SELBEN Datum — «heute» = letzter Tag des Coins (BTC an diesem Tag, [closeOnOrBefore]), «damals» =
     * [days] Tage davor. Fehlt einer der vier Schlüsse, null (Kennzahl entfällt statt Daten verschiedener Tage zu
     * vergleichen).
     */
    fun relativeChangePercent(coin: List<DayClose>, btc: List<DayClose>, days: Int): Double? {
        val end = coin.lastOrNull()?.takeIf { it.close.isFinite() && it.close > 0.0 } ?: return null
        val c0 = closeOnOrBefore(coin, end.day - days) ?: return null
        val b1 = closeOnOrBefore(btc, end.day) ?: return null
        val b0 = closeOnOrBefore(btc, end.day - days) ?: return null
        return ((end.close / c0) / (b1 / b0) - 1.0) * 100.0
    }

    fun smaOfLast(values: List<Double>, n: Int): Double? =
        if (n > 0 && values.size >= n) values.takeLast(n).average() else null

    /**
     * RSI nach Wilder über [period] Schritte; null bei zu wenig Daten oder [period] nicht positiv (wie iOS).
     * Durchschnittlicher Verlust 0: 100, wenn es Gewinne gab, sonst (völlig flach) 50.
     */
    fun rsi(closes: List<Double>, period: Int = 14): Double? {
        if (period <= 0 || closes.size <= period) return null
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
        // Völlig flache Kurse (weder Gewinn noch Verlust): neutral 50 statt «überkauft»; nur Gewinne: 100
        if (avgLoss == 0.0) return if (avgGain == 0.0) 50.0 else 100.0
        val rs = avgGain / avgLoss
        return 100.0 - 100.0 / (1.0 + rs)
    }
}
