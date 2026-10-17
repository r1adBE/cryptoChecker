// Start-Merkliste der leeren Merkliste (Teil von `WatchlistViewModel`): Coins, Logos und Kurse laden,
// Auswahl umschalten, gewählte Start-Paare hinzufügen.
// Eigene Datei, weil der Ablauf nur bis zum ersten Paar gebraucht wird; Erweiterungsfunktionen auf dem
// ViewModel, der Zustand (StateFlows, Jobs) bleibt in der Klasse.
package com.cryptochecker.app.ui.features.watchlist

import androidx.lifecycle.viewModelScope
import com.cryptochecker.app.domain.model.MarketInfo
import com.cryptochecker.app.domain.starter.AddMoment
import com.cryptochecker.app.domain.starter.StarterPairs
import com.cryptochecker.app.domain.starter.StarterSelection
import com.cryptochecker.app.settings.deviceRegionLocale
import com.cryptochecker.marketdata.config.MarketsConfig
import com.cryptochecker.marketdata.model.MarketPair
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import com.cryptochecker.app.domain.starter.StarterPrice

internal suspend fun WatchlistViewModel.ensureStarterLogos(coins: List<StarterPairs.Coin>) {
    if (!settingsRepository.cached.coinLogos) {
        _starterLogosReady.value = true
        return
    }
    kotlinx.coroutines.withTimeoutOrNull(STARTER_LOGO_WAIT_MILLIS) {
        coinLogoRepository.ensureStarterLogos(coins.map { it.symbol })
    }
    _starterLogosReady.value = true
}

/** Nur wenn die leere Merkliste gezeigt wird; höchstens ein Abruf gleichzeitig. */
fun WatchlistViewModel.loadStarterCoins() {
    if (starterJob?.isActive == true) return
    starterJob = viewModelScope.launch {
        // Binance am Standort gesperrt (451/403)? Parallel prüfen; gesperrt → Startpaare auf Binance.US
        val probe = async { starterCoinsRepository.probeBinance(deviceRegionLocale().country) }
        starterProbeJob = probe
        loadStarterList()
        if (probe.await()) {
            // Andere Startbörse: deren Liste, Kurse und Logos
            _starterCoins.value = starterCoinsRepository.cached(starterRegion())
            loadStarterList()
        }
    }
}

internal suspend fun WatchlistViewModel.loadStarterList(): Unit = kotlinx.coroutines.coroutineScope {
    // Kurse und Logos gleich für die sofort gezeigte Liste (parallel zur frischeren Liste)
    val pricesJob = launch { loadStarterPrices(_starterCoins.value) }
    val logosJob = launch { ensureStarterLogos(_starterCoins.value) }
    starterCoinsRepository.fresh(starterRegion())?.let { fresh ->
        val changed = fresh.map { it.symbol } != _starterCoins.value.map { it.symbol }
        _starterCoins.value = fresh
        if (changed) {
            pricesJob.cancel()
            // Neue Coins: deren Logos noch dazu (die Liste bleibt dabei sichtbar)
            launch { logosJob.join(); ensureStarterLogos(fresh) }
            loadStarterPrices(fresh)
        }
    }
    Unit
}

fun WatchlistViewModel.toggleStarter(symbol: String) {
    _starterDeselected.value = StarterSelection.toggle(_starterDeselected.value, symbol)
}

/** «Alle auswählen» bzw. «Keine auswählen». */
fun WatchlistViewModel.toggleAllStarters() {
    _starterDeselected.value = StarterSelection.toggleAll(_starterCoins.value, _starterDeselected.value)
}

internal suspend fun WatchlistViewModel.loadStarterPrices(coins: List<StarterPairs.Coin>) {
    _starterPrices.value = _starterPrices.value.copy(loading = true)
    val prices = starterCoinsRepository.prices(coins.map { it.symbol }, starterRegion())
    // Fehler: Zeilen ohne Kurs (alte Kurse bleiben, falls die Coins dieselben sind)
    _starterPrices.value = StarterPricesState(
        loading = false,
        prices = prices.ifEmpty { _starterPrices.value.prices },
    )
}

/**
 * Start-Merkliste: Paare der gewählten Coins anlegen (Binance …/USDT, in den USA
 * Coinbase …/USD; CH/LI: Kraken …/CHF bzw. …/USD), genau wie «Hinzufügen» (WatchRepository.addWatch, gleiche
 * Standardwerte, aber Kurs-Benachrichtigung aus). Vorhandene Paare werden nicht doppelt angelegt. Danach gleich
 * aktualisieren. Einstellungen (z. B. Umrechnung) bleiben unverändert.
 */
internal fun WatchlistViewModel.addStarter(symbols: List<String>) {
    // Doppeltipp: kein zweiter Durchlauf, der doppelt anlegt oder den Moment verwirft
    if (starterAddJob?.isActive == true || symbols.isEmpty()) return
    _starterAdding.value = true
    starterAddJob = viewModelScope.launch {
        try {
            addStarterPairs(symbols)
        } finally {
            _starterAdding.value = false
        }
    }
}

/** Die gewählten Start-Coins in der angezeigten Reihenfolge anlegen. */
fun WatchlistViewModel.addSelectedStarters() {
    addStarter(StarterSelection.selected(_starterCoins.value, _starterDeselected.value))
}

internal suspend fun WatchlistViewModel.addStarterPairs(symbols: List<String>) {
    // Prüfung der Standort-Sperre läuft noch: abwarten, damit die Paare gleich an der richtigen Börse landen
    starterProbeJob?.join()
    val region = starterRegion()
    val wanted = symbols.map { symbol ->
        StarterPairs.pairFor(symbol, region) { key -> MarketsConfig.MARKETS[key]?.name }
    }
    val existing = watchRepository.getWatches().map { Triple(it.marketKey, it.baseAsset, it.quoteAsset) }
    val toAdd = StarterPairs.missing(wanted, existing)
    if (toAdd.isEmpty()) return
    // Erst-Moment vor dem Speichern bereitstellen: Zeilen, Häkchen, Haptik und Banner
    addMoments.postStarter(toAdd.map { AddMoment.Added(it.marketKey, it.base, it.quote, "${it.base}/${it.quote}") })
    var added = 0
    for (pair in toAdd) {
        val id = watchRepository.addWatch(
            MarketInfo(key = pair.marketKey, name = pair.marketName),
            MarketPair(pair.base, pair.quote, pair.pairId),
            // Kurs-Benachrichtigung an wie jedes neue Paar; die Erlaubnis fragt die Start-Auswahl (WatchlistScreen)
        )
        if (id != null) added++
    }
    if (added > 0) {
        widgetUpdater.updateAll()
        refreshAll()
    } else {
        // Nichts gespeichert: den bereitgestellten Moment verwerfen
        addMoments.pending.value?.let(addMoments::consume)
    }
}

/** Kurse der Start-Merkliste: [loading] = Abruf läuft; fehlende Coins haben keinen Kurs. */
data class StarterPricesState(val loading: Boolean, val prices: Map<String, StarterPrice>)

/** So lange wartet die Start-Auswahl höchstens auf die Logos der Start-Coins, dann Initialen. */
private const val STARTER_LOGO_WAIT_MILLIS = 4_000L
