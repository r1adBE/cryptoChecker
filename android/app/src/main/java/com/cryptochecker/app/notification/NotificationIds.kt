package com.cryptochecker.app.notification

/**
 * Mitteilungs-Nummern je Paar bzw. Alarm, ohne Überschneidungen.
 *
 * Nicht nur «Basis + id» (Kurs 100 000, Alarm 200 000, Aktivität 300 000, Portfolio-Alarm 400 000):
 * Ab id 100 000 liefe ein Bereich in den nächsten (Kurs-Meldung von Paar 100 001 = Alarm 1), und
 * sehr grosse ids liefen über Int hinaus. Darum:
 *  - id 0 … 99 999: «Basis + id»;
 *  - sonst (auch negativ): eigener Block je Art ab 1 000 000 000, je [LARGE_SPAN] gross —
 *    getrennt von den kleinen Bereichen, den festen Nummern (< 100 000) und voneinander.
 *    Innerhalb einer Art gleich wären erst ids, die sich um ein Vielfaches von 250 Mio. unterscheiden.
 *
 * Reines Kotlin.
 */
object NotificationIds {

    /** Ab hier gilt der grosse Block der Art. */
    const val SMALL_LIMIT = 100_000L

    /** Beginn der grossen Blöcke. */
    const val LARGE_START = 1_000_000_000L

    /** Grösse eines grossen Blocks (4 Blöcke enden bei 1 999 999 999 < Int.MAX_VALUE). */
    const val LARGE_SPAN = 250_000_000L

    enum class Kind(val smallBase: Long) {
        PRICE(100_000L),
        ALARM(200_000L),
        ACTIVITY(300_000L),
        PORTFOLIO_ALARM(400_000L),
    }

    fun id(kind: Kind, id: Long): Int =
        if (id in 0 until SMALL_LIMIT) (kind.smallBase + id).toInt()
        else (LARGE_START + kind.ordinal * LARGE_SPAN + Math.floorMod(id, LARGE_SPAN)).toInt()

    /** Gehört die Mitteilungs-Nummer [notificationId] zur Art [kind] (kleiner oder grosser Bereich)? */
    fun isKind(kind: Kind, notificationId: Int): Boolean {
        val n = notificationId.toLong()
        val large = LARGE_START + kind.ordinal * LARGE_SPAN
        return n in kind.smallBase until kind.smallBase + SMALL_LIMIT || n in large until large + LARGE_SPAN
    }
}
