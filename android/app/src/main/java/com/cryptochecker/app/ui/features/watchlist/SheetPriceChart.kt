package com.cryptochecker.app.ui.features.watchlist

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cryptochecker.app.R
import com.cryptochecker.app.data.local.model.WatchEntity
import com.cryptochecker.app.domain.watch.SheetChart
import com.cryptochecker.app.domain.watch.SheetChartRange
import com.cryptochecker.app.domain.watch.SheetChartResult
import com.cryptochecker.app.domain.watch.ChangeBasisMath
import com.cryptochecker.app.util.ChangeBasisText
import com.cryptochecker.app.ui.components.SkeletonBlock
import com.cryptochecker.app.ui.components.SkeletonLine
import com.cryptochecker.app.ui.components.SkeletonPulse
import com.cryptochecker.app.ui.components.rememberReduceMotion
import com.cryptochecker.app.ui.theme.LocalHighContrast
import com.cryptochecker.app.ui.theme.PriceColors
import com.cryptochecker.app.ui.theme.amountNumbers
import com.cryptochecker.app.util.A11yText
import com.cryptochecker.app.util.BidiText
import com.cryptochecker.app.util.PriceFormat
import com.cryptochecker.app.widget.LevelKind
import com.cryptochecker.app.widget.WidgetCandle
import com.cryptochecker.app.widget.WidgetChartGeometry
import com.cryptochecker.app.widget.WidgetChartRange
import com.cryptochecker.app.widget.WidgetChartType
import java.time.ZoneId
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Höhe der Chart-Fläche im Aktionsblatt. */
private val CHART_HEIGHT = 180.dp

/** Beschriftungen ≈ 10 sp; grosse Systemschrift nur begrenzt (wie das Einzel-Widget). */
private const val LABEL_SP = 10f
private const val MAX_FONT_SCALE = 1.3f

/** Überblendung beim Wechsel von Zeitraum oder Chart-Art (ms). */
private const val CHART_FADE_MILLIS = 200

/** Über so vielen Kerzen (1 Jahr) beim Ziehen kein Ticken je Kerze — es wäre ein Dauersurren. */
private const val MAX_TICK_CANDLES = 100

/** Ein gezeichneter Verlauf: Zustand der Überblendung. */
private data class ChartFrame(
    val candles: List<WidgetCandle>,
    val type: WidgetChartType,
    val range: SheetChartRange,
    val periodLong: String,
)

/** Lage der zuletzt gezeichneten Geometrie für das Ziehen (kein Compose-Zustand). */
private class GeometryHolder {
    var geometry: WidgetChartGeometry? = null
}

