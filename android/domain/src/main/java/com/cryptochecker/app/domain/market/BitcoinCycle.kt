package com.cryptochecker.app.domain.market

import java.time.LocalDate

/** Letztes und — geschätzt — nächstes Bitcoin-Halving. Reines Datum, ohne Phase oder Fortschritt. */
data class HalvingInfo(
    val lastHalving: LocalDate,
    /** Schätzung: letztes Halving plus [BitcoinHalving.INTERVAL_DAYS]; die App kennt die Blockhöhe nicht. */
    val nextHalvingEstimate: LocalDate,
)

/**
 * Bitcoin-Halving-Termine. Rein kalenderbasiert, ohne Internet. Die App liest keine
 * Blockhöhe ein; das nächste Halving ist darum nur grob geschätzt und wird als Monat
 * («voraussichtlich ~April 2028 (geschätzt)») gezeigt. Wie `BitcoinHalving` (iOS).
 */
object BitcoinHalving {

    /** Tatsächliche Halving-Termine. */
    val HALVINGS: List<LocalDate> = listOf(
        LocalDate.of(2012, 11, 28),
        LocalDate.of(2016, 7, 9),
        LocalDate.of(2020, 5, 11),
        LocalDate.of(2024, 4, 20),
    )

    /** 210'000 Blöcke à rund 10 Minuten ≈ 1458 Tage — nur für die Schätzung des nächsten Termins. */
    const val INTERVAL_DAYS = 1_458L

    fun info(today: LocalDate = LocalDate.now()): HalvingInfo {
        val last = HALVINGS.lastOrNull { !it.isAfter(today) } ?: HALVINGS.first()

        // Liegt heute schon nach einem geschätzten künftigen Halving, weiterzählen.
        var lastHalving = last
        var next = last.plusDays(INTERVAL_DAYS)
        while (!next.isAfter(today)) {
            lastHalving = next
            next = next.plusDays(INTERVAL_DAYS)
        }
        return HalvingInfo(lastHalving, next)
    }
}

/**
 * Nächstes Halving rechnerisch aus der Blockhöhe: verbleibende Blöcke bis zur nächsten Höhe, die durch
 * 210 000 teilbar ist, mal die mittlere Blockzeit der laufenden Schwierigkeitsperiode (mempool.space).
 * Eine Fortschreibung, keine Zusage — die Blockzeit schwankt.
 */
data class HalvingEstimate(
    val date: LocalDate,
    /** Höhe des nächsten Halvings (z. B. 1 050 000). */
    val height: Int,
    val remainingBlocks: Int,
    /** Mittlere Blockzeit in Sekunden, mit der gerechnet wurde. */
    val avgBlockSeconds: Double,
)

object HalvingMath {
    const val BLOCKS_PER_HALVING = 210_000

    /** null bei ungültigen Werten (Höhe ≤ 0, Blockzeit ausserhalb 1–60 Minuten). */
    fun estimate(tipHeight: Int, avgBlockSeconds: Double, nowMillis: Long, zone: java.time.ZoneId = java.time.ZoneOffset.UTC): HalvingEstimate? {
        if (tipHeight <= 0 || !avgBlockSeconds.isFinite() || avgBlockSeconds < 60.0 || avgBlockSeconds > 3_600.0) return null
        val next = (tipHeight / BLOCKS_PER_HALVING + 1) * BLOCKS_PER_HALVING
        val remaining = next - tipHeight
        val millis = nowMillis + (remaining * avgBlockSeconds * 1000.0).toLong()
        val date = java.time.Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()
        return HalvingEstimate(date, next, remaining, avgBlockSeconds)
    }
}

