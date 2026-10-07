package com.cryptochecker.app.domain.market

import java.time.LocalDate

/** Fear & Greed Index (0 = extreme Angst, 100 = extreme Gier). */
data class FearGreed(
    val value: Int,
    val yesterday: Int?,
    val weekAgo: Int?,
    val monthAgo: Int?,
)

/** Marktanteile nach Marktkapitalisierung, in Prozent. */
data class Dominance(val btc: Double, val eth: Double?)

/**
 * Gesamter Kryptomarkt (CoinGecko `/global`): Marktkapitalisierung und 24-Stunden-
 * Volumen je Währung (Schlüssel in Kleinbuchstaben, z. B. «usd», «chf»), dazu die
 * Veränderung der Marktkapitalisierung in 24 Stunden (in USD gerechnet).
 */
data class MarketTotals(
    val marketCap: Map<String, Double>,
    val volume: Map<String, Double>,
    val changePercent24h: Double?,
) {
    /** Werte in einer Währung. */
    data class Values(val currency: String, val marketCap: Double, val volume: Double)

    /**
     * In [preferred] (z. B. Umrechnungswährung «CHF»), wenn CoinGecko beide Werte
     * dafür liefert, sonst in USD; null, wenn auch USD fehlt.
     */
    fun valuesIn(preferred: String?): Values? {
        fun valuesFor(code: String): Values? {
            val key = code.trim().lowercase()
            if (key.isEmpty()) return null
            val cap = marketCap[key]?.takeIf { it.isFinite() && it > 0.0 } ?: return null
            val vol = volume[key]?.takeIf { it.isFinite() && it >= 0.0 } ?: return null
            return Values(key.uppercase(), cap, vol)
        }
        return preferred?.let { valuesFor(it) } ?: valuesFor("usd")
    }
}

/** Antwort von CoinGecko `/global`: Dominanz und, falls vorhanden, die Markt-Summen. */
data class GlobalMarket(val dominance: Dominance, val totals: MarketTotals?)

/**
 * Altcoin-Saison: Wie viele grosse Altcoins haben Bitcoin in 90 Tagen
 * geschlagen? Ab 75 % gilt es als Altcoin-, bis 25 % als Bitcoin-Saison.
 */
data class AltSeason(val outperformers: Int, val total: Int) {
    val index: Int get() = if (total == 0) 0 else outperformers * 100 / total
}

/**
 * Markanter Punkt eines Zyklus (Hoch oder Tief danach).
 * [change] als Anteil: beim Hoch gegenüber dem Halving-Tag, beim Tief gegenüber dem Hoch.
 */
data class CycleMarker(
    val day: Int,
    val date: LocalDate,
    val priceUsd: Double,
    val multiple: Double,
    val change: Double,
)

/**
 * Kurs als Vielfaches des Halving-Tageskurses, je Zyklus.
 * [top] = höchster Kurs der ersten 1000 Tage, [bottom] = tiefster Kurs danach,
 * nur wenn er mindestens 30 % unter dem Hoch liegt.
 */
data class CycleSeries(
    val halving: LocalDate,
    val points: List<Pair<Int, Double>>,
    val top: CycleMarker? = null,
    val bottom: CycleMarker? = null,
    /** Doppel-Top/-Bottom: zweites Hoch bzw. Tief nahe am ersten (siehe [CycleExtremes]). */
    val secondTop: CycleMarker? = null,
    val secondBottom: CycleMarker? = null,
)

data class CycleHistory(val series: List<CycleSeries>)
