// Inhalt des Portfolio-Tabs mit Daten: Status, Gesamtkarte, Wertverlauf, Aufteilung, Positionen, geschlossene Coins.
// Eigene Datei, weil `PortfolioScreen` sonst zu lang würde; Zustand und Ableitungen bleiben dort, hier wird nur gezeichnet.
@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.cryptochecker.app.ui.features.portfolio

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.cryptochecker.app.R
import com.cryptochecker.app.data.portfolio.PortfolioPrices
import com.cryptochecker.app.domain.alarm.DepegAlarm
import com.cryptochecker.app.domain.portfolio.PortfolioChange
import com.cryptochecker.app.domain.portfolio.PortfolioCostFxFigures
import com.cryptochecker.app.domain.portfolio.PortfolioHistoryRange
import com.cryptochecker.app.domain.portfolio.PortfolioHistoryView
import com.cryptochecker.app.domain.portfolio.PortfolioInsights
import com.cryptochecker.app.domain.portfolio.PortfolioPriceFallback
import com.cryptochecker.app.domain.portfolio.PortfolioPriceState
import com.cryptochecker.app.domain.portfolio.PortfolioSummary
import com.cryptochecker.app.domain.portfolio.PortfolioWalletShortfall
import com.cryptochecker.app.domain.portfolio.PortfolioWidgetMath
import com.cryptochecker.app.domain.portfolio.TodayChange
import com.cryptochecker.app.domain.portfolio.WatchedAddress
import com.cryptochecker.app.domain.watch.ChangeBasis
import com.cryptochecker.app.ui.components.ListSegment
import com.cryptochecker.app.ui.components.ReadableInset
import com.cryptochecker.app.ui.features.watchlist.SwipeActionsRow
import com.cryptochecker.app.ui.theme.Spacing
import com.cryptochecker.app.ui.theme.label

/**
 * Liste des Portfolio-Tabs mit Daten: Status der Kurse, Gesamtkarte, Wertverlauf, Aufteilung, Positionen (nach links
 * wischen = Coin löschen) und geschlossene Positionen; nach unten ziehen lädt die Kurse neu.
 */
