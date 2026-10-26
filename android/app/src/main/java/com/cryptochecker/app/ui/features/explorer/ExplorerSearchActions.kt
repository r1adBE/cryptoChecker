// Coin-Suche von «Paar hinzufügen» (Teil von `ExplorerViewModel`): Suchbegriff, Treffer über alle
// gespeicherten Paarlisten, Coin wählen, fehlende Listen laden, Namensverzeichnis.
// Eigene Datei, weil die Suche über alle Börsen eine eigene Aufgabe neben der Auswahl nach Börse ist;
// Erweiterungsfunktionen auf dem ViewModel, der Zustand bleibt in der Klasse (wie `ExplorerSearchActions.swift`).
package com.cryptochecker.app.ui.features.explorer

import com.cryptochecker.app.domain.market.ExchangeOrder
import androidx.lifecycle.viewModelScope
import com.cryptochecker.app.data.remote.util.runCatchingCancellable
import com.cryptochecker.app.domain.model.MarketInfo
import com.cryptochecker.app.domain.model.MarketPairsInfo
import com.cryptochecker.app.domain.starter.StarterPairs
import com.cryptochecker.app.settings.deviceRegionLocale
import com.cryptochecker.marketdata.model.market.BinanceAlpha
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Semaphore

// =============================================================================================
// Coin-Suche
// =============================================================================================

fun ExplorerViewModel.setSearchQuery(query: String) {
    if (query != this.query.value) chosenCoin.value = null
    this.query.value = query
    if (query.isNotBlank()) {
        readStoredLists()
        watchNames()
    }
    runSearch()
}

/** Suche bestätigt (Taste «Suchen»): Begriff merken. */
fun ExplorerViewModel.confirmSearch() {
    recentSearchStore.add(query.value)
}

fun ExplorerViewModel.clearRecentSearches() = recentSearchStore.clear()

/** Coin antippen: seine Paare über alle geladenen Listen zeigen (Vorschlag zuerst). */
fun ExplorerViewModel.pickCoin(hit: CoinHit?) {
    chosenCoin.value = hit
    runSearch()
}

/** Treffer direkt in die Merkliste; der Suchbegriff kommt zu «Zuletzt gesucht». */
fun ExplorerViewModel.addSearchHit(hit: SearchHit) {
    recentSearchStore.add(query.value)
    launchAdd(hit.market, hit.pair) { added ->
        addResult.value = if (added) AddResult.ADDED else AddResult.ALREADY_IN_LIST
    }
}

/**
 * Fehlende Paarlisten laden — nur auf ausdrücklichen Wunsch: je Börse die vollständige öffentliche
 * Liste (für alle gleich), höchstens [LIST_LOAD_PARALLEL] zugleich. Treffer erscheinen nach und nach.
 */
fun ExplorerViewModel.loadMissingLists() {
    if (missingListsJob?.isActive == true) return
    val targets = unloadedMarkets
    if (targets.isEmpty()) return
    missingListsJob = viewModelScope.launch {
        lists.value = SearchLists(missing = targets.size, progress = SearchProgress(0, targets.size))
        val permits = Semaphore(LIST_LOAD_PARALLEL)
        var finished = 0
        targets.map { m ->
            async {
                permits.acquire()
                try {
                    syncForSearch(m)?.let { pairsByMarket[m.key] = it }
                } finally {
                    permits.release()
                }
                finished++
                lists.value = lists.value.copy(progress = SearchProgress(finished, targets.size))
                runSearch()
            }
        }.awaitAll()
        updateUnloaded()
        runSearch()
    }
}

/** Gespeicherte Namensliste beobachten (kein Netz); neue Namen → Suche nochmals. */
internal fun ExplorerViewModel.watchNames() {
    if (namesJob != null) return
    namesJob = viewModelScope.launch {
        coinLogoRepository.loadSavedNames()
        coinLogoRepository.names
            .map { list -> withContext(Dispatchers.Default) { NameIndex(list) } }
            .collect { index ->
                names = index
                if (query.value.isNotBlank()) runSearch()
            }
    }
}

/** Coins, Paar-Treffer und — bei angetipptem Coin — seine Paare neu berechnen (entprellt). */
internal fun ExplorerViewModel.runSearch() {
    searchJob?.cancel()
    val text = query.value.trim()
    if (text.isEmpty()) {
        pairHitList.value = emptyList()
        coinHitList.value = emptyList()
        chosenCoinPairList.value = emptyList()
        return
    }
    // Stand jetzt festhalten (nicht erst im Hintergrund lesen)
    val scope = assetScope.value
    val hidden = hiddenAlpha.value
    val markets = searchableMarkets.filter { scope.allowsMarket(it.key) }
    val picked = chosenCoin.value
    val includeUnlisted = unloadedMarkets.isNotEmpty()
    val preference = pairPreference(markets)
    // Alpha-Kennzeichen («α») und Namen gegen Fehltreffer
    val alphaSymbols = coinLogoRepository.alphaSymbols.value
    val alphaNames = coinLogoRepository.alphaNames.value
    searchJob = viewModelScope.launch {
        delay(SEARCH_DEBOUNCE_MS)
        // Rechnen im Hintergrund, Ergebnis auf dem Hauptthread setzen: Zustände nur von dort ändern
        // (sonst kann Compose im Test auf einem Hintergrund-Thread neu zusammensetzen – Absturz «multithreaded access»)
        val result = withContext(Dispatchers.Default) {
            val pairNames = markets.associate { it.key to tokenIdentityStore.marketNames(it.key) }
            val foundCoins = CoinSearch.coins(
                text, markets, pairsByMarket,
                names = names,
                scope = scope,
                hiddenAlpha = hidden,
                alphaSymbols = alphaSymbols,
                alphaNames = alphaNames,
                pairNames = pairNames,
                includeUnlisted = includeUnlisted,
            )
            val showPairs = CoinSearch.showPairHits(text, foundCoins.isNotEmpty())
            val foundPairs = if (!showPairs) emptyList() else PairSearch.find(
                text, markets, pairsByMarket,
                scope = scope,
                names = names,
                hiddenAlpha = hidden,
                alphaSymbols = alphaSymbols,
                alphaNames = alphaNames,
                pairNames = pairNames,
            )
            val coinPairs = picked?.let {
                CoinSearch.pairsFor(it, markets, pairsByMarket, preference, scope, hidden, alphaSymbols, alphaNames, pairNames)
            }.orEmpty()
            SearchResult(foundCoins, showPairs, foundPairs, coinPairs)
        }
        ensureActive()
        coinHitList.value = result.coins
        pairHitsShown.value = result.showPairs
        pairHitList.value = result.pairs
        chosenCoinPairList.value = result.coinPairs
    }
}

