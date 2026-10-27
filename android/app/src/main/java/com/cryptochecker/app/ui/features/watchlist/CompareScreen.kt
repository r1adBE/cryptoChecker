@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.cryptochecker.app.ui.features.watchlist

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.cryptochecker.app.R
import com.cryptochecker.app.data.local.model.WatchEntity
import com.cryptochecker.app.domain.watch.ChangeBasisMath
import com.cryptochecker.app.domain.watch.CompareFigures
import com.cryptochecker.app.domain.watch.CompareMath
import com.cryptochecker.app.domain.watch.ComparePoint
import com.cryptochecker.app.domain.watch.SheetChartRange
import com.cryptochecker.app.domain.watch.SheetChartResult
import com.cryptochecker.app.domain.watch.shownChange24h
import com.cryptochecker.app.ui.theme.Spacing
import com.cryptochecker.app.ui.theme.tabularNumbers
import com.cryptochecker.app.util.A11yText
import com.cryptochecker.app.util.ChangeBasisText
import com.cryptochecker.app.util.PriceFormat
import com.cryptochecker.app.widget.WidgetChartRange
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * Vergleichsansicht (Merkliste › markieren › «Vergleichen», 2–4 Paare, Wunsch 27.10.2026): oben eine Linie je Paar in %
 * ab Beginn des Zeitraums (24 h bzw. «Heute» / 7 T / 30 T / 1 J), darunter je Paar Kurs, 24 h, 7 T, 30 T und Abstand
 * zum 30-Tage-Hoch ([CompareMath]). Kerzen wie im Aktionsblatt ([load], derselbe Zwischenspeicher). Jede Linie hat
 * eigene Farbe UND eigene Strichart (Farbsehschwäche). Eigene Seite mit Zurück-Pfeil wie «Warum?». Wie iOS
 * `CompareScreen`.
 */
@Composable
internal fun CompareScreen(
    watches: List<WatchEntity>,
    load: suspend (WatchEntity, SheetChartRange) -> SheetChartResult,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val basis = LocalChangeView.current.basis
    val changeView = LocalChangeView.current
    var range by rememberSaveable { mutableStateOf(SheetChartRange.WEEK) }
    val ids = watches.map { it.id }
    // Linie je Paar (null = lädt, leer = keine Kursdaten)
    val series by produceState<List<List<ComparePoint>>?>(null, ids, range, basis) {
        value = null
        val today = range == SheetChartRange.DAY && basis.isDay
        val dayStart = if (today) ChangeBasisMath.dayStart(basis, System.currentTimeMillis()) else null
        value = coroutineScope {
            watches.map { w ->
                async {
                    val candles = (load(w, range) as? SheetChartResult.Ready)?.candles.orEmpty()
                    val shown = if (dayStart != null) ChangeBasisMath.sinceDayStart(candles, dayStart) { it.openTime } else candles
                    CompareMath.normalized(shown)
                }
            }.awaitAll()
        }
    }
    // Kennzahlen aus den Tageskerzen des Jahres (30 T braucht eine Kerze von vor 30 Tagen), unabhängig vom Zeitraum
    val figures by produceState<List<CompareFigures>?>(null, ids) {
        val now = System.currentTimeMillis()
        value = coroutineScope {
            watches.map { w ->
                async {
                    val daily = (load(w, SheetChartRange.YEAR) as? SheetChartResult.Ready)?.candles.orEmpty()
                    CompareMath.figures(daily, w.lastPrice, now)
                }
            }.awaitAll()
        }
    }
    val colors = compareColors()
    val rangeShort = rangeShortLabel(range, basis.isDay)
    val rangeLong = if (range == SheetChartRange.DAY && basis.isDay) ChangeBasisText.longLabel(basis)
    else stringResource(WidgetChartRange.valueOf(range.name).labelRes)

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.compare_title), fontWeight = FontWeight.SemiBold) },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.action_back))
                        }
                    },
                )
            },
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Spacing.md, vertical = Spacing.sm),
            ) {
                RangeChips(range, basis.isDay) { range = it }
                val lines = series
                val chartSentence = lines?.let { all ->
                    val parts = watches.mapIndexed { i, w ->
                        stringResource(R.string.label_value, w.displayName,
                            A11yText.change(context, all.getOrNull(i)?.let(CompareMath::last)))
                    }
                    stringResource(R.string.a11y_compare_chart, rangeLong, parts.joinToString("; "))
                }
                CompareChart(
                    series = lines.orEmpty(),
                    colors = colors,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(220.dp)
                        .padding(top = Spacing.sm)
                        .semantics { contentDescription = chartSentence ?: "" },
                )
                Text(
                    stringResource(R.string.compare_basis),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = Spacing.xs),
                )
                Spacer(Modifier.height(Spacing.sm))
                watches.forEachIndexed { i, w ->
                    HorizontalDivider()
                    CompareRow(
                        watch = w,
                        index = i,
                        color = colors[i % colors.size],
                        line = lines?.getOrNull(i),
                        rangeShort = rangeShort,
                        change24h = changeView.shown(w.shownChange24h),
                        change24hLabel = ChangeBasisText.shortLabel(context, basis),
                        figures = figures?.getOrNull(i),
                    )
                }
            }
        }
    }
}

/** Kurzlabel des Zeitraums («24h», bei Tages-Basis «Heute», «7D» …). */
@Composable
private fun rangeShortLabel(range: SheetChartRange, dayBasis: Boolean): String =
    if (range == SheetChartRange.DAY && dayBasis) stringResource(R.string.sheet_chart_today)
    else stringResource(WidgetChartRange.valueOf(range.name).shortLabelRes)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RangeChips(range: SheetChartRange, dayBasis: Boolean, onSelect: (SheetChartRange) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
        SheetChartRange.entries.forEach { option ->
            FilterChip(
                selected = option == range,
                onClick = { onSelect(option) },
                label = { Text(rangeShortLabel(option, dayBasis), maxLines = 1) },
            )
        }
    }
}

