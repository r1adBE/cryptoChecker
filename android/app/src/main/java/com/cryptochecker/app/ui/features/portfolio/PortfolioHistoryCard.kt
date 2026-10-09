package com.cryptochecker.app.ui.features.portfolio

import com.cryptochecker.app.ui.components.SectionTitle
import com.cryptochecker.app.ui.components.sectionTitleMarker
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.cryptochecker.app.R
import com.cryptochecker.app.domain.portfolio.PortfolioHistoryRange
import com.cryptochecker.app.domain.portfolio.PortfolioHistorySeries
import com.cryptochecker.app.domain.portfolio.PortfolioInsights
import com.cryptochecker.app.ui.components.SkeletonBlock
import com.cryptochecker.app.ui.components.SkeletonLine
import com.cryptochecker.app.ui.components.SkeletonPulse
import com.cryptochecker.app.ui.theme.Spacing
import com.cryptochecker.app.ui.theme.amountNumbers
import com.cryptochecker.app.util.A11yText
import com.cryptochecker.app.util.PriceFormat
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs

/** Kurzname auf dem Chip («30 T») und ausgeschrieben («30 Tage») für Screenreader und Chart-Satz. */
private val PortfolioHistoryRange.shortRes: Int
    get() = when (this) {
        PortfolioHistoryRange.WEEK -> R.string.portfolio_history_range_7d
        PortfolioHistoryRange.MONTH -> R.string.portfolio_history_range_30d
        PortfolioHistoryRange.YEAR -> R.string.portfolio_history_range_1y
        PortfolioHistoryRange.SINCE_FIRST -> R.string.portfolio_history_range_since_first
    }

private val PortfolioHistoryRange.longRes: Int
    get() = when (this) {
        PortfolioHistoryRange.WEEK -> R.string.portfolio_history_period_7d
        PortfolioHistoryRange.MONTH -> R.string.portfolio_history_period_30d
        PortfolioHistoryRange.YEAR -> R.string.portfolio_history_period_1y
        PortfolioHistoryRange.SINCE_FIRST -> R.string.portfolio_history_period_since_first
    }

/** Screenreader-Text des Chips («Verlauf für 30 Tage zeigen» / «Verlauf seit dem ersten Kauf zeigen»). */
@Composable
private fun rangeSpoken(range: PortfolioHistoryRange): String =
    if (range == PortfolioHistoryRange.SINCE_FIRST) {
        stringResource(R.string.portfolio_history_range_since_first_a11y)
    } else {
        stringResource(R.string.portfolio_history_range_a11y, stringResource(range.longRes))
    }

/** «+1’234.56 CHF» / «−12.00 CHF» / «0.00 CHF» (bei praktisch 0 ohne Vorzeichen). */
private fun signedValue(value: Double, unit: String): String {
    val sign = when {
        PortfolioFormat.isZero(value) -> ""
        value > 0 -> "+"
        else -> "−"
    }
    return sign + PriceFormat.valueWithCurrency(abs(value), unit)
}

/** Mittag des Tags (lokal) in ms — für die Datumsbeschriftung. */
internal fun dayMillis(epochDay: Long): Long =
    LocalDate.ofEpochDay(epochDay).atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

/**
 * Wertverlauf über den Positionen, standardmässig zugeklappt: eine Zeile «Wertverlauf · 30 T
 * ▲ +4.20%» (Änderung über den gewählten Zeitraum mit Vorzeichen, Pfeil und Kursfarbe; Platzhalter,
 * solange geladen wird). Tipp auf die Zeile klappt auf: Zeitraum-Chips (7 T / 30 T / 1 J / Seit 1.
 * Kauf), Änderung als Betrag und Prozent, Linie mit Fläche und Hinweise (umgerechnet, Coins ohne
 * Tageskurse, Käufe/Verkäufe im Zeitraum, auf 5 Jahre begrenzt). [history] null = lädt.
 * Ohne Bewegung gezeichnet (kein Aufdecken).
 */