/** Ergebnis einer Suche, im Hintergrund gerechnet ([runSearch]). */
private data class SearchResult(
    val coins: List<CoinHit>,
    val showPairs: Boolean,
    val pairs: List<SearchHit>,
    val coinPairs: List<SearchHit>,
)

/**
 * Vorschlag der Coin-Suche: Start-Börse der Region (bei Standort-Sperre Binance.US, wie die Start-Merkliste),
 * Lieblingsbörsen (gemerkt als Anzeigename), die Umrechnungswährung und der Volumenrang der Börsen.
 */
internal fun ExplorerViewModel.pairPreference(markets: List<MarketInfo>): CoinPairPreference {
    val favoriteNames = favoriteMarkets.value
    return CoinPairPreference(
        defaultExchange = StarterPairs.marketKey(starterCoinsRepository.starterRegion(deviceRegionLocale().country)),
        favoriteExchanges = markets.filter { it.name in favoriteNames }.mapTo(HashSet()) { it.key },
        displayCurrency = displayCurrency.value,
        exchangeRank = ExchangeOrder.ranks(exchangeRanking.volumes.value),
    )
}

/** Binance-Liste der Suche mit der aktuellen Alpha-Liste neu lesen (Alpha-Paare kommen und gehen). */
internal suspend fun ExplorerViewModel.refreshBinancePairs() {
    val binance = searchableMarkets.find { it.key == BinanceAlpha.MARKET_KEY } ?: return
    if (!pairsByMarket.containsKey(binance.key)) return
    val fresh = runCatchingCancellable { marketRepository.getMarketCurrencyPairsInfo(binance, withAlpha = true) }.getOrNull()
    if (fresh != null) pairsByMarket[binance.key] = fresh
}

/**
 * Gespeicherte Paarlisten aller Börsen in den Speicher der Suche — nur vom Gerät, kein Abruf. Einmal je
 * Sitzung (später geladene Listen kommen über [loadMissingLists] bzw. den Abgleich der gewählten Börse).
 */
internal fun ExplorerViewModel.readStoredLists() {
    if (storedListsJob != null) return
    storedListsJob = viewModelScope.launch {
        // Gleich «suche…» zeigen statt kurz «keine Treffer»
        lists.value = SearchLists(reading = true)
        val markets = runCatchingCancellable { marketRepository.getMarketList() }.getOrDefault(emptyList())
            .filter { it.key != DEX_KEY }
        if (markets.isEmpty()) {
            // Gescheitert: bei der nächsten Eingabe nochmals
            lists.value = SearchLists()
            storedListsJob = null
            return@launch
        }
        withContext(Dispatchers.IO) {
            for (m in markets) {
                runCatchingCancellable { marketRepository.getMarketCurrencyPairsInfo(m, withAlpha = true) }.getOrNull()
                    ?.takeIf { it.pairs.isNotEmpty() }
                    ?.let { pairsByMarket[m.key] = it }
            }
        }
        searchableMarkets = markets
        updateUnloaded()
        runSearch()
    }
}

/** Welche Börsen noch keine Liste haben, aber eine laden können. */
internal fun ExplorerViewModel.updateUnloaded() {
    unloadedMarkets = searchableMarkets.filter {
        !pairsByMarket.containsKey(it.key) && marketRepository.isMarketSupportsUpdatePairs(it)
    }
    lists.value = SearchLists(missing = unloadedMarkets.size)
}

/** Vollständige öffentliche Liste der Börse holen und gespeichert zurückgeben; null = Fehler oder leer. */
internal suspend fun ExplorerViewModel.syncForSearch(m: MarketInfo): MarketPairsInfo? = withContext(Dispatchers.IO) {
    runCatchingCancellable {
        marketRepository.updateMarketCurrencyPairs(m)
        marketRepository.getMarketCurrencyPairsInfo(m, withAlpha = true).takeIf { it.pairs.isNotEmpty() }
    }.getOrNull()
}

private const val SEARCH_DEBOUNCE_MS = 150L

private const val LIST_LOAD_PARALLEL = 4

/**
 * Paarlisten der Suche: [reading] solange die gespeicherten Listen gelesen werden, [missing] Börsen ohne
 * Liste auf dem Gerät, [progress] solange fehlende auf Wunsch laden.
 */
data class SearchLists(val reading: Boolean = false, val missing: Int = 0, val progress: SearchProgress? = null)
