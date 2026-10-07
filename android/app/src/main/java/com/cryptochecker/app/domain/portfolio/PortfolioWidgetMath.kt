package com.cryptochecker.app.domain.portfolio

import com.cryptochecker.app.domain.convert.CurrencyConversion
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale
import kotlin.math.floor
import kotlin.math.max

/**
 * Eine Position im Portfolio-Widget: Wert in der Anzeigewährung, Anteil am
 * Gesamtwert (0–100) und Veränderung «heute» des Kurses in Prozent (null = keine
 * Vergleichsbasis, siehe [PortfolioSnapshotMath.baseline]).
 */
data class PortfolioPosition(
    val symbol: String,
    val value: Double,
    val sharePercent: Double,
    val change24hPercent: Double?,
)

/** Ein Punkt des Wertverlaufs: Zeit (Epoch-ms) und Gesamtwert in der Anzeigewährung. */
data class PortfolioValuePoint(val time: Long, val value: Double)

/** Grösse des Portfolio-Widgets — bestimmt, was es zeigt. */
enum class PortfolioWidgetSize {
    /** Titel, Gesamtwert, ≈ USDT, Uhrzeit. */
    SMALL,

    /** Dazu «heute» und der Wertverlauf. */
    MEDIUM,

    /** Dazu die grössten Positionen. */
    LARGE,
}

/** Die grössten Positionen (absteigend nach Wert) und wie viele es sonst noch gibt. */
data class TopPositions(val positions: List<PortfolioPosition>, val others: Int)

/**
 * Was das Widget bei der verfügbaren Höhe zeigt (Gesamtwert immer). Vorrang:
 * Gesamtwert > «heute» > ≈ USDT > Wertverlauf > Uhrzeit > Positionsliste.
 * [listLines] = verfügbare Listenzeilen inklusive «+ n weitere».
 */
data class PortfolioWidgetParts(
    val today: Boolean,
    val usdt: Boolean,
    val chart: Boolean,
    val time: Boolean,
    val listLines: Int,
)

/** Sichtbare Zeilen der Positionsliste und die Zahl für «+ n weitere» (0 = keine Zeile). */
data class PositionRows(val shown: List<PortfolioPosition>, val more: Int)

/**
 * Reine Regeln des Portfolio-Widgets (ohne Android, getestet in PortfolioWidgetMathTest):
 * Grössenstufe, grösste Positionen mit Anteil, sichtbare Zeilen, Wertverlauf, Linie.
 */
object PortfolioWidgetMath {

    /** Höchstens so viele Positionen werden gespeichert und gezeigt. */
    const val MAX_POSITIONS = 5

    const val MEDIUM_MIN_HEIGHT_DP = 110
    const val LARGE_MIN_WIDTH_DP = 250
    const val LARGE_MIN_HEIGHT_DP = 180

    /** Zeile der Positionsliste: 12 sp Text plus Rand der Prozent-Pille (dp). */
    private const val ROW_TEXT_DP = 16f
    private const val ROW_EXTRA_DP = 4f

    /** Kleinste Höhe des Wertverlaufs (dp); darunter fällt er weg. */
    const val CHART_MIN_HEIGHT_DP = 32f

    /**
     * Stufe aus der Widget-Grösse in dp (Angaben des Launchers). Unbekannt (0) = klein.
     * Gross: mindestens 250 × 180 dp (schmal und hoch bleibt mittel); mittel ab 110 dp Höhe.
     */
    fun size(widthDp: Int, heightDp: Int): PortfolioWidgetSize = when {
        widthDp >= LARGE_MIN_WIDTH_DP && heightDp >= LARGE_MIN_HEIGHT_DP -> PortfolioWidgetSize.LARGE
        heightDp >= MEDIUM_MIN_HEIGHT_DP -> PortfolioWidgetSize.MEDIUM
        else -> PortfolioWidgetSize.SMALL
    }

