// Coin-Suche von «Paar hinzufügen» (Teil von `ExplorerViewModel`): Suchbegriff, Treffer über alle
// gespeicherten Paarlisten, Coin wählen, fehlende Listen laden, Namensverzeichnis.
// Eigene Datei, weil die Suche über alle Börsen eine eigene Aufgabe neben der Auswahl nach Börse ist;
// Erweiterungsfunktionen auf dem ViewModel, der Zustand bleibt in der Klasse (wie `ExplorerSearchActions.swift`).
package com.cryptochecker.app.ui.features.explorer

import com.cryptochecker.app.domain.market.ExchangeOrder
import androidx.lifecycle.viewModelScope
import com.cryptochecker.app.data.remote.util.runCatchingCancellable
import com.cryptochecker.app.domain.model.ExchangeInfo
import com.cryptochecker.app.domain.model.ExchangePairsInfo
import com.cryptochecker.app.domain.starter.StarterPairs
import com.cryptochecker.app.settings.deviceRegionLocale
import com.cryptochecker.marketdata.model.exchange.BinanceAlpha
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Semaphore

// =============================================================================================
// Coin-Suche
// =============================================================================================

fun ExplorerViewModel.setSearchQuery(query: String) {
    if (query != this.query.value) hits.chosenCoin.value = null
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
    hits.chosenCoin.value = hit
    runSearch()
}

/** Treffer direkt in die Merkliste; der Suchbegriff kommt zu «Zuletzt gesucht». */
fun ExplorerViewModel.addSearchHit(hit: SearchHit) {
    recentSearchStore.add(query.value)
    launchAdd(hit.exchange, hit.pair) { added ->
        addResult.value = if (added) AddResult.ADDED else AddResult.ALREADY_IN_LIST
    }
}

/**
 * Fehlende Paarlisten laden — nur auf ausdrücklichen Wunsch: je Börse die vollständige öffentliche
 * Liste (für alle gleich), höchstens [LIST_LOAD_PARALLEL] zugleich. Treffer erscheinen nach und nach.
 */
