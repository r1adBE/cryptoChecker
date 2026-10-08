package com.cryptochecker.app.ui.features.info

import android.content.Context
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.cryptochecker.app.R
import com.cryptochecker.app.domain.market.AltSeason
import com.cryptochecker.app.domain.market.CoinReport
import com.cryptochecker.app.domain.market.CoinSignal
import com.cryptochecker.app.domain.market.CoinSignalId
import com.cryptochecker.app.domain.market.CycleCachePolicy
import com.cryptochecker.app.domain.market.CycleHistory
import com.cryptochecker.app.domain.market.CycleInfo
import com.cryptochecker.app.domain.market.CycleMarker
import com.cryptochecker.app.domain.market.CycleSeries
import com.cryptochecker.app.domain.market.DataStamp
import com.cryptochecker.app.domain.market.Dominance
import com.cryptochecker.app.domain.market.FearGreed
import com.cryptochecker.app.domain.market.GasFees
import com.cryptochecker.app.domain.market.GasNetwork
import com.cryptochecker.app.domain.market.GasReport
import com.cryptochecker.app.domain.market.MarketReveal
import com.cryptochecker.app.domain.market.MarketTotals
import com.cryptochecker.app.domain.market.MarketZone
import com.cryptochecker.app.ui.theme.AssetColors
import com.cryptochecker.app.ui.theme.MarketScaleColors
import com.cryptochecker.app.ui.theme.PriceColors
import com.cryptochecker.app.util.CompactAmount
import androidx.compose.ui.platform.LocalConfiguration
import com.cryptochecker.app.ui.components.ComboBox
import com.cryptochecker.app.ui.components.SkeletonBlock
import com.cryptochecker.app.ui.components.SkeletonLine
import com.cryptochecker.app.ui.components.SkeletonPill
import com.cryptochecker.app.ui.components.SkeletonPulse
import com.cryptochecker.app.ui.components.SkeletonText
import com.cryptochecker.app.ui.components.rememberReduceMotion
import com.cryptochecker.app.ui.theme.Spacing
import com.cryptochecker.app.ui.theme.tabularNumbers
import com.cryptochecker.app.util.A11yText
import com.cryptochecker.app.util.BidiText
import com.cryptochecker.app.util.LocaleNumbers
import com.cryptochecker.app.util.PriceFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.roundToInt

/** Einheitliche Karte für die Bereiche des Markt-Tabs. */
@Composable
internal fun InsightCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
    ) {
        Column(modifier = Modifier.padding(Spacing.lg)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 12.dp)
            )
            content()
        }
    }
}

@Composable
private fun FailedRow(onRetry: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            stringResource(R.string.something_went_wrong),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.weight(1f)
        )
        TextButton(onClick = onRetry) { Text(stringResource(R.string.action_retry)) }
    }
}

/**
 * Eine kompakte Zeile «Marktdaten gerade nicht verfügbar» mit «Erneut» — die Karte
 * bleibt stehen, damit darunter nichts springt (Crypto Pulse, Krypto-Markt).
 */
@Composable
internal fun UnavailableRow(onRetry: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            stringResource(R.string.pulse_unavailable),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        TextButton(onClick = onRetry) { Text(stringResource(R.string.action_retry)) }
    }
}

/** Restliche Grössenwechsel (Platzhalter → Inhalt) weich, 200 ms; aus bei reduzierter Bewegung. */
internal fun Modifier.sizeAnimation(enabled: Boolean): Modifier =
    if (enabled) animateContentSize(animationSpec = tween(200)) else this

/** Art des Zustands (lädt, geladen, gescheitert) — nur ein Wechsel der Art wird überblendet. */
internal fun loadKey(state: LoadState<*>): Int = when (state) {
    LoadState.Loading -> 0
    is LoadState.Loaded<*> -> 1
    LoadState.Failed -> 2
}

