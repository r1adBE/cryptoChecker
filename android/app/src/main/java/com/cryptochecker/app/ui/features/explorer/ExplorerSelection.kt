package com.cryptochecker.app.ui.features.explorer

import com.cryptochecker.app.domain.model.MarketInfo
import com.cryptochecker.app.domain.model.MarketPairsInfo
import com.cryptochecker.app.domain.model.MarketTickerResult
import com.cryptochecker.marketdata.model.MarketPair
import com.cryptochecker.marketdata.model.FuturesContractType
import kotlinx.coroutines.flow.StateFlow

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

/** Ausgang von «Alle Paare übernehmen»; [running] solange es läuft. */
data class BulkAddResult(
    val added: Int,
    val skipped: Int,
    val running: Boolean = false,
)

/** Ausgang des Versuchs, ein Paar in die Merkliste zu übernehmen. */
enum class AddResult {
    ADDED,
    ALREADY_IN_LIST,
    NO_PAIR_SELECTED,
}