/**
 * Kurs-Chart oben im Aktionsblatt (unter Kopf und Kurs, über Alarm · Warum? · Favorit):
 * Veränderung über den Zeitraum, Chart wie das Einzel-Widget (Kerzen oder Linie, Preisstufen,
 * Gitter, Kurs-Etikett), darunter 24h · 7T · 30T · 1J und Kerzen/Linie. Lange drücken oder
 * waagrecht ziehen zeigt Kurs, Zeit und Veränderung der Kerze unter dem Finger.
 * DEX- und andere Paare ohne Kerzenquelle: ganzer Block ausgeblendet.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SheetPriceChart(
    watch: WatchEntity,
    /** Linie statt Kerzen (zuletzt gewählt, für alle Paare gleich). */
    line: Boolean,
    onLineChange: (Boolean) -> Unit,
    cached: (WatchEntity, SheetChartRange) -> SheetChartResult?,
    load: suspend (WatchEntity, SheetChartRange) -> SheetChartResult,
    modifier: Modifier = Modifier,
) {
    var range by rememberSaveable(watch.id) { mutableStateOf(SheetChartRange.DAY) }
    // Paar bearbeitet (gleiche Id, anderes Paar/Börse): neu laden
    val result by produceState(cached(watch, range), watch.id, watch.marketKey, watch.baseAsset, watch.quoteAsset, range) {
        // Beim Wechsel des Zeitraums: Zwischenspeicher sofort, sonst Platzhalter bis geladen
        value = cached(watch, range)
        if (value == null) value = load(watch, range)
    }
    if (result == SheetChartResult.Unsupported) return

    val type = if (line) WidgetChartType.LINE else WidgetChartType.CANDLES
    val widgetRange = WidgetChartRange.valueOf(range.name)
    // %-Basis: Bei «seit 00:00» heisst der erste Zeitraum «Heute» und beginnt beim Tagesbeginn
    val changeView = LocalChangeView.current
    val basis = changeView.basis
    val today = range == SheetChartRange.DAY && basis.isDay
    val reduceMotion = rememberReduceMotion()
    val periodShort = if (today) ChangeBasisText.shortLabel(basis) else stringResource(widgetRange.shortLabelRes)
    val periodLong = if (today) ChangeBasisText.longLabel(basis) else stringResource(widgetRange.labelRes)
    Column(modifier = modifier.fillMaxWidth()) {
        when (val r = result) {
            is SheetChartResult.Ready -> {
                val candles = if (today) {
                    val dayStart = ChangeBasisMath.dayStart(basis, System.currentTimeMillis())
                    if (dayStart != null) ChangeBasisMath.sinceDayStart(r.candles, dayStart) { it.openTime } else r.candles
                } else r.candles
                // Erster Zeitraum: dieselbe Zahl wie die Pille neben dem Kurs; 7T/30T/1J aus den Kerzen
                RangeChangeHeader(
                    change = SheetChart.headerChange(range, candles, type, watch.shownChange(changeView)),
                    periodShort = periodShort,
                    periodLong = periodLong,
                    sincePhrase = today,
                )
                // Zeitraum/Art gewechselt: alter und neuer Verlauf blenden kurz ineinander
                Crossfade(
                    targetState = ChartFrame(candles, type, range, periodLong),
                    modifier = Modifier.fillMaxWidth(),
                    animationSpec = if (reduceMotion) snap() else tween(CHART_FADE_MILLIS),
                    label = "sheetChart",
                ) { frame ->
                    SheetChartCanvas(
                        candles = frame.candles,
                        type = frame.type,
                        periodLong = frame.periodLong,
                        widgetRange = WidgetChartRange.valueOf(frame.range.name),
                        range = frame.range,
                        quote = watch.quoteAsset,
                        currentPrice = watch.lastPrice,
                    )
                }
            }
            SheetChartResult.NoData -> Text(
                text = stringResource(R.string.sheet_chart_no_data),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 12.dp)
            )
            else -> SkeletonPulse(modifier = Modifier.fillMaxWidth()) {
                SkeletonLine(
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.width(130.dp)
                )
                SkeletonBlock(
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp).height(CHART_HEIGHT),
                    corner = 12.dp
                )
            }
        }

        // Zeitraum links, Kerzen/Linie als kompakter Zwei-Symbol-Schalter rechts in derselben
        // Zeile; reicht der Platz nicht (grosse Schrift), rutscht der Schalter rechtsbündig darunter
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
        ) {
            SheetChartRange.entries.forEach { option ->
                val optionRange = WidgetChartRange.valueOf(option.name)
                // Tages-Basis: «Heute» statt «24h» (Screenreader: «Seit 00:00 UTC» bzw. «… Ortszeit»)
                val optionToday = option == SheetChartRange.DAY && basis.isDay
                val spoken = if (optionToday) ChangeBasisText.longLabel(basis) else stringResource(optionRange.labelRes)
                FilterChip(
                    selected = option == range,
                    onClick = { range = option },
                    label = {
                        Text(
                            if (optionToday) stringResource(R.string.sheet_chart_today) else stringResource(optionRange.shortLabelRes),
                            maxLines = 1,
                            modifier = Modifier.semantics { contentDescription = spoken }
                        )
                    }
                )
            }
            Box(
                contentAlignment = Alignment.CenterEnd,
                modifier = Modifier.weight(1f).align(Alignment.CenterVertically)
            ) {
                ChartTypeToggle(line = line, onLineChange = onLineChange)
            }
        }
    }
}

/**
 * Kerzen | Linie als zwei Symbole in einer Pille (wie ein Segment-Schalter); je Hälfte
 * 48 dp Tippfläche, Screenreader: Optionsfeld «Kerzen»/«Linie» mit Auswahl-Zustand.
 */
