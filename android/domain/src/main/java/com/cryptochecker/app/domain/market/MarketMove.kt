package com.cryptochecker.app.domain.market

import kotlin.math.abs

/**
 * Richtung und Stärke des Markts über 24 h — die eine Stelle, an der «kaum», «leicht»/«etwas» und
 * «deutlich» festgelegt sind. Schlagzeile und Leitsatz des Pulses ([CryptoPulse]) und der Satz zum
 * Gesamtwert in «Kurz gesagt» ([MarketPlainSummary]) nutzen dieselben Schwellen, damit nie «leicht»
 * neben «deutlich» für dieselbe Richtung steht. Reine Regeln; wie `MarketMove.swift` (iOS).
 */

/** Stärke einer 24-h-Veränderung (Betrag in %). */
enum class MarketMoveLevel { FLAT, SLIGHT, CLEAR }

/** Richtung für die Schlagzeile des Pulses. */
enum class MarketMoveDirection { UP, DOWN, MIXED, CALM }

/** Ergebnis von [MarketMoveScale.decide]; [level] nur bei UP/DOWN (SLIGHT oder CLEAR), sonst null. */
data class MarketMove(val direction: MarketMoveDirection, val level: MarketMoveLevel?)

object MarketMoveScale {

    /**
     * Grosswerte (BTC, ETH, SOL) gelten als gleichgerichtet, wenn alle drei betragsmässig über dieser
     * Rauschschwelle in dieselbe Richtung gehen (genau 0.25 zählt nicht).
     */
    const val NOISE_PERCENT = 0.25

    /**
     * Darunter «kaum verändert» bzw. «ruhig». Gleicher Wert wie die Ruhe-Schwelle der
     * Grosswerte und die «kaum»-Schwelle des Gesamtwerts.
     */
    const val FLAT_BELOW_PERCENT = 1.0

    /**
     * Ab hier «deutlich» statt «leicht»/«etwas». Gemessen an der Veränderung des Gesamtmarkts
     * (Marktkapitalisierung aller Coins); fehlt sie, am Schnitt von BTC, ETH und SOL. Gewählt ist der
     * Wert des Gesamtwert-Satzes (3 %), nicht die Einzelcoin-Schwelle 1.5 %: Der Gesamtmarkt
     * schwankt weniger als einzelne Coins, mit 1.5 % hiesse etwa jeder zweite Tag «deutlich».
     */
    const val CLEAR_FROM_PERCENT = 3.0

    /** Ab diesem Anteil steigender bzw. fallender Coins der Top-Liste neigt die Breite in eine Richtung. */
    const val BREADTH_LEAN_SHARE = 0.6

    fun level(change: Double): MarketMoveLevel {
        val size = abs(change)
        return when {
            size < FLAT_BELOW_PERCENT -> MarketMoveLevel.FLAT
            size >= CLEAR_FROM_PERCENT -> MarketMoveLevel.CLEAR
            else -> MarketMoveLevel.SLIGHT
        }
    }

    /**
     * Schlagzeile aus Grosswerten, Gesamtmarkt ([marketChange], null = unbekannt) und Breite
     * ([breadth], null = unbekannt). Reihenfolge:
     * 1. Ruhig: alle drei Grosswerte und der Gesamtmarkt (falls bekannt) unter [FLAT_BELOW_PERCENT].
     * 2. Grosswerte gleichgerichtet (über [NOISE_PERCENT]) → deren Richtung, ausser der Gesamtmarkt
     *    liegt mindestens [FLAT_BELOW_PERCENT] in der Gegenrichtung oder die Breite neigt (ab
     *    [BREADTH_LEAN_SHARE]) in die Gegenrichtung → gemischt. Die Schlagzeile folgt so dem ganzen
     *    Markt und widerspricht nie dem Satz zum Gesamtwert.
     * 3. Sonst entscheidet die Breite (ab [BREADTH_LEAN_SHARE]); widerspricht ihr der Gesamtmarkt
     *    (mindestens [FLAT_BELOW_PERCENT] in die Gegenrichtung) oder liegt sie um 50 % → gemischt.
     *    Ohne Breite: Gesamtmarkt ab [FLAT_BELOW_PERCENT], sonst gemischt.
     * Stärke: [level] des Gesamtmarkts (sonst Schnitt der Grosswerte); «deutlich» nur, wenn dieser
     * Wert in dieselbe Richtung zeigt.
     */
    fun decide(btc: Double, eth: Double, sol: Double, marketChange: Double?, breadth: PulseBreadth?): MarketMove {
        val big = listOf(btc, eth, sol)
        val market = marketChange?.takeIf { it.isFinite() }
        val calmMarket = market == null || abs(market) < FLAT_BELOW_PERCENT
        if (calmMarket && big.all { abs(it) < FLAT_BELOW_PERCENT }) return MarketMove(MarketMoveDirection.CALM, null)

        val sign = direction(big, market, breadth) ?: return MarketMove(MarketMoveDirection.MIXED, null)
        val value = market ?: big.average()
        val sameWay = if (sign > 0) value > 0.0 else value < 0.0
        val clear = sameWay && level(value) == MarketMoveLevel.CLEAR
        val strength = if (clear) MarketMoveLevel.CLEAR else MarketMoveLevel.SLIGHT
        return MarketMove(if (sign > 0) MarketMoveDirection.UP else MarketMoveDirection.DOWN, strength)
    }

    /** +1 steigend, −1 fallend, null = gemischt. */
    private fun direction(big: List<Double>, market: Double?, breadth: PulseBreadth?): Int? {
        val marketVote = when {
            market == null -> 0
            market >= FLAT_BELOW_PERCENT -> 1
            market <= -FLAT_BELOW_PERCENT -> -1
            else -> 0
        }
        val lean = breadth?.takeIf { it.total > 0 }?.let { breadthVote(it) }
        val bigVote = when {
            big.all { it > NOISE_PERCENT } -> 1
            big.all { it < -NOISE_PERCENT } -> -1
            else -> 0
        }
        // Grosswerte gleichgerichtet: gemischt, sobald Gesamtmarkt oder Breite klar dagegen stehen
        if (bigVote != 0) return if (marketVote == -bigVote || lean == -bigVote) null else bigVote
        if (lean == null) return marketVote.takeIf { it != 0 }
        return if (lean == 0 || marketVote == -lean) null else lean
    }

    /** +1 / −1 ab [BREADTH_LEAN_SHARE] steigend bzw. fallend, sonst 0. */
    private fun breadthVote(breadth: PulseBreadth): Int = when {
        breadth.up.toDouble() / breadth.total >= BREADTH_LEAN_SHARE - EPS -> 1
        breadth.down.toDouble() / breadth.total >= BREADTH_LEAN_SHARE - EPS -> -1
        else -> 0
    }

    /** Gleitkomma-Toleranz für Anteile (18 / 30 gegen 0.6). */
    private const val EPS = 1e-9
}
