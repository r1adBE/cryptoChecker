package com.cryptochecker.app.domain.watch

/**
 * Wie das Aktionsblatt den Kurs benennt, wenn er kein letzter Handel ist (reine Logik, wie iOS `PriceKindLabel` in `MarkFallback.swift`):
 *  - [MARK]: Mark-Preis, weil kein Mittelkurs kam (Hyperliquid bei dünnem Orderbuch) — geht allem vor, denn darauf
 *    lösen auch keine Alarme aus.
 *  - [MID]: Börse meldet den Mittelkurs ([com.cryptochecker.marketdata.model.Exchange.priceIsMid]).
 *  - [HOURLY]: Schluss der jüngsten Stundenkerze ([com.cryptochecker.marketdata.model.Exchange.priceIsHourlyClose], WOO X Spot).
 *  - [DAILY_REFERENCE]: täglicher Referenzkurs (EZB, NBU; [com.cryptochecker.marketdata.model.Exchange.priceIsDailyReference]).
 *  - [WEEKLY_REFERENCE]: wöchentlicher Referenzkurs (NBP, [com.cryptochecker.marketdata.model.ReferenceSource.weekly]).
 * null = gewöhnlicher letzter Kurs, kein Zusatz.
 */
enum class PriceKindLabel {
    MARK, MID, HOURLY, DAILY_REFERENCE, WEEKLY_REFERENCE;

    companion object {
        fun of(
            priceIsMid: Boolean,
            markFallback: Boolean,
            hourlyClose: Boolean,
            dailyReference: Boolean = false,
            weeklyReference: Boolean = false,
        ): PriceKindLabel? = when {
            markFallback -> MARK
            priceIsMid -> MID
            hourlyClose -> HOURLY
            dailyReference -> DAILY_REFERENCE
            weeklyReference -> WEEKLY_REFERENCE
            else -> null
        }
    }
}
