// Abschnitt «Vergleich» der Verlaufskarte: Prozent-Chart der gewählten Währung gegen USDT mit Legende
// und Währungseffekt. Eigene Datei, weil er nur bei einer Umrechnungswährung erscheint
// (wie `PortfolioHistoryCompareSection.swift`).
package com.cryptochecker.app.ui.features.portfolio

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.cryptochecker.app.R
import com.cryptochecker.app.domain.portfolio.PortfolioCompareSeries
import com.cryptochecker.app.ui.components.ChartPointStep
import com.cryptochecker.app.ui.components.chartPointSemantics
import com.cryptochecker.app.ui.theme.Spacing
import com.cryptochecker.app.ui.theme.amountNumbers
import com.cryptochecker.app.util.A11yText

/**
 * «Vergleich»: Legende, beide Kurven in Prozent seit dem Ausgangspunkt, Achse und darunter
 * «Währungseffekt: −5.10%» — am letzten Tag bzw. am gezogenen Tag (dann mit beiden Werten).
 * Screenreader: ein Chart-Satz mit beiden Prozenten und dem Währungseffekt; die Aktionen «Nächster/Vorheriger Punkt»
 * sagen Tag für Tag beide Linien an («12. März 2026: CHF gestiegen um 2.10%, USDT gestiegen um 1.50%»).
 */
@Composable
internal fun CompareChart(history: PortfolioHistoryUi, compare: PortfolioCompareSeries, period: String) {
    val context = LocalContext.current
    val currency = history.unit
    var scrub by remember(compare) { mutableStateOf<Int?>(null) }
    val color = plColor(compare.currency.last())
    val grey = MaterialTheme.colorScheme.onSurfaceVariant
    val effectText = stringResource(R.string.portfolio_currency_effect, PortfolioFormat.signedPercent(compare.effect))
    val chartSentence = stringResource(
        R.string.portfolio_compare_chart_a11y,
        period,
        currency,
        A11yText.change(context, compare.currency.last()),
        A11yText.change(context, compare.usdt.last()),
    ) + ". " + effectText
    var a11yPoint by remember(compare) { mutableStateOf<Int?>(null) }
    val pointCount = compare.epochDays.size
    val pointText = a11yPoint?.takeIf { it in 0..compare.lastIndex }?.let { i ->
        val lines = stringResource(
            R.string.a11y_chart_two_series,
            currency,
            A11yText.change(context, compare.currency.getOrNull(i)),
            PortfolioFormat.USDT,
            A11yText.change(context, compare.usdt.getOrNull(i)),
        )
        stringResource(R.string.label_value, PortfolioFormat.date(dayMillis(compare.epochDays[i])), lines)
    }

    CompareLegend(currency, color, grey)
    HistoryCompareChart(
        compare = compare,
        currency = currency,
        color = color,
        scrub = scrub,
        onScrub = { scrub = it },
        modifier = Modifier
            .fillMaxWidth()
            .height(HISTORY_CHART_HEIGHT)
            .padding(top = Spacing.xs)
            .chartPointSemantics(
                chartSentence,
                pointCount,
                pointText,
                stringResource(R.string.a11y_chart_next_point),
                stringResource(R.string.a11y_chart_previous_point),
            ) { forward ->
                a11yPoint = if (forward) ChartPointStep.next(a11yPoint, pointCount)
                else ChartPointStep.previous(a11yPoint, pointCount)
            }
    )
    // Beginn (Ausgangspunkt) und Ende der Achse; wie der Chart immer von links nach rechts
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp)
                .clearAndSetSemantics { }
        ) {
            Text(
                PortfolioFormat.date(dayMillis(compare.epochDays.first())),
                style = MaterialTheme.typography.labelSmall,
                color = grey
            )
            Spacer(Modifier.weight(1f))
            Text(
                stringResource(R.string.portfolio_history_today),
                style = MaterialTheme.typography.labelSmall,
                color = grey
            )
        }
    }
    // Währungseffekt am letzten bzw. gezogenen Tag (Prozent in der Währung minus Prozent in USDT)
    val index = scrub?.takeIf { it in 0..compare.lastIndex } ?: compare.lastIndex
    val effect = compare.effectAt(index) ?: compare.effect
    Text(
        stringResource(R.string.portfolio_currency_effect, PortfolioFormat.signedPercent(effect)),
        style = MaterialTheme.typography.labelLarge.amountNumbers(),
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(top = Spacing.xs)
    )
}

/** Legende «▬ CHF  ▬ USDT» (Strichstärke wie im Chart); für Screenreader im Chart-Satz enthalten. */
@Composable
private fun CompareLegend(currency: String, color: Color, grey: Color) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        modifier = Modifier
            .padding(top = Spacing.sm)
            .clearAndSetSemantics { }
    ) {
        Box(Modifier.size(width = 14.dp, height = 3.dp).background(color, CircleShape))
        Text(currency, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = grey)
        Spacer(Modifier.width(Spacing.sm))
        Box(Modifier.size(width = 14.dp, height = 1.5.dp).background(grey, CircleShape))
        Text(PortfolioFormat.USDT, style = MaterialTheme.typography.labelSmall, color = grey)
    }
}
