package com.cryptochecker.app.domain.watch

import kotlinx.coroutines.flow.StateFlow

/** Liquidität der DEX-Pools (Umsetzung: `DexLiquidityStore`). */
interface DexLiquidityValues {
    /** Pool-Schlüssel ([DexLiquidity.key]) → Liquidität in USD. */
    val liquidity: StateFlow<Map<String, Double>>

    /** Neue Messung; null = unbekannt (Eintrag entfällt). */
    fun put(pairId: String?, liquidityUsd: Double?)
}

/** Token-Adressen der DEX-Pools fürs Paar-Blatt (Umsetzung: `DexTokenAddressStore`). */
interface DexTokenAddresses {
    /** Adresse aus der Suche merken; leer/ungültig = nichts ändern. */
    fun put(pairId: String?, address: String?)
}
