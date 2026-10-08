package com.cryptochecker.app.ui.features.info

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.cryptochecker.app.R
import com.cryptochecker.app.domain.market.CycleHistory
import com.cryptochecker.app.domain.market.CycleMarker
import com.cryptochecker.app.domain.market.CycleSeries
import com.cryptochecker.app.ui.components.SkeletonBlock
import com.cryptochecker.app.ui.components.SkeletonLine
import com.cryptochecker.app.ui.components.SkeletonPulse
import com.cryptochecker.app.ui.theme.Spacing
import com.cryptochecker.app.ui.theme.tabularNumbers
import com.cryptochecker.app.util.A11yText
import com.cryptochecker.app.util.BidiText
import com.cryptochecker.app.util.LocaleNumbers
import com.cryptochecker.app.util.PriceFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.roundToInt

/** Marke im Zyklus-Chart: Hoch oder Tief eines Zyklus, einmal je Datenstand berechnet. */
private data class ChartMarker(
    val seriesIndex: Int,
    val isTop: Boolean,
    val isCurrentCycle: Boolean,
    val marker: CycleMarker,
    /** Zweites Hoch/Tief (Doppel-Top/-Bottom): kleiner, hohler Punkt. */
    val isSecondary: Boolean = false,
)

/** Achsenbereich und Marken — einmal je Datenstand, nicht je Frame. */
private class CycleChartModel(history: CycleHistory, currentHalving: LocalDate) {
    val minLog: Double
    val maxLog: Double
    val markers: List<ChartMarker>

    init {
        val allValues = history.series.flatMap { s -> s.points.map { it.second } }.filter { it > 0 }
        minLog = ln(allValues.minOrNull() ?: 0.5).coerceAtMost(ln(0.5))
        maxLog = ln(allValues.maxOrNull() ?: 2.0).coerceAtLeast(ln(2.0))
        markers = history.series.flatMapIndexed { i, s ->
            val current = s.halving == currentHalving
            listOfNotNull(
                s.top?.let { ChartMarker(i, isTop = true, isCurrentCycle = current, marker = it) },
                s.bottom?.let { ChartMarker(i, isTop = false, isCurrentCycle = current, marker = it) },
                s.secondTop?.let { ChartMarker(i, isTop = true, isCurrentCycle = current, marker = it, isSecondary = true) },
                s.secondBottom?.let { ChartMarker(i, isTop = false, isCurrentCycle = current, marker = it, isSecondary = true) },
            )
        }
    }

    fun position(day: Int, value: Double, w: Float, h: Float): Offset = Offset(
        day / CYCLE_MAX_DAY * w,
        (h - ((ln(value) - minLog) / (maxLog - minLog) * h)).toFloat()
    )
}

private const val CYCLE_MAX_DAY = 1440f

/** Prozent mit Vorzeichen und Tausendertrennung, ohne Nachkommastellen: „+1’130 %“. */
private fun cyclePercent(change: Double): String {
    val pct = change * 100
    val sign = if (pct >= 0) "+" else "−"
    return BidiText.ltr("$sign${LocaleNumbers.decimal(abs(pct), 0, grouping = true)} %")
}

/**
 * Frühere Zyklen übereinander: Kurs als Vielfaches des Halving-Tageskurses
 * (logarithmisch), Tage seit dem Halving auf der x-Achse. Der laufende Zyklus
 * in der Akzentfarbe, mit Punkt für heute. Je Zyklus ein Punkt am Hoch und
 * (nach mindestens 30 % Rückgang) am Tief danach; Antippen zeigt eine Sprechblase.
 */
