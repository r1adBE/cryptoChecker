package com.cryptochecker.app.ui.features.portfolio

import android.content.Context
import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.CacheDrawScope
import androidx.compose.ui.draw.DrawResult
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.cryptochecker.app.R
import com.cryptochecker.app.domain.portfolio.PortfolioCandle
import com.cryptochecker.app.domain.portfolio.PortfolioCandleBucket
import com.cryptochecker.app.domain.portfolio.PortfolioCandles
import com.cryptochecker.app.domain.portfolio.PortfolioChartAxis
import com.cryptochecker.app.domain.portfolio.PortfolioHistoryPoint
import com.cryptochecker.app.domain.portfolio.PortfolioInsights
import com.cryptochecker.app.domain.watch.SheetChart
import com.cryptochecker.app.ui.components.ChangePill
import com.cryptochecker.app.ui.components.withSmallQuote
import com.cryptochecker.app.ui.theme.ChipShape
import com.cryptochecker.app.ui.theme.PriceColors
import com.cryptochecker.app.ui.theme.amountNumbers
import com.cryptochecker.app.util.BidiText
import com.cryptochecker.app.util.PriceFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import com.cryptochecker.app.ui.components.HapticEvent
import com.cryptochecker.app.ui.components.rememberHaptics
import com.cryptochecker.app.ui.components.HapticPlayer

/** Höhe der Chart-Fläche im Wertverlauf (wie im Aktionsblatt eines Paars). */
internal val HISTORY_CHART_HEIGHT = 180.dp

/**
 * Wertverlauf wie das Chart im Aktionsblatt eines Paars: als Linie (Tagespunkte, mit Fläche) oder
 * als Kerzen ([candles], [PortfolioCandles.build] — bei langen Zeiträumen Wochen- bzw.
 * Monatskerzen); drei Stufen mit Werten rechts, feine senkrechte Linien an Kalendergrenzen,
 * gestrichelte Akzentlinie mit Etikett beim heutigen Wert und der höchste Tageswert des Zeitraums
 * als «Hoch …» am Punkt. «Beträge verbergen»: alle Werte als «•••».
 * Lange drücken oder waagrecht ziehen zeigt Wert, Datum und Veränderung seit Beginn des Punkts bzw.
 * der Kerze unter dem Finger; senkrechtes Wischen bleibt dem Bildschirm (Scrollen).
 * [pnl] (Gewinn/Verlust): eigene Skala, die 0 immer einschliesst ([PortfolioChartAxis.pnlBounds]), gestrichelte
 * Nulllinie, Beschriftung und Etikett mit Vorzeichen; die Linie über 0 in «steigend», darunter in «fallend»
 * (Fläche bis zur Nulllinie); kein «Hoch …»; beim Ziehen Betrag und Datum ohne Prozent.
 */
@Composable
internal fun HistoryChart(
    points: List<PortfolioHistoryPoint>,
    unit: String,
    change: Double,
    candles: Boolean,
    modifier: Modifier = Modifier,
    pnl: Boolean = false,
) {
    val context = LocalContext.current
    val haptics = rememberHaptics()
    val measurer = rememberTextMeasurer()
    val paint = rememberChartPaint()
    val hidden = LocalHidePortfolioAmounts.current
    val holder = remember { FrameHolder() }
    val lineColor = plColor(change)
    val upColor = PriceColors.up
    val downColor = PriceColors.down

    val bucket = remember(points) {
        PortfolioCandles.bucketFor(if (points.isEmpty()) 0 else (points.last().epochDay - points.first().epochDay + 1).toInt())
    }
    val candleList = remember(points, candles) { if (candles) PortfolioCandles.build(points) else null }
    val values = remember(points) { points.map { it.value } }
    // Höchster Tageswert des Zeitraums: Punkt und «Hoch …» (nicht beim Gewinn/Verlust)
    val maxIndex = remember(values, pnl) { if (pnl) null else PortfolioChartAxis.maxIndex(values) }
    val zeroLine = MaterialTheme.colorScheme.outline
    val highText = maxIndex?.let {
        stringResource(R.string.portfolio_chart_high, if (hidden) PortfolioInsights.HIDDEN else axisValue(values[it]))
    }
    val spans = remember(points, candleList) {
        candleList?.map { it.firstDay..it.lastDay } ?: points.map { it.epochDay..it.epochDay }
    }
    val count = spans.size
    var scrub by remember(points, candles) { mutableStateOf<Int?>(null) }

    Box(
        modifier = modifier.historyScrub(
            key = spans,
            count = count,
            haptics = haptics,
            indexAt = { x -> holder.frame?.let { SheetChart.scrubIndex(x, it.plotLeft, it.slot, count) } },
        ) { scrub = it }
            .drawWithCache {
                if (count < 2) return@drawWithCache onDrawBehind { }
                val raw = candleList?.let { PortfolioChartAxis.candleBounds(it) } ?: (values.min() to values.max())
                val bounds = if (pnl) PortfolioChartAxis.pnlBounds(raw.first, raw.second) else raw
                val format: (Double) -> String = {
                    if (hidden) PortfolioInsights.HIDDEN else if (pnl) signedAxisValue(it) else axisValue(it)
                }
                val (frame, texts) = measureFrame(
                    measurer, paint, spans, bounds.first, bounds.second, format, format(values.last())
                )
                holder.frame = frame
                val maxSlot = maxIndex?.let { PortfolioChartAxis.slotOfDay(spans, points[it].epochDay) }
                val highLayout = highText?.let {
                    measurer.measure(it, paint.labelStyle.copy(fontWeight = FontWeight.SemiBold, color = paint.marker))
                }
                drawContent(frame, paint) {
                    if (pnl) drawZeroLine(frame, zeroLine)
                    if (candleList != null) {
                        drawCandles(frame, candleList, upColor, downColor)
                    } else if (pnl) {
                        drawPnlLine(frame, values, upColor, downColor)
                    } else {
                        drawValueLine(frame, values, lineColor)
                    }
                    if (maxIndex != null && maxSlot != null && highLayout != null) {
                        drawHighMark(frame, paint, maxSlot, values[maxIndex], highLayout)
                    }
                    scrub?.let { i ->
                        val v = candleList?.getOrNull(i)?.close ?: values.getOrNull(i)
                        if (v != null) drawScrubMarker(frame, paint, i, v)
                    }
                    drawAxis(frame, paint, texts, frame.y(values.last()).coerceIn(frame.plotTop, frame.plotBottom), dashed = true)
                }
            }
    ) {
        val index = scrub
        if (index != null && index in 0 until count) {
            val start = candleList?.firstOrNull()?.open ?: values.first()
            val (value, date) = if (candleList != null) {
                candleList[index].close to candleDate(context, candleList[index], bucket)
            } else {
                values[index] to PortfolioFormat.date(dayMillis(points[index].epochDay))
            }
            val amount = if (pnl) signedChartValue(value, unit) else PriceFormat.valueWithCurrency(value, unit)
            ScrubLabel(
                centerX = { holder.frame?.x(index) ?: 0f },
                title = withSmallQuote(BidiText.isolate(maskAmount(amount))) + AnnotatedString(" · $date"),
                change = SheetChart.changePercent(start, value),
                showChange = !pnl,
            )
        }
    }
}

