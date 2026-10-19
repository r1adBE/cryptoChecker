@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.cryptochecker.app.ui.features.portfolio

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import com.cryptochecker.app.R
import com.cryptochecker.app.data.portfolio.PortfolioTxEntity
import com.cryptochecker.app.domain.portfolio.CostGapKind
import com.cryptochecker.app.domain.portfolio.PortfolioCalculator
import com.cryptochecker.app.domain.portfolio.PortfolioCostGap
import com.cryptochecker.app.domain.portfolio.PortfolioCostGaps
import com.cryptochecker.app.ui.theme.Spacing
import com.cryptochecker.app.ui.theme.body
import com.cryptochecker.app.ui.theme.label
import java.math.BigDecimal

/**
 * «Einträge ohne Einstand» (Build 84): Zugänge ohne Kaufpreis ([PortfolioCostGaps]), neueste zuerst, wahlweise nur ein
 * Coin ([coin]). Je Kauf/Einzahlung der Vorschlag Menge × geprüfter Tagesschluss des Datums («Vorschlag: Tagesschluss …»)
 * und «Einstand erfassen» (öffnet den Eintrag mit dem Vorschlag zum Übernehmen und Anpassen, [onEdit]) oder «Unbekannt
 * lassen» (zählt auf der Karte nicht mehr). Belohnungen: «Einstand = Wert bei Zufluss (Einkommen)» — kein Angebot, ihn zu
 * ändern (AT: Anschaffungskosten 0).
 */
@Composable
internal fun PortfolioCostGapsSheet(
    viewModel: PortfolioViewModel,
    transactions: List<PortfolioTxEntity>,
    coin: String? = null,
    onEdit: (PortfolioTxEntity) -> Unit,
    onDismiss: () -> Unit,
) {
    val ui = rememberCostGaps(viewModel, transactions)
    val gaps = remember(ui.gaps, coin) {
        coin?.let { c -> ui.gaps.filter { PortfolioCalculator.normalizeCoin(it.trade.coin) == PortfolioCalculator.normalizeCoin(c) } } ?: ui.gaps
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth().navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            item(key = "head") {
                Column(modifier = Modifier.padding(horizontal = Spacing.xl)) {
                    Text(
                        stringResource(R.string.cost_gaps_sheet_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        stringResource(if (gaps.isEmpty()) R.string.cost_gaps_empty else R.string.cost_gaps_sheet_intro),
                        style = MaterialTheme.typography.body,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = Spacing.xs, bottom = Spacing.sm)
                    )
                }
            }
            items(gaps, key = { it.trade.id }) { gap ->
                HorizontalDivider(modifier = Modifier.padding(horizontal = Spacing.xl))
                CostGapRow(
                    gap = gap,
                    acknowledged = gap.trade.id in ui.acknowledged,
                    viewModel = viewModel,
                    onEdit = {
                        transactions.firstOrNull { it.id == gap.trade.id }?.let { tx ->
                            viewModel.reopenCostGap(tx.id)
                            onEdit(tx)
                        }
                    },
                    onLeaveUnknown = { viewModel.acknowledgeCostGap(gap.trade.id) },
                )
            }
        }
    }
}

/** Eine Zeile: Datum · Art, Menge; Vorschlag bzw. Hinweis «Wert bei Zufluss» und die Aktionen. */
@Composable
private fun CostGapRow(
    gap: PortfolioCostGap,
    acknowledged: Boolean,
    viewModel: PortfolioViewModel,
    onEdit: () -> Unit,
    onLeaveUnknown: () -> Unit,
) {
    val t = gap.trade
    val coin = PortfolioCalculator.normalizeCoin(t.coin)
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.xl, vertical = Spacing.xs)) {
        Text(
            PortfolioFormat.date(t.time) + " · " + stringResource(t.type.labelRes()),
            style = MaterialTheme.typography.label,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            maskAmount(PortfolioFormat.amount(t.amount, coin)),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold
        )
        when (gap.kind) {
            CostGapKind.INCOME_RECEIPT_VALUE, CostGapKind.INCOME_ZERO -> Text(
                stringResource(if (gap.kind == CostGapKind.INCOME_ZERO) R.string.cost_gap_income_zero else R.string.cost_gap_income),
                style = MaterialTheme.typography.body,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Spacing.xs)
            )
            CostGapKind.EDITABLE -> {
                // Vorschlag: Menge × geprüfter Tagesschluss des Datums (gleiche Quelle wie das Erfassen-Blatt)
                var close by remember(t.id, t.time) { mutableStateOf<BigDecimal?>(null) }
                var loaded by remember(t.id, t.time) { mutableStateOf(false) }
                LaunchedEffect(t.id, t.time) {
                    close = viewModel.historicClose(coin, localDate(t.time))
                    loaded = true
                }
                val value = PortfolioCostGaps.suggestion(t, close)
                Text(
                    when {
                        !loaded -> stringResource(R.string.cost_gap_suggestion_loading)
                        value == null -> stringResource(R.string.cost_gap_suggestion_none)
                        else -> stringResource(
                            R.string.cost_gap_suggestion, PortfolioFormat.date(t.time), maskAmount(PortfolioFormat.usdt(value.toDouble())),
                        )
                    },
                    style = MaterialTheme.typography.body,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = Spacing.xs)
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onEdit) {
                        Text(stringResource(R.string.cost_gap_enter), fontWeight = FontWeight.SemiBold)
                    }
                    if (acknowledged) {
                        Text(
                            stringResource(R.string.cost_gap_left_unknown),
                            style = MaterialTheme.typography.label,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = Spacing.sm)
                        )
                    } else {
                        TextButton(onClick = onLeaveUnknown) { Text(stringResource(R.string.cost_gap_leave_unknown)) }
                    }
                }
            }
        }
    }
}
