package com.cryptochecker.app.ui.features.explorer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cryptochecker.app.domain.format.CompactAmount
import com.cryptochecker.app.ui.components.ChangePill
import com.cryptochecker.app.ui.theme.AppColors
import com.cryptochecker.app.ui.theme.Spacing
import com.cryptochecker.app.ui.theme.amountNumbers
import com.cryptochecker.marketdata.model.ExchangePair
import com.cryptochecker.marketdata.model.FuturesContractType
import com.cryptochecker.resources.R

// Liste der Paare unter «Mehrere Paare» ([BulkPairsCard]). Eigene Datei, weil ExplorerBulk.kt sonst zu lang würde.

/** So viele Paare zeigt die Vorschau, bevor «Alle anzeigen» den Rest aufklappt. */
private const val BULK_PREVIEW_ROWS = 25

/**
 * Liste der Paare unter «Mehrere Paare» ([BulkPairsCard]) mit Schnellfiltern: erst die ersten Paare, «Alle anzeigen»
 * klappt den Rest auf; ein Tipp wählt das Paar oben in «Paar wählen».
 */
@Composable
internal fun BulkPairList(
    pairs: List<ExchangePair>,
    stats: Map<String, PairStats>,
    quote: String?,
    coin: String?,
    pairQuote: String?,
    contract: FuturesContractType?,
    onSelectPair: (ExchangePair) -> Unit,
    onNeedStats: () -> Unit,
) {
    LaunchedEffect(pairs) { onNeedStats() }
    StepHint(stringResource(R.string.explorer_bulk_tap_hint))
    // Schnellfilter nur, wenn die Liste die Werte hat (sonst ausgeblendet)
    var filterName by rememberSaveable { mutableStateOf<String?>(null) }
    val available = remember(pairs, stats) {
        QuickFilters.available(pairs.map { p -> p.pairId?.let { stats[it] } })
    }
    val filter = QuickFilter.entries.firstOrNull { it.name == filterName }?.takeIf { it in available }
    QuickFilterChips(available, filter, onSelect = { filterName = QuickFilters.toggle(filter, it)?.name })
    val sortedPairs = remember(pairs, stats, filter) {
        QuickFilters.sort(pairs, filter) { p -> p.pairId?.let { stats[it] } }
    }
    // Antippbare Vorschau: wählt das Paar oben in «Paar wählen». Keine eigene rollende
    // Liste in der rollenden Seite (Scrollen im Scrollen): erst die ersten Paare,
    // «Alle anzeigen» klappt den Rest auf.
    var showAllPairs by rememberSaveable(quote) { mutableStateOf(false) }
    val visiblePairs = if (showAllPairs) sortedPairs else sortedPairs.take(BULK_PREVIEW_ROWS)
    Column(modifier = Modifier.fillMaxWidth()) {
        visiblePairs.forEach { pair ->
            key("${pair.base}/${pair.quote}/${pair.contractType}") {
                val pairStats = pair.pairId?.let { stats[it] }
                BulkPairRow(
                    pair = pair,
                    metric = filter?.let { f -> quickFilterText(f, pairStats, pair.quote) },
                    metricChange = filter?.let { f -> quickFilterChange(f, pairStats) },
                    selected = pair.base == coin &&
                        pair.quote == pairQuote &&
                        pair.contractType == contract,
                    onClick = { onSelectPair(pair) },
                )
            }
        }
    }
    if (!showAllPairs && sortedPairs.size > BULK_PREVIEW_ROWS) {
        TextButton(
            onClick = { showAllPairs = true },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.explorer_bulk_show_all, sortedPairs.size))
        }
    }
}

/** Ein Paar der Vorschau: «BTC/USDT», bei Futures die Laufzeit rechts; gewählt hinterlegt. */
@Composable
private fun BulkPairRow(
    pair: ExchangePair,
    selected: Boolean,
    onClick: () -> Unit,
    metric: String? = null,
    /** «Gewinner»/«Verlierer»: Veränderung als farbige Schrift statt [metric]. */
    metricChange: Double? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .background(
                if (selected) {
                    AppColors.accentTint(0.12f)
                } else {
                    Color.Transparent
                }
            )
            // Einzelauswahl: TalkBack sagt «ausgewählt» an (nicht nur die Hinterlegung zeigt es),
            // Trefferfläche mind. 48 dp
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .heightIn(min = 48.dp)
            .padding(horizontal = Spacing.sm, vertical = 8.dp)
    ) {
        Text(
            text = "${pair.base}/${pair.quote}",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (selected) FontWeight.SemiBold else null,
            modifier = Modifier.weight(1f)
        )
        if (pair.contractType != FuturesContractType.NONE) {
            Text(
                text = pair.contractType.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        // Wert des gewählten Schnellfilters: Anzeige «+3.21%» bzw. grau «12.3M USDT»
        if (metricChange != null) {
            ChangePill(change = metricChange, modifier = Modifier.padding(start = Spacing.sm))
        } else if (metric != null) {
            Text(
                text = metric,
                style = MaterialTheme.typography.labelMedium.amountNumbers(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = Spacing.sm)
            )
        }
        // Sichtbares Zeichen neben der Farbe (Farbsehschwäche); Ansage kommt aus selectable
        if (selected) {
            Icon(
                painterResource(R.drawable.ic_check),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = Spacing.sm).size(18.dp)
            )
        }
    }
}

/**
 * Schnellfilter «Gewinner», «Verlierer», «Volumen» über der Liste: eine Auswahl, erneutes
 * Antippen hebt sie auf (dann die gewohnte Reihenfolge). Screenreader: gewählt/nicht gewählt.
 */
@Composable
private fun QuickFilterChips(available: Set<QuickFilter>, selected: QuickFilter?, onSelect: (QuickFilter) -> Unit) {
    if (available.isEmpty()) return
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), modifier = Modifier.padding(bottom = 4.dp)) {
        QuickFilter.entries.filter { it in available }.forEach { filter ->
            val isSelected = filter == selected
            FilterChip(
                selected = isSelected,
                onClick = { onSelect(filter) },
                label = { Text(stringResource(filter.labelRes), maxLines = 1) },
                modifier = Modifier.semantics { this.selected = isSelected }
            )
        }
    }
}

/** Angezeigtes Volumen zum Filter «Volumen»; sonst und ohne Wert nichts. */
private fun quickFilterText(filter: QuickFilter, stats: PairStats?, quote: String): String? {
    if (filter != QuickFilter.VOLUME) return null
    val value = QuickFilters.value(stats, filter) ?: return null
    return CompactAmount.format(value, quote)
}

/** Veränderung zu «Gewinner»/«Verlierer» (als farbige Schrift); beim Volumen und ohne Wert null. */
private fun quickFilterChange(filter: QuickFilter, stats: PairStats?): Double? =
    if (filter == QuickFilter.VOLUME) null else QuickFilters.value(stats, filter)

private val QuickFilter.labelRes: Int
    get() = when (this) {
        QuickFilter.GAINERS -> R.string.explorer_filter_gainers
        QuickFilter.LOSERS -> R.string.explorer_filter_losers
        QuickFilter.VOLUME -> R.string.explorer_filter_volume
    }