/**
 * Inhalt einer schon sichtbaren Karte, der vom Ladezustand abhängt: Wechselt die Art
 * ([key], z. B. Platzhalter → Inhalt, weil Daten spät kommen), wird überblendet und nur
 * diese Karte ändert ihre Höhe ([MarketReveal.SWAP_MILLIS]); neue Werte derselben Art
 * (Ziehen nach unten, Hintergrund) ersetzen still an Ort und Stelle.
 * Bei reduzierter Bewegung ohne Animation.
 */
@Composable
internal fun <S> CardSwap(state: S, key: (S) -> Any, content: @Composable ColumnScope.(S) -> Unit) {
    if (rememberReduceMotion()) {
        Column(modifier = Modifier.fillMaxWidth()) { content(state) }
    } else {
        AnimatedContent(
            targetState = state,
            contentKey = key,
            transitionSpec = {
                (fadeIn(tween(MarketReveal.SWAP_MILLIS)) togetherWith fadeOut(tween(MarketReveal.SWAP_MILLIS)))
                    .using(SizeTransform(clip = true) { _, _ -> tween(MarketReveal.SWAP_MILLIS) })
            },
            label = "card_swap",
            modifier = Modifier.fillMaxWidth(),
        ) { shown ->
            Column(modifier = Modifier.fillMaxWidth()) { content(shown) }
        }
    }
}

@Composable
private fun SourceText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = Spacing.sm)
    )
}

// ───────────────────────── Halving & Zyklus-Vergleich ─────────────────────────

/**
 * Halving als Zeile unter «Einordnung»: rechts das geschätzte Datum des nächsten Halvings,
 * darunter der Fortschritt im Zyklus; Tippen klappt Countdown, Balken und Zyklus-Chart auf.
 */
