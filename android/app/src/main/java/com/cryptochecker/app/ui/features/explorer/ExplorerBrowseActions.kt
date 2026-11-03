// Übersicht von «Paar hinzufügen» (Teil von `ExplorerViewModel`): Markt-Chip, Börse öffnen und schliessen, «Beliebt».
// Eigene Datei wie `ExplorerBrowseActions.swift`; der Zustand bleibt in der Klasse ([ExplorerSearchHits]).
package com.cryptochecker.app.ui.features.explorer

import androidx.lifecycle.viewModelScope
import com.cryptochecker.app.data.remote.util.runCatchingCancellable
import com.cryptochecker.app.domain.model.ExchangeInfo
import com.cryptochecker.app.domain.starter.StarterCoins
import com.cryptochecker.app.domain.starter.StarterPairs
import com.cryptochecker.app.settings.AssetScope
import com.cryptochecker.app.settings.deviceRegionLocale
import com.cryptochecker.marketdata.model.ExchangePair
import com.cryptochecker.marketdata.model.FuturesContractType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Markt-Chip wählen: Börsenliste und Treffer der Suche zeigen nur noch diese Art. */
fun ExplorerViewModel.setMarketFilter(filter: MarketFilter) {
    if (hits.market.value == filter) return
    hits.market.value = filter
    runSearch()
}

/**
 * Börse aus der Übersicht oder der Suche öffnen: ihre Paare (Auswahl wie bisher) bzw. bei DexScreener die DEX-Suche.
 * Merkt sie unter «Zuletzt genutzte Börsen» (nur auf dem Gerät); ein Suchbegriff wird geleert.
 */
fun ExplorerViewModel.openExchange(target: ExchangeInfo) {
    recentSearchStore.addExchange(target.key)
    if (query.value.isNotEmpty()) setSearchQuery("")
    selectExchange(target)
}

/** Zurück von den Paaren einer Börse zur Übersicht. */
fun ExplorerViewModel.closeExchange() = selectExchange(null)

/**
 * «Beliebt» neu bestimmen: Coins der Start-Börse ([PopularPairs]) als …/USDT-Paare. Liegt ihre Paarliste schon auf dem
 * Gerät, nur Coins mit Spot-Paar in USDT (mit der Kennung aus der Liste); sonst wie die Start-Merkliste. Nur gespeicherte
 * Daten, kein Abruf. Ohne Start-Börse in der Auswahl oder bei «Nur TradFi»: leer.
 */
internal fun ExplorerViewModel.refreshPopular(exchanges: List<ExchangeInfo>, scope: AssetScope) {
    val region = starterCoinsRepository.starterRegion(deviceRegionLocale().country)
    val start = exchanges.firstOrNull { it.key == StarterPairs.exchangeKey(region) }
    if (start == null || !scope.allowsTokens) {
        hits.popular.value = emptyList()
        return
    }
    viewModelScope.launch {
        val stored = withContext(Dispatchers.IO) {
            runCatchingCancellable { exchangeRepository.getExchangeCurrencyPairsInfo(start) }.getOrNull()
        }?.takeIf { it.pairs.isNotEmpty() }
        val quote = StarterPairs.START_QUOTE
        val available = stored?.pairs
            ?.filter { it.quote.equals(quote, ignoreCase = true) && it.contractType == FuturesContractType.NONE }
            ?.mapTo(HashSet()) { it.base.uppercase() }
        val symbols = PopularPairs.pick(
            starter = starterCoinsRepository.cached(region).map { it.symbol },
            fallback = StarterCoins.fallback(region).map { it.symbol },
            available = available,
        )
        hits.popular.value = symbols.map { base ->
            val pair = stored?.getExchangePair(base, quote, FuturesContractType.NONE)
                ?: StarterPairs.pairFor(base, region).let { ExchangePair(it.base, it.quote, it.pairId) }
            SearchHit(start, pair)
        }
    }
}
