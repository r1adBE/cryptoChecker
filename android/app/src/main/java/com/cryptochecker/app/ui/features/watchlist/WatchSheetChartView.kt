package com.cryptochecker.app.ui.features.watchlist

import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.cryptochecker.app.R
import com.cryptochecker.app.data.local.model.WatchEntity
import com.cryptochecker.app.domain.watch.ChangeBasisMath
import com.cryptochecker.app.domain.watch.SheetChart
import com.cryptochecker.app.domain.portfolio.PortfolioStables
import com.cryptochecker.app.domain.watch.SheetChartFx
import com.cryptochecker.app.domain.watch.SheetChartRange
import com.cryptochecker.app.domain.watch.SheetChartResult
import com.cryptochecker.app.domain.watch.shownChange24h
import com.cryptochecker.app.ui.components.ChangePill
import com.cryptochecker.app.ui.components.ChartTypeSwitch
import com.cryptochecker.app.ui.components.ChangePillSize
import com.cryptochecker.app.ui.components.SkeletonBlock
import com.cryptochecker.app.ui.components.SkeletonLine
import com.cryptochecker.app.ui.components.SkeletonPulse
import com.cryptochecker.app.ui.components.rememberReduceMotion
import com.cryptochecker.app.ui.theme.Spacing
import com.cryptochecker.app.util.A11yText
import com.cryptochecker.app.util.BidiText
import com.cryptochecker.app.util.ChangeBasisText
import com.cryptochecker.app.widget.WidgetChartRange
import com.cryptochecker.app.widget.WidgetChartType
import java.time.ZoneId
import java.util.Date
import java.util.Locale

/** Höhe der Chart-Fläche im Aktionsblatt. */
internal val CHART_HEIGHT = 180.dp

/**
 * Beschriftungen ≈ 10 sp; grosse Systemschrift bis 1.5× (Build 46, vorher 1.3×). Die Spalte der
 * Preise misst ihre Breite (höchstens 42 % der Fläche) und die Ränder die Höhe — nichts wird abgeschnitten.
 * Wie iOS `ChartLabelMetrics`.
 */
internal const val LABEL_SP = 10f

internal const val MAX_FONT_SCALE = 1.5f

/** Überblendung beim Wechsel von Zeitraum oder Chart-Art (ms). */
private const val CHART_FADE_MILLIS = 200

/** Über so vielen Kerzen (1 Jahr) beim Ziehen kein Ticken je Kerze — es wäre ein Dauersurren. */
internal const val MAX_TICK_CANDLES = 100

/**
 * Kurs-Chart im Aktionsblatt (unter Kopf, Kurs und Alarm · «Was bewegt sich mit?» · Favorit): bei 7T/30T/1J
 * die Veränderung über den Zeitraum (24h steht schon farbig unter dem Kurs), Chart wie das Einzel-Widget (Kerzen oder Linie, Preisstufen,
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
    // Paar bearbeitet (gleiche Id, anderes Paar/Börse) oder Zeitraum gewechselt: Zwischenspeicher
    // sofort, sonst Platzhalter bis geladen
    var result by remember(watch.id, watch.marketKey, watch.baseAsset, watch.quoteAsset, range) {
        mutableStateOf(cached(watch, range))
    }
    // Neuer Kurs (auch nach «Aktualisieren»): verworfener Zwischenspeicher (von Hand, Kurssprung,
    // abgelaufen) lädt still neu — der alte Verlauf bleibt bis dahin stehen
    LaunchedEffect(watch.id, watch.marketKey, watch.baseAsset, watch.quoteAsset, range, watch.lastUpdate) {
        val hit = cached(watch, range)
        if (hit != null) {
            result = hit
        } else {
            val loaded = load(watch, range)
            // Neu laden fehlgeschlagen: stehender Verlauf bleibt (statt «Keine Kursdaten»)
            if (loaded is SheetChartResult.Ready || result !is SheetChartResult.Ready) result = loaded
        }
    }
    if (result == SheetChartResult.Unsupported) return
    val stable = remember(watch.baseAsset) { PortfolioStables.isStable(watch.baseAsset) }

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
                } else {
                    r.candles
                }
                // Erster Zeitraum: keine eigene Zeile — es wäre dieselbe Zahl wie die Änderung unter
                // dem Kurs; 7T/30T/1J: Veränderung aus den Kerzen über dem Chart
                if (range != SheetChartRange.DAY) {
                    RangeChangeHeader(
                        change = SheetChart.headerChange(range, candles, type, changeView.shown(watch.shownChange24h)),
                        periodShort = periodShort,
                        periodLong = periodLong,
                        sincePhrase = today,
                    )
                }
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
                        stable = stable,
                    )
                }
                SheetChartCaption(r, widgetRange.intervalMillis, ChangeBasisMath.chartZone(basis), listOf(watch.marketKey, watch.marketName))
            }
            SheetChartResult.NoData -> Text(
                text = stringResource(R.string.sheet_chart_no_data),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 12.dp)
            )
            else -> SkeletonPulse(modifier = Modifier.fillMaxWidth()) {
                // Kopfzeile nur bei 7T/30T/1J (wie oben)
                if (range != SheetChartRange.DAY) {
                    SkeletonLine(
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.width(130.dp)
                    )
                }
                SkeletonBlock(
                    modifier = Modifier.fillMaxWidth()
                        .padding(top = if (range != SheetChartRange.DAY) Spacing.sm else 0.dp)
                        .height(CHART_HEIGHT),
                    corner = 12.dp
                )
            }
        }

        // Zeitraum links, Kerzen/Linie als kompakter Zwei-Symbol-Schalter rechts in derselben
        // Zeile; reicht der Platz nicht (grosse Schrift), rutscht der Schalter rechtsbündig darunter
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.fillMaxWidth().padding(top = Spacing.sm)
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
                // Kerzen | Linie: derselbe Schalter wie im Wertverlauf des Portfolios
                ChartTypeSwitch(line = line, onLineChange = onLineChange)
            }
        }
    }
}

/**
 * «[+2.31%] in 24h» bzw. bei Tages-Basis «[+2.31%] heute» ([sincePhrase]): die Veränderung als
 * farbige Schrift ohne Pille ([ChangePill], mittel), der Rest des übersetzten Satzes grau davor/dahinter;
 * Screenreader: «up 2.31%, 24 hours» / «…, since 00:00 UTC».
 */