@Composable
internal fun HalvingRow(cycle: CycleInfo, history: LoadState<CycleHistory>, onRetry: () -> Unit, divider: Boolean = true) {
    val today = LocalDate.now()
    val total = ChronoUnit.DAYS.between(cycle.lastHalving, cycle.nextHalvingEstimate).coerceAtLeast(1)
    val elapsed = ChronoUnit.DAYS.between(cycle.lastHalving, today).coerceIn(0, total)
    val remaining = ChronoUnit.DAYS.between(today, cycle.nextHalvingEstimate).coerceAtLeast(0)
    val dateFormat = remember { LocaleNumbers.dates(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)) }
    var expanded by rememberSaveable { mutableStateOf(false) }

    MarketRow(
        title = stringResource(R.string.insights_halving_title),
        secondary = stringResource(R.string.insights_cycle_progress, (elapsed * 100 / total).toInt()),
        value = cycle.nextHalvingEstimate.format(dateFormat),
        divider = divider,
        expanded = expanded,
        onToggle = { expanded = !expanded },
    ) {
        Text(
            text = pluralStringResource(R.plurals.insights_halving_countdown, remaining.toInt(), remaining.toInt(), cycle.nextHalvingEstimate.format(dateFormat)),
            style = MaterialTheme.typography.bodyMedium,
        )
        LinearProgressIndicator(
            progress = { elapsed / total.toFloat() },
            modifier = Modifier.fillMaxWidth().padding(top = Spacing.sm, bottom = 16.dp).clip(RoundedCornerShape(50))
        )
        Text(stringResource(R.string.insights_cycle_chart), style = MaterialTheme.typography.labelLarge)
        CardSwap(history, { loadKey(it) }) { shown ->
            when (shown) {
                LoadState.Loading -> CycleChartSkeleton()
                LoadState.Failed -> FailedRow(onRetry)
                is LoadState.Loaded -> CycleChart(shown.value, currentHalving = cycle.lastHalving)
            }
        }
    }
}

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
private fun CycleChart(history: CycleHistory, currentHalving: LocalDate) {
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
private fun CycleChartSkeleton() {
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

// ───────────────────────── Fear & Greed ─────────────────────────

private val FearGreedColors = MarketScaleColors.steps

private fun fearGreedLevel(value: Int): Int = when {
    value < 25 -> 0
    value < 45 -> 1
    value <= 55 -> 2
    value <= 75 -> 3
    else -> 4
}

@Composable
internal fun fearGreedLabel(value: Int): String = stringResource(
    when (fearGreedLevel(value)) {
        0 -> R.string.fng_extreme_fear
        1 -> R.string.fng_fear
        2 -> R.string.fng_neutral
        3 -> R.string.fng_greed
        else -> R.string.fng_extreme_greed
    }
)

/**
 * Fear & Greed als erste Zeile unter «Einordnung»: Wert rechts, Stufe und Vortag darunter,
 * die Skala 0–100 in voller Breite direkt unter der Zeile. Tippen zeigt Verlauf und Quelle.
 */
@Composable
internal fun FearGreedRow(
    state: LoadState<FearGreed>,
    onRetry: () -> Unit,
    divider: Boolean = false,
    /** Herkunft und Stand (alternative.me) für die Nebenzeile. */
    stamp: DataStamp? = null,
) {
    val fg = (state as? LoadState.Loaded)?.value
    var expanded by rememberSaveable { mutableStateOf(false) }
    val label = fg?.let { fearGreedLabel(it.value) }.orEmpty()
    MarketRow(
        title = stringResource(R.string.insights_fng_title),
        secondary = fg?.yesterday?.let { stringResource(R.string.market_row_fng_yesterday, label, LocaleNumbers.integer(it)) } ?: label,
        value = fg?.let { LocaleNumbers.integer(it.value) },
        divider = divider,
        loading = state is LoadState.Loading,
        failure = if (state is LoadState.Failed) stringResource(R.string.something_went_wrong) else null,
        onRetry = onRetry,
        stamp = stamp,
        expanded = expanded,
        onToggle = if (fg != null) ({ expanded = !expanded }) else null,
        // Skala bleibt auch beim Laden und bei Fehler stehen (grau) — darunter springt nichts
        below = { FearGreedScale(fg?.value) },
    ) {
        if (fg != null) {
            Text(
                text = stringResource(
                    R.string.insights_fng_history,
                    fg.yesterday?.let { LocaleNumbers.integer(it) } ?: "—",
                    fg.weekAgo?.let { LocaleNumbers.integer(it) } ?: "—",
                    fg.monthAgo?.let { LocaleNumbers.integer(it) } ?: "—"
                ),
                style = MaterialTheme.typography.bodySmall.tabularNumbers(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SourceText(stringResource(R.string.insights_source_fng))
        }
    }
}

/** Skala 0–100 mit Markierung; [value] null = grauer Platzhalter in derselben Höhe. */
@Composable
private fun FearGreedScale(value: Int?) {
    if (value == null) {
        Box(modifier = Modifier.fillMaxWidth().height(18.dp)) {
            SkeletonBlock(Modifier.align(Alignment.Center).fillMaxWidth().height(8.dp))
        }
        return
    }
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val gaugeDescription = stringResource(
        R.string.a11y_gauge,
        stringResource(R.string.fng_extreme_fear),
        stringResource(R.string.fng_extreme_greed),
        value
    )
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(18.dp)
            .semantics { contentDescription = gaugeDescription }
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(50))
                // Skala folgt der Leserichtung: Verlauf (absolut gezeichnet) bei RTL
                // umgedreht, die Markierung (offset) spiegelt sich selbst
                .background(Brush.horizontalGradient(if (rtl) FearGreedColors.reversed() else FearGreedColors))
        )
        val marker = 18.dp
        Box(
            modifier = Modifier
                .offset(x = (maxWidth - marker) * (value / 100f))
                .size(marker)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.onSurface)
                .padding(3.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surface)
        )
    }
}

// ───────────────────────── Krypto-Markt (Marktkapitalisierung, Volumen) ─────────────────────────

/**
 * «Krypto-Markt» als erste Zeile unter «Daten»: rechts die gesamte Marktkapitalisierung mit
 * Veränderung in 24 Std., darunter das 24-Stunden-Volumen, in der Umrechnungswährung
 * [currency] (sonst USD). Beim Laden ein form-gleicher Platzhalter, ohne Daten
 * «gerade nicht verfügbar» mit «Erneut». Tippen zeigt die Quelle.
 */
