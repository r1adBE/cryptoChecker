package com.cryptochecker.app.ui.features.portfolio

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.cryptochecker.app.R
import com.cryptochecker.app.domain.portfolio.PortfolioCompare
import com.cryptochecker.app.domain.portfolio.PortfolioHistoryRange
import com.cryptochecker.app.domain.portfolio.PortfolioHistoryView
import com.cryptochecker.app.domain.portfolio.PortfolioInsights
import com.cryptochecker.app.ui.components.AppCard
import com.cryptochecker.app.ui.components.CardPadding
import com.cryptochecker.app.ui.components.ChartPointStep
import com.cryptochecker.app.ui.components.SkeletonBlock
import com.cryptochecker.app.ui.components.SkeletonLine
import com.cryptochecker.app.ui.components.SkeletonPulse
import com.cryptochecker.app.ui.components.chartPointSemantics
import com.cryptochecker.app.ui.theme.Spacing
import com.cryptochecker.app.ui.theme.label
import com.cryptochecker.app.util.A11yText
import com.cryptochecker.app.util.BidiText
import com.cryptochecker.app.util.PriceFormat
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs

/** Kurzname auf dem Chip («30 T») und ausgeschrieben («30 Tage») für Screenreader und Chart-Satz. */
internal val PortfolioHistoryRange.shortRes: Int
    get() = when (this) {
        PortfolioHistoryRange.WEEK -> R.string.portfolio_history_range_7d
        PortfolioHistoryRange.MONTH -> R.string.portfolio_history_range_30d
        PortfolioHistoryRange.YEAR -> R.string.portfolio_history_range_1y
        PortfolioHistoryRange.SINCE_FIRST -> R.string.portfolio_history_range_since_first
    }

internal val PortfolioHistoryRange.longRes: Int
    get() = when (this) {
        PortfolioHistoryRange.WEEK -> R.string.portfolio_history_period_7d
        PortfolioHistoryRange.MONTH -> R.string.portfolio_history_period_30d
        PortfolioHistoryRange.YEAR -> R.string.portfolio_history_period_1y
        PortfolioHistoryRange.SINCE_FIRST -> R.string.portfolio_history_period_since_first
    }

/** Screenreader-Text des Chips («Verlauf für 30 Tage zeigen» / beim Chip «Seit Kauf» ausgeschrieben «Seit 1. Kauf»). */
@Composable
internal fun rangeSpoken(range: PortfolioHistoryRange): String =
    if (range == PortfolioHistoryRange.SINCE_FIRST) {
        stringResource(R.string.portfolio_history_range_since_first)
    } else {
        stringResource(R.string.portfolio_history_range_a11y, stringResource(range.longRes))
    }

/**
 * «+1’234.56 CHF» / «−12.00 CHF» / «0.00 CHF» (bei praktisch 0 ohne Vorzeichen); auch das Etikett
 * beim Ziehen im Diagramm. Als links-nach-rechts-Insel (RTL: Vorzeichen bleibt vor der Zahl) — wie iOS.
 */
internal fun signedValue(value: Double, unit: String): String {
    val sign = when {
        PortfolioFormat.isZero(value) -> ""
        value > 0 -> "+"
        else -> "−"
    }
    return BidiText.ltr(sign + PriceFormat.valueWithCurrency(abs(value), unit))
}

/** In USDT: derselbe Verlauf unumgerechnet (Umschalter, Vergleich und Hinweise bleiben). */
private fun PortfolioHistoryUi.forView(view: PortfolioHistoryView): PortfolioHistoryUi {
    val usdt = usdtSeries
    return if (view == PortfolioHistoryView.USDT && usdt != null) copy(series = usdt, unit = PortfolioFormat.USDT) else this
}

/** Mittag des Tags (lokal) in ms — für die Datumsbeschriftung. */
internal fun dayMillis(epochDay: Long): Long =
    LocalDate.ofEpochDay(epochDay).atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

