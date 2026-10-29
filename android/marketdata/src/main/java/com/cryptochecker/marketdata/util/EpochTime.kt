package com.cryptochecker.marketdata.util

/** Zeitangaben der Börsen in einer Einheit (ms). iOS: `EpochTime`. */
object EpochTime {

    private const val SECOND_MS = 1_000L
    private const val YEAR_MS = 365L * 24 * 60 * 60 * SECOND_MS

    /**
     * Zeitangabe einer Börse in ms, egal ob sie in s, ms, µs oder ns kommt — entschieden an der Grösse. Was
     * unter einem Jahr in ms liegt, sind Sekunden; was über 5000 Jahren liegt, wird so oft durch 1000 geteilt,
     * bis es passt (µs einmal, ns wie bei Phemex zweimal). Wie iOS.
     */
    fun parseTimeToMillis(time: Long): Long {
        // Unplausibel klein (× 1000 liefe über): keine Zeit
        if (time < YEAR_MS) return if (time < Long.MIN_VALUE / SECOND_MS) 0L else time * SECOND_MS
        var millis = time
        while (millis > 5000 * YEAR_MS) millis /= 1000
        return millis
    }

    /** Höchste plausible Sekundenangabe eines Servers (10¹³ s, rund 317 000 Jahre; × 1000 passt sicher in Long). */
    const val MAX_PLAUSIBLE_SECONDS = 10_000_000_000_000L

    /**
     * Sekunden eines Servers (z. B. Kerzen-Beginn, Zeitstempel) → ms; null bei ≤ 0 oder ab [MAX_PLAUSIBLE_SECONDS] —
     * nie ein übergelaufener Wert. Wie iOS `EpochTime.secondsToMillis`; Testfälle `server_values.json`.
     */
    fun secondsToMillis(seconds: Long): Long? = if (seconds in 1 until MAX_PLAUSIBLE_SECONDS) seconds * SECOND_MS else null
}
