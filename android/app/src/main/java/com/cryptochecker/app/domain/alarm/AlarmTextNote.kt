package com.cryptochecker.app.domain.alarm

/**
 * Zusatz am Ende eines Alarmtexts («… · Binance (Kerzen)», «… · USDT = 1 angenommen»): [candleSource] = Börse fremder
 * Stundenkerzen beim Volumen-Spike ([com.cryptochecker.app.domain.watch.DayChange.foreignCandleSource]); [usdtAssumed] =
 * Schwelle in anderer Währung, umgerechnet mit USDT = 1 (keine Messung USDT/USD). Wie iOS `AlarmTexts.withNote`.
 */
data class AlarmTextNote(val candleSource: String? = null, val usdtAssumed: Boolean = false) {
    /** Nichts anzuhängen. */
    val isEmpty: Boolean get() = candleSource.isNullOrBlank() && !usdtAssumed

    companion object {
        /** Teile mit « · » an [body] hängen; leere Teile fallen weg. */
        fun join(body: String, parts: List<String?>): String =
            (listOf(body) + parts.filterNot { it.isNullOrBlank() }.map { it!! }).joinToString(" · ")

        /** Notiz oder null, wenn nichts anzuhängen ist. */
        fun of(candleSource: String? = null, usdtAssumed: Boolean = false): AlarmTextNote? =
            AlarmTextNote(candleSource, usdtAssumed).takeUnless { it.isEmpty }
    }
}
