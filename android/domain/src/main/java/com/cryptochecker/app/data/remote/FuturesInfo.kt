package com.cryptochecker.app.data.remote

/** Funding Rate und Open Interest eines Perpetual-Kontrakts. */
data class FuturesInfo(
    /** Aktuelle Funding Rate in Prozent (z. B. 0.01 = 0,01 %). */
    val fundingRatePercent: Double?,
    /** Zeitpunkt der nächsten Funding-Zahlung (Epoch-ms). */
    val nextFundingTime: Long?,
    /** Offene Positionen in USD. */
    val openInterestUsd: Double?,
    /** Woher die Daten stammen ([FuturesSources]: «Binance Futures», «Bybit», «OKX», «WOO X Futures» …). */
    val source: String,
    /**
     * Abrechnungsintervall dieses Kontrakts in Stunden, wie die Börse es meldet (Binance 1/4/8,
     * Bybit, OKX, Bitget, MEXC); null = die Antwort nennt es nicht.
     */
    val fundingIntervalHours: Int? = null,
) {
    /**
     * Wirksames Intervall: gemeldet, sonst das feste der Quelle ([FuturesSources.fundingIntervalHours]);
     * null = unbekannt (Quelle mit Intervall je Kontrakt, das gerade nicht geladen werden konnte) — dann
     * keine Umrechnung auf 8 Std. und kein Funding-Alarm.
     */
    val intervalHours: Int? get() = fundingIntervalHours ?: FuturesSources.fundingIntervalHours(source)
}