@Composable
internal fun MarketTotalsRow(
    state: LoadState<MarketTotals>,
    currency: String,
    onRetry: () -> Unit,
    divider: Boolean = false,
    /** Herkunft und Stand (CoinGecko) für die Nebenzeile. */
    stamp: DataStamp? = null,
) {
    val title = stringResource(R.string.market_cap_title)
    val capLabel = stringResource(R.string.market_cap_label)
    val volumeLabel = stringResource(R.string.market_volume_label)
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    var expanded by rememberSaveable { mutableStateOf(false) }

    val totals = (state as? LoadState.Loaded)?.value
    val values = totals?.valuesIn(currency)
    val cap = remember(values, locale) { values?.let { CompactAmount.format(it.marketCap, it.currency, locale) } }
    val volume = remember(values, locale) { values?.let { CompactAmount.format(it.volume, it.currency, locale) } }
    val change = totals?.changePercent24h
    // Screenreader: ein Satz mit Titel, Marktkapitalisierung samt Veränderung in Worten und Volumen
    val spoken = if (cap != null && volume != null) buildString {
        append(title).append(": ").append(capLabel).append(' ').append(cap)
        if (change != null) append(", ").append(A11yText.change(context, change))
        append("; ").append(volumeLabel).append(' ').append(volume)
    } else null
    // Vorzeichen und Pfeil folgen der Richtung, die Farbe der Einstellung «Kursfarben»
    val formatted = change?.let { PriceFormat.changePercent(it) }
    MarketRow(
        title = title,
        secondary = volume?.let { "$volumeLabel $it" }.orEmpty(),
        value = cap,
        divider = divider,
        loading = state is LoadState.Loading,
        // Fehler oder keine Werte (auch nicht in USD)
        failure = if (state !is LoadState.Loading && cap == null) stringResource(R.string.pulse_unavailable) else null,
        onRetry = onRetry,
        change = when {
            change == null -> null
            formatted == null -> PriceFormat.zeroPercent()
            else -> "${PriceFormat.changeArrow(change)} $formatted"
        },
        changeColor = if (change == null || formatted == null) MaterialTheme.colorScheme.onSurfaceVariant
        else PriceColors.forChange(change),
        spoken = spoken,
        stamp = stamp,
        expanded = expanded,
        onToggle = if (cap != null) ({ expanded = !expanded }) else null,
    ) {
        SourceText(stringResource(R.string.market_cap_source))
    }
}

// ───────────────────────── Dominanz & Altcoin-Saison ─────────────────────────

/**
 * Bitcoin-Dominanz und Altcoin-Saison als zwei Zeilen unter «Einordnung». Tippen zeigt bei
 * der Dominanz die Anteile als Balken, bei der Altcoin-Saison Balken, Erklärung, Stand mit
 * «Aktualisieren» und die Quelle.
 */
