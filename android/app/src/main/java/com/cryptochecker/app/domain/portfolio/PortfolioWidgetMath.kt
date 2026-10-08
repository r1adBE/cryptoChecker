package com.cryptochecker.app.domain.portfolio

import com.cryptochecker.app.domain.convert.CurrencyConversion
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale
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

/**
 * Grösse des Portfolio-Widgets — bestimmt Layout und Inhalt (eigenes Layout je Stufe,
 * widget_portfolio*.xml). Kopfzeile (Logo, «Portfolio», Pille) und Gesamtwert immer.
 */
enum class PortfolioWidgetSize {
    /** Dazu die Zeile mit dem Betrag, Uhrzeit, ≈ USDT — kein Chart. */
    SMALL,

    /** Schmal und hoch (z. B. 2 × 3): wie klein, darunter der Wertverlauf. */
    TALL,

    /** Breit (z. B. 4 × 2): Werte links, Wertverlauf rechts. */
    MEDIUM,

    /** Breit und hoch (z. B. 4 × 3): Werte, Wertverlauf, die drei grössten Positionen. */
    LARGE,
}

/**
 * Was das Widget in seiner Stufe zeigt (Gesamtwert immer): [changeLine] «▼ −1’968.40 CHF · 24h»,
 * [usdt] «≈ … USDT», [footer] «Stand 15:19 · 24h», [rows] Zeilen der Positionsliste.
 * Vorrang bei knapper Höhe: Betrag > Fusszeile > ≈ USDT — nie abgeschnitten.
 */
data class PortfolioWidgetLayout(
    val size: PortfolioWidgetSize,
    val changeLine: Boolean,
    val usdt: Boolean,
    val footer: Boolean,
    val rows: Int,
) {
    val chart: Boolean get() = size != PortfolioWidgetSize.SMALL
}

/** Die grössten Positionen (absteigend nach Wert) und wie viele es sonst noch gibt. */
data class TopPositions(val positions: List<PortfolioPosition>, val others: Int)

/** Sichtbare Zeilen der Positionsliste und die Zahl für «+ n weitere» (0 = keine Zeile). */
data class PositionRows(val shown: List<PortfolioPosition>, val more: Int)

/**
 * Reine Regeln des Portfolio-Widgets (ohne Android, getestet in PortfolioWidgetMathTest):
 * Grössenstufe, grösste Positionen mit Anteil, sichtbare Zeilen, Wertverlauf, Linie.
 */
object PortfolioWidgetMath {

    /** Höchstens so viele Positionen werden gespeichert. */
    const val MAX_POSITIONS = 5

    /** So viele Positionen zeigt die grosse Stufe. */
    const val LARGE_ROWS = 3

    /** Ab dieser Breite stehen Werte und Wertverlauf nebeneinander (mittel, gross). */
    const val WIDE_MIN_WIDTH_DP = 250

    /** Kleinste Höhe des Wertverlaufs (dp). */
    const val CHART_MIN_HEIGHT_DP = 44f

    /** Rand 2 × 12 dp (widget_portfolio*.xml). */
    private const val PADDING_DP = 24f

    /** Abstand über dem Wertverlauf (Spalte) bzw. links davon (mittel), dp. */
    private const val CHART_GAP_DP = 6f
    private const val MEDIUM_GAP_DP = 12f

    /** Abstand über der Positionsliste (dp). */
    private const val ROWS_GAP_DP = 6f

    /** Abstand unter der Kopfzeile in der mittleren Stufe (dp). */
    private const val MEDIUM_BODY_GAP_DP = 4f

    /** Zeilenhöhe ≈ 1.35 × Schriftgrösse (sp) — wie die übrigen Widgets. */
    private fun line(sp: Float, fontScale: Float): Float = sp * 1.35f * fontScale

    /** Kopfzeile: Logo 16 dp, Titel 13 sp, Pille 11 sp mit 2 × 2 dp Innenrand. */
    fun headerHeightDp(fontScale: Float): Float = maxOf(16f, line(13f, fontScale), line(11f, fontScale) + 4f)

