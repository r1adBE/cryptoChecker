package com.cryptochecker.app.ui.features.watchlist

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cryptochecker.app.domain.watch.SheetChart
import com.cryptochecker.app.domain.watch.SheetChartRange
import com.cryptochecker.app.domain.widget.LevelKind
import com.cryptochecker.app.domain.widget.WidgetCandle
import com.cryptochecker.app.domain.widget.WidgetChartGeometry
import com.cryptochecker.app.domain.widget.WidgetChartType
import com.cryptochecker.app.ui.components.ChartPointStep
import com.cryptochecker.app.ui.components.HapticEvent
import com.cryptochecker.app.ui.components.chartPointSemantics
import com.cryptochecker.app.ui.components.rememberHaptics
import com.cryptochecker.app.ui.theme.AppColors
import com.cryptochecker.app.ui.theme.LocalHighContrast
import com.cryptochecker.app.ui.theme.PriceColors
import com.cryptochecker.app.ui.theme.Spacing
import com.cryptochecker.app.ui.theme.amountNumbers
import com.cryptochecker.app.util.A11yText
import com.cryptochecker.app.util.PriceFormat
import com.cryptochecker.app.widget.WidgetChartRange
import com.cryptochecker.resources.R
import java.time.ZoneId
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Ein gezeichneter Verlauf: Zustand der Überblendung. */
internal data class ChartFrame(
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
 * Zeichnet mit der Geometrie des Einzel-Widgets ([WidgetChartGeometry]) auf ein Compose-
 * Canvas — gleiche Farben und Regeln wie [com.cryptochecker.app.widget.WidgetChartRenderer].
 * Für Screenreader ein Element mit dem Chart-Satz (Start, Ende, Hoch, Tief); die Aktionen «Nächster Punkt» /
 * «Vorheriger Punkt» sagen Kerze für Kerze Zeit und Schlusskurs an ([chartPointSemantics]).
 */
@Composable
internal fun SheetChartCanvas(
    candles: List<WidgetCandle>,
    type: WidgetChartType,
    /** Zeitraum für den Screenreader («24 hours», «Since 00:00 UTC» …). */
    periodLong: String,
    widgetRange: WidgetChartRange,
    range: SheetChartRange,
    quote: String,
    currentPrice: Double?,
    /** Zeitzone der Uhrzeiten und Raster ([ChangeBasisMath.chartZone]). */
    zone: ZoneId,
    /** Stablecoin: Achse mindestens ±0.5 % hoch ([SheetChart.axisLevels]). */
    stable: Boolean = false,
) {
    val context = LocalContext.current
    val haptics = rememberHaptics()
    val measurer = rememberTextMeasurer()
    val holder = remember { GeometryHolder() }
    var scrub by remember(candles, type) { mutableStateOf<Int?>(null) }
    // Vom Screenreader gewählte Kerze (Aktionen «Nächster/Vorheriger Punkt»)
    var a11yPoint by remember(candles, type) { mutableStateOf<Int?>(null) }
    // Volumen-Balken nur, wenn die Kerzen Umsätze tragen (sonst null = ausblenden)
    val volumes = remember(candles) { SheetChart.volumeFractions(candles.map { it.volume }) }

    val upColor = PriceColors.up
    val downColor = PriceColors.down
    val accent = MaterialTheme.colorScheme.primary
    val onAccent = AppColors.onAccent
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

    // Texte messen (Main-Thread), Geometrie danach abseits davon rechnen: Gezeichnet wird nur
    // fertige Geometrie (1 Jahr = 365 Kerzen)
    val dp = LocalDensity.current.density
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    val levels = remember(candles, type, stable) { SheetChart.axisLevels(candles, type, stable) }
    val tagPrice = currentPrice?.takeIf { it > 0.0 } ?: candles.last().close
    val labelLayouts: Map<LevelKind, TextLayoutResult> = remember(levels, labelStyle) {
        mapOf(
            LevelKind.HIGH to measurer.measure(PriceFormat.price(levels.high), labelStyle),
            LevelKind.MID to measurer.measure(PriceFormat.price(levels.mid), labelStyle),
            LevelKind.LOW to measurer.measure(PriceFormat.price(levels.low), labelStyle),
        )
    }
    val tagLayout = remember(tagPrice, tagStyle) { measurer.measure(PriceFormat.price(tagPrice), tagStyle) }
    val gap = 3f * dp
    val tagPadH = 3f * dp
    val tagPadV = 1.5f * dp
    val labelWidth = labelLayouts.values.maxOf { it.size.width }.toFloat()
    val column = min(gap + max(labelWidth, tagLayout.size.width.toFloat()) + 2 * tagPadH, canvasSize.width * 0.42f)
    val labelHeight = labelLayouts.values.maxOf { it.size.height }.toFloat()
    val tagHeight = tagLayout.size.height + 2 * tagPadV
    val geometry by produceState<WidgetChartGeometry?>(
        null, candles, type, canvasSize, levels, tagPrice, column, labelHeight, tagHeight, zone, widgetRange,
    ) {
        val size = canvasSize
        if (size.width <= 0 || size.height <= 0) return@produceState
        value = withContext(Dispatchers.Default) {
            WidgetChartGeometry(
                candles = candles,
                type = type,
                gridUnit = widgetRange.gridUnit,
                intervalMillis = widgetRange.intervalMillis,
                zone = zone,
                width = size.width.toFloat(),
                height = size.height.toFloat(),
                labelColumnWidth = column,
                labelHeight = labelHeight,
                tagHeight = tagHeight,
                compact = false,
                gap = 1f * dp,
                currentPrice = tagPrice,
                levelsOverride = levels,
            ).also {
                // Träge Teile gleich hier rechnen, nicht erst beim Zeichnen
                it.candleShapes
                it.linePoints
            }
        }
    }
    SideEffect { holder.geometry = geometry }

    val summary = A11yText.chart(
        context,
        periodLong,
        WidgetChartGeometry.summary(candles, type),
    ) { PriceFormat.priceWithCurrency(it, quote) }

    // Datum/Uhrzeit beim Ziehen in der Chart-Zeitzone (wie die %-Basis); 12/24 h nach Systemeinstellung
    val is24 = android.text.format.DateFormat.is24HourFormat(context)
    val locale = LocalConfiguration.current.locales[0] ?: Locale.ROOT
    val timeFormat = remember(range, is24, zone, locale) {
        android.icu.text.DateFormat.getInstanceForSkeleton(
            range.timeSkeleton(is24),
            locale,
        ).apply { timeZone = icuTimeZone(zone) }
    }
    val pointCandle = a11yPoint?.let { candles.getOrNull(it) }
    val pointText = pointCandle?.let {
        stringResource(
            R.string.label_value,
            timeFormat.format(Date(it.openTime)),
            PriceFormat.priceWithCurrency(it.close, quote),
        )
    }
    val nextLabel = stringResource(R.string.a11y_chart_next_point)
    val previousLabel = stringResource(R.string.a11y_chart_previous_point)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = Spacing.sm)
            .height(CHART_HEIGHT)
            .onSizeChanged { canvasSize = it }
            .chartPointSemantics(summary, candles.size, pointText, nextLabel, previousLabel) { forward ->
                a11yPoint = if (forward) {
                    ChartPointStep.next(a11yPoint, candles.size)
                } else {
                    ChartPointStep.previous(a11yPoint, candles.size)
                }
            }
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
                        val index = SheetChart.scrubIndex(atX, geo.plotLeft, geo.slotWidth, geo.candles.size)
                        // Leichtes Ticken je neuer Kerze (folgt den System-Einstellungen)
                        if (candles.size <= MAX_TICK_CANDLES && SheetChart.isNewCandle(scrub, index)) {
                            haptics.play(HapticEvent.SELECTION)
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
            .sheetChartDrawing(
                geometry = { geometry },
                scrub = { scrub },
                type = type,
                volumes = volumes,
                paint = SheetChartPaint(upColor, downColor, accent, secondary, marker, highContrast),
                tag = SheetChartTag(tagLayout, labelLayouts, gap, tagPadH),
            )
    ) {
        // Etikett beim Ziehen: «98’450 USDT · Di 14:00» und die Anzeige «+1.20%», über der Markierung
        val index = scrub
        val candle = index?.let { candles.getOrNull(it) }
        if (index != null && candle != null) {
            SheetChartScrubLabel(
                candles = candles,
                type = type,
                index = index,
                candle = candle,
                showVolume = volumes != null,
                quote = quote,
                timeFormat = timeFormat,
                xCenter = { holder.geometry?.xCenter(index) ?: 0f },
                labelColor = secondary,
            )
        }
    }
}

/** ICU-Zeitzone zu [zone]; feste Versätze als «GMT+08:00» (ICU kennt «+08:00» nicht). */
internal fun icuTimeZone(zone: ZoneId): android.icu.util.TimeZone {
    val id = (zone as? java.time.ZoneOffset)?.let { if (it.totalSeconds == 0) "GMT" else "GMT" + it.id } ?: zone.id
    return android.icu.util.TimeZone.getTimeZone(id)
}