    /**
     * Zweite Zeile «≈ … USDT» nur, wenn gewünscht und die Anzeigewährung nicht selbst
     * USD bzw. ein USD-Stablecoin ist (ohne Devisenkurs zeigt das Widget USD).
     */
    fun showsUsdt(enabled: Boolean, currency: String): Boolean =
        enabled && CurrencyConversion.normalize(currency) !in CurrencyConversion.USD_STABLES

    /** Anteil von [value] an [total] in Prozent (0–100); ohne Gesamtwert 0. */
    fun sharePercent(value: Double, total: Double): Double =
        if (total > 0.0 && value.isFinite()) (value / total * 100.0).coerceIn(0.0, 100.0) else 0.0

    /** Anteil mit einer Nachkommastelle, z. B. «62.3%» (Dezimalzeichen je Sprache). */
    fun shareText(percent: Double, locale: Locale = Locale.getDefault()): String =
        DecimalFormat("0.0", DecimalFormatSymbols.getInstance(locale)).format(percent.coerceIn(0.0, 100.0)) + "%"

    /**
     * Kursveränderung je Coin seit [baseline] in Prozent (nur Coins mit beiden Kursen).
     * Gleiche Vergleichsbasis wie «heute» des ganzen Portfolios.
     */
    fun coinChanges(current: Map<String, Double>, baseline: Map<String, Double>?): Map<String, Double> {
        if (baseline == null) return emptyMap()
        val out = HashMap<String, Double>()
        for ((coin, now) in current) {
            if (!(now > 0.0) || now.isInfinite()) continue
            val then = baseline[coin]?.takeIf { it > 0.0 && !it.isInfinite() } ?: continue
            out[coin] = (now / then - 1.0) * 100.0
        }
        return out
    }

    /**
     * Die [limit] wertvollsten Positionen, absteigend nach Wert (gleicher Wert: nach Kürzel).
     * Positionen ohne Kurs (Wert null) erscheinen nicht in der Liste, zählen aber zu
     * [TopPositions.others].
     * @param valuesUsd Wert je offenem Coin in USDT (null = kein Kurs)
     * @param totalUsd Gesamtwert in USDT (Basis für den Anteil)
     * @param fxRate USD → Anzeigewährung
     * @param changes Kursveränderung «heute» je Coin in Prozent
     */
    fun topPositions(
        valuesUsd: Map<String, Double?>,
        totalUsd: Double,
        fxRate: Double,
        changes: Map<String, Double>,
        limit: Int = MAX_POSITIONS,
    ): TopPositions {
        val priced = valuesUsd.mapNotNull { (coin, value) ->
            value?.takeIf { it.isFinite() && it >= 0.0 }?.let { coin to it }
        }.sortedWith(compareByDescending<Pair<String, Double>> { it.second }.thenBy { it.first })
        val top = priced.take(limit.coerceAtLeast(0)).map { (coin, value) ->
            PortfolioPosition(
                symbol = coin,
                value = value * fxRate,
                sharePercent = sharePercent(value, totalUsd),
                change24hPercent = changes[coin],
            )
        }
        return TopPositions(top, valuesUsd.size - top.size)
    }

    /**
     * Sichtbare Zeilen bei [maxLines] verfügbaren Zeilen (inklusive der Zeile «+ n weitere»).
     * Passen nicht alle Positionen, belegt «+ n weitere» die letzte Zeile. Bliebe nur
     * diese Zeile übrig, entfällt die Liste ganz.
     */
    fun rows(top: TopPositions, maxLines: Int): PositionRows {
        val total = top.positions.size + top.others
        if (maxLines <= 0 || top.positions.isEmpty()) return PositionRows(emptyList(), 0)
        var shown = minOf(top.positions.size, maxLines)
        if (shown < total && shown + 1 > maxLines) shown = maxLines - 1
        if (shown <= 0) return PositionRows(emptyList(), 0)
        return PositionRows(top.positions.take(shown), total - shown)
    }

    /** Innenrand der «heute»-Pille links + rechts (dp, widget_portfolio.xml). */
    const val TODAY_PILL_PADDING_DP = 14f

