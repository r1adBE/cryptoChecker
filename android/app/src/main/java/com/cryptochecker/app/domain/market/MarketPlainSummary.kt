package com.cryptochecker.app.domain.market

import kotlin.math.abs

/**
 * «Kurz gesagt» oben im Markt-Tab: höchstens drei Sätze in Alltagssprache aus Daten, die der Tab
 * ohnehin hat — Richtung des Gesamtmarkts, Stimmung, was heraussticht. Reine Regeln ohne Android
 * und ohne Netz; die Sätze macht die Oberfläche (übersetzbar). Wie `MarketPlainSummary.swift` (iOS).
 *
 * Grundsätze: nur beschreiben, was die Daten zeigen (keine Prognose, keine Empfehlung), neutrale
 * Wörter für die Richtung, und lieber gar kein Satz als einer auf fehlenden oder veralteten Daten
 * («veraltet» wie die Nebenzeilen der Karten: [DataFreshness.isStale]).
 */

/**
 * Eingaben; was fehlt, ist null.
 *
 * @param pulseMarketChange24h Veränderung der Marktkapitalisierung aller Coins über 24 h in %
 *   (CoinGecko `/global`, mit dem Puls geholt), Stand [pulseStamp]
 * @param globalMarketChange24h derselbe Wert aus der Karte «Krypto-Markt» (eigener Abruf, Stand
 *   [globalStamp]) — Ersatz, wenn der Puls ihn nicht hat
 * @param fearGreedTime Zeitpunkt des Index-Werts selbst (alternative.me: Tagesbeginn); null = unbekannt
 * @param unusual «Heute auffällig», Stand [unusualStamp]
 */
data class PlainSummaryInput(
    val now: Long,
    val pulseMarketChange24h: Double? = null,
    val pulseStamp: DataStamp? = null,
    val globalMarketChange24h: Double? = null,
    val globalStamp: DataStamp? = null,
    val fearGreed: Int? = null,
    val fearGreedTime: Long? = null,
    val fearGreedStamp: DataStamp? = null,
    val unusual: UnusualReport? = null,
    val unusualStamp: DataStamp? = null,
)

/** Richtung des Gesamtmarkts über 24 h. */
enum class PlainDirectionKind { FLAT, UP_SLIGHT, UP_CLEAR, DOWN_SLIGHT, DOWN_CLEAR }

/** Satz 1: Richtung samt Veränderung in % (mit Vorzeichen). */
data class PlainDirection(val kind: PlainDirectionKind, val changePercent: Double)

/** Stimmung nach den Stufen der Fear-&-Greed-Karte. */
enum class PlainMoodKind { EXTREME_FEAR, FEAR, NEUTRAL, GREED, EXTREME_GREED }

/** Satz 2: Stimmung samt Index-Wert 0–100. */
data class PlainMood(val kind: PlainMoodKind, val value: Int)

/** Was heraussticht: die auffälligste Tatsache der obersten Zeile von «Heute auffällig» — oder nichts. */
enum class PlainStandoutKind { ABOVE_BTC, BELOW_BTC, AGAINST_BTC, VOLUME, FUTURES_UP, FUTURES_DOWN, QUIET }

/**
 * Satz 3. Bei [PlainStandoutKind.QUIET] sind alle Felder ausser [kind] leer.
 *
 * @param coin Name des Coins (sonst sein Symbol)
 * @param change24h 24-h-Veränderung des Coins in % (für die Vergleiche mit Bitcoin)
 * @param btc24h 24-h-Veränderung von Bitcoin in % (für die Vergleiche mit Bitcoin)
 * @param volumeRatio Umsatz ÷ üblich (nur [PlainStandoutKind.VOLUME])
 */
data class PlainStandout(
    val kind: PlainStandoutKind,
    val coin: String? = null,
    val change24h: Double? = null,
    val btc24h: Double? = null,
    val volumeRatio: Double? = null,
)

/** Die drei Sätze; null = Satz entfällt. Alle null = der Block entfällt. */
data class PlainSummary(
    val direction: PlainDirection?,
    val mood: PlainMood?,
    val standout: PlainStandout?,
) {
    val isEmpty: Boolean get() = direction == null && mood == null && standout == null
}

object MarketPlainSummary {

    /** Darunter «kaum verändert» (Betrag in %). */
    const val FLAT_BELOW_PERCENT = 1.0

    /** Ab hier «deutlich» statt «etwas» (Betrag in %). */
    const val CLEAR_FROM_PERCENT = 3.0

    /**
     * Der Index-Wert selbst gilt so lange (er erscheint einmal am Tag mit dem Tagesbeginn als
     * Zeitpunkt; zwei Tage decken Zeitzone und späte Veröffentlichung ab). Älter: Anbieter hängt.
     */
    const val FEAR_GREED_MAX_AGE_MILLIS = 48 * 60 * 60_000L

    fun evaluate(input: PlainSummaryInput): PlainSummary = PlainSummary(
        direction = direction(input),
        mood = mood(input),
        standout = standout(input),
    )