@Composable
internal fun PortfolioList(
    modifier: Modifier,
    viewModel: PortfolioViewModel,
    listState: LazyListState,
    current: PortfolioSummary,
    watchedAddresses: List<WatchedAddress>,
    prices: PortfolioPrices,
    refreshing: Boolean,
    hideAmounts: Boolean,
    now: Long,
    staleAfter: Long,
    online: Boolean,
    reduceMotion: Boolean,
    currency: String,
    fxRate: Double?,
    costFx: PortfolioCostFxFigures?,
    todayPercent: Double?,
    todayAmount: Double?,
    todaySkipped: List<String>,
    weekChange: TodayChange?,
    yearChange: TodayChange?,
    periodScaled: List<String>,
    walletShortfalls: List<PortfolioWalletShortfall>,
    onShortfall: (Long) -> Unit,
    depegReading: DepegAlarm.Reading?,
    depegThreshold: Int,
    history: PortfolioHistoryUi?,
    historyRange: PortfolioHistoryRange,
    historyExpanded: Boolean,
    historyView: PortfolioHistoryView,
    historyCandles: Boolean,
    historyPnl: Boolean,
    coinPnl: Map<String, PortfolioChange>,
    changeBasis: ChangeBasis,
    txCounts: Map<String, Int>,
    bridgedStables: Map<String, List<String>>,
    showClosed: Boolean,
    onShowClosedChange: (Boolean) -> Unit,
    onDeleteCoin: (String) -> Unit,
    onOpenCoin: (String) -> Unit,
    /** Offene Einträge ohne Einstand (Karte nur, wenn > 0). */
    costGapsPending: Int = 0,
    onOpenCostGaps: () -> Unit = {},
) {
    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = { viewModel.refresh(force = true) },
        modifier = modifier
    ) {
        // Tablet/Querformat: Inhalt höchstens 640 dp breit, Liste bleibt voll breit scrollbar
        ReadableInset { inset ->
            CompositionLocalProvider(LocalHidePortfolioAmounts provides hideAmounts) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    // Unten Platz für den «+»-Knopf
                    contentPadding = PaddingValues(start = 16.dp + inset, top = 4.dp, end = 16.dp + inset, bottom = Spacing.fabClearance),
                    // Positionen ohne Fuge als eine Karte ([ListSegment]); die Karten darüber
                    // bringen den Abstand selbst mit
                    verticalArrangement = Arrangement.spacedBy(ListSegment.Gap)
                ) {
                    // Zustand der Kurse wie in der Merkliste: aktuell / veraltet / offline mit «Stand …»
                    val priceState = PortfolioPriceFallback.state(
                        updatedAt = prices.updatedAt,
                        now = now,
                        staleAfterMillis = staleAfter,
                        online = online,
                        hasOpenCoins = current.open.isNotEmpty(),
                    )
                    val stale = priceState == PortfolioPriceState.STALE ||
                        (priceState == PortfolioPriceState.OFFLINE &&
                            PortfolioPriceFallback.isStale(prices.updatedAt, now, staleAfter))
                    if (priceState != PortfolioPriceState.NONE) {
                        item(key = "status") {
                            PortfolioStatusPill(
                                state = priceState,
                                updatedAt = prices.updatedAt,
                                now = now,
                                reduceMotion = reduceMotion,
                                modifier = Modifier.padding(bottom = Spacing.sm)
                            )
                        }
                    }
                    item(key = "total") {
                        Box(Modifier.padding(bottom = ListSegment.Spacing)) { TotalCard(
                            summary = current,
                            updatedAt = prices.updatedAt,
                            currency = currency,
                            fxRate = fxRate,
                            costFx = costFx,
                            todayPercent = todayPercent,
                            todayAmount = todayAmount,
                            todaySkipped = todaySkipped,
                            weekChange = weekChange,
                            yearChange = yearChange,
                            stale = stale,
                            priceSources = prices.sources,
                            exchangeFailed = prices.exchangeFailed,
                            mixedExchange = prices.mixedExchange,
                            periodScaled = periodScaled,
                            walletShortfalls = walletShortfalls,
                            onShortfall = onShortfall,
                            // «USDT = 1 angenommen»: Messung 6 Std. gültig, Uhr alle 30 s ([DepegAlarm.cardNote] in der Karte)
                            depegReading = depegReading,
                            depegThresholdTenths = depegThreshold,
                            now = now,
                        ) }
                    }
                    // Einträge ohne Einstand: sanfte Karte zum Ergänzen
                    if (costGapsPending > 0) {
                        item(key = "cost_gaps") {
                            Box(Modifier.padding(bottom = ListSegment.Spacing)) { CostGapsCard(costGapsPending, onOpenCostGaps) }
                        }
                    }
                    // Wertverlauf über den Positionen
                    item(key = "history") {
                        Box(Modifier.padding(bottom = ListSegment.Spacing)) {
                            PortfolioHistoryCard(
                                history = history,
                                range = historyRange,
                                onRange = viewModel::setHistoryRange,
                                expanded = historyExpanded,
                                onExpandedChange = viewModel::setHistoryExpanded,
                                view = historyView,
                                onView = viewModel::setHistoryView,
                                candles = historyCandles,
                                onCandlesChange = viewModel::setHistoryCandles,
                                pnl = historyPnl,
                                onPnlChange = viewModel::setHistoryPnl
                            )
                        }
                    }
                    // Regel der Adress-Guthaben für Heute/7 T/1 J und Verlauf offen nennen (ab der ersten Beobachtung)
                    if (watchedAddresses.any { !it.lastBalance.isNullOrEmpty() }) {
                        item(key = "address_hint") {
                            Box(Modifier.padding(start = 4.dp, end = 4.dp, bottom = ListSegment.Spacing)) {
                                Text(
                                    stringResource(R.string.portfolio_address_period_observed),
                                    style = MaterialTheme.typography.label,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                    // Aufteilung (vier grösste Coins + «Andere»), erst ab zwei Teilen
                    val slices = PortfolioInsights.allocation(current.open)
                    if (slices.size >= 2) {
                        item(key = "allocation") { Box(Modifier.padding(bottom = ListSegment.Spacing)) { AllocationCard(slices) } }
                    }
                    // Nach links wischen = Coin löschen nach Rückfrage (Rückgängig im Banner); kein Favorit
                    itemsIndexed(current.open, key = { _, it -> "open:${it.coin}" }) { index, position ->
                        val shape = ListSegment.shape(index, current.open.size)
                        // Coin nur aus einer Adresse: nichts zu löschen (Adresse über ⋯ › «Adressen» entfernen)
                        val ownTx = (txCounts[position.coin] ?: 0) > 0
                        SwipeActionsRow(
                            enabled = ownTx,
                            favorite = false,
                            onDelete = { onDeleteCoin(position.coin) },
                            onToggleFavorite = null,
                            reduceMotion = reduceMotion,
                            shape = shape,
                            confirmsDelete = true,
                            modifier = Modifier.animateItem()
                        ) {
                            CoinRow(
                                shape = shape,
                                position = position,
                                onClick = { onOpenCoin(position.coin) },
                                pnl = coinPnl[position.coin] ?: coinPnl[position.coin.uppercase()],
                                basis = changeBasis,
                                sharePercent = position.value?.let { PortfolioWidgetMath.sharePercent(it, current.totalValue) },
                                onDelete = { onDeleteCoin(position.coin) },
                                txCount = txCounts[position.coin] ?: 0,
                                stale = stale,
                                // Ohne Kurs ungeprüft oder Binance-Kurs mit unbestätigtem Token: «Kurs ungeprüft»
                                unchecked = position.coin in prices.unchecked || position.coin in prices.unverified,
                                unavailable = position.coin in prices.unavailable,
                                // Wert und 24h wie im Widget in der Anzeigewährung (Detail bleibt USDT)
                                unit = cardUnit(currency, fxRate),
                                // Börse des Kurses klein in der Zeile (nicht nur unter «Hinweise»)
                                exchange = prices.sources[position.coin],
                                viaBase = prices.viaBase[position.coin],
                                bridgedStable = bridgedStables[position.coin]?.joinToString(", "),
                            )
                        }
                    }
                    if (current.closed.isNotEmpty()) {
                        item(key = "closed_header") {
                            // Echtes Chevron statt «▸»/«▾»; Zustand für den Screenreader (wie iOS)
                            val stateText = stringResource(if (showClosed) R.string.a11y_expanded else R.string.a11y_collapsed)
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(role = Role.Button) { onShowClosedChange(!showClosed) }
                                    .semantics(mergeDescendants = true) { stateDescription = stateText }
                                    .padding(horizontal = 4.dp, vertical = Spacing.md)
                            ) {
                                Icon(
                                    painterResource(R.drawable.ic_chevron_right),
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier
                                        .size(18.dp)
                                        .rotate(if (showClosed) 90f else 0f)
                                )
                                Text(
                                    text = pluralStringResource(R.plurals.portfolio_closed, current.closed.size, current.closed.size),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(start = 4.dp)
                                )
                            }
                        }
                        if (showClosed) {
                            items(current.closed, key = { "closed:${it.coin}" }) { position ->
                                ClosedRow(position = position, onClick = { onOpenCoin(position.coin) })
                            }
                        }
                    }
                    item(key = "disclaimer") { PortfolioDisclaimer() }
                }
            }
        }
    }
}