/**
 * Wertverlauf über den Positionen, standardmässig zugeklappt: eine Zeile «Wertverlauf · 30 T
 * [+4.20%]» (Änderung über den gewählten Zeitraum als farbige Schrift; Platzhalter,
 * solange geladen wird). Tipp auf die Zeile klappt auf: Änderung als Betrag und Prozent, Linie mit
 * Fläche, UNTER dem Chart die Zeitraum-Chips (7 T / 30 T / 1 J / Seit Kauf) und Hinweise
 * (umgerechnet, Coins ohne Tageskurse, Käufe/Verkäufe im Zeitraum, Kursdaten erst ab 2017). [history] null = lädt.
 * Bei einer Umrechnungswährung mit Devisen-Tageskursen zusätzlich der Umschalter
 * «CHF | USDT | Vergleich» ([view], gespeichert über [onView]); «Vergleich» zeigt beide Kurven in
 * Prozent und den Währungseffekt. Ohne Bewegung gezeichnet (kein Aufdecken).
 * Wie im Aktionsblatt eines Paars rechts neben den Chips (unter dem Chart) der Schalter Kerzen | Linie ([candles],
 * gespeichert über [onCandlesChange]); im «Vergleich» ausgeblendet (dort nur Linien).
 * Über dem Chart die Chips «Wert | Gewinn/Verlust» ([pnl], gespeichert über [onPnlChange]): «Wert» die
 * Wertkurve, «Gewinn/Verlust» MEIN Gewinn/Verlust je Tag ([PortfolioHistorySeries.pnlPoints], Käufe/Verkäufe
 * verschieben ihn nicht) mit Nulllinie; im «Vergleich» ausgeblendet. Die Kopfzeile zeigt immer beides:
 * «Wert 93’818.00 CHF · Gewinn +2’340.00 CHF (+2.40%)» (Gewinn über den Zeitraum, [PortfolioHistorySeries.pnlChange]).
 */
