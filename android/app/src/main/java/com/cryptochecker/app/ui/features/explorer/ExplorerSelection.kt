package com.cryptochecker.app.ui.features.explorer

import com.cryptochecker.app.domain.model.MarketInfo
import com.cryptochecker.app.domain.model.MarketPairsInfo
import com.cryptochecker.app.domain.model.MarketTickerResult
import com.cryptochecker.marketdata.model.MarketPair
import com.cryptochecker.marketdata.model.FuturesContractType
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Auswahl «Erweitert / nach Börse» nach dem Laden der Börsen: die feste Börsenliste und die laufend
 * wechselnden Teile als Flows (vom [ExplorerViewModel] gehalten). Wie die `@Published`-Felder von
 * `ExplorerViewModel.swift`.
 */
class ExplorerSelection(
    // Börse
    val exchanges: List<MarketInfo>,
    val exchange: StateFlow<MarketInfo?>,
    val canReloadPairs: StateFlow<Boolean>,

    // Paarliste der gewählten Börse und ihr Abgleich
    val pairList: StateFlow<MarketPairsInfo?>,
    val pairListSync: StateFlow<PairListSync>,

    // Gewähltes Paar
    val coin: StateFlow<String?>,
    val quote: StateFlow<String?>,
    val contract: StateFlow<FuturesContractType?>,

    // Auswahllisten dazu
    val coins: StateFlow<List<String>>,
    val quotes: StateFlow<List<String>>,
    val contracts: StateFlow<List<FuturesContractType>>,

    // Kurs und «Alle …-Paare»
    val price: StateFlow<MarketTickerResult?>,
    val bulkPairs: StateFlow<List<MarketPair>>,
    /** «Alle …-Paare» angewendet, aber zur Gegenwährung gibt es keine. */
    val bulkEmpty: StateFlow<Boolean>,

    // Rückmeldungen
    val addResult: StateFlow<AddResult?>,
    val bulkAddResult: StateFlow<BulkAddResult?>,

    /** Gegenwährungen für «Alle …-Paare». */
    val bulkQuotes: StateFlow<List<String>>,
    /** Gewählte Gegenwährung für «Alle …-Paare». */
    val bulkQuote: StateFlow<String?>,
    /** Kennzahlen der Liste «Alle …-Paare» für die Schnellfilter, Schlüssel = Paar-Kennung. */
    val bulkStats: StateFlow<Map<String, PairStats>>,
)

/** Abgleich der Paarliste der gewählten Börse: läuft gerade und/oder letzter Fehlertext. */
data class PairListSync(
    val inProgress: Boolean = false,
    val error: String? = null,
)

/** Ausgang von «Alle Paare übernehmen»; [running] solange es läuft, [refused] = wegen voller Merkliste nicht angelegt. */
data class BulkAddResult(
    val added: Int,
    val skipped: Int,
    val running: Boolean = false,
    val refused: Int = 0,
)

/** Ausgang des Versuchs, ein Paar in die Merkliste zu übernehmen. */
enum class AddResult {
    ADDED,
    ALREADY_IN_LIST,
    NO_PAIR_SELECTED,
    /** Merkliste hat schon die Höchstzahl Paare ([com.cryptochecker.app.domain.watch.WatchlistLimits.MAX_PAIRS]). */
    WATCHLIST_FULL,
}

/** Laden der Börsenliste: läuft, bereit (mit der Auswahl) oder gescheitert. */
sealed interface ExplorerLoad {
    data object Loading : ExplorerLoad

    data class Ready(val selection: ExplorerSelection) : ExplorerLoad

    data class Failed(val error: Throwable) : ExplorerLoad
}

/** Auswahl «Erweitert / nach Börse» zur geladenen Börsenliste ([ExplorerViewModel.pageState]). */
internal fun ExplorerViewModel.buildSelection(exchangeList: List<MarketInfo>) = ExplorerSelection(
    exchanges = exchangeList,
    exchange = exchange.asStateFlow(),
    canReloadPairs = canReloadPairs,
    pairList = pairList,
    pairListSync = pairListSync.asStateFlow(),
    coin = coin.asStateFlow(),
    quote = quote.asStateFlow(),
    contract = contract.asStateFlow(),
    coins = coins,
    quotes = quotes,
    contracts = contracts,
    price = price.asStateFlow(),
    bulkPairs = bulkPairs.asStateFlow(),
    bulkEmpty = bulkEmpty.asStateFlow(),
    addResult = addResult.asStateFlow(),
    bulkAddResult = bulkAdd.asStateFlow(),
    bulkQuotes = bulkQuotes,
    bulkQuote = bulkQuote.asStateFlow(),
    bulkStats = bulkStats.asStateFlow(),
)

/**
 * Zuletzt gewählte Ziel-Gruppe — absichtlich nur im Speicher: gilt für die laufende Sitzung,
 * nach dem nächsten Start wieder «Keine Gruppe».
 */
internal val sessionTargetGroup = MutableStateFlow<String?>(null)