    /** Gesamtwert: höchstens 26 sp (passt sich der Breite an), 2 dp Abstand. */
    fun valueHeightDp(fontScale: Float): Float = line(26f, fontScale) + 2f

    /** «▼ −1’968.40 CHF · 24h»: 12 sp, 2 dp Abstand. */
    fun changeLineHeightDp(fontScale: Float): Float = line(12f, fontScale) + 2f

    /** ≈ USDT: 11 sp, 1 dp Abstand. */
    fun usdtHeightDp(fontScale: Float): Float = line(11f, fontScale) + 1f

    /** Fusszeile: 10 sp, 4 dp Abstand. */
    fun footerHeightDp(fontScale: Float): Float = line(10f, fontScale) + 4f

    /** Zeile der Positionsliste: 12 sp und 4 dp Abstand. */
    fun rowHeightDp(fontScale: Float): Float = line(12f, fontScale) + 4f

    /** Positionsliste mit [rows] Zeilen samt Abstand darüber; 0 ohne Zeilen. */
    fun rowsHeightDp(fontScale: Float, rows: Int): Float =
        if (rows > 0) ROWS_GAP_DP + rows * rowHeightDp(fontScale) else 0f

    /** Immer belegt: Rand, Kopfzeile, Gesamtwert. */
    fun baseHeightDp(fontScale: Float): Float = PADDING_DP + headerHeightDp(fontScale) + valueHeightDp(fontScale)

    /** Spalte mit Wertverlauf (schmal-hoch, gross): Grundhöhe, Betrag, Fusszeile, Chart. */
    private fun chartColumnHeightDp(fontScale: Float): Float =
        baseHeightDp(fontScale) + changeLineHeightDp(fontScale) + footerHeightDp(fontScale) +
            CHART_MIN_HEIGHT_DP + CHART_GAP_DP

    /**
     * Stufe aus der Widget-Grösse in dp (Angaben des Launchers); unbekannt (0) = klein.
     * Breit (ab [WIDE_MIN_WIDTH_DP]): gross, wenn Wertverlauf und drei Positionszeilen
     * untereinander passen, sonst mittel (sobald Wert und Betrag passen). Schmal: schmal-hoch,
     * wenn der Wertverlauf unter die Werte passt. Alles andere klein.
     */
    fun size(widthDp: Int, heightDp: Int, fontScale: Float = 1f): PortfolioWidgetSize {
        if (heightDp <= 0) return PortfolioWidgetSize.SMALL
        val wide = widthDp >= WIDE_MIN_WIDTH_DP
        val column = chartColumnHeightDp(fontScale)
        return when {
            wide && heightDp >= column + rowsHeightDp(fontScale, LARGE_ROWS) -> PortfolioWidgetSize.LARGE
            wide && heightDp >= baseHeightDp(fontScale) + changeLineHeightDp(fontScale) -> PortfolioWidgetSize.MEDIUM
            !wide && heightDp >= column -> PortfolioWidgetSize.TALL
            else -> PortfolioWidgetSize.SMALL
        }
    }

    /**
     * Inhalt in der Stufe von [widthDp] × [heightDp] ([size]): Ein Teil erscheint nur, wenn er
     * gewünscht ist und ganz passt (Vorrang Betrag > Fusszeile > ≈ USDT). In der Spalte mit
     * Wertverlauf bleibt dafür mindestens [CHART_MIN_HEIGHT_DP]. Unbekannte Höhe: alles Gewünschte.
     * @param hasChange Veränderung über 24 h bekannt
     * @param positions Zahl der gespeicherten Positionen (gross zeigt höchstens [LARGE_ROWS])
     */
    fun layout(
        widthDp: Int,
        heightDp: Int,
        fontScale: Float,
        hasChange: Boolean,
        wantUsdt: Boolean,
        positions: Int,
    ): PortfolioWidgetLayout {
        val size = size(widthDp, heightDp, fontScale)
        val rows = if (size == PortfolioWidgetSize.LARGE) positions.coerceIn(0, LARGE_ROWS) else 0
        if (heightDp <= 0) return PortfolioWidgetLayout(size, hasChange, wantUsdt, footer = true, rows = rows)
        val column = size == PortfolioWidgetSize.TALL || size == PortfolioWidgetSize.LARGE
        var free = heightDp - baseHeightDp(fontScale) -
            (if (column) CHART_MIN_HEIGHT_DP + CHART_GAP_DP else 0f) - rowsHeightDp(fontScale, rows)
        fun take(wanted: Boolean, need: Float): Boolean {
            val fits = wanted && need <= free
            if (fits) free -= need
            return fits
        }
        // Strenger Vorrang: ein Teil nur, wenn alle wichtigeren (gewünschten) passen
        val change = take(hasChange, changeLineHeightDp(fontScale))
        val footer = (change || !hasChange) && take(true, footerHeightDp(fontScale))
        val usdt = footer && take(wantUsdt, usdtHeightDp(fontScale))
        return PortfolioWidgetLayout(size, change, usdt, footer, rows)
    }

