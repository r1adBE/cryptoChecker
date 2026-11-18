package com.cryptochecker.app.domain.convert

import kotlinx.coroutines.flow.Flow

/** «≈ Umrechnung» von der Quote-Währung eines Paars in eine Zielwährung (Umsetzung: `CurrencyConverter`). */
interface QuoteConversion {
    /** Faktor Quote → Ziel; gleiche Währung = 1.0, null wenn ein Kurs fehlt. */
    suspend fun rate(quote: String, target: String): Double?

    /** Faktoren für mehrere Quote-Währungen; fehlende bleiben weg. Schlüssel in Grossbuchstaben. */
    suspend fun rates(quotes: Collection<String>, target: String): Map<String, Double>

    /** Faktor nur aus den Zwischenspeichern, ohne Netz. */
    fun cachedRate(quote: String, target: String): Double?

    fun cachedRates(quotes: Collection<String>, target: String): Map<String, Double>

    /** Alter der beteiligten Devisenkurse je Quote-Währung, nur veraltete. */
    fun staleFxTimes(quotes: Collection<String>, target: String, now: Long = System.currentTimeMillis()): Map<String, Long>

    /** Angenommene bzw. unsichere USDT-Umrechnung je Quote-Währung. */
    fun usdtNotes(
        quotes: Collection<String>,
        target: String,
        now: Long = System.currentTimeMillis(),
    ): Map<String, FiatAlarmGuard.UsdtState>

    /** Gibt es eine frische Messung USDT/USD? */
    fun usdtMeasured(): Flow<Boolean>

    /** Live-Kurs USDT/USD; null = keiner (USDT gilt 1). */
    fun liveUsdtUsd(): Double?
}
