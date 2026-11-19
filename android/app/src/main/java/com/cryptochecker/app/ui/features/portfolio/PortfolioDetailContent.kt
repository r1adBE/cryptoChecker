@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)

package com.cryptochecker.app.ui.features.portfolio

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.cryptochecker.app.domain.portfolio.PortfolioTxEntity
import com.cryptochecker.app.ui.UiTestTags
import com.cryptochecker.app.ui.components.ReadableInset
import com.cryptochecker.app.ui.components.SectionTitle
import com.cryptochecker.app.ui.components.SkeletonList
import com.cryptochecker.app.ui.components.sectionTitleMarker
import com.cryptochecker.app.ui.features.watchlist.SwipeActionsRow
import com.cryptochecker.app.ui.features.watchlist.WatchlistBanner
import com.cryptochecker.app.ui.theme.SnackbarKind
import com.cryptochecker.app.ui.theme.Spacing
import com.cryptochecker.resources.R

// Inhalt der Coin-Seite im Portfolio ([PortfolioDetailScreen]). Eigene Datei, weil PortfolioDetailScreen.kt sonst zu
// lang würde.

/** Inhalt der Coin-Seite ([PortfolioDetailScreen]): Kennzahlen, Hinweise, offene Posten und die Transaktionen. */
@Composable
internal fun PortfolioDetailContent(
    padding: PaddingValues,
    detail: CoinDetailUi?,
    viewModel: PortfolioViewModel,
    symbol: String,
    refreshing: Boolean,
    hideAmounts: Boolean,
    listState: LazyListState,
    reduceMotion: Boolean,
    banner: WatchlistBanner,
    onSheet: (TxDraft) -> Unit,
    onConfirmDelete: (PortfolioTxEntity) -> Unit,
    onCostGaps: () -> Unit,
    onLotsFor: (Long) -> Unit,
) {
    // Nicht geschrieben: gesperrt bzw. Fehler nennen (wie iOS) statt «entfernt»
    val resources = LocalResources.current
    val d = detail
    if (d == null) {
        SkeletonList(modifier = Modifier.padding(padding))
        return
    }
    val ledger = d.ledger
    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = { viewModel.refresh(force = true) },
        modifier = Modifier.fillMaxSize().padding(padding)
    ) {
        // Tablet/Querformat: Inhalt höchstens 640 dp breit, Liste bleibt voll breit scrollbar
        ReadableInset { inset ->
            CompositionLocalProvider(LocalHidePortfolioAmounts provides hideAmounts) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = 16.dp + inset,
                        top = 4.dp,
                        end = 16.dp + inset,
                        bottom = Spacing.lg
                    ),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    item(key = "summary") {
                        PositionCard(
                            ledger.position,
                            d.addressAmount,
                            overlap = d.own.isNotEmpty(),
                            retarget = d.retarget,
                            onMissingCost = onCostGaps,
                            onAddressCost = d.lotAddress?.let { a -> { onLotsFor(a.id) } },
                            onRetarget = { r ->
                                viewModel.retargetTransfer(r.trade.id, r.to) { result ->
                                    if (result != PortfolioWriteResult.OK) {
                                        banner.show(resources.getString(result.notSavedText()), SnackbarKind.ERROR)
                                    }
                                }
                            },
                        )
                    }
                    // FIFO je Wallet: Abgang über den Bestand seiner Wallet — antippen öffnet die Transaktion
                    if (ledger.walletShortfalls.isNotEmpty()) {
                        item(key = "shortfalls") {
                            WalletShortfallBanner(ledger.walletShortfalls) { id ->
                                d.all.firstOrNull { it.id == id }?.let { onSheet(it.toDraft()) }
                            }
                        }
                    }
                    // Offene Kauf-Posten (zugeklappt)
                    if (ledger.openLots.isNotEmpty()) {
                        item(key = "open_lots") { OpenLotsSection(ledger.openLots, symbol) }
                    }
                    item(key = "tx_header") {
                        Text(
                            stringResource(R.string.portfolio_transactions),
                            style = SectionTitle.style,
                            color = SectionTitle.color,
                            modifier = Modifier.padding(start = 4.dp, top = 12.dp, bottom = 4.dp).sectionTitleMarker()
                        )
                    }
                    items(d.own, key = { it.id }) { tx ->
                        SwipeActionsRow(
                            enabled = true,
                            favorite = false,
                            onDelete = { onConfirmDelete(tx) },
                            onToggleFavorite = null,
                            reduceMotion = reduceMotion,
                            modifier = (if (reduceMotion) Modifier else Modifier.animateItem()).testTag(UiTestTags.PORTFOLIO_TX_ROW),
                            // Rückfrage statt sofort: die Zeile gleitet zurück, gelöscht wird erst nach «Löschen»
                            confirmsDelete = true,
                        ) {
                            TxRow(
                                tx = tx,
                                coin = symbol,
                                onClick = { onSheet(tx.toDraft()) },
                                onDelete = { onConfirmDelete(tx) }
                            )
                        }
                    }
                    item(key = "disclaimer") { PortfolioDisclaimer() }
                }
            }
        }
    }
}