@Composable
internal fun DominanceRows(
    dominance: LoadState<Dominance>,
    altSeason: LoadState<AltSeason>,
    onRetry: () -> Unit,
    /** Zeitpunkt der gezeigten Altcoin-Saison (Zwischenspeicher 3 h); null = noch nichts. */
    altSeasonAsOf: Long? = null,
    altSeasonRefreshing: Boolean = false,
    onRefreshAltSeason: () -> Unit = {},
    /** Herkunft und Stand der Dominanz (CoinGecko) bzw. der Altcoin-Saison (Kerzen-Anbieter). */
    dominanceStamp: DataStamp? = null,
    altSeasonStamp: DataStamp? = null,
) {
    val failed = stringResource(R.string.something_went_wrong)
    val d = (dominance as? LoadState.Loaded)?.value
    var dominanceExpanded by rememberSaveable { mutableStateOf(false) }
    MarketRow(
        title = stringResource(R.string.insights_dominance_title),
        // BTC steht rechts; hier ETH (ohne ETH-Wert bleibt die Zeile leer, gleiche Höhe)
        secondary = d?.eth?.let { stringResource(R.string.insights_dominance_eth, percentOne(it)) }.orEmpty(),
        value = d?.let { percentOne(it.btc) },
        loading = dominance is LoadState.Loading,
        failure = if (dominance is LoadState.Failed) failed else null,
        onRetry = onRetry,
        stamp = dominanceStamp,
        expanded = dominanceExpanded,
        onToggle = if (d != null) ({ dominanceExpanded = !dominanceExpanded }) else null,
    ) {
        if (d != null) {
            // Anteile als Balken: BTC · ETH · übrige
            val eth = (d.eth ?: 0.0).coerceAtLeast(0.0)
            val rest = (100.0 - d.btc - eth).coerceAtLeast(0.0)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(10.dp)
                    .clip(RoundedCornerShape(50))
            ) {
                Box(Modifier.weight(d.btc.toFloat().coerceAtLeast(0.1f)).height(10.dp).background(AssetColors.bitcoin))
                if (eth > 0) Box(Modifier.weight(eth.toFloat()).height(10.dp).background(AssetColors.ethereum))
                if (rest > 0) Box(Modifier.weight(rest.toFloat()).height(10.dp).background(MaterialTheme.colorScheme.outlineVariant))
            }
            SourceText(stringResource(R.string.insights_source_dominance))
        }
    }

    val a = (altSeason as? LoadState.Loaded)?.value
    var altExpanded by rememberSaveable { mutableStateOf(false) }
    val altLabel = a?.let {
        stringResource(
            when {
                it.index >= 75 -> R.string.altseason_alt
                it.index <= 25 -> R.string.altseason_btc
                else -> R.string.altseason_mixed
            }
        )
    }.orEmpty()
    MarketRow(
        title = stringResource(R.string.insights_altseason_title),
        // Anbieter und Alter («… · Binance · heute 14:05») hängt MarketRow aus dem Stand an
        secondary = altLabel,
        value = a?.let { LocaleNumbers.integer(it.index) },
        loading = altSeason is LoadState.Loading,
        failure = if (altSeason is LoadState.Failed) failed else null,
        onRetry = onRetry,
        stamp = altSeasonStamp,
        expanded = altExpanded,
        onToggle = if (a != null) ({ altExpanded = !altExpanded }) else null,
    ) {
        if (a != null) {
            LinearProgressIndicator(
                progress = { a.index / 100f },
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(50))
            )
            Text(
                pluralStringResource(R.plurals.insights_altseason_value, a.outperformers, a.outperformers, a.total),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Spacing.xs)
            )
            if (altSeasonAsOf != null) {
                AltSeasonAsOfRow(altSeasonAsOf, altSeasonRefreshing, onRefreshAltSeason)
            }
            SourceText(stringResource(R.string.insights_source_dominance))
        }
    }
}

/** «58.4 %» — eine Nachkommastelle, in den Ziffern der App-Sprache. */
private fun percentOne(value: Double): String = LocaleNumbers.decimal(value, 1) + " %"

/**
 * «Stand 14:05» und ein kleines «Aktualisieren» unter der Altcoin-Saison. Der Knopf ist erst
 * 5 Min. nach dem letzten Stand wieder aktiv ([CycleCachePolicy.MANUAL_MIN_INTERVAL_MILLIS]),
 * sonst gilt der 3-h-Zwischenspeicher.
 */
@Composable
private fun AltSeasonAsOfRow(asOf: Long, refreshing: Boolean, onRefresh: () -> Unit) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(asOf) {
        while (true) {
            now = System.currentTimeMillis()
            if (CycleCachePolicy.canManualRefresh(asOf, now, CycleCachePolicy.MANUAL_MIN_INTERVAL_MILLIS)) break
            delay(15_000L)
        }
    }
    val enabled = !refreshing &&
        CycleCachePolicy.canManualRefresh(asOf, now, CycleCachePolicy.MANUAL_MIN_INTERVAL_MILLIS)
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(
            stringResource(R.string.pulse_updated, PriceFormat.time(asOf)),
            style = MaterialTheme.typography.labelSmall.tabularNumbers(),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        TextButton(onClick = onRefresh, enabled = enabled) {
            Text(stringResource(R.string.action_refresh), style = MaterialTheme.typography.labelMedium)
        }
    }
}

