@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.cryptochecker.app.ui.features.watchlist

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cryptochecker.app.R
import com.cryptochecker.app.data.FavoriteKind
import com.cryptochecker.app.data.local.model.WatchEntity
import com.cryptochecker.app.domain.watch.WatchEdit
import com.cryptochecker.app.ui.components.ComboBox
import com.cryptochecker.app.ui.theme.Spacing
import com.cryptochecker.app.ui.theme.headline
import com.cryptochecker.app.util.BidiText
import com.cryptochecker.marketdata.model.FuturesContractType

/**
 * «Paar bearbeiten» (Stift neben dem Paar im Aktionsblatt), volle Höhe: Börse, Coin,
 * Gegenwert und Kontrakt wie «Genau auswählen» im Explorer, vorbelegt mit dem Eintrag.
 * «Speichern» ändert denselben Eintrag (siehe [WatchEditViewModel.save]); steht das Paar
 * schon in der Merkliste, bleibt das Blatt offen und sagt es. Hat das Paar Alarme und ist
 * etwas anderes gewählt: «Alarme bleiben bestehen – Schwellen prüfen».
 */
@Composable
internal fun WatchEditSheet(
    watch: WatchEntity,
    alarmCount: Int,
    onDismiss: () -> Unit,
    viewModel: WatchEditViewModel = hiltViewModel(key = "watch_edit"),
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val state by viewModel.state.collectAsStateWithLifecycle()
    val favMarkets by viewModel.favoriteMarkets.collectAsStateWithLifecycle()
    val favCoins by viewModel.favoriteCoins.collectAsStateWithLifecycle()
    val favQuotes by viewModel.favoriteQuotes.collectAsStateWithLifecycle()

    // Bei jedem Öffnen mit dem Eintrag vorbelegen (nicht nach dem Drehen: Auswahl bleibt)
    var started by rememberSaveable(watch.id) { mutableStateOf(false) }
    LaunchedEffect(watch.id) {
        if (!started) {
            started = true
            viewModel.start(watch)
        }
    }
    // Gespeichert (Zähler höher als beim Öffnen): schliessen
    val savedAtOpen = rememberSaveable(watch.id) { viewModel.state.value.savedCount }
    LaunchedEffect(state.savedCount) { if (state.savedCount != savedAtOpen) onDismiss() }

    val current = WatchEdit.Key(watch.marketKey, watch.baseAsset, watch.quoteAsset, watch.contractType.name)
    val target = state.key
    val changed = target != null && target != current
    val gap = 12.dp

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(start = 24.dp, end = 24.dp, bottom = 16.dp)
        ) {
            Text(
                text = stringResource(R.string.watch_edit_title),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.semantics { heading() }
            )
            Text(
                text = "${BidiText.isolate(watch.displayName)} · ${BidiText.isolate(watch.marketName)}",
                style = MaterialTheme.typography.headline,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            Card(
                shape = MaterialTheme.shapes.large,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    val markets = state.markets
                    if (markets == null) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(50)))
                    } else {
                        ComboBox(
                            modifier = Modifier.fillMaxWidth(),
                            itemList = markets.map { it.name },
                            selectedIndex = markets.indexOf(state.market),
                            label = stringResource(R.string.market_screen_market),
                            searchable = true,
                            emptyText = stringResource(R.string.explorer_select_placeholder),
                            favorites = favMarkets,
                            onToggleFavorite = { viewModel.toggleFavorite(FavoriteKind.MARKET, it) },
                            onValueChange = { viewModel.setMarket(markets[it]) }
                        )
                    }

                    val bases = state.bases
                    when {
                        state.market == null && markets != null -> Hint(stringResource(R.string.explorer_select_market_hint))
                        state.market == null -> Unit
                        state.syncing || state.pairs == null -> Column(modifier = Modifier.padding(top = gap)) {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(50)))
                            Hint(stringResource(R.string.explorer_loading_pairs))
                        }
                        bases.isEmpty() -> Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(top = 8.dp)
                        ) {
                            Text(
                                text = state.syncError?.let { stringResource(R.string.check_error_generic_prefix, friendlyError(it)) }
                                    ?: stringResource(R.string.explorer_no_pairs_yet),
                                style = MaterialTheme.typography.bodySmall,
                                color = if (state.syncError != null) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(onClick = viewModel::sync) { Text(stringResource(R.string.action_retry)) }
                        }
                        else -> {
                            ComboBox(
                                modifier = Modifier.fillMaxWidth().padding(top = gap),
                                itemList = bases,
                                selectedIndex = bases.indexOf(state.base),
                                label = stringResource(R.string.market_screen_base),
                                searchable = true,
                                favorites = favCoins,
                                onToggleFavorite = { viewModel.toggleFavorite(FavoriteKind.COIN, it) },
                                onValueChange = { viewModel.setBase(bases[it]) }
                            )
                            val quotes = state.quotes
                            ComboBox(
                                modifier = Modifier.fillMaxWidth().padding(top = gap),
                                itemList = quotes,
                                selectedIndex = quotes.indexOf(state.quote),
                                label = stringResource(R.string.market_screen_quote),
                                searchable = true,
                                favorites = favQuotes,
                                onToggleFavorite = { viewModel.toggleFavorite(FavoriteKind.QUOTE, it) },
                                onValueChange = { viewModel.setQuote(quotes[it]) }
                            )
                            // Kontrakt nur bei Futures; auswählbar nur bei mehreren Laufzeiten
                            val contracts = state.contracts
                            if (contracts.isNotEmpty() && !(contracts.size == 1 && contracts[0] == FuturesContractType.NONE)) {
                                ComboBox(
                                    modifier = Modifier.fillMaxWidth().padding(top = gap),
                                    itemList = contracts.map { contractName(it) },
                                    selectedIndex = contracts.indexOf(state.contract),
                                    label = stringResource(R.string.market_screen_contract_type),
                                    enabled = contracts.size > 1,
                                    onValueChange = { viewModel.setContract(contracts[it]) }
                                )
                            }
                        }
                    }
                }
            }

            // Alarme hängen am Eintrag und bleiben; ihre Schwellen galten dem alten Kurs
            if (WatchEdit.warnAlarms(current, target, alarmCount)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = gap)
                ) {
                    Icon(
                        painterResource(R.drawable.ic_notifications),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.padding(end = 8.dp).size(18.dp)
                    )
                    Text(
                        text = stringResource(R.string.watch_edit_alarms_warning),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
            if (state.duplicate) {
                Text(
                    text = stringResource(R.string.explorer_already_in_watchlist),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier
                        .padding(top = gap)
                        .semantics { liveRegion = LiveRegionMode.Polite }
                )
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(top = Spacing.lg)
            ) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
                Button(
                    onClick = { viewModel.save(watch.id) },
                    enabled = changed && !state.saving && !state.syncing && !state.duplicate
                ) {
                    if (state.saving) {
                        CircularProgressIndicator(
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.padding(end = 8.dp).size(16.dp)
                        )
                    }
                    Text(stringResource(R.string.action_save))
                }
            }
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp)
    )
}

@Composable
private fun contractName(contractType: FuturesContractType): String =
    if (contractType == FuturesContractType.NONE) stringResource(R.string.market_screen_spot)
    else contractType.toString()
