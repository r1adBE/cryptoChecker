package com.cryptochecker.app.ui.features.explorer

import com.cryptochecker.app.ui.theme.AppColors
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.cryptochecker.app.R
import com.cryptochecker.app.data.FavoriteKind
import com.cryptochecker.app.ui.components.ComboBox
import com.cryptochecker.app.ui.theme.Spacing
import com.cryptochecker.marketdata.model.CurrencyPairInfo
import com.cryptochecker.marketdata.model.FuturesContractType

/**
 * Mehrere Paare auf einmal (Expertenfunktion, eingeklappt bis man sie öffnet): Gegenwährung
 * wählen, «Alle …-Paare» übernehmen, Zusammenfassung mit Ziel-Gruppe und «Alle hinzufügen»;
 * die Liste der Paare nur auf Wunsch — ein Tipp wählt das Paar oben in Schritt 2.
 */
@Composable
internal fun BulkPairsCard(
    quotes: List<String>,
    quote: String?,
    pairs: List<CurrencyPairInfo>,
    /** Zur gewählten Gegenwährung gibt es keine Paare. */
    emptyMessage: Boolean,
    syncing: Boolean,
    /** «Alle hinzufügen» läuft. */
    adding: Boolean,
    currentBaseAsset: String?,
    currentQuoteAsset: String?,
    currentContractType: FuturesContractType?,
    favorites: FavoritesUi,
    groupTarget: GroupTargetUi,
    onQuoteChanged: (String) -> Unit,
    onApply: () -> Unit,
    onSelectPair: (CurrencyPairInfo) -> Unit,
    /** Rückfrage «Alle hinzufügen?» öffnen. */
    onAddAll: () -> Unit,
    /** Liste der Paare zeigen (bleibt, auch wenn die Karte kurz verschwindet). */
    showBulkList: Boolean,
    onToggleList: () -> Unit,
) {
    val showBulk = ExplorerSections.bulk
    val gap = 12.dp
    StepCard {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.small)
                .clickable { ExplorerSections.bulk = !showBulk }
                .padding(vertical = 4.dp)
        ) {
            Text(
                text = stringResource(R.string.explorer_bulk_title),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f)
            )
            Icon(
                painterResource(R.drawable.ic_chevron_right),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.graphicsLayer { rotationZ = if (showBulk) 90f else 0f }
            )
        }
        if (showBulk) {
            Spacer(modifier = Modifier.size(gap))
            Row(verticalAlignment = Alignment.CenterVertically) {
                ComboBox(
                    modifier = Modifier.weight(1f),
                    itemList = quotes,
                    selectedIndex = quotes.indexOf(quote),
                    label = stringResource(id = R.string.market_screen_quote),
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
                    TextButton(onClick = onToggleList) {
                        Text(
                            stringResource(
                                if (showBulkList) R.string.explorer_bulk_hide_list
                                else R.string.explorer_bulk_show_list
                            )
                        )
                    }
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
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    } else {
                        Text(pluralStringResource(R.plurals.explorer_add_all_pairs, pairs.size, pairs.size))
                    }
                }

                if (showBulkList) {
                    StepHint(stringResource(R.string.explorer_bulk_tap_hint))
                    // Antippbare Vorschau: wählt das Paar oben in Schritt 2.
                    // Alle Paare: eigene Liste, scrollt in sich (lazy, auch bei Hunderten flüssig)
                    LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                        items(pairs, key = { "${it.currencyBase}/${it.currencyCounter}/${it.contractType}" }) { pair ->
                            BulkPairRow(
                                pair = pair,
                                selected = pair.currencyBase == currentBaseAsset &&
                                    pair.currencyCounter == currentQuoteAsset &&
                                    pair.contractType == currentContractType,
                                onClick = { onSelectPair(pair) },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Ein Paar der Vorschau: «BTC/USDT», bei Futures die Laufzeit rechts; gewählt hinterlegt. */
@Composable
private fun BulkPairRow(pair: CurrencyPairInfo, selected: Boolean, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .background(
                if (selected) AppColors.accentTint(0.12f)
                else Color.Transparent
            )
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.sm, vertical = 8.dp)
    ) {
        Text(
            text = "${pair.currencyBase}/${pair.currencyCounter}",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )
        if (pair.contractType != FuturesContractType.NONE) {
            Text(
                text = pair.contractType.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** Rückfrage vor «Alle hinzufügen»: wie viele Paare, welche Gegenwährung, welche Börse. */
@Composable
internal fun BulkConfirmDialog(count: Int, quote: String, market: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(pluralStringResource(R.plurals.explorer_bulk_confirm_title, count, count)) },
        text = { Text(pluralStringResource(R.plurals.explorer_bulk_confirm_text, count, count, quote, market)) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.explorer_add_to_watchlist)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        }
    )
}
