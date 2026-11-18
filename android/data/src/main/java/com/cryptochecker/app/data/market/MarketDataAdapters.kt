package com.cryptochecker.app.data.market

import com.cryptochecker.app.data.MacroCalendarRepository
import com.cryptochecker.app.data.MarketExtraCodecs
import com.cryptochecker.app.data.cycle.CacheCodec
import com.cryptochecker.app.data.cycle.CycleCacheCodecs
import com.cryptochecker.app.data.cycle.CycleCacheStore
import com.cryptochecker.app.data.remote.CycleDataSource
import com.cryptochecker.app.data.remote.GasDataSource
import com.cryptochecker.app.data.remote.InsightsDataSource
import com.cryptochecker.app.data.remote.PulseDataSource
import com.cryptochecker.app.data.remote.UnusualDataSource
import com.cryptochecker.app.domain.macro.MacroEvent
import com.cryptochecker.app.domain.market.AltSeason
import com.cryptochecker.app.domain.market.CachedValue
import com.cryptochecker.app.domain.market.CoinInputs
import com.cryptochecker.app.domain.market.CycleHistory
import com.cryptochecker.app.domain.market.CycleInputs
import com.cryptochecker.app.domain.market.FearGreed
import com.cryptochecker.app.domain.market.GasReport
import com.cryptochecker.app.domain.market.GlobalMarket
import com.cryptochecker.app.domain.market.MarketCache
import com.cryptochecker.app.domain.market.MarketCacheKind
import com.cryptochecker.app.domain.market.MarketCacheKinds
import com.cryptochecker.app.domain.market.MarketSources
import com.cryptochecker.app.domain.market.OnChainValues
import com.cryptochecker.app.domain.market.PulseInput
import com.cryptochecker.app.domain.market.Sourced
import com.cryptochecker.app.domain.market.UnusualInput
import javax.inject.Inject
import javax.inject.Singleton

/** [MarketCache] über [CycleCacheStore]: je Art die JSON-Umwandlung aus [CycleCacheCodecs] bzw. [MarketExtraCodecs]. */
@Singleton
class MarketCacheAdapter @Inject constructor(private val store: CycleCacheStore) : MarketCache {
    override suspend fun <T> read(name: String, kind: MarketCacheKind<T>): CachedValue<T>? = store.read(name, codec(kind))

    override suspend fun <T> write(name: String, value: T, savedAt: Long, kind: MarketCacheKind<T>, provider: String?) =
        store.write(name, value, savedAt, codec(kind), provider)

    companion object {
        private val CODECS: Map<MarketCacheKind<*>, CacheCodec<*>> = mapOf(
            MarketCacheKinds.pulseInput to CycleCacheCodecs.pulseInput,
            MarketCacheKinds.unusualInput to MarketExtraCodecs.unusualInput,
            MarketCacheKinds.gas to CycleCacheCodecs.gas,
            MarketCacheKinds.coinInputs to CycleCacheCodecs.coinInputs,
            MarketCacheKinds.fearGreed to CycleCacheCodecs.fearGreed,
            MarketCacheKinds.global to CycleCacheCodecs.global,
            MarketCacheKinds.altSeason to CycleCacheCodecs.altSeason,
            MarketCacheKinds.history to CycleCacheCodecs.history,
            MarketCacheKinds.cycleInputs to CycleCacheCodecs.cycleInputs,
            MarketCacheKinds.onChain to CycleCacheCodecs.onChain,
        )

        /** Umwandlung der Art [kind]; jede Art in [MarketCacheKinds.all] hat genau eine (Test `MarketCacheAdapterTest`). */
        @Suppress("UNCHECKED_CAST")
        internal fun <T> codec(kind: MarketCacheKind<T>): CacheCodec<T> =
            checkNotNull(CODECS[kind]) { "Keine Umwandlung für $kind" } as CacheCodec<T>

        internal fun hasCodec(kind: MarketCacheKind<*>): Boolean = kind in CODECS
    }
}

/** [MarketSources] über die Datenquellen des Markt-Tabs. */
@Singleton
class MarketSourcesAdapter @Inject constructor(
    private val cycle: CycleDataSource,
    private val gasSource: GasDataSource,
    private val insights: InsightsDataSource,
    private val pulse: PulseDataSource,
    private val unusual: UnusualDataSource,
    private val macro: MacroCalendarRepository,
) : MarketSources {
    override suspend fun cycleInputs(knownOnChain: OnChainValues?): Sourced<CycleInputs> =
        cycle.fetchSourced(knownOnChain = knownOnChain)

    override suspend fun gas(maxAgeMillis: Long): Sourced<GasReport> = gasSource.fetchSourced(maxAgeMillis)
    override suspend fun bitcoinBlocks(): Pair<Int, Double>? = gasSource.bitcoinBlocks()
    override suspend fun coin(symbol: String): Sourced<CoinInputs> = insights.fetchCoin(symbol)
    override suspend fun fearGreed(): FearGreed = insights.fearGreed()
    override suspend fun global(): GlobalMarket = insights.global()
    override suspend fun altSeason(): Sourced<AltSeason> = insights.altSeason()
    override suspend fun cycleHistory(): CycleHistory = insights.cycleHistory()
    override suspend fun pulseInput(): PulseInput = pulse.fetchInput()
    override suspend fun unusualInput(): UnusualInput = unusual.fetchInput()
    override suspend fun macroEvents(): List<MacroEvent> = macro.events()
}