@Composable
private fun ChartTypeToggle(line: Boolean, onLineChange: (Boolean) -> Unit) {
    val outline = MaterialTheme.colorScheme.outline
    Row(
        modifier = Modifier
            .selectableGroup()
            .padding(vertical = 8.dp)
            .clip(CircleShape)
            .border(1.dp, outline, CircleShape)
    ) {
        ChartTypeSegment(
            icon = R.drawable.ic_chart_candles,
            label = stringResource(R.string.widget_chart_candles),
            selected = !line,
            onClick = { if (line) onLineChange(false) },
        )
        Box(Modifier.width(1.dp).height(32.dp).background(outline))
        ChartTypeSegment(
            icon = R.drawable.ic_chart_line,
            label = stringResource(R.string.widget_chart_line),
            selected = line,
            onClick = { if (!line) onLineChange(true) },
        )
    }
}

@Composable
private fun ChartTypeSegment(icon: Int, label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(width = 48.dp, height = 32.dp)
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = label }
    ) {
        Icon(
            painterResource(icon),
            contentDescription = null,
            tint = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp)
        )
    }
}

/**
 * «▲ +2.31% in 24h» bzw. bei Tages-Basis «▲ +2.31% heute» ([sincePhrase]) in der Kursfarbe;
 * Screenreader: «up 2.31%, 24 hours» / «…, since 00:00 UTC».
 */
@Composable
private fun RangeChangeHeader(change: Double?, periodShort: String, periodLong: String, sincePhrase: Boolean) {
    val context = LocalContext.current
    val formatted = PriceFormat.changePercent(change)
    val value = when {
        change == null -> "—"
        formatted != null -> "${PriceFormat.changeArrow(change)} $formatted"
        else -> PriceFormat.zeroPercent()
    }
    val color = if (change == null || formatted == null) MaterialTheme.colorScheme.onSurfaceVariant
    else PriceColors.forChange(change)
    val spoken = A11yText.change(context, change) + ", " + periodLong
    Text(
        text = stringResource(if (sincePhrase) R.string.sheet_chart_change_since else R.string.sheet_chart_change, value, periodShort),
        style = MaterialTheme.typography.labelLarge.amountNumbers(),
        fontWeight = FontWeight.SemiBold,
        color = color,
        maxLines = 1,
        modifier = Modifier.clearAndSetSemantics { contentDescription = spoken }
    )
}

/**
 * Zeichnet mit der Geometrie des Einzel-Widgets ([WidgetChartGeometry]) auf ein Compose-
 * Canvas — gleiche Farben und Regeln wie [com.cryptochecker.app.widget.WidgetChartRenderer].
 * Für Screenreader ein Element mit dem Chart-Satz (Start, Ende, Hoch, Tief).
 */
