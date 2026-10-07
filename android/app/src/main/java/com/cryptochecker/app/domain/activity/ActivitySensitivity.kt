package com.cryptochecker.app.domain.activity

import kotlin.math.abs

/**
 * Empfindlichkeit von «Ungewöhnliche Aktivität» (Einstellung «Empfindlichkeit»).
 * [factor] skaliert alle ⚡-Schwellen (Kurs-z, Mindestbewegung, Volumen, Funding,
 * Open Interest) — grösser = strenger, also weniger Meldungen. Gilt an EINER Stelle
 * ([SignalThresholds.severityOf]) für Karte, ⚡ an den Zeilen und Benachrichtigungen.
 */
enum class ActivitySensitivity(
    val factor: Double,
    /** Höchstens so viele Coins in der Karte «Hier passiert gerade etwas»; null = alle. */
    val maxCardCoins: Int?,
) {
    /** Strenger: Schwellen ×1,5, in der Karte nur die 3 stärksten. */
    LESS(1.5, 3),

    /** Bisherige Schwellen (Standard). */
    NORMAL(1.0, null),

    /** Lockerer: Schwellen ×0,75. */
    MORE(0.75, null),
    ;

    companion object {
        /** Gespeicherter Name; unbekannt oder fehlend (ältere Sicherung) = NORMAL. */
        fun fromName(name: String?): ActivitySensitivity = entries.firstOrNull { it.name == name } ?: NORMAL
    }
}

/** ⚡-Schwellen einer Empfindlichkeit; [NORMAL] = die Werte aus [ActivityAnalyzer]. */
data class SignalThresholds(
    val priceZ: Double,
    val priceMinMovePercent: Double,
    val priceStrongZ: Double,
    val priceStrongMovePercent: Double,
    val volumeRatio: Double,
    val volumeStrongRatio: Double,
    val fundingPercent: Double,
    val fundingStrongPercent: Double,
    val oiPercent: Double,
    val oiStrongPercent: Double,
) {
    /** Alle Schwellen mal [factor]. */
    fun scaled(factor: Double): SignalThresholds = if (factor == 1.0) this else SignalThresholds(
        priceZ = priceZ * factor,
        priceMinMovePercent = priceMinMovePercent * factor,
        priceStrongZ = priceStrongZ * factor,
        priceStrongMovePercent = priceStrongMovePercent * factor,
        volumeRatio = volumeRatio * factor,
        volumeStrongRatio = volumeStrongRatio * factor,
        fundingPercent = fundingPercent * factor,
        fundingStrongPercent = fundingStrongPercent * factor,
        oiPercent = oiPercent * factor,
        oiStrongPercent = oiStrongPercent * factor,
    )

    /**
     * Stärke eines (möglichen) Signals unter diesen Schwellen; null = nicht auffällig.
     * Werte wie in [ActivitySignal] (Kurs: value = Bewegung %, factor = |z|).
     */
    fun severityOf(signal: ActivitySignal): SignalSeverity? {
        val value = abs(signal.value)
        // Ungültige Werte (NaN) nie als auffällig werten — wie früher die direkten Vergleiche
        if (value.isNaN() || signal.factor?.isNaN() == true) return null
        return when (signal.kind) {
            SignalKind.PRICE_MOVE -> {
                // Ältere gespeicherte Signale ohne z: nur nach der Bewegung beurteilen
                val z = signal.factor?.let { abs(it) }
                if ((z != null && z < priceZ) || value < priceMinMovePercent) null
                else if ((z != null && z >= priceStrongZ) || value >= priceStrongMovePercent) SignalSeverity.STRONG
                else SignalSeverity.NOTABLE
            }
            SignalKind.VOLUME_SPIKE -> grade(signal.value, volumeRatio, volumeStrongRatio)
            SignalKind.OPEN_INTEREST_JUMP -> grade(value, oiPercent, oiStrongPercent)
            SignalKind.FUNDING_EXTREME -> grade(value, fundingPercent, fundingStrongPercent)
        }
    }

    private fun grade(value: Double, notable: Double, strong: Double): SignalSeverity? = when {
        value >= strong -> SignalSeverity.STRONG
        value >= notable -> SignalSeverity.NOTABLE
        else -> null
    }

    companion object {
        val NORMAL = SignalThresholds(
            priceZ = ActivityAnalyzer.SIGNAL_PRICE_Z,
            priceMinMovePercent = ActivityAnalyzer.SIGNAL_PRICE_MIN_MOVE_PERCENT,
            priceStrongZ = ActivityAnalyzer.SIGNAL_PRICE_STRONG_Z,
            priceStrongMovePercent = ActivityAnalyzer.SIGNAL_PRICE_STRONG_MOVE_PERCENT,
            volumeRatio = ActivityAnalyzer.SIGNAL_VOLUME_RATIO,
            volumeStrongRatio = ActivityAnalyzer.SIGNAL_VOLUME_STRONG_RATIO,
            fundingPercent = ActivityAnalyzer.SIGNAL_FUNDING_PERCENT,
            fundingStrongPercent = ActivityAnalyzer.SIGNAL_FUNDING_STRONG_PERCENT,
            oiPercent = ActivityAnalyzer.SIGNAL_OI_PERCENT,
            oiStrongPercent = ActivityAnalyzer.SIGNAL_OI_STRONG_PERCENT,
        )

        fun of(sensitivity: ActivitySensitivity): SignalThresholds = NORMAL.scaled(sensitivity.factor)
    }
}