// ───────────────────────── Coin-Analyse ─────────────────────────

/**
 * Coin-Analyse als letzte Zeile unter «Daten»: rechts die Zone des gewählten Coins, darunter
 * Coin und Kurs. Tippen klappt Auswahl und Analyse auf — auch beim Laden und bei Fehler,
 * damit sich ein anderer Coin wählen lässt.
 */
@Composable
internal fun CoinRow(
    coins: List<String>,
    selected: String,
    favorites: Set<String>,
    onSelect: (String) -> Unit,
    onToggleFavorite: (String) -> Unit,
    state: LoadState<CoinReport>,
    onRetry: () -> Unit,
    divider: Boolean = true,
    /** Herkunft und Stand des gezeigten Coins (Kerzen-Anbieter) für die Nebenzeile. */
    stamp: DataStamp? = null,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val report = (state as? LoadState.Loaded)?.value
    MarketRow(
        title = stringResource(R.string.coin_title),
        secondary = report?.let { "${it.symbol} · ${PriceFormat.priceWithCurrency(it.price, "USDT")}" } ?: selected,
        value = report?.let { stringResource(zoneLabel(it.zone)) },
        divider = divider,
        loading = state is LoadState.Loading,
        failure = if (state is LoadState.Failed) stringResource(R.string.coin_no_data) else null,
        onRetry = onRetry,
        stamp = stamp,
        expanded = expanded,
        onToggle = { expanded = !expanded },
    ) {
        ComboBox(
            modifier = Modifier.fillMaxWidth(),
            itemList = coins,
            selectedIndex = coins.indexOf(selected),
            label = stringResource(R.string.coin_choose),
            searchable = true,
            emptyText = selected,
            favorites = favorites,
            onToggleFavorite = onToggleFavorite,
            onValueChange = { index -> onSelect(coins[index]) }
        )
        CardSwap(state, { loadKey(it) }) { shown ->
            when (shown) {
                LoadState.Loading -> CoinReportSkeleton()
                // Meldung und «Erneut» stehen schon in der Zeile
                LoadState.Failed -> Unit
                is LoadState.Loaded -> CoinReportContent(shown.value)
            }
        }
    }
}

@Composable
private fun CoinReportContent(report: CoinReport) {
    val zoneColor = ZoneColors.getValue(report.zone)
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 16.dp)) {
        Text(
            text = stringResource(zoneLabel(report.zone)),
            style = MaterialTheme.typography.titleLarge,
            color = zoneTextColor(report.zone),
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(zoneColor)
                .padding(horizontal = 16.dp, vertical = Spacing.xs)
        )
        Text(
            text = PriceFormat.priceWithCurrency(report.price, "USDT"),
            style = MaterialTheme.typography.titleMedium.tabularNumbers(),
            modifier = Modifier.weight(1f).padding(start = 12.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.End
        )
    }

    ZoneGauge(index = report.index, modifier = Modifier.padding(top = Spacing.md))

    Text(
        text = stringResource(R.string.market_scores, report.topScore, report.bottomScore),
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(top = Spacing.sm)
    )

    report.signals.forEach { CoinSignalRow(it) }

    if (report.historyDays in 1 until 1400) {
        Text(
            pluralStringResource(R.plurals.coin_short_history, report.historyDays, report.historyDays),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp)
        )
    }
    Text(
        stringResource(R.string.coin_price_only),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp)
    )
}

/** Kurze Erklärung per ⓘ (RSI und Pi Cycle); null = ohne Knopf. */
private fun coinExplainRes(id: CoinSignalId): Int? = when (id) {
    CoinSignalId.RSI_WEEKLY, CoinSignalId.RSI_DAILY -> R.string.explain_rsi
    CoinSignalId.PI_CYCLE -> R.string.explain_pi_cycle
    else -> null
}

