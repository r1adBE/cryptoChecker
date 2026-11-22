package com.cryptochecker.app.ui.features.explorer

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.cryptochecker.app.domain.watch.FavoriteKind
import com.cryptochecker.app.ui.components.DialogText
import com.cryptochecker.app.ui.components.DropdownField
import com.cryptochecker.app.ui.components.ExpandChevronIcon
import com.cryptochecker.app.ui.components.ExpandToggleButton
import com.cryptochecker.app.ui.theme.AppColors
import com.cryptochecker.app.ui.theme.DisabledColors
import com.cryptochecker.marketdata.model.ExchangePair
import com.cryptochecker.marketdata.model.FuturesContractType
import com.cryptochecker.resources.R

/**
 * Mehrere Paare auf einmal (Expertenfunktion, eingeklappt bis man sie öffnet): Gegenwährung
 * wählen, «Alle …-Paare» übernehmen, Zusammenfassung mit Ziel-Gruppe und «Alle hinzufügen»;
 * die Liste der Paare nur auf Wunsch — ein Tipp wählt das Paar oben in «Paar wählen».
 */
@Composable
internal fun BulkPairsCard(
    quotes: List<String>,
    quote: String?,
    pairs: List<ExchangePair>,
    /** Zur gewählten Gegenwährung gibt es keine Paare. */
    emptyMessage: Boolean,
    syncing: Boolean,
    /** «Alle hinzufügen» läuft. */
    adding: Boolean,
    /** Während der Aufnahme: verarbeitete und alle Paare (Balken unter dem Knopf); total 0 = noch kein Stand. */
    addDone: Int = 0,
    addTotal: Int = 0,
    /** Davon noch nicht in der Merkliste; 0 = Knopf aus («Alle schon in der Merkliste»). */
    newCount: Int = pairs.size,
    /** Gewähltes Paar (in der Liste hervorgehoben). */
    coin: String?,
    pairQuote: String?,
    contract: FuturesContractType?,
    favorites: FavoritesUi,
    groupTarget: GroupTargetUi,
    onQuoteChanged: (String) -> Unit,
    onApply: () -> Unit,
    onSelectPair: (ExchangePair) -> Unit,
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
        if (!asTab) {
            // Für den Screenreader ein Knopf mit Zustand («aufgeklappt»/«zugeklappt»), wie `ExpandToggleButton`
            val state = stringResource(if (showBulk) R.string.a11y_expanded else R.string.a11y_collapsed)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.small)
                    .clickable(role = Role.Button) { onBulkOpenChange(!showBulk) }
                    .semantics { stateDescription = state }
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
        }
        if (showBulk) {
            if (!asTab) Spacer(modifier = Modifier.size(gap))
            Row(verticalAlignment = Alignment.CenterVertically) {
                DropdownField(
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
                colors = DisabledColors.filledTonalButton(),
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

                // Vorab gezählt: «312 neu, 434 schon in der Merkliste» (nur wenn schon welche da sind)
                if (newCount < pairs.size) {
                    StepHint(pluralStringResource(R.plurals.explorer_bulk_counts, newCount, newCount, pairs.size - newCount))
                }
                GroupTargetSelector(groupTarget, modifier = Modifier.padding(top = 4.dp))
                BulkAddAllButton(adding = adding, syncing = syncing, newCount = newCount, total = pairs.size, onAddAll = onAddAll)
                // Läuft weiter, auch wenn der Bildschirm verlassen wird (BulkAddRunner)
                if (adding && addTotal > 0) {
                    com.cryptochecker.app.ui.components.BulkAddProgress(
                        done = addDone,
                        total = addTotal,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                }

                if (showBulkList) {
                    BulkPairList(
                        pairs = pairs,
                        stats = stats,
                        quote = quote,
                        coin = coin,
                        pairQuote = pairQuote,
                        contract = contract,
                        onSelectPair = onSelectPair,
                        onNeedStats = onNeedStats,
                    )
                }
            }
        }
    }
}

/** «Alle hinzufügen» bzw. «N neue hinzufügen» ([BulkPairsCard]); läuft die Aufnahme, ein Fortschrittskreis. */
@Composable
private fun BulkAddAllButton(adding: Boolean, syncing: Boolean, newCount: Int, total: Int, onAddAll: () -> Unit) {
    Button(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        enabled = !adding && !syncing && newCount > 0,
        colors = DisabledColors.button(),
        onClick = onAddAll
    ) {
        if (adding) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = AppColors.onAccent
            )
        } else {
            Text(
                when {
                    newCount <= 0 -> stringResource(R.string.explorer_bulk_all_present)
                    newCount < total -> pluralStringResource(R.plurals.explorer_add_new_pairs, newCount, newCount)
                    else -> pluralStringResource(R.plurals.explorer_add_all_pairs, total, total)
                }
            )
        }
    }
}

/** Rückfrage vor «Alle hinzufügen»: wie viele Paare, welche Gegenwährung, welche Börse. */
@Composable
internal fun BulkConfirmDialog(
    count: Int,
    total: Int,
    quote: String,
    exchange: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(pluralStringResource(R.plurals.explorer_bulk_confirm_title, count, count)) },
        text = {
            DialogText { Text(pluralStringResource(R.plurals.explorer_bulk_confirm_text, total, total, quote, exchange)) }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.explorer_add_to_watchlist)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        }
    )
}