@Composable
internal fun CycleChart(history: CycleHistory, currentHalving: LocalDate) {
    val accent = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outlineVariant
    val cardBackground = MaterialTheme.colorScheme.surfaceContainer
    val older = listOf(
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
    )
    val series = history.series
    if (series.isEmpty()) return

    val model = remember(history, currentHalving) { CycleChartModel(history, currentHalving) }
    val maxDay = CYCLE_MAX_DAY
    // Ältere Zyklen grau (älteste am hellsten), der laufende in der Akzentfarbe
    val colors = series.mapIndexed { i, _ ->
        if (i == series.lastIndex) accent else older[i.coerceAtMost(older.lastIndex)]
    }
    // Höchstens eine Sprechblase; Index in model.markers
    var selected by remember(model) { mutableStateOf<Int?>(null) }
    val chartTopPadding = 10.dp
    // Screenreader: je Zyklus ein Satz (Stand seit dem Halving, Hoch, Tief, Doppel-Hoch/-Tief)
    val context = LocalContext.current
    val description = remember(history, context) { cycleChartDescription(context, series) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(170.dp)
            .semantics { contentDescription = description }
            .pointerInput(model) {
                detectTapGestures { tap ->
                    val top = chartTopPadding.toPx()
                    val w = size.width.toFloat()
                    val h = size.height - top
                    val hitRadius = 16.dp.toPx() // Trefferfläche 32 dp
                    val hit = model.markers.withIndex()
                        .map { (index, m) ->
                            val p = model.position(m.marker.day, m.marker.multiple, w, h)
                            index to (Offset(p.x, p.y + top) - tap).getDistance()
                        }
                        .filter { it.second <= hitRadius }
                        .minByOrNull { it.second }
                        ?.first
                    // Gleiche Marke nochmals oder daneben getippt: schliessen
                    selected = if (hit == null || hit == selected) null else hit
                }
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize().padding(top = chartTopPadding)) {
            val w = size.width
            val h = size.height
            fun x(day: Int) = day / maxDay * w
            fun y(v: Double) = model.position(0, v, w, h).y

            // Hilfslinien: je Jahr senkrecht, 1× waagrecht
            for (year in 1..3) {
                drawLine(grid, Offset(x(year * 360), 0f), Offset(x(year * 360), h), strokeWidth = 1f)
            }
            drawLine(grid, Offset(0f, y(1.0)), Offset(w, y(1.0)), strokeWidth = 1f)

            series.forEachIndexed { i, s ->
                val path = Path()
                s.points.forEachIndexed { j, (day, v) ->
                    if (j == 0) path.moveTo(x(day), y(v)) else path.lineTo(x(day), y(v))
                }
                drawPath(path, colors[i], style = Stroke(width = if (i == series.lastIndex) 5f else 3f))
            }
            series.lastOrNull()?.points?.lastOrNull()?.let { (day, v) ->
                drawCircle(accent, radius = 9f, center = Offset(x(day), y(v)))
            }

            // Hoch/Tief: kleiner Punkt in Zyklusfarbe mit Ring in Kartenfarbe
            val dotRadius = 3.25.dp.toPx()
            val ring = 1.25.dp.toPx()
            model.markers.forEachIndexed { index, m ->
                val center = model.position(m.marker.day, m.marker.multiple, w, h)
                val base = if (m.isSecondary) dotRadius * 0.8f else dotRadius
                val r = if (index == selected) base + 1.dp.toPx() else base
                drawCircle(cardBackground, radius = r + ring, center = center)
                drawCircle(colors[m.seriesIndex], radius = r, center = center)
                // Doppel-Top/-Bottom: hohl, damit das Haupt-Hoch/-Tief hervorsticht
                if (m.isSecondary) drawCircle(cardBackground, radius = r * 0.45f, center = center)
            }
        }

        selected?.let { model.markers.getOrNull(it) }?.let { m ->
            CycleMarkerBubble(
                marker = m,
                anchorDp = chartTopPadding,
                anchor = { w, h -> model.position(m.marker.day, m.marker.multiple, w, h) },
            )
        }
    }

    // Achse: Jahresmarken genau unter den Hilfslinien (für den Screenreader ohne Inhalt).
    // Absolut (nicht gespiegelt) wie die Zeichnung: die Zeitachse läuft immer von links nach rechts
    BoxWithConstraints(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp).height(16.dp).clearAndSetSemantics { },
        contentAlignment = AbsoluteAlignment.TopLeft,
    ) {
        val labelWidth = 16.dp
        (0..4).forEach { year ->
            val x = (maxWidth * (year * 360f / maxDay)) - labelWidth / 2
            Text(
                LocaleNumbers.integer(year),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier
                    .absoluteOffset(x = x.coerceIn(0.dp, maxWidth - labelWidth))
                    .size(width = labelWidth, height = 16.dp)
            )
        }
    }
    Text(
        stringResource(R.string.insights_chart_axis),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Row(modifier = Modifier.padding(top = 8.dp)) {
        series.forEachIndexed { i, s ->
            Box(Modifier.padding(top = Spacing.xs).size(10.dp).clip(CircleShape).background(colors[i]))
            Text(
                LocaleNumbers.integer(s.halving.year),
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(start = 4.dp, end = 12.dp)
            )
        }
    }
    Text(
        stringResource(R.string.insights_chart_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = Spacing.xs)
    )
    SourceText(stringResource(R.string.insights_source_history))
}

/**
 * Platzhalter in der Form von [CycleChart]: Chart-Fläche (170 dp), Jahresachse, Legende;
 * feste Texte (Achse, Hinweis, Quelle) stehen schon echt da.
 */
@Composable
internal fun CycleChartSkeleton() {
    val loading = stringResource(R.string.loading_hint)
    SkeletonPulse(modifier = Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = loading }) {
        Box(modifier = Modifier.fillMaxWidth().height(170.dp).padding(top = 10.dp)) {
            SkeletonBlock(Modifier.fillMaxSize(), corner = 12.dp)
        }
        SkeletonBlock(Modifier.fillMaxWidth().padding(top = 4.dp).height(16.dp), corner = 8.dp)
    }
    Text(
        stringResource(R.string.insights_chart_axis),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    SkeletonPulse {
        SkeletonLine(MaterialTheme.typography.labelMedium, Modifier.padding(top = 8.dp).width(180.dp))
    }
    Text(
        stringResource(R.string.insights_chart_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = Spacing.xs)
    )
    SourceText(stringResource(R.string.insights_source_history))
}

