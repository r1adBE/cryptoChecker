package com.cryptochecker.app.domain.activity

import kotlin.math.abs

/**
 * Prüft Kerzenreihen, bevor «Warum bewegt sich das?» und «Ungewöhnliche Aktivität»
 * damit rechnen. Eine Quelle kann ein Paar noch führen, obwohl dort nicht mehr
 * gehandelt wird (z. B. XMRUSDT im Binance-Spot nach dem Delisting: die Kerzen enden
 * beim Delisting). Mit solchen Reihen wären 1h/24h immer 0.00 % und die Einordnung
 * falsch — sie zählen deshalb als «keine Daten». Reines Kotlin, testbar.
 */
object CandleSeries {

    /** Ergebnis der Prüfung. */
    enum class Status { OK, MISSING, STALE, FLAT, NO_VOLUME }

    /** Die letzte (laufende) Stundenkerze darf höchstens so alt sein (Uhrabweichung eingerechnet). */
    const val STALE_MILLIS = 2 * ActivityAnalyzer.HOUR_MILLIS

    /** Geprüftes Fenster für «völlig flach» / «kein Umsatz»: 24 Stunden plus die laufende. */
    const val WINDOW = ActivityAnalyzer.WINDOW_HOURS + 1

    /** Ab dieser 24-h-Veränderung im Ticker gilt ein völlig flacher Verlauf als unplausibel. */
    const val TICKER_MOVE_PERCENT = 0.5

    /** Relative Toleranz für «gleicher Schlusskurs». */
    private const val FLAT_TOLERANCE = 1e-9

    /** Untergrenze für [isLive], damit dünn gehandelte Paare mit Lücken nicht herausfallen. */
    const val LIVE_MIN_MILLIS = 24 * ActivityAnalyzer.HOUR_MILLIS

    /**
     * Stundenreihe für die Auswertung: leer/fehlend, veraltet (letzte Kerze älter als
     * [STALE_MILLIS]), ohne jeden Umsatz oder völlig flach, obwohl der Ticker
     * ([tickerChange24h], %) eine Bewegung zeigt.
     */
    fun status(candles: List<HourCandle>?, now: Long, tickerChange24h: Double?): Status {
        if (candles == null || candles.size < 2) return Status.MISSING
        val last = candles.last()
        if (now - last.openTime > STALE_MILLIS) return Status.STALE
        val window = candles.takeLast(WINDOW)
        if (window.all { it.volume <= 0.0 }) return Status.NO_VOLUME
        val ticker = tickerChange24h?.takeIf { it.isFinite() }
        if (ticker != null && abs(ticker) >= TICKER_MOVE_PERCENT && isFlat(window)) return Status.FLAT
        return Status.OK
    }

    /** [candles], wenn [status] OK ist, sonst null («keine Daten»). */
    fun usable(candles: List<HourCandle>?, now: Long, tickerChange24h: Double?): List<HourCandle>? =
        candles.takeIf { status(it, now, tickerChange24h) == Status.OK }

    /** Alle Schlusskurse gleich (innerhalb einer winzigen relativen Toleranz). */
    fun isFlat(candles: List<HourCandle>): Boolean {
        if (candles.isEmpty()) return false
        val min = candles.minOf { it.close }
        val max = candles.maxOf { it.close }
        return max - min <= abs(max) * FLAT_TOLERANCE
    }

    /**
     * Für die Ausweich-Kette der Kerzenquellen: liefert eine Quelle noch laufende Daten?
     * Nein, wenn die letzte Kerze älter als vier Intervalle (mindestens 24 h) ist —
     * dann ist das Paar dort nicht mehr gehandelt und die nächste Quelle ist dran.
     */
    fun isLive(candles: List<HourCandle>?, intervalMillis: Long, now: Long): Boolean {
        val last = candles?.lastOrNull() ?: return false
        return now - last.openTime <= maxOf(4 * intervalMillis, LIVE_MIN_MILLIS)
    }

    /**
     * 24-h-Veränderung im Blatt: die des Tickers (dieselbe wie Pille und Merkliste),
     * sonst die aus den Kerzen.
     */
    fun change24h(tickerChange24h: Double?, candleChange24h: Double?): Double? =
        tickerChange24h?.takeIf { it.isFinite() } ?: candleChange24h?.takeIf { it.isFinite() }

    /**
     * Welches beobachtete Paar ein Coin aus «Heute auffällig» öffnet: zuerst Spot
     * (wie die Karte, Binance-Spot …USDT), dabei USDT/USD vor anderen Quotes; sonst
     * ein Perpetual, dann übrige Kontrakte. null = Coin nicht beobachtet.
     */
    fun <T> pickWatch(
        items: List<T>,
        symbol: String,
        base: (T) -> String,
        quote: (T) -> String,
        isSpot: (T) -> Boolean,
        isPerpetual: (T) -> Boolean,
    ): T? {
        val wanted = symbol.trim()
        return items.filter { base(it).trim().equals(wanted, ignoreCase = true) }
            .minByOrNull { item ->
                val market = when {
                    isSpot(item) -> 0
                    isPerpetual(item) -> 2
                    else -> 4
                }
                val q = quote(item).trim().uppercase()
                market + if (q == "USDT" || q == "USD") 0 else 1
            }
    }
}
