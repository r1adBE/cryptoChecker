package com.cryptochecker.app.ui.features.explorer

import com.cryptochecker.marketdata.model.CurrencyPairInfo
import com.cryptochecker.marketdata.model.FuturesContractType
import com.cryptochecker.app.domain.model.MarketTickerResult
import com.cryptochecker.app.domain.model.MarketInfo
import com.cryptochecker.app.domain.model.MarketPairsInfo
import com.cryptochecker.app.ui.features.explorer.dto.MarketPairsUpdateState
import kotlinx.coroutines.flow.StateFlow

class ExplorerViewState(
    val markets: List<MarketInfo>,
    val currentMarket: StateFlow<MarketInfo?>,
    val canUpdatePairs: StateFlow<Boolean>,

    val currentMarketPairsInfo: StateFlow<MarketPairsInfo?>,
    val marketPairsUpdateState: StateFlow<MarketPairsUpdateState>,

    val currentBaseAsset: StateFlow<String?>,
    val currentQuoteAsset: StateFlow<String?>,
    val currentContractType: StateFlow<FuturesContractType?>,

    val baseAssets: StateFlow<List<String>>,
    val quoteAssets: StateFlow<List<String>>,
    val contractTypes: StateFlow<List<FuturesContractType>>,

    val marketTicker: StateFlow<MarketTickerResult?>,
    val bulkPairs: StateFlow<List<CurrencyPairInfo>>,
    val bulkPairsMessage: StateFlow<String?>,

    val addToWatchlistState: StateFlow<AddToWatchlistState?>,
    val bulkAddState: StateFlow<BulkAddState?>,

    /** Gegenwährungen für «Alle …-Paare» und die gewählte. */
    val bulkQuotes: StateFlow<List<String>> = kotlinx.coroutines.flow.MutableStateFlow(emptyList()),
    val bulkQuote: StateFlow<String?> = kotlinx.coroutines.flow.MutableStateFlow(null),
)

/** Ergebnis von „Alle USDT-PERPS übernehmen“. */
data class BulkAddState(
    val added: Int,
    val skipped: Int,
    val running: Boolean = false,
)

/** Ergebnis des Versuchs, das gewählte Paar in die Watchlist zu übernehmen. */
enum class AddToWatchlistState {
    ADDED,
    ALREADY_IN_LIST,
    NO_PAIR_SELECTED,
}