@Composable
internal fun PortfolioHistoryCard(
    history: PortfolioHistoryUi?,
    range: PortfolioHistoryRange,
    onRange: (PortfolioHistoryRange) -> Unit,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
) {
    // Ein Verlauf eines anderen Zeitraums (gerade gewechselt) zählt wie «lädt»
    val current = history?.takeIf { it.range == range }
    Card(
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
            HistoryHeader(current, range, expanded, onToggle = { onExpandedChange(!expanded) })

            if (expanded) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                ) {
                    PortfolioHistoryRange.entries.forEach { option ->
                        val spoken = rangeSpoken(option)
                        FilterChip(
                            selected = option == range,
                            onClick = { onRange(option) },
                            label = {
                                Text(
                                    stringResource(option.shortRes),
                                    maxLines = 1,
                                    modifier = Modifier.semantics { contentDescription = spoken }
                                )
                            }
                        )
                    }
                }

                // Beim Wechsel des Zeitraums bleibt der bisherige Verlauf stehen, bis der neue gerechnet ist
                if (history == null) HistorySkeleton() else HistoryContent(history)
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

/**
 * Kopfzeile (ganze Zeile tippbar): Titel, zugeklappt mit Zeitraum und Änderung, Pfeil zum
 * Auf-/Zuklappen. Für den Screenreader ein Satz samt Zustand.
 */
@Composable
private fun HistoryHeader(
    history: PortfolioHistoryUi?,
    range: PortfolioHistoryRange,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val title = stringResource(R.string.portfolio_history_title)
    val spokenChange = history?.let { changeSentence(it) }
    val loading = stringResource(R.string.portfolio_history_loading)
    val spoken = when {
        expanded -> title
        history == null -> "$title, $loading"
        spokenChange != null -> "$title. $spokenChange"
        else -> "$title, ${stringResource(range.longRes)}"
    }
    val actionLabel = stringResource(
        if (expanded) R.string.portfolio_history_collapse else R.string.portfolio_history_expand
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(onClickLabel = actionLabel, role = Role.Button, onClick = onToggle)
            .clearAndSetSemantics {
                contentDescription = spoken
                role = Role.Button
                onClick(label = actionLabel) {
                    onToggle()
                    true
                }
            }
    ) {
        Text(
            if (expanded) title else "$title · ${stringResource(range.shortRes)}",
            style = SectionTitle.style,
            color = SectionTitle.color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).sectionTitleMarker()
        )
        if (!expanded) {
            if (history == null) {
                SkeletonPulse(modifier = Modifier.width(72.dp)) {
                    SkeletonLine(style = MaterialTheme.typography.labelLarge, modifier = Modifier.fillMaxWidth())
                }
            } else {
                CollapsedChange(history.series, history.unit)
            }
        }
        Icon(
            painter = painterResource(R.drawable.ic_chevron_right),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .padding(start = Spacing.xs)
                .size(20.dp)
                .rotate(if (expanded) -90f else 90f)
        )
    }
}

/** Zugeklappt: «▲ +4.20%» in der Kursfarbe; ohne Prozent der Betrag, ohne Verlauf «—». */
@Composable
private fun CollapsedChange(series: PortfolioHistorySeries, unit: String) {
    val change = series.change?.takeIf { series.hasChart }
    val percent = series.changePercent?.takeIf { change != null }
    val text = when {
        change == null -> "—"
        else -> {
            val arrow = if (PortfolioFormat.isZero(change)) "" else PriceFormat.changeArrow(change)
            val value = percent?.let { PortfolioFormat.signedPercent(it) } ?: maskAmount(signedValue(change, unit))
            if (arrow.isEmpty()) value else "$arrow $value"
        }
    }
    Text(
        text,
        style = MaterialTheme.typography.labelLarge.amountNumbers(),
        fontWeight = FontWeight.SemiBold,
        color = plColor(change),
        maxLines = 1
    )
}