@Composable
private fun SheetChartCanvas(
    candles: List<WidgetCandle>,
    type: WidgetChartType,
    /** Zeitraum für den Screenreader («24 hours», «Since 00:00 UTC» …). */
    periodLong: String,
    widgetRange: WidgetChartRange,
    range: SheetChartRange,
    quote: String,
    currentPrice: Double?,
) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val measurer = rememberTextMeasurer()
    val holder = remember { GeometryHolder() }
    var scrub by remember(candles, type) { mutableStateOf<Int?>(null) }

    val upColor = PriceColors.up
    val downColor = PriceColors.down
    val accent = MaterialTheme.colorScheme.primary
    val onAccent = MaterialTheme.colorScheme.onPrimary
    val secondary = MaterialTheme.colorScheme.onSurfaceVariant
    val marker = MaterialTheme.colorScheme.onSurface
    val highContrast = LocalHighContrast.current
    val fontScale = LocalDensity.current.fontScale.takeIf { it > 0f } ?: 1f
    val labelStyle = MaterialTheme.typography.labelSmall.amountNumbers().copy(
        fontSize = (LABEL_SP * min(fontScale, MAX_FONT_SCALE) / fontScale).sp,
        fontWeight = FontWeight.Medium,
        color = secondary,
    )
    val tagStyle = labelStyle.copy(fontWeight = FontWeight.SemiBold, color = onAccent)

    val summary = A11yText.chart(
        context,
        periodLong,
        WidgetChartGeometry.summary(candles, type),
    ) { PriceFormat.priceWithCurrency(it, quote) }

    // Datum/Uhrzeit beim Ziehen in der Ortszeit; 12/24 h nach Systemeinstellung
    val is24 = android.text.format.DateFormat.is24HourFormat(context)
    val timeFormat = remember(range, is24) {
        android.icu.text.DateFormat.getInstanceForSkeleton(
            range.timeTemplate.replace("j", if (is24) "H" else "h"),
            Locale.getDefault(),
        )
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp)
            .height(CHART_HEIGHT)
            .clearAndSetSemantics { contentDescription = summary }
            .pointerInput(candles, type) {
                // Waagrecht ziehen oder lange drücken = Kerze zeigen; senkrecht bleibt dem Blatt
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val slop = viewConfiguration.touchSlop
                    var total = Offset.Zero
                    var x = down.position.x
                    val start: Boolean? = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                        var decided: Boolean? = null
                        while (decided == null) {
                            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id }
                            if (change == null || !change.pressed || change.isConsumed) {
                                decided = false
                            } else {
                                total += change.positionChange()
                                x = change.position.x
                                if (abs(total.x) > slop && abs(total.x) > abs(total.y)) {
                                    change.consume()
                                    decided = true
                                } else if (abs(total.y) > slop) {
                                    decided = false
                                }
                            }
                        }
                        decided
                    }
                    // null = lange gedrückt, ohne sich zu bewegen
                    if (start == false) return@awaitEachGesture

                    fun show(atX: Float) {
                        val geo = holder.geometry ?: return
                        val index = SheetChart.scrubIndex(atX, geo.plotLeft, geo.slotWidth, candles.size)
                        // Leichtes Ticken je neuer Kerze (folgt den System-Einstellungen)
                        if (candles.size <= MAX_TICK_CANDLES && SheetChart.isNewCandle(scrub, index)) {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        }
                        scrub = index
                    }
                    show(x)
                    while (true) {
                        val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        change.consume()
                        show(change.position.x)
                    }
                    scrub = null
                }
            }
            .drawWithCache {
                val dp = density
                val levels = WidgetChartGeometry.levels(candles, type)
                val tagPrice = currentPrice?.takeIf { it > 0.0 } ?: candles.last().close
                val labelLayouts: Map<LevelKind, TextLayoutResult> = mapOf(
                    LevelKind.HIGH to measurer.measure(PriceFormat.price(levels.high), labelStyle),
                    LevelKind.MID to measurer.measure(PriceFormat.price(levels.mid), labelStyle),
                    LevelKind.LOW to measurer.measure(PriceFormat.price(levels.low), labelStyle),
                )
                val tagLayout = measurer.measure(PriceFormat.price(tagPrice), tagStyle)
                val gap = 3f * dp
                val tagPadH = 3f * dp
                val tagPadV = 1.5f * dp
                val labelWidth = labelLayouts.values.maxOf { it.size.width }.toFloat()
                val column = min(gap + max(labelWidth, tagLayout.size.width.toFloat()) + 2 * tagPadH, size.width * 0.42f)
                val labelHeight = labelLayouts.values.maxOf { it.size.height }.toFloat()
                val geo = WidgetChartGeometry(
                    candles = candles,
                    type = type,
                    gridUnit = widgetRange.gridUnit,
                    intervalMillis = widgetRange.intervalMillis,
                    zone = ZoneId.systemDefault(),
                    width = size.width,
                    height = size.height,
                    labelColumnWidth = column,
                    labelHeight = labelHeight,
                    tagHeight = tagLayout.size.height + 2 * tagPadV,
                    compact = false,
                    gap = 1f * dp,
                    currentPrice = tagPrice,
                )
                holder.geometry = geo

                val hairline = max(1f, 0.6f * dp)
                val gridColor = secondary.copy(alpha = secondary.alpha * if (highContrast) 0.35f else 0.12f)
                val levelColor = secondary.copy(alpha = secondary.alpha * if (highContrast) 0.35f else 0.20f)
                val lineColor = if (geo.up) upColor else downColor
                val linePath = Path().apply {
                    geo.linePoints.forEachIndexed { i, (px, py) -> if (i == 0) moveTo(px, py) else lineTo(px, py) }
                }
                val fillPath = Path().apply {
                    addPath(linePath)
                    lineTo(geo.linePoints.last().first, geo.plotBottom)
                    lineTo(geo.linePoints.first().first, geo.plotBottom)
                    close()
                }
                val dash = PathEffect.dashPathEffect(floatArrayOf(3f * dp, 2.5f * dp), 0f)
                val tagLeft = geo.plotRight + gap

                onDrawBehind {
                    // Zeitgitter und Preisstufen
                    for (gx in geo.gridXs) drawLine(gridColor, Offset(gx, 0f), Offset(gx, size.height), hairline)
                    for (price in listOf(levels.high, levels.mid, levels.low)) {
                        val y = geo.y(price)
                        drawLine(levelColor, Offset(geo.plotLeft, y), Offset(geo.plotRight, y), hairline)
                    }
                    // Kerzen oder Linie
                    when (type) {
                        WidgetChartType.CANDLES -> for (c in geo.candleShapes) {
                            val color = if (c.up) upColor else downColor
                            val wick = min(1f * dp, c.bodyWidth)
                            drawRect(color, Offset(c.x - wick / 2f, c.wickTop), Size(wick, c.wickBottom - c.wickTop))
                            drawRect(color, Offset(c.x - c.bodyWidth / 2f, c.bodyTop), Size(c.bodyWidth, c.bodyBottom - c.bodyTop))
                        }
                        WidgetChartType.LINE -> {
                            drawPath(fillPath, lineColor.copy(alpha = 40f / 255f))
                            drawPath(
                                linePath,
                                lineColor,
                                style = Stroke(width = 1.5f * dp, cap = StrokeCap.Round, join = StrokeJoin.Round)
                            )
                        }
                    }
                    // Aktueller Kurs: gestrichelte Akzentlinie und Etikett
                    drawLine(
                        accent.copy(alpha = if (highContrast) 1f else 0.7f),
                        Offset(geo.plotLeft, geo.currentY),
                        Offset(geo.plotRight, geo.currentY),
                        strokeWidth = max(1f, 0.8f * dp),
                        pathEffect = dash,
                    )
                    drawRoundRect(
                        accent,
                        topLeft = Offset(tagLeft, geo.tagSpan.top),
                        size = Size(size.width - tagLeft, geo.tagSpan.bottom - geo.tagSpan.top),
                        cornerRadius = CornerRadius(3f * dp),
                    )
                    drawText(
                        tagLayout,
                        topLeft = Offset(tagLeft + tagPadH, (geo.tagSpan.top + geo.tagSpan.bottom) / 2f - tagLayout.size.height / 2f),
                    )
                    // Beschriftungen der Preisstufen (das Etikett gewinnt)
                    for (label in geo.labels) {
                        val layout = labelLayouts.getValue(label.kind)
                        drawText(layout, topLeft = Offset(tagLeft + tagPadH, label.y - layout.size.height / 2f))
                    }
                    // Ziehen: senkrechte Markierung und Punkt auf dem Schluss
                    scrub?.let { i ->
                        val candle = candles.getOrNull(i) ?: return@let
                        val mx = geo.xCenter(i)
                        drawLine(
                            marker.copy(alpha = 0.6f),
                            Offset(mx, geo.plotTop),
                            Offset(mx, geo.plotBottom),
                            strokeWidth = max(1f, 1f * dp),
                        )
                        drawCircle(marker, radius = 3.5f * dp, center = Offset(mx, geo.y(candle.close)))
                    }
                }
            }
    ) {
        // Etikett beim Ziehen: «98’450 USDT · Di 14:00» und «▲ +1.20%», über der Markierung
        val index = scrub
        val candle = index?.let { candles.getOrNull(it) }
        if (index != null && candle != null) {
            val change = SheetChart.scrubChange(candles, type, index)
            val formatted = PriceFormat.changePercent(change)
            val changeText = when {
                change == null -> "—"
                formatted != null -> "${PriceFormat.changeArrow(change)} $formatted"
                else -> PriceFormat.zeroPercent()
            }
            val changeColor = if (change == null || formatted == null) secondary else PriceColors.forChange(change)
            Column(
                modifier = Modifier
                    .layout { measurable, constraints ->
                        val placeable = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
                        val centerX = holder.geometry?.xCenter(index) ?: 0f
                        val left = SheetChart.labelLeft(centerX, placeable.width.toFloat(), 0f, constraints.maxWidth.toFloat())
                        layout(constraints.maxWidth, placeable.height) {
                            // Absolut (nicht gespiegelt): die Zeitachse läuft immer von links nach rechts
                            placeable.place(left.roundToInt(), 0)
                        }
                    }
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(
                    text = BidiText.isolate(PriceFormat.priceWithCurrency(candle.close, quote)) + " · " +
                        timeFormat.format(Date(candle.openTime)),
                    style = MaterialTheme.typography.labelMedium.amountNumbers(),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                )
                Text(
                    text = changeText,
                    style = MaterialTheme.typography.labelSmall.amountNumbers(),
                    fontWeight = FontWeight.SemiBold,
                    color = changeColor,
                    maxLines = 1,
                )
            }
        }
    }
}
