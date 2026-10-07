package com.cryptochecker.app.domain.portfolio

import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale
import kotlin.math.abs

/**
 * Momentaufnahme für das Portfolio-Widget: Gesamtwert und Veränderung «heute»
 * in der Umrechnungswährung. [changeAmount]/[changePercent] null = unbekannt
 * (noch keine Vergleichsbasis).
 * Die Angaben ab [totalUsdt] kamen später dazu: Ältere gespeicherte Aufnahmen haben
 * sie nicht (null bzw. leer), das Widget blendet die Teile dann aus.
 */
data class PortfolioSnapshot(
    val total: Double,
    val changeAmount: Double?,
    val changePercent: Double?,
    val currency: String,
    val time: Long,
    /** Keine offenen Positionen (Widget zeigt den Hinweis zum Erfassen). */
    val empty: Boolean,
    /** Gesamtwert in USDT; null = unbekannt (ältere Aufnahme). */
    val totalUsdt: Double? = null,
    /** Die grössten Positionen, absteigend nach Wert (höchstens [PortfolioWidgetMath.MAX_POSITIONS]). */
    val positions: List<PortfolioPosition> = emptyList(),
    /** Weitere offene Positionen ausser [positions]. */
    val otherPositions: Int = 0,
    /** Wertverlauf des heutigen Bestands (Anzeigewährung), aufsteigend nach Zeit. */
    val history: List<PortfolioValuePoint> = emptyList(),
)

/** USDT-Kurse aller Coins zu einem Zeitpunkt. */
data class PriceSample(val time: Long, val prices: Map<String, Double>)

/** Veränderung in USDT und in Prozent. */
data class TodayChange(val amountUsd: Double, val percent: Double?)

/**
 * Rechnung hinter dem Portfolio-Widget (reines Kotlin, testbar).
 *
 * «Heute» = Veränderung über rund die letzten 24 Stunden. Die Portfolio-Kurse der
 * App sind reine Letztkurse ohne 24-h-Veränderung (PortfolioPriceSource holt
 * `ticker/price`). Deshalb merkt sich die App bei jeder Berechnung höchstens alle
 * [SAMPLE_SPACING_MILLIS] die USDT-Kurse aller Coins ([PriceSample], [HISTORY_MILLIS]
 * lang). Vergleichsbasis ist die jüngste Aufnahme, die mindestens
 * [MIN_BASELINE_AGE_MILLIS] alt ist. Gerechnet wird über den HEUTIGEN Bestand:
 * Σ Menge × (Kurs jetzt − Kurs damals) — Käufe und Verkäufe seither zählen also
 * nicht als Gewinn oder Verlust. Coins ohne Kurs in der Basis zählen nicht mit.
 * Ohne Basis (z. B. erste 20 Stunden) bleibt «heute» leer.
 */
object PortfolioSnapshotMath {

    const val MIN_BASELINE_AGE_MILLIS = 20 * 60 * 60_000L
    const val HISTORY_MILLIS = 48 * 60 * 60_000L
    const val SAMPLE_SPACING_MILLIS = 30 * 60_000L

    /**
     * Hängt [sample] an, wenn die letzte Aufnahme mindestens [SAMPLE_SPACING_MILLIS]
     * älter ist. Entfernt Aufnahmen älter als [HISTORY_MILLIS] und solche aus der
     * «Zukunft» (Uhr zurückgestellt). Aufsteigend nach Zeit.
     */
    fun addSample(history: List<PriceSample>, sample: PriceSample): List<PriceSample> {
        if (sample.prices.isEmpty()) return prune(history, sample.time)
        val kept = prune(history, sample.time)
        val last = kept.lastOrNull()
        return if (last == null || sample.time - last.time >= SAMPLE_SPACING_MILLIS) kept + sample else kept
    }

    private fun prune(history: List<PriceSample>, now: Long): List<PriceSample> =
        history.filter { now - it.time in 0..HISTORY_MILLIS }.sortedBy { it.time }