/**
 * Platzhalter in der Form von [CoinReportContent]: Zonen-Etikett und Kurs, Skala, Scores,
 * je Signal eine Zeile (mit Platz für ⓘ, wo es ihn gibt); der feste Hinweis steht echt da.
 */
@Composable
private fun CoinReportSkeleton() {
    val loading = stringResource(R.string.loading_hint)
    SkeletonPulse(modifier = Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = loading }) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 16.dp)) {
            SkeletonPill(MaterialTheme.typography.titleLarge, Modifier.width(110.dp), horizontal = Spacing.lg, vertical = Spacing.xs)
            Spacer(modifier = Modifier.weight(1f))
            SkeletonLine(MaterialTheme.typography.titleMedium.tabularNumbers(), Modifier.width(100.dp))
        }
        ZoneGaugeSkeleton(Modifier.padding(top = Spacing.md))
        SkeletonText(
            stringResource(R.string.market_scores, 0, 0),
            MaterialTheme.typography.bodyMedium,
            Modifier.padding(top = Spacing.sm)
        )
        CoinSignalId.entries.forEach { id ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SkeletonLine(MaterialTheme.typography.bodyMedium, Modifier.width(120.dp))
                        // Gleiche Höhe wie der ⓘ-Knopf (28 dp) neben dem Namen
                        if (coinExplainRes(id) != null) Spacer(modifier = Modifier.size(28.dp))
                    }
                    SkeletonLine(MaterialTheme.typography.bodySmall.tabularNumbers(), Modifier.width(64.dp))
                }
                SkeletonLine(MaterialTheme.typography.labelMedium, Modifier.width(40.dp))
            }
        }
    }
    Text(
        stringResource(R.string.coin_price_only),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp)
    )
}

