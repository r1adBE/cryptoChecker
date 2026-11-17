// Teile des Adressen-Blatts ([PortfolioAddressSheet]): Rückfragen und Inhalt. Eigene Datei, weil
// PortfolioAddressSheet.kt sonst zu lang würde.
@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.cryptochecker.app.ui.features.portfolio

import android.content.res.Resources
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cryptochecker.app.domain.portfolio.AddressChain
import com.cryptochecker.app.domain.portfolio.AddressValidation
import com.cryptochecker.app.domain.portfolio.AddressWithdrawalMatch
import com.cryptochecker.app.domain.portfolio.PortfolioTrade
import com.cryptochecker.app.domain.portfolio.WatchedAddress
import com.cryptochecker.app.domain.portfolio.WatchedAddressRules
import com.cryptochecker.app.ui.components.AppSnackbarHost
import com.cryptochecker.app.ui.components.DialogText
import com.cryptochecker.app.ui.features.watchlist.SwipeActionsRow
import com.cryptochecker.app.ui.features.watchlist.WatchlistBanner
import com.cryptochecker.app.ui.theme.SnackbarKind
import com.cryptochecker.app.ui.theme.Spacing
import com.cryptochecker.resources.R

/** Rückfragen des Adressen-Blatts ([PortfolioAddressSheet]): Überschneidung, Verwerfen und Datenschutz-Hinweis. */
@Composable
internal fun AddressSheetDialogs(
    askOverlap: AddressChain?,
    onAskOverlapChange: (AddressChain?) -> Unit,
    askDiscard: Boolean,
    onAskDiscardChange: (Boolean) -> Unit,
    askPrivacy: Boolean,
    onAskPrivacyChange: (Boolean) -> Unit,
    check: AddressValidation.Check,
    btcServer: String?,
    onAdd: (countInPortfolio: Boolean, overlapChecked: Boolean) -> Unit,
    onAddChecked: () -> Unit,
    onDismiss: () -> Unit,
) {
    askOverlap?.let { chain ->
        AlertDialog(
            onDismissRequest = { onAskOverlapChange(null) },
            title = { Text(stringResource(R.string.portfolio_address_overlap_title)) },
            text = { DialogText { Text(stringResource(R.string.portfolio_address_overlap_ask, chain.coin)) } },
            // «Nur anzeigen» ist die sichere Wahl (kein Doppelzählen) und steht vorn;
            // «Zählen» nur, wenn die Adresse sicher nicht dieselben Coins hält
            confirmButton = {
                TextButton(onClick = {
                    onAskOverlapChange(null)
                    onAdd(false, true)
                }) {
                    Text(stringResource(R.string.portfolio_address_show_only_short), fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    onAskOverlapChange(null)
                    onAdd(true, true)
                }) {
                    Text(stringResource(R.string.portfolio_address_count))
                }
            }
        )
    }

    if (askDiscard) {
        DiscardChangesDialog(onDiscard = {
            onAskDiscardChange(false)
            onDismiss()
        }, onKeep = { onAskDiscardChange(false) })
    }
    if (askPrivacy) {
        AlertDialog(
            onDismissRequest = { onAskPrivacyChange(false) },
            title = { Text(stringResource(R.string.portfolio_addresses_title)) },
            // Langer Hinweis (alle Hosts) bei grosser Schrift: scrollbar statt abgeschnitten
            text = { Column(Modifier.verticalScroll(rememberScrollState())) { Text(privacyText(check, btcServer)) } },
            confirmButton = {
                TextButton(onClick = {
                    onAskPrivacyChange(false)
                    onAddChecked()
                }) { Text(stringResource(R.string.portfolio_address_add)) }
            },
            dismissButton = {
                TextButton(onClick = { onAskPrivacyChange(false) }) { Text(stringResource(R.string.action_cancel)) }
            }
        )
    }
}

/** Inhalt des Adressen-Blatts ([PortfolioAddressSheet]): Titel, Adressen mit Wischen, Hinzufügen und eigenes Banner. */
@Composable
internal fun AddressSheetContent(
    viewModel: PortfolioViewModel,
    addresses: List<WatchedAddress>,
    now: Long,
    hideAmounts: Boolean,
    ownCoins: Set<String>,
    trades: List<PortfolioTrade>,
    reduceMotion: Boolean,
    banner: WatchlistBanner,
    resources: Resources,
    dirty: Boolean,
    onAskDiscard: () -> Unit,
    onAskRemove: (WatchedAddress) -> Unit,
    onCostLots: (Long) -> Unit,
    input: String,
    onInput: (String) -> Unit,
    label: String,
    onLabel: (String) -> Unit,
    error: Int?,
    check: AddressValidation.Check,
    btcServer: String?,
    canAdd: Boolean,
    onAdd: () -> Unit,
) {
    BackHandler(enabled = dirty) { onAskDiscard() }
    Box {
        LazyColumn(
            modifier = Modifier.fillMaxWidth().navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            item(key = "title") {
                Text(
                    stringResource(R.string.portfolio_addresses_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 24.dp)
                )
            }
            items(addresses, key = { "a:${it.id}" }) { a ->
                // Wischen zum Entfernen wie iOS: Rückfrage, die Zeile federt zurück (auch über das Menü der Zeile)
                SwipeActionsRow(
                    enabled = true,
                    favorite = false,
                    onDelete = { onAskRemove(a) },
                    onToggleFavorite = null,
                    reduceMotion = reduceMotion,
                    confirmsDelete = true,
                ) {
                    AddressRow(
                        a, now, hideAmounts,
                        overlap = WatchedAddressRules.overlap(a, ownCoins),
                        retarget = remember(a, trades) { AddressWithdrawalMatch.retarget(a, trades) },
                        onRemove = { onAskRemove(a) },
                        onCountTokens = { on -> viewModel.setWatchedAddressCountTokens(a.id, on) },
                        onNetworks = { list -> viewModel.setWatchedAddressNetworks(a.id, list) },
                        onShowOnly = { showOnly -> viewModel.setWatchedAddressCountInPortfolio(a.id, !showOnly) },
                        onCostLots = { onCostLots(a.id) },
                        onRetarget = { r ->
                            viewModel.retargetTransfer(r.trade.id, r.to) { result ->
                                if (result !=
                                    PortfolioWriteResult.OK
                                ) {
                                    banner.show(resources.getString(result.notSavedText()), SnackbarKind.ERROR)
                                }
                            }
                        },
                        trades = trades,
                        allAddresses = addresses,
                    )
                }
            }
            item(key = "form") {
                AddressAddForm(
                    addresses = addresses,
                    input = input,
                    onInput = onInput,
                    label = label,
                    onLabel = onLabel,
                    error = error,
                    check = check,
                    btcServer = btcServer,
                    canAdd = canAdd,
                    onAdd = onAdd,
                )
            }
        }
        AppSnackbarHost(banner.host, Modifier.align(Alignment.BottomCenter).navigationBarsPadding())
    }
}