    /** Jüngste Aufnahme, die mindestens 20 h (und höchstens 48 h) alt ist. */
    fun baseline(history: List<PriceSample>, now: Long): PriceSample? =
        history.filter { now - it.time in MIN_BASELINE_AGE_MILLIS..HISTORY_MILLIS }.maxByOrNull { it.time }

    /**
     * Veränderung des heutigen Bestands seit [baseline].
     * @param holdings Menge je Coin (Grossschreibung)
     * @return null, wenn kein Coin in beiden Kursständen vorkommt
     */
    fun todayChange(
        holdings: Map<String, Double>,
        current: Map<String, Double>,
        baseline: Map<String, Double>,
    ): TodayChange? {
        var then = 0.0
        var now = 0.0
        var counted = 0
        for ((coin, amount) in holdings) {
            if (!(amount > 0.0) || amount.isInfinite()) continue
            val p1 = current[coin]?.takeIf { it > 0.0 && !it.isInfinite() } ?: continue
            val p0 = baseline[coin]?.takeIf { it > 0.0 && !it.isInfinite() } ?: continue
            then += amount * p0
            now += amount * p1
            counted++
        }
        if (counted == 0) return null
        val change = now - then
        return TodayChange(change, if (then > 0.0) change / then * 100.0 else null)
    }

    /**
     * Momentaufnahme aus dem heutigen Bestand.
     * @param holdings Menge je offenem Coin
     * @param totalUsd Gesamtwert in USDT (Coins ohne Kurs zählen nicht mit)
     * @param current aktuelle USDT-Kurse
     * @param history bisherige Kursaufnahmen (ohne die aktuelle)
     * @param fxRate USD → [currency]
     * Dazu: Gesamtwert in USDT, die grössten Positionen (Anteil, Veränderung je Coin seit
     * derselben Basis wie «heute») und der Wertverlauf aus den Kursaufnahmen.
     */
    fun snapshot(
        holdings: Map<String, Double>,
        totalUsd: Double,
        current: Map<String, Double>,
        history: List<PriceSample>,
        now: Long,
        fxRate: Double,
        currency: String,
    ): PortfolioSnapshot {
        val open = holdings.filterValues { it > PortfolioCalculator.EPS }
        val base = baseline(history, now)
        val change = base?.let { todayChange(open, current, it.prices) }
        // Wert je Coin wie im Rechner: Menge × aktueller Kurs; ohne Kurs null
        val values = open.mapValues { (coin, amount) -> current[coin]?.takeIf { it > 0.0 }?.let { amount * it } }
        val top = PortfolioWidgetMath.topPositions(
            valuesUsd = values,
            totalUsd = totalUsd,
            fxRate = fxRate,
            changes = PortfolioWidgetMath.coinChanges(current, base?.prices),
        )
        return PortfolioSnapshot(
            total = totalUsd * fxRate,
            changeAmount = change?.amountUsd?.times(fxRate),
            changePercent = change?.percent,
            currency = currency,
            time = now,
            empty = open.isEmpty(),
            totalUsdt = totalUsd,
            positions = top.positions,
            otherPositions = top.others,
            history = PortfolioWidgetMath.valueHistory(open, history, current, now, fxRate),
        )
    }

    /** «+123.45 CHF» / «−12.00 CHF» (echtes Minuszeichen), Tausendertrennung je Sprache. */
    fun signedAmount(value: Double, currency: String, locale: Locale = Locale.getDefault()): String {
        val format = DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(locale))
        val sign = when {
            isZero(value) -> ""
            value > 0 -> "+"
            else -> "−"
        }
        return "$sign${format.format(abs(value))} $currency"
    }

    /** «+1.23%» / «−0.50%»; ±0 ohne Vorzeichen. */
    fun signedPercent(value: Double, locale: Locale = Locale.getDefault()): String {
        val format = DecimalFormat("0.00", DecimalFormatSymbols.getInstance(locale))
        val rounded = abs(value)
        val sign = when {
            rounded < 0.005 -> ""
            value > 0 -> "+"
            else -> "−"
        }
        return "$sign${format.format(rounded)}%"
    }

    /** Betrag unter einem halben Rappen/Cent gilt als null. */
    fun isZero(value: Double): Boolean = abs(value) < 0.005
}