@Composable
internal fun PortfolioHistoryCard(
    history: PortfolioHistoryUi?,
    range: PortfolioHistoryRange,
    onRange: (PortfolioHistoryRange) -> Unit,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    view: PortfolioHistoryView = PortfolioHistoryView.CURRENCY,
    onView: (PortfolioHistoryView) -> Unit = {},
    candles: Boolean = false,
    onCandlesChange: (Boolean) -> Unit = {},
    pnl: Boolean = false,
    onPnlChange: (Boolean) -> Unit = {},
) {
    // Darstellung, die für diesen Verlauf gilt: USDT und Vergleich nur mit Devisen-Tageskursen
    // (Vergleich zusätzlich mit Ausgangswert), sonst in der Währung
    val shownView = history?.let { PortfolioCompare.effectiveView(view, it.usdtSeries != null, it.compare != null) }
        ?: PortfolioHistoryView.CURRENCY
    val shown = history?.forView(shownView)
    // Ein Verlauf eines anderen Zeitraums (gerade gewechselt) zählt wie «lädt»
    val current = shown?.takeIf { it.range == range }
    AppCard(padding = CardPadding.List) {
        HistoryHeader(current, range, expanded, onToggle = { onExpandedChange(!expanded) })

        if (expanded) {
            // Zeitraum und Kerzen/Linie stehen unter dem Chart (vor den Hinweisen)
            val controls: @Composable () -> Unit = {
                HistoryControls(
                    range = range,
                    onRange = onRange,
                    // Im «Vergleich» nur Linien: Schalter ausgeblendet
                    showTypeSwitch = shownView != PortfolioHistoryView.COMPARE,
                    candles = candles,
                    onCandlesChange = onCandlesChange
                )
            }
            // «CHF | USDT | Vergleich» nur bei einer Umrechnungswährung mit Tageskursen
            if (history?.usdtSeries != null) {
                HistoryViewSwitch(
                    currency = history.unit,
                    selected = shownView,
                    compareAvailable = history.compare != null,
                    onView = onView
                )
            }
            // Beim Wechsel des Zeitraums bleibt der bisherige Verlauf stehen, bis der neue gerechnet ist
            if (shown == null) {
                HistorySkeleton()
                controls()
            } else {
                HistoryContent(shown, shownView, candles, controls, pnl, onPnlChange)
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun HistoryContent(
    history: PortfolioHistoryUi,
    view: PortfolioHistoryView,
    candles: Boolean,
    controls: @Composable () -> Unit,
    pnl: Boolean,
    onPnlChange: (Boolean) -> Unit,
) {
    val series = history.series
    val period = stringResource(history.range.longRes)
    val compare = history.compare.takeIf { view == PortfolioHistoryView.COMPARE }
    // «Gewinn/Verlust» nur ausserhalb des Vergleichs (dort Prozent beider Währungen)
    val showPnl = pnl && compare == null
    if (series.hasChart) {
        // Wert und Gewinn über den Zeitraum — immer beides
        ValueGainLine(history)
        if (compare != null) {
            CompareChart(history, compare, period)
        } else {
            HistoryModeSwitch(pnl, onPnlChange)
            if (!showPnl) {
                SingleChart(history, series.change ?: 0.0, period, candles, pnl = false)
            } else if (series.hasPnlChart) {
                SingleChart(history, series.pnlChange ?: 0.0, period, candles, pnl = true)
            } else {
                // Alle Coins fehlen im Gewinn/Verlust (Zufluss ohne Preis): das ehrlich sagen statt «kein Verlauf»
                PortfolioHint(
                    stringResource(
                        if (series.pnlSkipped.isNotEmpty()) R.string.portfolio_history_pnl_needs_prices
                        else R.string.portfolio_history_unavailable
                    )
                )
            }
        }
    } else {
        PortfolioHint(
            stringResource(
                if (series.points.isEmpty()) R.string.portfolio_history_unavailable
                else R.string.portfolio_history_too_short
            )
        )
    }
    // Zeitraum und Kerzen/Linie direkt unter dem Chart
    controls()

    // Hinweise unter dem Chart
    // Eigene Börse nicht erreichbar: der Verlauf ist ein älterer Stand — zuerst und mit Zeit, nie still als heute
    history.staleSince?.takeIf { series.hasChart }?.let {
        HistoryCaption(stringResource(R.string.portfolio_history_as_of, portfolioStampTime(it)))
    }
    // Nur wenn die Devisen-Tageskurse fehlten und alles mit dem heutigen Kurs umgerechnet ist
    if (series.hasChart && history.converted && history.approximateFx) {
        HistoryCaption(stringResource(R.string.portfolio_history_converted))
    }
    // Mit Tageskursen umgerechnet: Kurve enthält die Devisenbewegung; USDT zum USD-Marktkurs (je einmal, klein)
    if (series.hasChart && history.converted &&
        com.cryptochecker.app.domain.portfolio.PortfolioHistoryFx.includesFxMovement(history.unit, history.approximateFx)
    ) {
        HistoryCaption(stringResource(R.string.portfolio_history_incl_fx))
    }
    // USDT zum USD-Marktkurs (Tagesschluss USDT/USD); an Tagen ohne Kurs 1 als Näherung — dann «USDT = 1 angenommen»
    if (series.hasChart && history.converted && com.cryptochecker.app.domain.portfolio.PortfolioHistoryFx.convertsUsdtAsUsd(history.unit)) {
        HistoryCaption(stringResource(R.string.portfolio_usdt_as_usd))
        if (history.usdtApproximate) HistoryCaption(stringResource(R.string.portfolio_usdt_assumed))
    }
    if (series.skipped.isNotEmpty()) {
        // Grund: keine oder nicht passende Tageskurse (anderer Token, kein aktueller Kurs)
        HistoryCaption(stringResource(R.string.portfolio_history_without_matching, series.skipped.joinToString(", ")))
    }
    if (series.hasChart && history.chainSources.isNotEmpty()) {
        // Binance führt den Coin nicht: Tageskurse einer anderen Börse (geprüft auf denselben Token)
        HistoryCaption(
            stringResource(
                R.string.portfolio_history_chain_sources,
                com.cryptochecker.app.domain.portfolio.PortfolioExchangeRoute.sourcesText(history.chainSources),
            ),
        )
    }
    if (series.hasChart && history.scaled.isNotEmpty()) {
        // Keine eigenen Tageskerzen der Börse: Binance-Verlauf auf den Börsenkurs skaliert
        HistoryCaption(stringResource(R.string.portfolio_history_scaled, history.scaled.joinToString(", ")))
    }
    // Die Wertkurve springt bei Käufen/Verkäufen (der Gewinn/Verlust nicht)
    if (series.hasChart && series.tradesInRange && !showPnl && compare == null) {
        HistoryCaption(stringResource(R.string.portfolio_history_includes_trades))
    }
    // Gewinn/Verlust enthält die Wertänderung ausgebuchter Coins bis zur Auszahlung (Karte «Seit Kauf» nicht)
    if (series.hasChart && series.withdrawalsInRange) {
        HistoryCaption(stringResource(R.string.portfolio_history_incl_withdrawn))
    }
    // Coins, die im Gewinn/Verlust fehlen (Zufluss ohne Preis, Tag ohne Kurs) — ehrlich nennen
    // (nicht doppelt, wenn der Gewinn/Verlust-Modus schon deswegen leer ist)
    if (series.hasChart && series.pnlSkipped.isNotEmpty() && !(showPnl && !series.hasPnlChart)) {
        HistoryCaption(stringResource(R.string.portfolio_history_pnl_without, series.pnlSkipped.joinToString(", ")))
    }
    if (series.capped) {
        HistoryCaption(stringResource(R.string.portfolio_history_capped_data_start))
    }
    // Tage, Heute / 7 T / 1 J und Kerzen laufen in UTC (Steuerdaten in Ortszeit) — einmal, klein
    if (series.hasChart) {
        HistoryCaption(stringResource(R.string.portfolio_history_utc_days))
    }
}

/**
 * Linie oder Kerzen ([candles]) in [PortfolioHistoryUi.unit] mit Achse darunter (Währung oder USDT);
 * [pnl]: Gewinn/Verlust je Tag ([PortfolioHistorySeries.pnlPoints]) mit Nulllinie und Vorzeichen.
 */
@Composable
private fun SingleChart(history: PortfolioHistoryUi, change: Double, period: String, candles: Boolean, pnl: Boolean) {
    val series = history.series
    val context = LocalContext.current
    val points = if (pnl) series.pnlPoints else series.points
    val values = points.map { it.value }
    val hiddenSpoken = spokenAmount(PortfolioInsights.HIDDEN)
    val hidden = LocalHidePortfolioAmounts.current
    val chartSentence = A11yText.chart(context, period, values) {
        if (hidden) hiddenSpoken else if (pnl) signedValue(it, history.unit) else PriceFormat.valueWithCurrency(it, history.unit)
    }
    // Screenreader: Tag für Tag (Aktionen «Nächster/Vorheriger Punkt»); bei verborgenen Beträgen nur der Chart-Satz
    var a11yPoint by remember(points, pnl) { mutableStateOf<Int?>(null) }
    val stepCount = if (hidden) 0 else points.size
    val pointText = a11yPoint?.let { points.getOrNull(it) }?.takeIf { !hidden }?.let {
        val amount =
            if (pnl) signedValue(it.value, history.unit) else PriceFormat.valueWithCurrency(it.value, history.unit)
        stringResource(R.string.label_value, PortfolioFormat.date(dayMillis(it.epochDay)), amount)
    }
    HistoryChart(
        points = points,
        unit = history.unit,
        change = change,
        candles = candles,
        pnl = pnl,
        modifier = Modifier
            .fillMaxWidth()
            .height(HISTORY_CHART_HEIGHT)
            .padding(top = Spacing.sm)
            .chartPointSemantics(
                chartSentence,
                stepCount,
                pointText,
                stringResource(R.string.a11y_chart_next_point),
                stringResource(R.string.a11y_chart_previous_point),
            ) { forward ->
                a11yPoint = if (forward) ChartPointStep.next(a11yPoint, stepCount)
                else ChartPointStep.previous(a11yPoint, stepCount)
            }
    )
    // Beginn und Ende der Achse (für Screenreader im Chart-Satz enthalten).
    // Wie der Chart darüber: Beginn links, «heute» rechts — auch bei Rechts-nach-links-Sprachen
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp)
                .clearAndSetSemantics { }
        ) {
            Text(
                PortfolioFormat.date(dayMillis(points.first().epochDay)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.weight(1f))
            Text(
                stringResource(R.string.portfolio_history_today),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun HistoryCaption(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.label,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = Spacing.xs)
    )
}

/** Platzhalter in der Form von Änderung und Chart. */
@Composable
private fun HistorySkeleton() {
    val loading = stringResource(R.string.portfolio_history_loading)
    SkeletonPulse(modifier = Modifier.fillMaxWidth().semantics { contentDescription = loading }) {
        SkeletonLine(
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = Spacing.xs).width(150.dp)
        )
        SkeletonBlock(
            modifier = Modifier.fillMaxWidth().padding(top = Spacing.sm).height(HISTORY_CHART_HEIGHT),
            corner = 12.dp
        )
        SkeletonLine(
            style = MaterialTheme.typography.label,
            modifier = Modifier.padding(top = 4.dp).width(80.dp)
        )
    }
}