@Composable
private fun CoinSignalRow(signal: CoinSignal) {
    val name = when (signal.id) {
        CoinSignalId.MAYER -> "Mayer Multiple"
        CoinSignalId.MA200W -> stringResource(R.string.ind_ma200w)
        CoinSignalId.DRAWDOWN -> stringResource(R.string.ind_drawdown)
        CoinSignalId.RSI_WEEKLY -> stringResource(R.string.ind_rsi_weekly)
        CoinSignalId.RSI_DAILY -> stringResource(R.string.ind_rsi_daily)
        CoinSignalId.PI_CYCLE -> stringResource(R.string.ind_pi_cycle)
        CoinSignalId.PARABOLIC -> stringResource(R.string.ind_parabolic)
        CoinSignalId.CROSS -> stringResource(R.string.ind_cross)
        CoinSignalId.VS_BTC -> stringResource(R.string.ind_vs_btc)
    }
    val (points, color) = when {
        signal.topPoints > 0 -> stringResource(R.string.ind_points_top, signal.topPoints) to ZoneColors.getValue(MarketZone.BULL)
        signal.bottomPoints > 0 -> stringResource(R.string.ind_points_bottom, signal.bottomPoints) to ZoneColors.getValue(MarketZone.BEAR)
        else -> "–" to MaterialTheme.colorScheme.outline
    }
    val explainRes = coinExplainRes(signal.id)
    var showExplain by remember(signal.id) { mutableStateOf(false) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f, fill = false))
                if (explainRes != null) {
                    IconButton(onClick = { showExplain = !showExplain }, modifier = Modifier.size(28.dp)) {
                        Icon(
                            painterResource(R.drawable.ic_info),
                            contentDescription = stringResource(R.string.explain_show),
                            tint = if (showExplain) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
            Text(
                signal.value ?: stringResource(R.string.ind_missing),
                style = MaterialTheme.typography.bodySmall.tabularNumbers(),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (explainRes != null && showExplain) {
                Text(
                    stringResource(explainRes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
        Text(points, style = MaterialTheme.typography.labelMedium, color = color)
    }
}

// ───────────────────────── Netzwerkgebühren (#167) ─────────────────────────

/**
 * Netzwerkgebühren als Zeile unter «Daten»: rechts die normale Ethereum-Gebühr, darunter
 * Bitcoin. Tippen klappt alle Netze (Ethereum und Bitcoin mit langsam/normal/schnell, die
 * L2-/Seitennetze mit der normalen Gebühr, rechts die Kosten einer einfachen Überweisung),
 * den Hinweis auf aktive Gas-Alarme und die Quelle auf.
 */
@Composable
internal fun GasSummaryRow(
    state: LoadState<GasReport>,
    ethAlertGwei: Double,
    btcAlertSat: Int,
    onRetry: () -> Unit,
    divider: Boolean = true,
    /** Herkunft (Ethereum-Knoten, mempool.space) und Stand für die Nebenzeile. */
    stamp: DataStamp? = null,
) {
    // Aktive Gas-Alarme stehen in den Einstellungen fest
    val alerts = listOfNotNull(
        ethAlertGwei.takeIf { it > 0 }?.let { "Ethereum < ${GasFees.formatGwei(it)} gwei" },
        btcAlertSat.takeIf { it > 0 }?.let { "Bitcoin < ${LocaleNumbers.integer(it)} sat/vB" },
    )
    val report = (state as? LoadState.Loaded)?.value
    val eth = report?.evm?.firstOrNull { it.network == GasNetwork.ETHEREUM }
    val btcText = report?.btc?.let { "Bitcoin ${GasFees.formatGwei(it.normal)} sat/vB" }
    var expanded by rememberSaveable { mutableStateOf(false) }
    MarketRow(
        title = stringResource(R.string.gas_title),
        secondary = listOfNotNull(eth?.network?.title, btcText).joinToString(" · "),
        value = eth?.let { "${GasFees.formatGwei(it.normalGwei)} gwei" }
            ?: report?.btc?.let { "${GasFees.formatGwei(it.normal)} sat/vB" },
        divider = divider,
        loading = state is LoadState.Loading,
        failure = if (state is LoadState.Failed) stringResource(R.string.something_went_wrong) else null,
        onRetry = onRetry,
        stamp = stamp,
        expanded = expanded,
        onToggle = if (report != null) ({ expanded = !expanded }) else null,
    ) {
        if (report != null) {
            report.evm.forEach { gas ->
                GasNetworkRow(
                    name = gas.network.title,
                    value = GasFees.formatGwei(gas.normalGwei),
                    unit = "gwei",
                    cost = gas.transferUsd,
                    detail = if (gas.network == GasNetwork.ETHEREUM && gas.fastGwei > gas.slowGwei) stringResource(
                        R.string.gas_slow_fast,
                        GasFees.formatGwei(gas.slowGwei),
                        GasFees.formatGwei(gas.fastGwei)
                    ) else null
                )
            }
            report.btc?.let { btc ->
                GasNetworkRow(
                    name = "Bitcoin",
                    value = GasFees.formatGwei(btc.normal),
                    unit = "sat/vB",
                    cost = btc.transferUsd,
                    detail = if (btc.fast > btc.slow) stringResource(
                        R.string.gas_slow_fast,
                        GasFees.formatGwei(btc.slow),
                        GasFees.formatGwei(btc.fast)
                    ) else null
                )
            }
            GasAlertText(alerts)
            SourceText(stringResource(R.string.gas_source))
        }
    }
}

@Composable
private fun GasAlertText(alerts: List<String>) {
    if (alerts.isNotEmpty()) {
        Text(
            text = stringResource(R.string.gas_alert_active, alerts.joinToString(" · ")),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 8.dp)
        )
    }
}

@Composable
private fun GasNetworkRow(name: String, value: String, unit: String, cost: Double?, detail: String?) {
    // Netz, Gebühr und Kosten als ein Element für den Screenreader
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.xs).semantics(mergeDescendants = true) { }
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.bodyLarge)
            detail?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall.tabularNumbers(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                "$value $unit",
                style = MaterialTheme.typography.bodyLarge.tabularNumbers(),
                fontWeight = FontWeight.SemiBold
            )
            cost?.let {
                Text(
                    stringResource(R.string.gas_transfer_cost, GasFees.formatUsd(it)),
                    style = MaterialTheme.typography.labelSmall.tabularNumbers(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
