package com.cryptochecker.app.ui.features.watchlist

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
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
import androidx.compose.ui.res.stringResource
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
import com.cryptochecker.app.ui.components.SkeletonBlock
import com.cryptochecker.app.ui.components.SkeletonLine
import com.cryptochecker.app.ui.components.SkeletonPulse
import com.cryptochecker.app.ui.theme.LocalHighContrast
import com.cryptochecker.app.ui.theme.PriceColors
import com.cryptochecker.app.ui.theme.amountNumbers
import com.cryptochecker.app.util.A11yText
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

/** Lage der zuletzt gezeichneten Geometrie für das Ziehen (kein Compose-Zustand). */
private class GeometryHolder {
    var geometry: WidgetChartGeometry? = null
}

/**
 * Kurs-Chart oben im Aktionsblatt (unter Kopf und Kurs, über Alarm · Warum? · Favorit):
 * Veränderung über den Zeitraum, Chart wie das Einzel-Widget (Kerzen oder Linie, Preisstufen,
 * Gitter, Kurs-Etikett), darunter 24h · 7T · 30T und Kerzen/Linie. Lange drücken oder
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
    val result by produceState(cached(watch, range), watch.id, range) {
        // Beim Wechsel des Zeitraums: Zwischenspeicher sofort, sonst Platzhalter bis geladen
        value = cached(watch, range)
        if (value == null) value = load(watch, range)
    }
    if (result == SheetChartResult.Unsupported) return

    val type = if (line) WidgetChartType.LINE else WidgetChartType.CANDLES
    val widgetRange = WidgetChartRange.valueOf(range.name)
    Column(modifier = modifier.fillMaxWidth()) {
        when (val r = result) {
            is SheetChartResult.Ready -> {
                RangeChangeHeader(SheetChart.rangeChange(r.candles, type), widgetRange)
                SheetChartCanvas(
                    candles = r.candles,
                    type = type,
                    widgetRange = widgetRange,
                    range = range,
                    quote = watch.quoteAsset,
                    currentPrice = watch.lastPrice,
                )
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

        // Zeitraum und Chart-Art: Chips mit Auswahl-Zustand (Screenreader: langer Zeitraum)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
        ) {
            SheetChartRange.entries.forEach { option ->
                val optionRange = WidgetChartRange.valueOf(option.name)
                val spoken = stringResource(optionRange.labelRes)
                FilterChip(
                    selected = option == range,
                    onClick = { range = option },
                    label = {
                        Text(
                            stringResource(optionRange.shortLabelRes),
                            maxLines = 1,
                            modifier = Modifier.semantics { contentDescription = spoken }
                        )
                    }
                )
            }
            Spacer(Modifier.width(6.dp))
            FilterChip(
                selected = !line,
                onClick = { if (line) onLineChange(false) },
                label = { Text(stringResource(R.string.widget_chart_candles), maxLines = 1) }
            )
            FilterChip(
                selected = line,
                onClick = { if (!line) onLineChange(true) },
                label = { Text(stringResource(R.string.widget_chart_line), maxLines = 1) }
            )
        }
    }
}

/** «▲ +2.31% in 24h» in der Kursfarbe; Screenreader: «up 2.31%, 24h». */
@Composable
private fun RangeChangeHeader(change: Double?, widgetRange: WidgetChartRange) {
    val context = LocalContext.current
    val formatted = PriceFormat.changePercent(change)
    val value = when {
        change == null -> "—"
        formatted != null -> "${PriceFormat.changeArrow(change)} $formatted"
        else -> "0.00%"
    }
    val color = if (change == null || formatted == null) MaterialTheme.colorScheme.onSurfaceVariant
    else PriceColors.forChange(change)
    val spoken = A11yText.change(context, change) + ", " + stringResource(widgetRange.labelRes)
    Text(
        text = stringResource(R.string.sheet_chart_change, value, stringResource(widgetRange.shortLabelRes)),
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
        stringResource(widgetRange.labelRes),
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
                        if (SheetChart.isNewCandle(scrub, index)) {
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
                else -> "0.00%"
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
                    text = PriceFormat.priceWithCurrency(candle.close, quote) + " · " + timeFormat.format(Date(candle.openTime)),
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
