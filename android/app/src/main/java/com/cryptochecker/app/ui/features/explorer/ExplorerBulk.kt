package com.cryptochecker.app.ui.features.explorer

import com.cryptochecker.app.ui.components.DialogText
import com.cryptochecker.app.ui.components.ExpandChevronIcon
import com.cryptochecker.app.ui.components.ExpandToggleButton
import com.cryptochecker.app.ui.theme.AppColors
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.saveable.rememberSaveable
import com.cryptochecker.app.util.CompactAmount
import com.cryptochecker.app.R
import com.cryptochecker.app.data.FavoriteKind
import com.cryptochecker.app.ui.components.ChangePill
import com.cryptochecker.app.ui.components.ComboBox
import com.cryptochecker.app.ui.theme.amountNumbers
import com.cryptochecker.app.ui.theme.Spacing
import com.cryptochecker.marketdata.model.MarketPair
import com.cryptochecker.marketdata.model.FuturesContractType

/** So viele Paare zeigt die Vorschau, bevor «Alle anzeigen» den Rest aufklappt. */
private const val BULK_PREVIEW_ROWS = 25

/**
 * Mehrere Paare auf einmal (Expertenfunktion, eingeklappt bis man sie öffnet): Gegenwährung
 * wählen, «Alle …-Paare» übernehmen, Zusammenfassung mit Ziel-Gruppe und «Alle hinzufügen»;
 * die Liste der Paare nur auf Wunsch — ein Tipp wählt das Paar oben in Schritt 2.
 */
@Composable
internal fun BulkPairsCard(
    quotes: List<String>,
    quote: String?,
    pairs: List<MarketPair>,
    /** Zur gewählten Gegenwährung gibt es keine Paare. */
    emptyMessage: Boolean,
    syncing: Boolean,
    /** «Alle hinzufügen» läuft. */
    adding: Boolean,
    /** Gewähltes Paar (in der Liste hervorgehoben). */
    coin: String?,
    pairQuote: String?,
    contract: FuturesContractType?,
    favorites: FavoritesUi,
    groupTarget: GroupTargetUi,
    onQuoteChanged: (String) -> Unit,
    onApply: () -> Unit,
    onSelectPair: (MarketPair) -> Unit,
    /** Rückfrage «Alle hinzufügen?» öffnen. */
    onAddAll: () -> Unit,
    /** Liste der Paare zeigen (bleibt, auch wenn die Karte kurz verschwindet). */
    showBulkList: Boolean,
    onToggleList: () -> Unit,
    /** Unter den Registern: ohne eigenen aufklappbaren Kopf, immer offen. */
    asTab: Boolean = false,
    /** Ohne Register: Liste aufgeklappt ([ExplorerSectionsState.bulk]) und deren Umschalter. */
    bulkOpen: Boolean = false,
    onBulkOpenChange: (Boolean) -> Unit = {},
    /** Kennzahlen aus der Sammelabfrage der Börse (Schlüssel = Paar-Kennung) für die Schnellfilter. */
    stats: Map<String, PairStats> = emptyMap(),
    /** Liste offen: Kennzahlen laden lassen (eine Sammelabfrage, nur wo die Börse eine hat). */
    onNeedStats: () -> Unit = {},
) {
    val showBulk = asTab || bulkOpen
    val gap = 12.dp
    StepCard {
        if (!asTab) Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.small)
                .clickable { onBulkOpenChange(!showBulk) }
                .padding(vertical = 4.dp)
        ) {
            Text(
                text = stringResource(R.string.explorer_bulk_title),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f)
            )
            // Auf-/Zuklapp-Pfeil überall in der Themenfarbe (wie ExpandToggleButton)
            ExpandChevronIcon(expanded = showBulk)
        }
        if (showBulk) {
            if (!asTab) Spacer(modifier = Modifier.size(gap))
            Row(verticalAlignment = Alignment.CenterVertically) {
                ComboBox(
                    modifier = Modifier.weight(1f),
                    itemList = quotes,
                    selectedIndex = quotes.indexOf(quote),
                    label = stringResource(id = R.string.explorer_field_quote),
                    searchable = true,
                    favorites = favorites.quotes,
                    onToggleFavorite = { favorites.onToggle(FavoriteKind.QUOTE, it) },
                    onValueChange = { index -> onQuoteChanged(quotes[index]) }
                )
            }
            FilledTonalButton(
                modifier = Modifier.fillMaxWidth().padding(top = gap),
                enabled = quote != null && !syncing,
                onClick = onApply
            ) {
                Text(text = stringResource(id = R.string.bulk_all_pairs, quote.orEmpty()))
            }

            if (emptyMessage) {
                StepHint(stringResource(R.string.bulk_all_pairs_empty, quote.orEmpty()))
            }

            if (pairs.isNotEmpty()) {
                // Zusammenfassung zuerst; die Liste nur auf Wunsch.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = gap)
                ) {
                    Text(
                        text = pluralStringResource(R.plurals.bulk_all_pairs_applied, pairs.size, pairs.size, quote.orEmpty()),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f)
                    )
                    ExpandToggleButton(
                        text = stringResource(
                            if (showBulkList) R.string.explorer_bulk_hide_list else R.string.explorer_bulk_show_list
                        ),
                        expanded = showBulkList,
                        onClick = onToggleList,
                    )
                }

                GroupTargetSelector(groupTarget, modifier = Modifier.padding(top = 4.dp))
                Button(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    enabled = !adding && !syncing,
                    onClick = onAddAll
                ) {
                    if (adding) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = AppColors.onAccent
                        )
                    } else {
                        Text(pluralStringResource(R.plurals.explorer_add_all_pairs, pairs.size, pairs.size))
                    }
                }

                if (showBulkList) {
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
                    // Antippbare Vorschau: wählt das Paar oben in Schritt 2. Keine eigene rollende
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
            }
        }
    }
}

/** Ein Paar der Vorschau: «BTC/USDT», bei Futures die Laufzeit rechts; gewählt hinterlegt. */
@Composable
private fun BulkPairRow(
    pair: MarketPair,
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
                if (selected) AppColors.accentTint(0.12f)
                else Color.Transparent
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

private val QuickFilter.labelRes: Int
    get() = when (this) {
        QuickFilter.GAINERS -> R.string.explorer_filter_gainers
        QuickFilter.LOSERS -> R.string.explorer_filter_losers
        QuickFilter.VOLUME -> R.string.explorer_filter_volume
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

/** Rückfrage vor «Alle hinzufügen»: wie viele Paare, welche Gegenwährung, welche Börse. */
@Composable
internal fun BulkConfirmDialog(count: Int, quote: String, market: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(pluralStringResource(R.plurals.explorer_bulk_confirm_title, count, count)) },
        text = {
            DialogText { Text(pluralStringResource(R.plurals.explorer_bulk_confirm_text, count, count, quote, market)) }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.explorer_add_to_watchlist)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        }
    )
}
