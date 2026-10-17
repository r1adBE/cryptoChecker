// Bedienelemente der Verlaufskarte: Zeitraum-Chips mit Schalter Kerzen | Linie, «Wert | Gewinn/Verlust»
// und «CHF | USDT | Vergleich».
// Eigene Datei, weil es reine Schalter ohne eigene Rechnung sind (wie `PortfolioHistoryControls.swift`).
package com.cryptochecker.app.ui.features.portfolio

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import com.cryptochecker.app.R
import com.cryptochecker.app.domain.portfolio.PortfolioHistoryRange
import com.cryptochecker.app.domain.portfolio.PortfolioHistoryView
import com.cryptochecker.app.ui.components.ChartTypeSwitch
import com.cryptochecker.app.ui.theme.Spacing
import com.cryptochecker.app.ui.theme.label

/**
 * Chips «Wert | Gewinn/Verlust» über dem Chart ([pnl] = Gewinn/Verlust). Screenreader: Auswahl-Chips mit Zustand.
 */
@Composable
internal fun HistoryModeSwitch(pnl: Boolean, onPnlChange: (Boolean) -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        modifier = Modifier.fillMaxWidth().padding(top = Spacing.sm)
    ) {
        FilterChip(
            selected = !pnl,
            onClick = { onPnlChange(false) },
            label = { Text(stringResource(R.string.portfolio_value), maxLines = 1) }
        )
        FilterChip(
            selected = pnl,
            onClick = { onPnlChange(true) },
            label = { Text(stringResource(R.string.ledger_gain_title), maxLines = 1) }
        )
    }
}

/**
 * Zeitraum-Chips links, Kerzen/Linie rechts in derselben Zeile (wie im Aktionsblatt); reicht der
 * Platz nicht, rutscht der Schalter rechtsbündig darunter. «Seit 1. Kauf» steht als Text auf dem Chip
 * («Seit Kauf»), nicht mehr als ∞.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun HistoryControls(
    range: PortfolioHistoryRange,
    onRange: (PortfolioHistoryRange) -> Unit,
    showTypeSwitch: Boolean,
    candles: Boolean,
    onCandlesChange: (Boolean) -> Unit,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        modifier = Modifier.fillMaxWidth().padding(top = Spacing.sm)
    ) {
        PortfolioHistoryRange.entries.forEach { option ->
            val spoken = rangeSpoken(option)
            val label = stringResource(
                if (option == PortfolioHistoryRange.SINCE_FIRST) R.string.portfolio_history_chip_since_first else option.shortRes
            )
            FilterChip(
                selected = option == range,
                onClick = { onRange(option) },
                label = {
                    Text(
                        label,
                        maxLines = 1,
                        modifier = Modifier.semantics { contentDescription = spoken }
                    )
                }
            )
        }
        if (showTypeSwitch) {
            Box(
                contentAlignment = Alignment.CenterEnd,
                modifier = Modifier.weight(1f).align(Alignment.CenterVertically)
            ) {
                ChartTypeSwitch(line = !candles, onLineChange = { onCandlesChange(!it) })
            }
        }
    }
}

/**
 * Umschalter «CHF | USDT | Vergleich» ([compareAvailable] false: ohne «Vergleich»).
 * Screenreader: Optionsfelder mit Auswahl-Zustand («Wert in CHF», «Vergleich CHF und USDT in Prozent»).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HistoryViewSwitch(
    currency: String,
    selected: PortfolioHistoryView,
    compareAvailable: Boolean,
    onView: (PortfolioHistoryView) -> Unit,
) {
    val options = if (compareAvailable) {
        PortfolioHistoryView.entries
    } else {
        listOf(PortfolioHistoryView.CURRENCY, PortfolioHistoryView.USDT)
    }
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth().padding(top = Spacing.sm)) {
        options.forEachIndexed { index, option ->
            val label = when (option) {
                PortfolioHistoryView.CURRENCY -> currency
                PortfolioHistoryView.USDT -> PortfolioFormat.USDT
                PortfolioHistoryView.COMPARE -> stringResource(R.string.portfolio_compare)
            }
            val spoken = when (option) {
                PortfolioHistoryView.CURRENCY -> stringResource(R.string.portfolio_history_in_a11y, currency)
                PortfolioHistoryView.USDT -> stringResource(R.string.portfolio_history_in_a11y, PortfolioFormat.USDT)
                PortfolioHistoryView.COMPARE -> stringResource(R.string.portfolio_compare_a11y, currency)
            }
            SegmentedButton(
                selected = option == selected,
                onClick = { onView(option) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                icon = {}
            ) {
                Text(
                    label,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.semantics { contentDescription = spoken }
                )
            }
        }
    }
}