    /**
     * Platz für den Text der «heute»-Pille in dp bei [widthDp] Widget-Breite
     * (Rand 2 × 12 dp, Innenrand der Pille); null = Breite unbekannt.
     */
    fun todayTextWidthDp(widthDp: Int): Float? =
        if (widthDp > 0) widthDp - 24f - TODAY_PILL_PADDING_DP else null

    /** Höhe einer Listenzeile in dp. */
    fun rowHeightDp(fontScale: Float): Float = ROW_TEXT_DP * fontScale + ROW_EXTRA_DP

    /** Abstand über dem Wertverlauf bzw. über der Positionsliste (dp, widget_portfolio.xml). */
    private const val CHART_GAP_DP = 6f
    private const val LIST_GAP_DP = 4f

    /** Zeilenhöhe ≈ 1.35 × Schriftgrösse (sp) — wie beim Einzel-Widget. */
    private fun line(sp: Float, fontScale: Float): Float = sp * 1.35f * fontScale

    /** Immer belegt: Rand 2 × 12 dp, Titel 14 sp, Gesamtwert 24 sp (+4 dp). */
    fun baseHeightDp(fontScale: Float): Float = 24f + line(14f, fontScale) + 4f + line(24f, fontScale)

    /**
     * Kompakte Stufe (klein, z. B. 2 × 1 mit rund 92 dp Höhe): Rand oben/unten 2 × 8 dp,
     * Titel 12 sp, Gesamtwert 20 sp (+2 dp) — damit «≈ … USDT» darunter Platz hat.
     */
    fun compactBaseHeightDp(fontScale: Float): Float = 16f + line(12f, fontScale) + 2f + line(20f, fontScale)

    /** Kompakt zeichnen? Nur die kleine Stufe mit bekannter Höhe. */
    fun isCompact(size: PortfolioWidgetSize, heightDp: Int): Boolean =
        size == PortfolioWidgetSize.SMALL && heightDp > 0

    /** «heute»-Pille: 12 sp, 2 × 2 dp Innenrand, 4 dp Abstand. */
    fun todayHeightDp(fontScale: Float): Float = line(12f, fontScale) + 4f + 4f

    /** ≈ USDT: 11 sp, 1 dp Abstand. */
    fun usdtHeightDp(fontScale: Float): Float = line(11f, fontScale) + 1f

    /** Uhrzeit: 10 sp, 2 dp Abstand. */
    fun timeHeightDp(fontScale: Float): Float = line(10f, fontScale) + 2f

    /**
     * Welche Teile in [heightDp] Platz haben, nach Vorrang (siehe [PortfolioWidgetParts]):
     * Ein Teil erscheint nur, wenn er gewünscht ist und ganz passt — so wird nichts
     * abgeschnitten. Der Wertverlauf braucht mindestens [CHART_MIN_HEIGHT_DP]; was danach
     * bleibt, geht an die Liste (falls [list]), der Rest an den Wertverlauf.
     * Unbekannte Höhe (0): alles Gewünschte, keine Liste. [compact] = kleinere Kopfzeile
     * und kleinerer Gesamtwert ([compactBaseHeightDp], nur Stufe klein).
     */
    fun parts(
        heightDp: Int,
        fontScale: Float,
        today: Boolean,
        usdt: Boolean,
        chart: Boolean,
        list: Boolean,
        compact: Boolean = false,
    ): PortfolioWidgetParts {
        if (heightDp <= 0) return PortfolioWidgetParts(today, usdt, chart, time = true, listLines = 0)
        var free = heightDp - if (compact) compactBaseHeightDp(fontScale) else baseHeightDp(fontScale)
        fun take(wanted: Boolean, need: Float): Boolean {
            val fits = wanted && need <= free
            if (fits) free -= need
            return fits
        }
        val showToday = take(today, todayHeightDp(fontScale))
        val showUsdt = take(usdt, usdtHeightDp(fontScale))
        val showChart = take(chart, CHART_MIN_HEIGHT_DP + CHART_GAP_DP)
        val showTime = take(true, timeHeightDp(fontScale))
        val lines = if (list && free > LIST_GAP_DP) {
            floor((free - LIST_GAP_DP) / rowHeightDp(fontScale)).toInt().coerceIn(0, MAX_POSITIONS + 1)
        } else 0
        return PortfolioWidgetParts(showToday, showUsdt, showChart, showTime, lines)
    }

