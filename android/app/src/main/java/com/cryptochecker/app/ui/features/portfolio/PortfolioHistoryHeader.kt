// Kopfzeile der Verlaufskarte («Wertverlauf · 30 T» mit Änderung, zum Auf-/Zuklappen) und die Zeile
// «Wert … · Gewinn …» über dem Chart.
// Eigene Datei, weil Kopf und Wertzeile eigene Vorlese-Texte bauen und die Karte sonst zu lang wird
// (wie `PortfolioHistoryHeader.swift`).
package com.cryptochecker.app.ui.features.portfolio

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.cryptochecker.app.R
import com.cryptochecker.app.domain.portfolio.PortfolioHistoryRange
import com.cryptochecker.app.domain.portfolio.PortfolioHistorySeries
import com.cryptochecker.app.ui.components.ChangePillSize
import com.cryptochecker.app.ui.components.ExpandChevronIcon
import com.cryptochecker.app.ui.components.SectionTitle
import com.cryptochecker.app.ui.components.SkeletonLine
import com.cryptochecker.app.ui.components.SkeletonPulse
import com.cryptochecker.app.ui.components.sectionTitleMarker
import com.cryptochecker.app.ui.components.withSmallQuote
import com.cryptochecker.app.ui.theme.Spacing
import com.cryptochecker.app.ui.theme.amountNumbers
import com.cryptochecker.app.ui.theme.label
import com.cryptochecker.app.util.A11yText
import com.cryptochecker.app.util.PriceFormat
import kotlin.math.abs

/**
 * Kopfzeile (ganze Zeile tippbar): Titel, zugeklappt mit Zeitraum und Änderung, Pfeil zum
 * Auf-/Zuklappen in der Themenfarbe (wie [com.cryptochecker.app.ui.components.ExpandToggleButton]).
 * Für den Screenreader ein Satz samt Zustand.
 */
@Composable
internal fun HistoryHeader(
    history: PortfolioHistoryUi?,
    range: PortfolioHistoryRange,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val title = stringResource(R.string.portfolio_history_title)
    // Zugeklappt wie die Zahl: Wert und Gewinn über den Zeitraum
    val spokenChange = history?.let { gainSentence(it) }
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
        ExpandChevronIcon(expanded = expanded, modifier = Modifier.padding(start = Spacing.xs))
    }
}

/**
 * Zugeklappt: MEIN Gewinn über den Zeitraum ([PortfolioHistorySeries.pnlChange], Käufe/Verkäufe zählen nie
 * als Gewinn) als «↗ 2.40%» in der Kursfarbe; ohne Prozent der Betrag, ohne Gewinn/Verlust «—».
 */
@Composable
private fun CollapsedChange(series: PortfolioHistorySeries, unit: String) {
    val change = series.pnlChange?.takeIf { series.hasChart }
    val percent = series.pnlPercent?.takeIf { change != null }
    when {
        change == null -> PlValuePill("—", null, size = ChangePillSize.MEDIUM)
        percent != null -> PlPercentPill(percent, change, size = ChangePillSize.MEDIUM)
        else -> PlValuePill(maskAmount(signedValue(change, unit)), change, size = ChangePillSize.MEDIUM)
    }
}

/**
 * Satz zu Wert und Gewinn über den Zeitraum («Wert 93’818.00 CHF. Gewinn, 30 Tage: gestiegen um …, …»);
 * null ohne Verlauf. Ohne Gewinn/Verlust «nicht verfügbar».
 */
@Composable
private fun gainSentence(history: PortfolioHistoryUi): String? {
    val series = history.series
    if (!series.hasChart) return null
    val context = LocalContext.current
    val value = spokenAmount(PriceFormat.valueWithCurrency(series.points.last().value, history.unit))
    val change = series.pnlChange
        ?: return stringResource(R.string.portfolio_value) + " " + value + ". " +
            stringResource(R.string.portfolio_card_figure_none_a11y, stringResource(R.string.portfolio_history_gain_label))
    val spokenGain = when {
        PortfolioFormat.isZero(change) -> stringResource(R.string.a11y_change_flat)
        change > 0 -> stringResource(R.string.a11y_change_up, spokenAmount(PriceFormat.valueWithCurrency(abs(change), history.unit)))
        else -> stringResource(R.string.a11y_change_down, spokenAmount(PriceFormat.valueWithCurrency(abs(change), history.unit)))
    }
    val spokenPercent = series.pnlPercent?.let { A11yText.change(context, it) }
    val gain = listOfNotNull(spokenGain, spokenPercent).joinToString(", ")
    return stringResource(R.string.portfolio_history_gain_a11y, value, stringResource(history.range.longRes), gain)
}

/**
 * Kopfzeile des Verlaufs, immer beides: «Wert 93’818.00 CHF · Gewinn +2’340.00 CHF (+2.40%)». Wert = heute,
 * Gewinn = MEIN Gewinn/Verlust über den Zeitraum ([PortfolioHistorySeries.pnlChange]) in der Kursfarbe,
 * Prozent vom eingesetzten Kapital ([PortfolioHistorySeries.pnlPercent], ohne Basis nur der Betrag);
 * ohne Gewinn/Verlust «—». Ein Satz für den Screenreader.
 */
@Composable
internal fun ValueGainLine(history: PortfolioHistoryUi) {
    val series = history.series
    val spoken = gainSentence(history).orEmpty()
    val change = series.pnlChange
    val gainText = when {
        change == null -> "—"
        else -> {
            val amount = maskAmount(signedValue(change, history.unit))
            series.pnlPercent?.let { "$amount (${PortfolioFormat.signedPercent(it)})" } ?: amount
        }
    }
    val secondary = MaterialTheme.colorScheme.onSurfaceVariant
    val gainColor = plColor(change)
    val valueLabel = stringResource(R.string.portfolio_value)
    val gainLabel = stringResource(R.string.portfolio_history_gain_label)
    // Umbruch nur zwischen den Teilen («Wert …» · «Gewinn …»), nie in einer Zahl — darin
    // geschützte Leerzeichen (gleich lang, die Stile der Währung bleiben an ihrer Stelle)
    val small = withSmallQuote(maskAmount(PriceFormat.valueWithCurrency(series.points.last().value, history.unit)))
    val valueText = AnnotatedString(small.text.replace(' ', NO_BREAK_SPACE), spanStyles = small.spanStyles)
    val text = buildAnnotatedString {
        withStyle(SpanStyle(color = secondary)) { append("$valueLabel$NO_BREAK_SPACE") }
        append(valueText)
        withStyle(SpanStyle(color = secondary)) { append(" · $gainLabel$NO_BREAK_SPACE") }
        withStyle(SpanStyle(color = gainColor, fontWeight = FontWeight.SemiBold)) { append(gainText.replace(' ', NO_BREAK_SPACE)) }
    }
    Text(
        text,
        style = MaterialTheme.typography.titleSmall.amountNumbers(),
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = Spacing.xs)
            .clearAndSetSemantics { contentDescription = spoken }
    )
}

/** Geschütztes Leerzeichen: kein Zeilenumbruch in Beträgen der Kopfzeile ([ValueGainLine]). */
private const val NO_BREAK_SPACE = '\u00A0'