fun ExplorerViewModel.loadMissingLists() {
    if (missingListsJob?.isActive == true) return
    val targets = unloadedExchanges
    if (targets.isEmpty()) return
    missingListsJob = viewModelScope.launch {
        lists.value = SearchLists(missing = targets.size, progress = SearchProgress(0, targets.size))
        val permits = Semaphore(LIST_LOAD_PARALLEL)
        var finished = 0
        val failed = mutableListOf<String>()
        targets.map { m ->
            async {
                permits.acquire()
                try {
                    val info = syncForSearch(m)
                    if (info != null) pairsByExchange[m.key] = info else failed += m.name
                } finally {
                    permits.release()
                }
                finished++
                lists.value = lists.value.copy(progress = SearchProgress(finished, targets.size))
                runSearch()
            }
        }.awaitAll()
        updateUnloaded()
        // Börsen ohne Antwort (Wartung, Zeitüberschreitung, kein Netz) mit Namen melden statt wortlos
        // dieselbe Karte zu zeigen
        val stillMissing = unloadedExchanges.map { it.name }.toSet()
        lists.value = lists.value.copy(failed = targets.map { it.name }.filter { it in failed && it in stillMissing })
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
        hits.pairs.value = emptyList()
        hits.coins.value = emptyList()
        hits.chosenCoinPairs.value = emptyList()
        return
    }
    // Stand jetzt festhalten (nicht erst im Hintergrund lesen)
    val scope = assetScope.value
    val hidden = hiddenAlpha.value
    val exchanges = searchableExchanges.filter { scope.allowsExchange(it.key) }
    val picked = hits.chosenCoin.value
    val includeUnlisted = unloadedExchanges.isNotEmpty()
    val preference = pairPreference(exchanges)
    // Alpha-Kennzeichen («α») und Namen gegen Fehltreffer
    val alphaSymbols = coinLogoRepository.alphaSymbols.value
    val alphaNames = coinLogoRepository.alphaNames.value
    searchJob = viewModelScope.launch {
        delay(SEARCH_DEBOUNCE_MS)
        // Rechnen im Hintergrund, Ergebnis auf dem Hauptthread setzen: Zustände nur von dort ändern
        // (sonst kann Compose im Test auf einem Hintergrund-Thread neu zusammensetzen – Absturz «multithreaded access»)
        val result = withContext(Dispatchers.Default) {
            val pairNames = exchanges.associate { it.key to tokenIdentityStore.exchangeNames(it.key) }
            val foundCoins = CoinSearch.coins(
                text, exchanges, pairsByExchange,
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
                text, exchanges, pairsByExchange,
                scope = scope,
                names = names,
                hiddenAlpha = hidden,
                alphaSymbols = alphaSymbols,
                alphaNames = alphaNames,
                pairNames = pairNames,
            )
            val coinPairs = picked?.let {
                CoinSearch.pairsFor(it, exchanges, pairsByExchange, preference, scope, hidden, alphaSymbols, alphaNames, pairNames)
            }.orEmpty()
            SearchResult(foundCoins, showPairs, foundPairs, coinPairs)
        }
        ensureActive()
        hits.coins.value = result.coins
        hits.pairsShown.value = result.showPairs
        hits.pairs.value = result.pairs
        hits.chosenCoinPairs.value = result.coinPairs
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
internal fun ExplorerViewModel.pairPreference(exchanges: List<ExchangeInfo>): CoinPairPreference {
    val favoriteNames = favoriteExchanges.value
    return CoinPairPreference(
        defaultExchange = StarterPairs.exchangeKey(starterCoinsRepository.starterRegion(deviceRegionLocale().country)),
        favoriteExchanges = exchanges.filter { it.name in favoriteNames }.mapTo(HashSet()) { it.key },
        displayCurrency = displayCurrency.value,
        exchangeRank = ExchangeOrder.ranks(exchangeRanking.volumes.value),
    )
}

/** Binance-Liste der Suche mit der aktuellen Alpha-Liste neu lesen (Alpha-Paare kommen und gehen). */
internal suspend fun ExplorerViewModel.refreshBinancePairs() {
    val binance = searchableExchanges.find { it.key == BinanceAlpha.EXCHANGE_KEY } ?: return
    if (!pairsByExchange.containsKey(binance.key)) return
    val fresh = runCatchingCancellable { exchangeRepository.getExchangeCurrencyPairsInfo(binance, withAlpha = true) }.getOrNull()
    if (fresh != null) pairsByExchange[binance.key] = fresh
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
        val exchanges = runCatchingCancellable { exchangeRepository.getExchangeList() }.getOrDefault(emptyList())
            .filter { it.key != DEX_KEY }
        if (exchanges.isEmpty()) {
            // Gescheitert: bei der nächsten Eingabe nochmals
            lists.value = SearchLists()
            storedListsJob = null
            return@launch
        }
        withContext(Dispatchers.IO) {
            for (m in exchanges) {
                runCatchingCancellable { exchangeRepository.getExchangeCurrencyPairsInfo(m, withAlpha = true) }.getOrNull()
                    ?.takeIf { it.pairs.isNotEmpty() }
                    ?.let { pairsByExchange[m.key] = it }
            }
        }
        searchableExchanges = exchanges
        updateUnloaded()
        runSearch()
    }
}

/** Welche Börsen noch keine Liste haben, aber eine laden können. */
internal fun ExplorerViewModel.updateUnloaded() {
    unloadedExchanges = searchableExchanges.filter {
        !pairsByExchange.containsKey(it.key) && exchangeRepository.isExchangeSupportsUpdatePairs(it)
    }
    lists.value = SearchLists(missing = unloadedExchanges.size)
}

/** Vollständige öffentliche Liste der Börse holen und gespeichert zurückgeben; null = Fehler oder leer. */
internal suspend fun ExplorerViewModel.syncForSearch(m: ExchangeInfo): ExchangePairsInfo? = withContext(Dispatchers.IO) {
    runCatchingCancellable {
        exchangeRepository.updateExchangeCurrencyPairs(m)
        exchangeRepository.getExchangeCurrencyPairsInfo(m, withAlpha = true).takeIf { it.pairs.isNotEmpty() }
    }.getOrNull()
}

private const val SEARCH_DEBOUNCE_MS = 150L

private const val LIST_LOAD_PARALLEL = 4

/**
 * Paarlisten der Suche: [reading] solange die gespeicherten Listen gelesen werden, [missing] Börsen ohne
 * Liste auf dem Gerät, [progress] solange fehlende auf Wunsch laden, [failed] Namen der Börsen, die beim
 * letzten Laden nicht antworteten.
 */
data class SearchLists(
    val reading: Boolean = false,
    val missing: Int = 0,
    val progress: SearchProgress? = null,
    val failed: List<String> = emptyList(),
)
