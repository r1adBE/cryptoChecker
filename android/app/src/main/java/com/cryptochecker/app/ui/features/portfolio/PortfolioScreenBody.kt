// Inhalt des Portfolio-Tabs mit Daten: Status, Gesamtkarte, Wertverlauf, Aufteilung, Positionen, geschlossene Coins.
// Eigene Datei, weil `PortfolioScreen` sonst zu lang würde; Zustand und Ableitungen bleiben dort, hier wird nur gezeichnet.
@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.cryptochecker.app.ui.features.portfolio

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cryptochecker.app.domain.alarm.DepegAlarm
import com.cryptochecker.app.domain.portfolio.PortfolioChange
import com.cryptochecker.app.domain.portfolio.PortfolioCostFxFigures
import com.cryptochecker.app.domain.portfolio.PortfolioHistoryRange
import com.cryptochecker.app.domain.portfolio.PortfolioHistoryView
import com.cryptochecker.app.domain.portfolio.PortfolioPriceFallback
import com.cryptochecker.app.domain.portfolio.PortfolioPriceState
import com.cryptochecker.app.domain.portfolio.PortfolioPrices
import com.cryptochecker.app.domain.portfolio.PortfolioSummary
import com.cryptochecker.app.domain.portfolio.PortfolioWalletShortfall
import com.cryptochecker.app.domain.portfolio.TodayChange
import com.cryptochecker.app.domain.portfolio.WatchedAddress
import com.cryptochecker.app.domain.watch.ChangeBasis
import com.cryptochecker.app.ui.components.ListSegment
import com.cryptochecker.app.ui.components.ReadableInset
import com.cryptochecker.app.ui.theme.Spacing

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
                        (
                            priceState == PortfolioPriceState.OFFLINE &&
                                PortfolioPriceFallback.isStale(prices.updatedAt, now, staleAfter)
                            )
                    portfolioCardItems(
                        priceState = priceState,
                        stale = stale,
                        prices = prices,
                        now = now,
                        reduceMotion = reduceMotion,
                        current = current,
                        currency = currency,
                        fxRate = fxRate,
                        costFx = costFx,
                        todayPercent = todayPercent,
                        todayAmount = todayAmount,
                        todaySkipped = todaySkipped,
                        weekChange = weekChange,
                        yearChange = yearChange,
                        periodScaled = periodScaled,
                        walletShortfalls = walletShortfalls,
                        onShortfall = onShortfall,
                        depegReading = depegReading,
                        depegThreshold = depegThreshold,
                        costGapsPending = costGapsPending,
                        onOpenCostGaps = onOpenCostGaps,
                        viewModel = viewModel,
                        history = history,
                        historyRange = historyRange,
                        historyExpanded = historyExpanded,
                        historyView = historyView,
                        historyCandles = historyCandles,
                        historyPnl = historyPnl,
                        watchedAddresses = watchedAddresses,
                    )
                    portfolioPositionItems(
                        current = current,
                        txCounts = txCounts,
                        reduceMotion = reduceMotion,
                        coinPnl = coinPnl,
                        changeBasis = changeBasis,
                        stale = stale,
                        prices = prices,
                        currency = currency,
                        fxRate = fxRate,
                        bridgedStables = bridgedStables,
                        showClosed = showClosed,
                        onShowClosedChange = onShowClosedChange,
                        onDeleteCoin = onDeleteCoin,
                        onOpenCoin = onOpenCoin,
                    )
                    item(key = "disclaimer") { PortfolioDisclaimer() }
                }
            }
        }
    }
}
