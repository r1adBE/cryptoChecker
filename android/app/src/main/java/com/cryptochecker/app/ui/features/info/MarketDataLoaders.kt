// Laden der Markt-Daten (Teil von `InfoViewModel`): Puls, Auffälliges, Termine, Gas, Coin-Karte, Angst &
// Gier, Gesamtmarkt, Altcoin-Saison, Zyklus-Verlauf, Halving-Schätzung, Coin-Liste und Bitcoin-Bericht.
// Eigene Datei, weil hier nur festgelegt ist, WAS je Karte geladen wird; WIE (Zwischenspeicher, Zeitlimit)
// steht in `MarketDataSync.kt` (wie `MarketDataLoaders.swift`).
package com.cryptochecker.app.ui.features.info

import androidx.lifecycle.viewModelScope
import com.cryptochecker.app.data.CycleCacheCodecs
import com.cryptochecker.app.data.MarketExtraCodecs
import com.cryptochecker.app.domain.market.BitcoinIndicatorModel
import com.cryptochecker.app.domain.market.CoinIndicatorModel
import com.cryptochecker.app.domain.market.CryptoPulse
import com.cryptochecker.app.domain.market.CycleCachePolicy
import com.cryptochecker.app.domain.market.CycleSource
import com.cryptochecker.app.domain.market.DataFreshness
import com.cryptochecker.app.domain.market.HalvingMath
import com.cryptochecker.app.domain.market.MarketUnusual
import com.cryptochecker.app.domain.market.OnChainValues
import com.cryptochecker.app.domain.market.Sourced
import com.cryptochecker.marketdata.model.FuturesContractType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/** @param force true = Zwischenspeicher (5 Min.) übergehen. Ohne Marktdaten: Failed. */
fun InfoViewModel.loadPulse(force: Boolean = false): Job = sync(
    source = CycleSource.PULSE,
    force = force,
    codec = CycleCacheCodecs.pulseInput,
    // Ohne Marktdaten keine Auswertung: weder zeigen noch speichern
    isUsable = { CryptoPulse.evaluate(it) != null },
    timeOf = { it.time },
    show = { input -> CryptoPulse.evaluate(input)?.let { _pulse.value = LoadState.Loaded(it) } },
    loading = { _pulse.value = LoadState.Loading },
    failed = { _pulse.value = LoadState.Failed },
    fetch = { Sourced(pulseDataSource.fetchInput(), null) },
)

/** @param force true = Zwischenspeicher (10 Min.) übergehen (Ziehen nach unten, «Erneut»). */
fun InfoViewModel.loadUnusual(force: Boolean = false): Job = sync(
    source = CycleSource.UNUSUAL,
    force = force,
    codec = MarketExtraCodecs.unusualInput,
    isUsable = { MarketUnusual.evaluate(it) != null },
    timeOf = { it.time },
    show = { input -> MarketUnusual.evaluate(input)?.let { _unusual.value = LoadState.Loaded(it) } },
    loading = { _unusual.value = LoadState.Loading },
    failed = { _unusual.value = LoadState.Failed },
    fetch = { Sourced(unusualDataSource.fetchInput(), null) },
)

/** Höchstens einmal am Tag aus dem Netz (siehe [MacroCalendarRepository]); wirft nie. */
internal fun InfoViewModel.loadMacro(): Job = viewModelScope.launch {
    _macroEvents.value = macroCalendar.events()
}

/** @param force true = Zwischenspeicher übergehen (Ziehen nach unten, «Erneut»). */
fun InfoViewModel.loadGas(force: Boolean = false): Job = sync(
    source = CycleSource.GAS,
    force = force,
    codec = CycleCacheCodecs.gas,
    timeOf = { it.time },
    show = { _gas.value = LoadState.Loaded(it) },
    loading = { _gas.value = LoadState.Loading },
    failed = { _gas.value = LoadState.Failed },
    // Ohne force darf der Gas-Alarm-Abruf der letzten Minute mitgenutzt werden
    fetch = { gasDataSource.fetchSourced(if (force) 0L else CycleSource.GAS.ttlMillis) },
)

/** Coin-Karte; je Coin ein eigener Zwischenspeicher (15 Min.). */
fun InfoViewModel.loadCoin(force: Boolean = false): Job {
    val symbol = _selectedCoin.value
    return sync(
        source = CycleSource.COIN,
        name = CycleCachePolicy.coinName(symbol),
        force = force,
        codec = CycleCacheCodecs.coinInputs,
        show = { _coin.value = LoadState.Loaded(CoinIndicatorModel.evaluate(symbol, it)) },
        loading = { _coin.value = LoadState.Loading },
        failed = {
            Timber.w("Coin-Daten für %s nicht verfügbar", symbol)
            _coin.value = LoadState.Failed
        },
        fetch = { insights.fetchCoin(symbol) },
    )
}

internal fun InfoViewModel.startAll(force: Boolean): List<Job> = listOf(
    loadMacro(),
    loadPulse(force),
    loadUnusual(force),
    loadMarket(force),
    loadFearGreed(force),
    loadGlobal(force),
    loadAltSeason(force),
    loadHistory(force),
    loadCoin(force),
    loadGas(force),
    loadHalvingEstimate(force),
)