    /**
     * Fläche des Wertverlaufs in dp (Breite, Höhe): mittel = rechte Spalte unter der Kopfzeile,
     * schmal-hoch und gross = volle Breite, was neben den gezeigten Teilen bleibt (mindestens
     * [CHART_MIN_HEIGHT_DP]). Klein oder unbekannte Grösse: (0, 0).
     */
    fun chartSizeDp(layout: PortfolioWidgetLayout, widthDp: Int, heightDp: Int, fontScale: Float): Pair<Float, Float> {
        if (!layout.chart || widthDp <= 0 || heightDp <= 0) return 0f to 0f
        val inner = widthDp - PADDING_DP
        if (layout.size == PortfolioWidgetSize.MEDIUM) {
            val h = heightDp - PADDING_DP - headerHeightDp(fontScale) - MEDIUM_BODY_GAP_DP
            return ((inner - MEDIUM_GAP_DP) / 2f).coerceAtLeast(40f) to h.coerceAtLeast(CHART_MIN_HEIGHT_DP)
        }
        val used = baseHeightDp(fontScale) + CHART_GAP_DP +
            (if (layout.changeLine) changeLineHeightDp(fontScale) else 0f) +
            (if (layout.usdt) usdtHeightDp(fontScale) else 0f) +
            (if (layout.footer) footerHeightDp(fontScale) else 0f) +
            rowsHeightDp(fontScale, layout.rows)
        return inner to max(CHART_MIN_HEIGHT_DP, heightDp - used)
    }

    /** Rand 2 × 12 dp, Logo 16 dp und Abstand 6 dp, Abstand vor der Pille 6 dp, Innenrand der Pille 2 × 7 dp. */
    private const val HEADER_FIXED_DP = 24f + 16f + 6f + 6f + 14f

    /**
     * Titel «Portfolio» in der Kopfzeile nur, wenn er neben der Pille ganz Platz hat — sonst
     * Logo und Pille allein (lieber kein Titel als «Portf…»; der Screenreader nennt ihn trotzdem).
     * Ohne Pille ([pillTextDp] null) oder unbekannte Breite: immer.
     */
    fun showsTitle(widthDp: Int, titleDp: Float, pillTextDp: Float?): Boolean =
        pillTextDp == null || widthDp <= 0 || titleDp + pillTextDp + HEADER_FIXED_DP + 2f <= widthDp

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

    /**
     * y-Lage von [value] in derselben Skala wie [linePoints] (z. B. die gestrichelte Linie
     * beim Ausgangswert), auf die Fläche geklemmt; null, wenn es keine Linie gibt.
     */
    fun valueY(points: List<PortfolioValuePoint>, value: Double, height: Float, inset: Float): Float? {
        val clean = points.filter { it.value.isFinite() }
        if (clean.size < 2 || !value.isFinite()) return null
        val low = clean.minOf { it.value }
        val high = clean.maxOf { it.value }
        val top = inset
        val bottom = max(inset, height - inset)
        if (!(high > low)) return (top + bottom) / 2f
        val y = bottom - (value - low) / (high - low) * (bottom - top)
        return y.toFloat().coerceIn(top, bottom)
    }
}
