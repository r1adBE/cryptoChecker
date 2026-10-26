package com.cryptochecker.app.domain.market

/**
 * Abstand zwischen Geld (Kauf) und Brief (Verkauf) in Prozent der Mitte. Ab [WIDE_PERCENT] zeigt das
 * Kursblatt einen Hinweis: Wer jetzt kauft oder verkauft, zahlt deutlich mehr bzw. bekommt deutlich
 * weniger als der letzte Kurs vermuten lässt (dünner Markt). Nur im Blatt, nie in der Zeile.
 * Reine Logik, wie `BidAskSpread.swift`.
 */
object BidAskSpread {
    /** Ab diesem Abstand (in % der Mitte) gilt er als gross. */
    const val WIDE_PERCENT = 2.0

    /** Abstand in % der Mitte; null, wenn Geld oder Brief fehlen, nicht endlich oder verkehrt sind. */
    fun percentOfMid(bid: Double, ask: Double): Double? {
        if (!bid.isFinite() || !ask.isFinite() || bid <= 0.0 || ask <= 0.0 || ask < bid) return null
        val mid = (ask + bid) / 2
        return (ask - bid) / mid * 100
    }

    /** Abstand, wenn er gross ist (über [WIDE_PERCENT]); sonst null. */
    fun widePercent(bid: Double, ask: Double): Double? =
        percentOfMid(bid, ask)?.takeIf { it > WIDE_PERCENT }
}