/** Rechnerische Halving-Schätzung aus Blockhöhe und Blockzeit (mempool.space), höchstens einmal je Stunde. */
internal fun InfoViewModel.loadHalvingEstimate(force: Boolean): Job = viewModelScope.launch {
    val now = System.currentTimeMillis()
    if (!force && now - halvingEstimateAt in 0 until HALVING_ESTIMATE_TTL_MILLIS && _halvingEstimate.value != null) return@launch
    val blocks = gasDataSource.bitcoinBlocks() ?: return@launch
    HalvingMath.estimate(blocks.first, blocks.second, now, java.time.ZoneId.systemDefault())?.let {
        _halvingEstimate.value = it
        halvingEstimateAt = now
    }
}

internal fun InfoViewModel.loadFearGreed(force: Boolean): Job = sync(
    source = CycleSource.FEAR_GREED,
    force = force,
    codec = CycleCacheCodecs.fearGreed,
    show = { _fearGreed.value = LoadState.Loaded(it) },
    loading = { _fearGreed.value = LoadState.Loading },
    failed = { _fearGreed.value = LoadState.Failed },
    fetch = { Sourced(insights.fearGreed(), DataFreshness.ALTERNATIVE_ME) },
)

/** Ein CoinGecko-Aufruf für Dominanz und Markt-Summen (kein zweiter Abruf). */
internal fun InfoViewModel.loadGlobal(force: Boolean): Job = sync(
    source = CycleSource.GLOBAL,
    force = force,
    codec = CycleCacheCodecs.global,
    show = { global ->
        _dominance.value = LoadState.Loaded(global.dominance)
        _marketTotals.value = global.totals?.let { LoadState.Loaded(it) } ?: LoadState.Failed
    },
    loading = {
        _dominance.value = LoadState.Loading
        _marketTotals.value = LoadState.Loading
    },
    failed = {
        _dominance.value = LoadState.Failed
        _marketTotals.value = LoadState.Failed
    },
    fetch = { Sourced(insights.global(), DataFreshness.COINGECKO) },
)

internal fun InfoViewModel.loadAltSeason(force: Boolean): Job = sync(
    source = CycleSource.ALT_SEASON,
    force = force,
    codec = CycleCacheCodecs.altSeason,
    show = { _altSeason.value = LoadState.Loaded(it) },
    loading = { _altSeason.value = LoadState.Loading },
    failed = { _altSeason.value = LoadState.Failed },
    fetch = { insights.altSeason() },
)

internal fun InfoViewModel.loadHistory(force: Boolean): Job = sync(
    source = CycleSource.HISTORY,
    force = force,
    codec = CycleCacheCodecs.history,
    isUsable = { it.series.isNotEmpty() },
    show = { _history.value = LoadState.Loaded(it) },
    loading = { _history.value = LoadState.Loading },
    failed = { _history.value = LoadState.Failed },
    fetch = { Sourced(insights.cycleHistory(), null) },
)

internal fun InfoViewModel.loadCoinList() {
    viewModelScope.launch {
        runCatching {
            withContext(Dispatchers.IO) {
                val binance = marketRepository.getMarketList().firstOrNull { it.key == "Binance" }
                    ?: return@withContext null
                var info = marketRepository.getMarketCurrencyPairsInfo(binance)
                if (info.pairs.isEmpty() && marketRepository.isMarketSupportsUpdatePairs(binance)) {
                    marketRepository.updateMarketCurrencyPairs(binance)
                    info = marketRepository.getMarketCurrencyPairsInfo(binance)
                }
                info.pairs
                    .filter { it.quote == "USDT" && it.contractType == FuturesContractType.NONE }
                    .map { it.base }
                    .distinct()
                    .sorted()
            }
        }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { _coins.value = it }
    }
}

/**
 * Bitcoin-Kennzahlen (1 Std.). Die On-Chain-Werte darin (Coin Metrics) gelten 12 Std. und
 * werden bis dahin aus ihrem eigenen Zwischenspeicher übernommen statt neu geholt.
 */
fun InfoViewModel.loadMarket(force: Boolean = false): Job = sync(
    source = CycleSource.MARKET,
    force = force,
    codec = CycleCacheCodecs.cycleInputs,
    show = { _market.value = MarketState.Loaded(BitcoinIndicatorModel.evaluate(it)) },
    loading = { _market.value = MarketState.Loading },
    failed = { _market.value = MarketState.Failed },
    fetch = {
        val now = System.currentTimeMillis()
        val known = if (force) null else cacheStore.read(CycleSource.ON_CHAIN.key, CycleCacheCodecs.onChain)
            ?.takeIf { CycleCachePolicy.isFresh(it.savedAt, now, CycleSource.ON_CHAIN.ttlMillis) }
            ?.value
        val sourced = cycleDataSource.fetchSourced(knownOnChain = known)
        val inputs = sourced.value
        if (known == null) {
            val onChain = OnChainValues(inputs.mvrv, inputs.puell, inputs.hash30d, inputs.hash60d)
            if (onChain.hasAny) {
                cacheStore.write(
                    CycleSource.ON_CHAIN.key, onChain, System.currentTimeMillis(), CycleCacheCodecs.onChain,
                    provider = DataFreshness.COIN_METRICS,
                )
            }
        }
        sourced
    },
)

/** Blockhöhe für die Halving-Schätzung höchstens so oft abfragen. */
private const val HALVING_ESTIMATE_TTL_MILLIS = 60 * 60_000L
