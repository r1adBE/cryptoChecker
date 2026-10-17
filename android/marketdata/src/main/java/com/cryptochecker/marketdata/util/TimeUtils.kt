package com.cryptochecker.marketdata.util

/** Zeitangaben der Börsen in einer Einheit (ms). iOS: `TimeUtils`. */
object TimeUtils {

    private const val SECOND_MS = 1_000L
    private const val YEAR_MS = 365L * 24 * 60 * 60 * SECOND_MS

    /**
     * Zeitangabe einer Börse in ms, egal ob sie in s, ms, µs oder ns kommt — entschieden an der Grösse. Was
     * unter einem Jahr in ms liegt, sind Sekunden; was über 5000 Jahren liegt, wird so oft durch 1000 geteilt,
     * bis es passt (µs einmal, ns wie bei Phemex zweimal). Wie iOS.
     */
    fun parseTimeToMillis(time: Long): Long {
        if (time < YEAR_MS) return time * SECOND_MS
        var millis = time
        while (millis > 5000 * YEAR_MS) millis /= 1000
        return millis
    }
}