    /**
     * Höhe des Wertverlaufs in dp: was neben den gezeigten Teilen und [usedListLines]
     * Listenzeilen bleibt (mindestens [CHART_MIN_HEIGHT_DP]); 0 = unbekannt.
     */
    fun chartHeightDp(heightDp: Int, fontScale: Float, parts: PortfolioWidgetParts, usedListLines: Int): Float {
        if (heightDp <= 0) return 0f
        val used = baseHeightDp(fontScale) +
            (if (parts.today) todayHeightDp(fontScale) else 0f) +
            (if (parts.usdt) usdtHeightDp(fontScale) else 0f) +
            (if (parts.time) timeHeightDp(fontScale) else 0f) +
            CHART_GAP_DP +
            (if (usedListLines > 0) LIST_GAP_DP + usedListLines * rowHeightDp(fontScale) else 0f)
        return max(CHART_MIN_HEIGHT_DP, heightDp - used)
    }

    /**
     * Wertverlauf des HEUTIGEN Bestands über die gemerkten Kursstände ([history], aufsteigend)
     * und den aktuellen Stand — wie «heute»: Käufe und Verkäufe seither verschieben die
     * Linie nicht. Fehlt einem Coin in einem Stand der Kurs, zählt der aktuelle Kurs.
     * @return aufsteigend nach Zeit, letzter Punkt = [now]; leer ohne offenen Bestand
     */
    fun valueHistory(
        holdings: Map<String, Double>,
        history: List<PriceSample>,
        current: Map<String, Double>,
        now: Long,
        fxRate: Double,
        maxAgeMillis: Long = PortfolioSnapshotMath.HISTORY_MILLIS,
    ): List<PortfolioValuePoint> {
        val open = holdings.filter { (_, amount) -> amount > PortfolioCalculator.EPS && amount.isFinite() }
        if (open.isEmpty()) return emptyList()
        fun valid(price: Double?): Double? = price?.takeIf { it > 0.0 && it.isFinite() }
        fun value(prices: Map<String, Double>): Double =
            open.entries.sumOf { (coin, amount) -> amount * (valid(prices[coin]) ?: valid(current[coin]) ?: 0.0) }
        val past = history
            .filter { now - it.time in 1..maxAgeMillis }
            .sortedBy { it.time }
            .map { PortfolioValuePoint(it.time, value(it.prices) * fxRate) }
        return past + PortfolioValuePoint(now, value(current) * fxRate)
    }

    /** Steigend (letzter Wert ≥ erster) → Kursfarbe «steigend». */
    fun isUp(points: List<PortfolioValuePoint>): Boolean =
        points.size < 2 || points.last().value >= points.first().value

    /**
     * Linie in Pixeln: x nach der Zeit über [width], y zwischen [inset] und [height] − [inset]
     * (höchster Wert oben). Ohne Spanne mittig. Weniger als zwei Punkte: leer.
     */
    fun linePoints(points: List<PortfolioValuePoint>, width: Float, height: Float, inset: Float): List<Pair<Float, Float>> {
        val clean = points.filter { it.value.isFinite() }
        if (clean.size < 2) return emptyList()
        val t0 = clean.first().time
        val span = (clean.last().time - t0).toDouble()
        val low = clean.minOf { it.value }
        val high = clean.maxOf { it.value }
        val top = inset
        val bottom = max(inset, height - inset)
        return clean.mapIndexed { i, p ->
            val fx = if (span > 0.0) (p.time - t0) / span else i.toDouble() / (clean.size - 1)
            val x = (fx * width).toFloat()
            val y = if (high > low) {
                (bottom - (p.value - low) / (high - low) * (bottom - top)).toFloat()
            } else {
                (top + bottom) / 2f
            }
            x to y
        }
    }
}