/** Zeichnet in fester Reihenfolge: Gitter, Inhalt ([body]), Achse — als Ergebnis von drawWithCache. */
internal fun CacheDrawScope.drawContent(
    frame: ChartFrame,
    paint: ChartPaint,
    body: DrawScope.() -> Unit,
): DrawResult = onDrawBehind {
    drawGrid(frame, paint)
    body()
}

/** «+1’234.56 CHF» / «−12.00 CHF» / «0.00 CHF» für das Etikett beim Ziehen (Gewinn/Verlust). */
private fun signedChartValue(value: Double, unit: String): String {
    val sign = when {
        PortfolioFormat.isZero(value) -> ""
        value > 0 -> "+"
        else -> "−"
    }
    return sign + PriceFormat.valueWithCurrency(abs(value), unit)
}

/** Datum einer Kerze: Tag, Woche («6.–12. Okt. 2026») bzw. Monat («Okt. 2026»), Format der Sprache. */
private fun candleDate(context: Context, candle: PortfolioCandle, bucket: PortfolioCandleBucket): String = when (bucket) {
    PortfolioCandleBucket.DAY -> PortfolioFormat.date(dayMillis(candle.lastDay))
    PortfolioCandleBucket.WEEK -> DateUtils.formatDateRange(
        context,
        dayMillis(candle.firstDay),
        dayMillis(candle.lastDay),
        DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH or DateUtils.FORMAT_SHOW_YEAR,
    )
    PortfolioCandleBucket.MONTH -> android.icu.text.DateFormat.getInstanceForSkeleton("yMMM", Locale.getDefault())
        .format(Date(dayMillis(candle.lastDay)))
}

/**
 * Etikett beim Ziehen über der Markierung wie im Aktionsblatt: «12’345.67 CHF · 7. Okt. 2026» und
 * darunter die Anzeige «+1.20%» (Veränderung seit Beginn des Zeitraums; [change] null = «—»; ohne
 * [showChange] nur die erste Zeile).
 */
@Composable
private fun ScrubLabel(centerX: () -> Float, title: AnnotatedString, change: Double?, showChange: Boolean = true) {
    Column(
        modifier = Modifier
            .scrubLabelPlacement(centerX)
            .clip(ChipShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium.amountNumbers(),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
        )
        if (showChange) ChangePill(change = change, dashWhenMissing = true, modifier = Modifier.padding(top = 2.dp))
    }
}

/** Mittig über [centerX], aber ganz in der Breite ([SheetChart.labelLeft]); absolut, nicht gespiegelt. */
internal fun Modifier.scrubLabelPlacement(centerX: () -> Float): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
    val left = SheetChart.labelLeft(centerX(), placeable.width.toFloat(), 0f, constraints.maxWidth.toFloat())
    layout(constraints.maxWidth, placeable.height) {
        // Die Zeitachse läuft immer von links nach rechts
        placeable.place(left.roundToInt(), 0)
    }
}

/**
 * Ziehen im Wertverlauf (gemeinsam für [HistoryChart] und [HistoryCompareChart]): lange drücken
 * oder waagrecht ziehen wählt den Slot unter dem Finger ([indexAt]), mit leichtem Ticken je neuem
 * Slot (bis [MAX_TICK_POINTS]); [onIndex] bekommt jeden neuen Slot und beim Loslassen null.
 * Senkrechtes Wischen bleibt dem Bildschirm (Scrollen).
 */
internal fun Modifier.historyScrub(
    key: Any?,
    count: Int,
    haptics: HapticPlayer,
    indexAt: (Float) -> Int?,
    onIndex: (Int?) -> Unit,
): Modifier = pointerInput(key, count) {
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

        var last: Int? = null
        fun show(atX: Float) {
            val index = indexAt(atX)
            if (index == last) return
            // Leichtes Ticken je neuem Slot (folgt den System-Einstellungen)
            if (index != null && count <= MAX_TICK_POINTS) haptics.play(HapticEvent.SELECTION)
            last = index
            onIndex(index)
        }
        show(x)
        while (true) {
            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
            if (!change.pressed) break
            change.consume()
            show(change.position.x)
        }
        onIndex(null)
    }
}
