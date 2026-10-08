package com.cryptochecker.app.ui.features.watchlist

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cryptochecker.app.R
import com.cryptochecker.app.data.local.model.WatchEntity
import com.cryptochecker.app.domain.watch.ChangeBasisMath
import com.cryptochecker.app.domain.watch.SheetChart
import com.cryptochecker.app.domain.watch.SheetChartRange
import com.cryptochecker.app.domain.watch.SheetChartResult
import com.cryptochecker.app.ui.components.SkeletonBlock
import com.cryptochecker.app.ui.components.SkeletonLine
import com.cryptochecker.app.ui.components.SkeletonPulse
import com.cryptochecker.app.ui.components.rememberReduceMotion
import com.cryptochecker.app.ui.theme.PriceColors
import com.cryptochecker.app.ui.theme.amountNumbers
import com.cryptochecker.app.util.A11yText
import com.cryptochecker.app.util.ChangeBasisText
import com.cryptochecker.app.util.PriceFormat
import com.cryptochecker.app.widget.WidgetChartRange
import com.cryptochecker.app.widget.WidgetChartType

/** Höhe der Chart-Fläche im Aktionsblatt. */
internal val CHART_HEIGHT = 180.dp

/** Beschriftungen ≈ 10 sp; grosse Systemschrift nur begrenzt (wie das Einzel-Widget). */
internal const val LABEL_SP = 10f

internal const val MAX_FONT_SCALE = 1.3f

/** Überblendung beim Wechsel von Zeitraum oder Chart-Art (ms). */
private const val CHART_FADE_MILLIS = 200

/** Über so vielen Kerzen (1 Jahr) beim Ziehen kein Ticken je Kerze — es wäre ein Dauersurren. */
internal const val MAX_TICK_CANDLES = 100

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
                        zone = ChangeBasisMath.chartZone(basis),
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
