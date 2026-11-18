package com.cryptochecker.app.domain.market

import com.cryptochecker.app.domain.macro.MacroEvent

/**
 * Ein gespeicherter Wert, wann er geholt wurde (Epoch-ms) und von welchem Anbieter
 * ([provider], z. B. «CoinGecko»; null = unbekannt, auch bei Dateien ohne diese Angabe).
 */
data class CachedValue<T>(val value: T, val savedAt: Long, val provider: String? = null)

/**
 * Art eines Werts im Zwischenspeicher des Markt-Tabs. Nur ein Schlüssel: wie der Wert auf dem Gerät steht (JSON), weiss
 * allein `:data` (`MarketCacheAdapter`). Feste Arten in [MarketCacheKinds].
 */
class MarketCacheKind<T> internal constructor(val name: String) {
    override fun toString(): String = name
}

/** Die Arten, die der Markt-Tab speichert; die Umsetzung in `:data` kennt für jede genau eine Umwandlung. */
object MarketCacheKinds {
    val pulseInput = MarketCacheKind<PulseInput>("pulseInput")
    val unusualInput = MarketCacheKind<UnusualInput>("unusualInput")
    val gas = MarketCacheKind<GasReport>("gas")
    val coinInputs = MarketCacheKind<CoinInputs>("coinInputs")
    val fearGreed = MarketCacheKind<FearGreed>("fearGreed")
    val global = MarketCacheKind<GlobalMarket>("global")
    val altSeason = MarketCacheKind<AltSeason>("altSeason")
    val history = MarketCacheKind<CycleHistory>("history")
    val cycleInputs = MarketCacheKind<CycleInputs>("cycleInputs")
    val onChain = MarketCacheKind<OnChainValues>("onChain")

    val all: List<MarketCacheKind<*>> =
        listOf(pulseInput, unusualInput, gas, coinInputs, fearGreed, global, altSeason, history, cycleInputs, onChain)
}

/** Zwischenspeicher des Markt-Tabs auf dem Gerät (Umsetzung: `MarketCacheAdapter` → `CycleCacheStore`). */
interface MarketCache {
    /** Gespeicherter Eintrag [name] der Art [kind]; null = nichts (oder unlesbar). */
    suspend fun <T> read(name: String, kind: MarketCacheKind<T>): CachedValue<T>?

    suspend fun <T> write(name: String, value: T, savedAt: Long, kind: MarketCacheKind<T>, provider: String? = null)
}

/**
 * Datenquellen des Markt-Tabs (Umsetzung: `MarketSourcesAdapter` über die Datenquellen in `:data`). Alle Abrufe werfen
 * bei Fehlern; Zeitgrenzen setzt der Aufrufer.
 */
interface MarketSources {
    /** Zyklus-Kennzahlen; [knownOnChain]: zuletzt gespeicherte On-Chain-Werte (Coin Metrics nur, wenn älter). */
    suspend fun cycleInputs(knownOnChain: OnChainValues?): Sourced<CycleInputs>

    /** Netzgebühren; [maxAgeMillis] 0 = frisch holen. */
    suspend fun gas(maxAgeMillis: Long): Sourced<GasReport>

    /** Höhe und Zeit des letzten Bitcoin-Blocks (Minuten); null = unbekannt. */
    suspend fun bitcoinBlocks(): Pair<Int, Double>?

    suspend fun coin(symbol: String): Sourced<CoinInputs>
    suspend fun fearGreed(): FearGreed
    suspend fun global(): GlobalMarket
    suspend fun altSeason(): Sourced<AltSeason>
    suspend fun cycleHistory(): CycleHistory
    suspend fun pulseInput(): PulseInput
    suspend fun unusualInput(): UnusualInput

    /** Wirtschaftstermine (höchstens einmal am Tag aus dem Netz); wirft nie. */
    suspend fun macroEvents(): List<MacroEvent>
}