@Composable
private fun RangeChangeHeader(change: Double?, periodShort: String, periodLong: String, sincePhrase: Boolean) {
    val context = LocalContext.current
    val spoken = A11yText.change(context, change) + ", " + periodLong
    // Satz mit Platzhalter für die Änderung, damit die Wortstellung der Übersetzung bleibt («24h で …»)
    val sentence = stringResource(
        if (sincePhrase) R.string.sheet_chart_change_since else R.string.sheet_chart_change,
        CHANGE_MARK,
        periodShort
    )
    val before = sentence.substringBefore(CHANGE_MARK).trim()
    val after = sentence.substringAfter(CHANGE_MARK, "").trim()
    val textStyle = MaterialTheme.typography.labelLarge
    val textColor = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        modifier = Modifier.clearAndSetSemantics { contentDescription = spoken }
    ) {
        if (before.isNotEmpty()) {
            Text(before, style = textStyle, color = textColor, maxLines = 1)
        }
        ChangePill(change = change, dashWhenMissing = true, size = ChangePillSize.MEDIUM)
        if (after.isNotEmpty()) {
            Text(after, style = textStyle, color = textColor, maxLines = 1)
        }
    }
}

/**
 * Kleine graue Zeile unter dem Chart: Börse der Kerzen (fremde Börse als «Chart: Binance», [markets] = Schlüssel und
 * Name der Börse des Paars), bei der Ausweich-Reihe die Umrechnung
 * («BTC/USDT, umgerechnet mit Tageskursen» bzw. «… (aktueller Kurs)») und der Stand der letzten
 * Kerze («Stand 14:00»), getrennt mit « · ».
 */
@Composable
private fun SheetChartCaption(result: SheetChartResult.Ready, intervalMillis: Long, zone: ZoneId, markets: List<String>) {
    val context = LocalContext.current
    val last = result.candles.lastOrNull() ?: return
    val stamp = SheetChart.stampMillis(last.openTime, intervalMillis, result.fetchedAt)
    val is24 = android.text.format.DateFormat.is24HourFormat(context)
    val template = SheetChart.stampTemplate(stamp, System.currentTimeMillis(), zone)
    val locale = LocalConfiguration.current.locales[0] ?: Locale.ROOT
    val time = remember(stamp, template, is24, zone, locale) {
        android.icu.text.DateFormat.getInstanceForSkeleton(template.replace("j", if (is24) "H" else "h"), locale)
            .apply { timeZone = icuTimeZone(zone) }
            .format(Date(stamp))
    }
    val pair = result.sourcePair.orEmpty()
    val fx = when (result.fx) {
        SheetChartFx.DAILY -> stringResource(R.string.sheet_chart_converted_daily, BidiText.isolate(pair))
        SheetChartFx.CURRENT -> stringResource(R.string.sheet_chart_converted_current, BidiText.isolate(pair))
        SheetChartFx.NONE -> null
    }
    // Kerzen einer anderen Börse (Ausweich-Kette): als fremd kennzeichnen, «Chart: Binance» statt nur «Binance»
    val provider = com.cryptochecker.app.domain.watch.DayChange.foreignCandleSource(markets, result.provider)
        ?.let { stringResource(R.string.watch_row_source_chart, BidiText.isolate(it)) }
        ?: result.provider
    val text = listOfNotNull(provider, fx, stringResource(R.string.pulse_updated, BidiText.isolate(time)))
        .joinToString(" · ")
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp)
    )
}

/** Platzhalter im Satz «%1$s in %2$s» an der Stelle der Änderung. */
private const val CHANGE_MARK = "\uE000"