/** Farben der Linien aus dem Thema (lesbar in hell und dunkel; Grün/Rot bleiben der Kursrichtung vorbehalten). */
@Composable
private fun compareColors(): List<Color> = listOf(
    MaterialTheme.colorScheme.primary,
    MaterialTheme.colorScheme.onSurface,
    MaterialTheme.colorScheme.tertiary,
    MaterialTheme.colorScheme.secondary,
)

/** Strichart je Linie: durchgezogen, gestrichelt, gepunktet, Strich-Punkt. */
private fun dash(index: Int): PathEffect? = when (index % 4) {
    0 -> null
    1 -> PathEffect.dashPathEffect(floatArrayOf(18f, 10f))
    2 -> PathEffect.dashPathEffect(floatArrayOf(4f, 8f))
    else -> PathEffect.dashPathEffect(floatArrayOf(18f, 8f, 4f, 8f))
}

/** Linien in % mit Null-Linie; Achse oben/unten mit der grössten bzw. kleinsten Änderung. */
@Composable
private fun CompareChart(series: List<List<ComparePoint>>, colors: List<Color>, modifier: Modifier) {
    val bounds = CompareMath.bounds(series)
    val grey = MaterialTheme.colorScheme.outlineVariant
    val label = MaterialTheme.colorScheme.onSurfaceVariant
    Column(modifier) {
        Text(
            bounds?.let { PriceFormat.changePercent(it.maxPercent) } ?: "",
            style = MaterialTheme.typography.labelSmall.tabularNumbers(),
            color = label,
            modifier = Modifier.clearAndSetSemantics { },
        )
        Canvas(Modifier.fillMaxWidth().weight(1f).padding(vertical = 4.dp)) {
            val b = bounds ?: return@Canvas
            val span = (b.maxTime - b.minTime).coerceAtLeast(1L).toFloat()
            val pSpan = (b.maxPercent - b.minPercent).toFloat()
            fun x(t: Long) = (t - b.minTime) / span * size.width
            fun y(p: Double) = size.height - ((p - b.minPercent).toFloat() / pSpan) * size.height
            drawLine(grey, Offset(0f, y(0.0)), Offset(size.width, y(0.0)), strokeWidth = 1.dp.toPx())
            series.forEachIndexed { i, points ->
                if (points.size < 2) return@forEachIndexed
                val path = Path().apply {
                    moveTo(x(points.first().time), y(points.first().percent))
                    points.drop(1).forEach { lineTo(x(it.time), y(it.percent)) }
                }
                drawPath(path, colors[i % colors.size], style = Stroke(width = 2.dp.toPx(), pathEffect = dash(i)))
            }
        }
        Text(
            bounds?.let { PriceFormat.changePercent(it.minPercent) } ?: "",
            style = MaterialTheme.typography.labelSmall.tabularNumbers(),
            color = label,
            modifier = Modifier.clearAndSetSemantics { },
        )
    }
}

/** Muster der Linie vor dem Namen (wie im Chart). */
@Composable
private fun LineSample(index: Int, color: Color) {
    Canvas(Modifier.size(width = 28.dp, height = 12.dp).clearAndSetSemantics { }) {
        drawLine(
            color, Offset(0f, size.height / 2), Offset(size.width, size.height / 2),
            strokeWidth = 2.dp.toPx(), pathEffect = dash(index),
        )
    }
}

/** Eine Zeile der Tabelle: Linienmuster, Name, Kurs; darunter Zeitraum, 24 h, 7 T, 30 T, Abstand zum Hoch. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CompareRow(
    watch: WatchEntity,
    index: Int,
    color: Color,
    line: List<ComparePoint>?,
    rangeShort: String,
    change24h: Double?,
    change24hLabel: String,
    figures: CompareFigures?,
) {
    val context = LocalContext.current
    val noData = line != null && line.isEmpty()
    val values = listOf(
        rangeShort to line?.let(CompareMath::last),
        change24hLabel to change24h,
        stringResource(R.string.widget_range_short_7d) to figures?.change7d,
        stringResource(R.string.widget_range_short_30d) to figures?.change30d,
        stringResource(R.string.compare_from_high) to figures?.fromHigh30d,
    )
    val price = PriceFormat.priceWithCurrency(watch.lastPrice, watch.quoteAsset)
    val spoken = buildList {
        add(watch.displayName)
        add(stringResource(R.string.label_value, stringResource(R.string.watchlist_sort_price), price))
        if (noData) add(stringResource(R.string.sheet_chart_no_data))
        values.dropLast(1).forEach { (label, v) -> add(stringResource(R.string.label_value, label, A11yText.change(context, v))) }
        figures?.fromHigh30d?.let { add(stringResource(R.string.a11y_compare_from_high, PriceFormat.changePercent(it) ?: "")) }
    }.joinToString(", ")
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.sm)
            .clearAndSetSemantics { contentDescription = spoken },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LineSample(index, color)
            Spacer(Modifier.width(Spacing.sm))
            Text(
                watch.displayName,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(price, style = MaterialTheme.typography.bodyMedium.tabularNumbers(), maxLines = 1)
        }
        if (noData) {
            Text(
                stringResource(R.string.sheet_chart_no_data),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.md), modifier = Modifier.padding(top = 4.dp)) {
            values.forEach { (label, v) ->
                Row {
                    Text("$label ", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        PriceFormat.changePercent(v) ?: "—",
                        style = MaterialTheme.typography.labelMedium.tabularNumbers(),
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}