/**
 * Beschreibung des Zyklus-Charts für Screenreader, je Zyklus:
 * «Zyklus 2024: gestiegen um 45% seit dem Halving, Tag 530. Hoch … an Tag …»
 */
private fun cycleChartDescription(context: Context, series: List<CycleSeries>): String =
    series.mapNotNull { s ->
        val (day, multiple) = s.points.lastOrNull() ?: return@mapNotNull null
        fun price(m: CycleMarker) = PriceFormat.priceWithCurrency(m.priceUsd, "USD")
        listOfNotNull(
            context.getString(
                R.string.a11y_cycle,
                LocaleNumbers.integer(s.halving.year),
                A11yText.change(context, (multiple - 1.0) * 100.0, decimals = 0),
                day
            ),
            s.top?.let { context.getString(R.string.a11y_cycle_top, price(it), it.day) },
            s.secondTop?.let { context.getString(R.string.a11y_cycle_double_top, price(it), it.day) },
            s.bottom?.let { context.getString(R.string.a11y_cycle_bottom, price(it), it.day) },
            s.secondBottom?.let { context.getString(R.string.a11y_cycle_double_bottom, price(it), it.day) },
        ).joinToString(" ")
    }.joinToString(" ").ifEmpty { context.getString(R.string.a11y_chart_empty) }

/**
 * Sprechblase neben einer Marke, immer innerhalb des Charts: bevorzugt rechts
 * oberhalb, sonst links bzw. unterhalb.
 */
@Composable
private fun BoxScope.CycleMarkerBubble(
    marker: ChartMarker,
    anchorDp: Dp,
    anchor: (w: Float, h: Float) -> Offset,
) {
    val m = marker.marker
    val dateFormat = remember { LocaleNumbers.dates(DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT)) }
    val label = when {
        marker.isSecondary && marker.isTop -> stringResource(R.string.cycle_marker_double_top, LocaleNumbers.integer(m.date.year))
        marker.isSecondary -> stringResource(R.string.cycle_marker_double_bottom, LocaleNumbers.integer(m.date.year))
        marker.isTop && marker.isCurrentCycle -> stringResource(R.string.cycle_marker_high_so_far)
        marker.isTop -> stringResource(R.string.cycle_marker_top, LocaleNumbers.integer(m.date.year))
        else -> stringResource(R.string.cycle_marker_bottom, LocaleNumbers.integer(m.date.year))
    }
    val change = if (marker.isTop) {
        stringResource(R.string.cycle_since_halving, cyclePercent(m.change))
    } else {
        stringResource(R.string.cycle_from_top, cyclePercent(m.change))
    }
    val price = PriceFormat.priceWithCurrency(m.priceUsd, "USD")

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.inverseSurface,
        contentColor = MaterialTheme.colorScheme.inverseOnSurface,
        shadowElevation = 2.dp,
        modifier = Modifier
            .matchParentSize()
            .layout { measurable, constraints ->
                val width = constraints.maxWidth
                val height = constraints.maxHeight
                val placeable = measurable.measure(
                    Constraints(maxWidth = (width * 0.75f).toInt(), maxHeight = height)
                )
                layout(width, height) {
                    val top = anchorDp.toPx()
                    val p = anchor(width.toFloat(), height - top)
                    val ax = p.x.roundToInt()
                    val ay = (p.y + top).roundToInt()
                    val gap = 10.dp.roundToPx()
                    // Waagrecht: rechts vom Punkt, sonst links, notfalls an den Rand geschoben
                    var bx = ax + gap
                    if (bx + placeable.width > width) bx = ax - gap - placeable.width
                    bx = bx.coerceIn(0, (width - placeable.width).coerceAtLeast(0))
                    // Senkrecht: über dem Punkt, sonst darunter
                    var by = ay - gap - placeable.height
                    if (by < 0) by = ay + gap
                    by = by.coerceIn(0, (height - placeable.height).coerceAtLeast(0))
                    placeable.place(bx, by)
                }
            }
    ) {
        Column(modifier = Modifier.padding(horizontal = Spacing.sm, vertical = Spacing.sm)) {
            Text(
                "$label · $price",
                style = MaterialTheme.typography.labelMedium.tabularNumbers(),
                fontWeight = FontWeight.SemiBold
            )
            Text(
                "$change · ${m.date.format(dateFormat)}",
                style = MaterialTheme.typography.labelSmall.tabularNumbers()
            )
        }
    }
}