    /** Satz 1 aus dem Puls, sonst aus der Karte «Krypto-Markt» — nur ein gültiger, frischer Wert zählt. */
    fun direction(input: PlainSummaryInput): PlainDirection? {
        val change = usable(input.pulseMarketChange24h, input.pulseStamp, input.now)
            ?: usable(input.globalMarketChange24h, input.globalStamp, input.now)
            ?: return null
        return PlainDirection(directionKind(change), change)
    }

    fun directionKind(change: Double): PlainDirectionKind {
        val size = abs(change)
        return when {
            size < FLAT_BELOW_PERCENT -> PlainDirectionKind.FLAT
            change > 0 -> if (size >= CLEAR_FROM_PERCENT) PlainDirectionKind.UP_CLEAR else PlainDirectionKind.UP_SLIGHT
            else -> if (size >= CLEAR_FROM_PERCENT) PlainDirectionKind.DOWN_CLEAR else PlainDirectionKind.DOWN_SLIGHT
        }
    }

    /** Satz 2: nur mit frischem Abruf und einem Index-Wert, der nicht älter als zwei Tage ist. */
    fun mood(input: PlainSummaryInput): PlainMood? {
        val value = input.fearGreed?.takeIf { it in 0..100 } ?: return null
        if (!isFresh(input.fearGreedStamp, input.now)) return null
        val valueTime = input.fearGreedTime
        if (valueTime != null && input.now - valueTime > FEAR_GREED_MAX_AGE_MILLIS) return null
        return PlainMood(moodKind(value), value)
    }

    /** Gleiche Stufen wie die Farbskala der Fear-&-Greed-Karte (`MarketScalePalette.level`). */
    fun moodKind(value: Int): PlainMoodKind = when {
        value < 25 -> PlainMoodKind.EXTREME_FEAR
        value < 45 -> PlainMoodKind.FEAR
        value <= 55 -> PlainMoodKind.NEUTRAL
        value <= 75 -> PlainMoodKind.GREED
        else -> PlainMoodKind.EXTREME_GREED
    }

    /**
     * Satz 3 aus «Heute auffällig»: oberste Zeile (die Karte sortiert schon nach Auffälligkeit), ihre
     * stärkste Tatsache. Leerer frischer Bericht → «nichts sticht heraus» (das ist eine Tatsache);
     * fehlend oder veraltet → kein Satz.
     */
    fun standout(input: PlainSummaryInput): PlainStandout? {
        val report = input.unusual ?: return null
        if (!isFresh(input.unusualStamp, input.now)) return null
        val row = report.rows.firstOrNull() ?: return PlainStandout(PlainStandoutKind.QUIET)
        val coin = row.name.trim().ifEmpty { row.symbol }
        val fact = row.fact
        return when (fact.kind) {
            UnusualFactKind.STRONGER_THAN_BTC -> versusBtc(PlainStandoutKind.ABOVE_BTC, coin, row, report)
            UnusualFactKind.WEAKER_THAN_BTC -> versusBtc(PlainStandoutKind.BELOW_BTC, coin, row, report)
            UnusualFactKind.AGAINST_MARKET -> versusBtc(PlainStandoutKind.AGAINST_BTC, coin, row, report)
            UnusualFactKind.VOLUME -> fact.volumeRatio?.takeIf { it.isFinite() && it > 0.0 }
                ?.let { PlainStandout(PlainStandoutKind.VOLUME, coin = coin, volumeRatio = it) }
            UnusualFactKind.FUNDING_HIGH -> PlainStandout(PlainStandoutKind.FUTURES_UP, coin = coin)
            UnusualFactKind.FUNDING_NEGATIVE -> PlainStandout(PlainStandoutKind.FUTURES_DOWN, coin = coin)
        }
    }

    /** Vergleich mit Bitcoin nur mit beiden Werten (ohne BTC-Wert gäbe es diese Tatsache gar nicht). */
    private fun versusBtc(kind: PlainStandoutKind, coin: String, row: UnusualRow, report: UnusualReport): PlainStandout? {
        val btc = report.btc24h?.takeIf { it.isFinite() } ?: return null
        val change = row.change24h.takeIf { it.isFinite() } ?: return null
        return PlainStandout(kind, coin = coin, change24h = change, btc24h = btc)
    }

    /** Wert nur, wenn er gültig ist und sein Stand bekannt und nicht veraltet. */
    private fun usable(value: Double?, stamp: DataStamp?, now: Long): Double? =
        value?.takeIf { it.isFinite() && isFresh(stamp, now) }

    /** Frisch genug für einen Satz: Stand bekannt und nicht veraltet (wie die Nebenzeilen der Karten). */
    fun isFresh(stamp: DataStamp?, now: Long): Boolean =
        stamp != null && stamp.savedAt > 0L && !DataFreshness.isStale(stamp.savedAt, now, stamp.ttlMillis)
}
