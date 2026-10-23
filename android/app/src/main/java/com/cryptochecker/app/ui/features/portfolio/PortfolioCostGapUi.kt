// Einträge ohne Einstand und Einstand für Adressguthaben: Zustand, Aktionen und kleine Bausteine (Karte im
// Portfolio-Tab, antippbare Hinweise). Blätter in `PortfolioCostGapsSheet.kt` und `PortfolioAddressLotsSheet.kt`.
package com.cryptochecker.app.ui.features.portfolio

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.cryptochecker.app.R
import com.cryptochecker.app.data.portfolio.PortfolioTxEntity
import com.cryptochecker.app.domain.portfolio.AddressCostLot
import com.cryptochecker.app.domain.portfolio.PortfolioCostGap
import com.cryptochecker.app.domain.portfolio.PortfolioCostGaps
import com.cryptochecker.app.domain.portfolio.TaxTemplate
import com.cryptochecker.app.ui.components.AppCard
import com.cryptochecker.app.ui.theme.Spacing
import com.cryptochecker.app.ui.theme.body
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Id eines Hinweises unter der Gesamtkarte, der die Einträge ohne Einstand öffnet (keine echte Transaktion). */
internal const val COST_GAPS_HINT_ID = Long.MIN_VALUE

/** Lücken beim Einstand ([PortfolioCostGaps.gaps]) und die bewusst «unbekannt gelassenen» Ids. */
internal data class CostGapsUi(val gaps: List<PortfolioCostGap>, val acknowledged: Set<Long>) {
    /** Offene, erfassbare Lücken (Karte «N Einträge ohne Einstand»). */
    val pending: List<PortfolioCostGap> get() = PortfolioCostGaps.pending(gaps, acknowledged)
}

/** Steuer-Vorlage aus den Einstellungen (AT: Belohnungen mit Anschaffungskosten 0). */
@Composable
internal fun rememberTaxTemplate(viewModel: PortfolioViewModel): TaxTemplate {
    val flow = remember(viewModel) { viewModel.settingsRepository.settings.map { it.taxTemplate } }
    val template by flow.collectAsStateWithLifecycle(initialValue = TaxTemplate.DEFAULT)
    return template
}

/** Lücken der gespeicherten Transaktionen; Merker ohne Lücke mehr werden weggeräumt. */
@Composable
internal fun rememberCostGaps(viewModel: PortfolioViewModel, transactions: List<PortfolioTxEntity>?): CostGapsUi {
    val template = rememberTaxTemplate(viewModel)
    val acknowledged by viewModel.costGapStore.acknowledged.collectAsStateWithLifecycle()
    val gaps = remember(transactions, template) { PortfolioCostGaps.gaps(transactions.orEmpty().map { it.toTrade() }, template) }
    // Erst wenn die Datenbank geantwortet hat (sonst wären alle Merker kurz «ohne Lücke»)
    LaunchedEffect(gaps, acknowledged, transactions != null) {
        if (transactions != null) viewModel.costGapStore.prune(PortfolioCostGaps.prune(acknowledged, gaps))
    }
    return CostGapsUi(gaps, acknowledged)
}

/** «Unbekannt lassen»: zählt auf der Karte nicht mehr. */
internal fun PortfolioViewModel.acknowledgeCostGap(id: Long) = costGapStore.acknowledge(id)

/** Wieder als offen zählen. */
internal fun PortfolioViewModel.reopenCostGap(id: Long) = costGapStore.reopen(id)

/** Eröffnungsposten einer beobachteten Adresse ersetzen (geprüft im Repository). */
internal fun PortfolioViewModel.setAddressCostLots(id: Long, lots: List<AddressCostLot>) {
    viewModelScope.launch { safe { repository.setWatchedAddressCostLots(id, lots) } }
}

/** Antippbar, wenn [onClick] gesetzt ist (Hinweis «ohne Kaufpreis» → Blatt zum Ergänzen). */
internal fun Modifier.costLink(onClick: (() -> Unit)?): Modifier =
    if (onClick == null) this else clickable(role = Role.Button, onClick = onClick)

/** Hinweis wie [PortfolioHint], antippbar (Farbe der Aktion). */
@Composable
internal fun CostLinkHint(text: String, onClick: () -> Unit) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = Spacing.sm).costLink(onClick)
    )
}

/**
 * Sanfte Karte im Portfolio-Tab: «N Einträge ohne Einstand» mit kurzer Erklärung; antippen öffnet das Blatt. Nur mit
 * offenen, erfassbaren Lücken (Belohnungen = Wert bei Zufluss und «unbekannt gelassene» zählen nicht).
 */
@Composable
internal fun CostGapsCard(count: Int, onOpen: () -> Unit) {
    AppCard(modifier = Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onOpen)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.cost_gaps_card, count),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    stringResource(R.string.cost_gaps_card_text),
                    style = MaterialTheme.typography.body,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = Spacing.xs)
                )
            }
            Icon(
                painterResource(R.drawable.ic_chevron_right),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = Spacing.sm).padding(2.dp)
            )
        }
    }
}
