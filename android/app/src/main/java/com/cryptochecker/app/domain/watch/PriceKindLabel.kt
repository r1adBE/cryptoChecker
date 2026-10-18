package com.cryptochecker.app.domain.watch

/**
 * Wie das Aktionsblatt den Kurs benennt, wenn er kein letzter Handel ist (reine Logik, wie `PriceKindLabel.swift`):
 *  - [MARK]: Mark-Preis, weil kein Mittelkurs kam (Hyperliquid bei dünnem Orderbuch) — geht allem vor, denn darauf
 *    lösen auch keine Alarme aus.
 *  - [MID]: Börse meldet den Mittelkurs ([com.cryptochecker.marketdata.model.Market.priceIsMid]).
 *  - [HOURLY]: Schluss der jüngsten Stundenkerze ([com.cryptochecker.marketdata.model.Market.priceIsHourlyClose], WOO X Spot).
 * null = gewöhnlicher letzter Kurs, kein Zusatz.
 */
enum class PriceKindLabel {
    MARK, MID, HOURLY;

    companion object {
        fun of(priceIsMid: Boolean, markFallback: Boolean, hourlyClose: Boolean): PriceKindLabel? = when {
            markFallback -> MARK
            priceIsMid -> MID
            hourlyClose -> HOURLY
            else -> null
        }
    }
}
