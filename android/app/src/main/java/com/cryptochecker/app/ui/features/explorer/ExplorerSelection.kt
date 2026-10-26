package com.cryptochecker.app.ui.features.explorer

import com.cryptochecker.app.domain.market.ExchangeOrder
import com.cryptochecker.app.domain.model.MarketInfo
import com.cryptochecker.app.domain.model.MarketPairsInfo
import com.cryptochecker.app.domain.model.MarketTickerResult
import com.cryptochecker.marketdata.config.MarketsConfig
import com.cryptochecker.marketdata.model.MarketPair
import com.cryptochecker.marketdata.model.FuturesContractType
import com.cryptochecker.marketdata.model.MarketCategory
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
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
    /** Abschnitt je Eintrag von [exchanges] (gleiche Länge): Spot · Futures · DEX-Pools · Forex (Build 133). */
    val exchangeCategories: List<MarketCategory>,
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
    /** Davon noch nicht in der Merkliste ([bulkNewCount]); 0 = Knopf aus. */
    val bulkNew: StateFlow<Int>,
)

/** Abgleich der Paarliste der gewählten Börse: läuft gerade und/oder letzter Fehlertext. */
data class PairListSync(
    val inProgress: Boolean = false,
    val error: String? = null,
)

/**
 * Ausgang von «Alle Paare übernehmen»; [running] solange es läuft, [refused] = wegen voller Merkliste nicht angelegt,
 * [failed] = mit Fehler abgebrochen.
 */
data class BulkAddResult(
    val added: Int,
    val skipped: Int,
    val running: Boolean = false,
    val refused: Int = 0,
    /** Während der Aufnahme: verarbeitete und alle Paare (Fortschrittsbalken). */
    val done: Int = 0,
    val total: Int = 0,
    /** Neu angelegte Paare (für «Rückgängig»). */
    val addedIds: List<Long> = emptyList(),
    /** Ende der Aufnahme (ms) für das Fenster von «Rückgängig» (`BulkAddRunner.undoOffered`). */
    val finishedAt: Long = 0,
    val failed: Boolean = false,
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

/**
 * Börsenauswahl in Abschnitten Spot · Futures · DEX-Pools · Forex ([ExchangeOrder]), darin nach dem Volumen am Tag der
 * Installation; ohne Ranking bleibt die Reihenfolge von [alphabetical] (wie vor Build 133). Liefert die Börsen und je
 * Eintrag den Abschnitt. Wie iOS `ExplorerViewModel.exchangeSections`.
 */
internal fun exchangeSections(
    alphabetical: List<MarketInfo>,
    volumes: Map<String, Double>?,
): Pair<List<MarketInfo>, List<MarketCategory>> {
    val byKey = alphabetical.associateBy { it.key }
    val entries = alphabetical.map { ExchangeOrder.Entry(it.key, MarketsConfig.MARKETS[it.key]?.category ?: MarketCategory.SPOT) }
    val sections = ExchangeOrder.sections(entries, volumes)
    return sections.flatMap { (_, keys) -> keys.mapNotNull(byKey::get) } to
        sections.flatMap { (category, keys) -> keys.map { category } }
}

/** Auswahl «Erweitert / nach Börse» zur geladenen Börsenliste ([ExplorerViewModel.pageState]). */
internal fun ExplorerViewModel.buildSelection(
    exchangeList: List<MarketInfo>,
    categories: List<MarketCategory>,
) = ExplorerSelection(
    exchanges = exchangeList,
    exchangeCategories = categories,
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
    // Startwert gleich aus den aktuellen Werten: sonst stünde im ersten Bild «Alle schon in der Merkliste»
    bulkNew = combine(bulkPairs, watchedKeys, exchange) { pairs, keys, market -> bulkNewOf(pairs, keys, market) }
        .stateIn(
            viewModelScope, SharingStarted.WhileSubscribed(5_000),
            bulkNewOf(bulkPairs.value, watchedKeys.value, exchange.value),
        ),
)

private fun bulkNewOf(pairs: List<MarketPair>, keys: Set<String>, market: MarketInfo?): Int =
    market?.let { bulkNewCount(it.key, pairs, keys) } ?: 0

/**
 * Wie viele von [pairs] sind noch nicht in der Merkliste ([watched], Schlüssel [watchKey] mit demselben Kontrakttyp wie
 * die Dublettenprüfung beim Anlegen)? Gleiche Regel wie iOS `ExplorerBulk.newCount`.
 */
internal fun bulkNewCount(marketKey: String, pairs: List<MarketPair>, watched: Set<String>): Int = pairs.count { pair ->
    val contract = FuturesContractType.normalized(marketKey, pair.pairId, pair.contractType)
    watchKey(marketKey, pair.base, pair.quote, contract.name) !in watched
}

/**
 * Zuletzt gewählte Ziel-Gruppe — absichtlich nur im Speicher: gilt für die laufende Sitzung,
 * nach dem nächsten Start wieder «Keine Gruppe».
 */
internal val sessionTargetGroup = MutableStateFlow<String?>(null)