/** Satz zur Änderung über den Zeitraum («Wert über 30 Tage: gestiegen um …, …»); null ohne Verlauf. */
@Composable
private fun changeSentence(history: PortfolioHistoryUi): String? {
    val series = history.series
    if (!series.hasChart) return null
    val context = LocalContext.current
    val change = series.change ?: 0.0
    val spokenAmount = when {
        PortfolioFormat.isZero(change) -> stringResource(R.string.a11y_change_flat)
        change > 0 -> stringResource(R.string.a11y_change_up, spokenAmount(PriceFormat.valueWithCurrency(abs(change), history.unit)))
        else -> stringResource(R.string.a11y_change_down, spokenAmount(PriceFormat.valueWithCurrency(abs(change), history.unit)))
    }
    val spokenPercent = series.changePercent?.let { A11yText.change(context, it) }
    val period = stringResource(history.range.longRes)
    return when {
        spokenPercent == null -> "$period: $spokenAmount"
        history.range == PortfolioHistoryRange.SINCE_FIRST ->
            stringResource(R.string.portfolio_history_change_since_first_a11y, spokenAmount, spokenPercent)
        else -> stringResource(R.string.portfolio_history_change_a11y, period, spokenAmount, spokenPercent)
    }
}

@Composable
private fun HistoryContent(history: PortfolioHistoryUi) {
    val series = history.series
    val context = LocalContext.current
    val period = stringResource(history.range.longRes)
    if (series.hasChart) {
        val change = series.change ?: 0.0
        val color = plColor(change)
        val arrow = if (PortfolioFormat.isZero(change)) "" else PriceFormat.changeArrow(change)
        val amount = maskAmount(signedValue(change, history.unit)).let { if (arrow.isEmpty()) it else "$arrow $it" }
        val spokenChange = changeSentence(history).orEmpty()

        // Änderung über den Zeitraum — ein Satz für den Screenreader
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = Spacing.xs)
                .clearAndSetSemantics { contentDescription = spokenChange }
        ) {
            Text(
                amount,
                style = MaterialTheme.typography.titleMedium.amountNumbers(),
                fontWeight = FontWeight.SemiBold,
                color = color,
                // Grosse Schrift: Betrag bricht um statt abgeschnitten zu werden
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
            if (series.changePercent != null) {
                PlPill(series.changePercent, modifier = Modifier.padding(start = 8.dp))
            }
        }

        val values = series.points.map { it.value }
        val hiddenSpoken = spokenAmount(PortfolioInsights.HIDDEN)
        val hidden = LocalHidePortfolioAmounts.current
        val chartSentence = A11yText.chart(context, period, values) {
            if (hidden) hiddenSpoken else PriceFormat.valueWithCurrency(it, history.unit)
        }
        HistoryChart(
            points = series.points,
            unit = history.unit,
            change = change,
            modifier = Modifier
                .fillMaxWidth()
                .height(140.dp)
                .padding(top = Spacing.sm)
                .clearAndSetSemantics { contentDescription = chartSentence }
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
                    PortfolioFormat.date(dayMillis(series.points.first().epochDay)),
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
    } else {
        PortfolioHint(
            stringResource(
                if (series.points.isEmpty()) R.string.portfolio_history_unavailable
                else R.string.portfolio_history_too_short
            )
        )
    }

    // Hinweise unter dem Chart
    if (series.hasChart && history.converted) {
        HistoryCaption(stringResource(R.string.portfolio_history_converted))
    }
    if (series.skipped.isNotEmpty()) {
        HistoryCaption(stringResource(R.string.portfolio_history_without, series.skipped.joinToString(", ")))
    }
    if (series.hasChart && series.tradesInRange) {
        HistoryCaption(stringResource(R.string.portfolio_history_includes_trades))
    }
    if (series.capped) {
        HistoryCaption(stringResource(R.string.portfolio_history_capped))
    }
}

@Composable
private fun HistoryCaption(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
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
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp).height(140.dp),
            corner = 12.dp
        )
        SkeletonLine(
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(top = 4.dp).width(80.dp)
        )
    }
}
