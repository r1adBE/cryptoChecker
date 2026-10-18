package com.cryptochecker.app.data

import android.content.Context
import androidx.core.content.edit
import com.cryptochecker.app.domain.watch.DexLiquidity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Zuletzt gemessene Liquidität (USD) je DEX-Pool ([DexLiquidity]): aus der Suche beim Hinzufügen und aus jeder
 * Kursabfrage (`Ticker.liquidityUsd`). Kleine Einstellungsdatei statt einer Spalte — abgeleiteter Marktwert, nicht in
 * der Sicherung; fehlt er, gilt die Liquidität als unbekannt (kein Alarm bis zur nächsten Abfrage). Wie
 * `DexLiquidityStore` (iOS).
 */
@Singleton
class DexLiquidityStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private val prefs by lazy { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    private val state by lazy { MutableStateFlow(load()) }

    /** Pool-Schlüssel ([DexLiquidity.key]) → Liquidität in USD. */
    val liquidity: StateFlow<Map<String, Double>> get() = state.asStateFlow()

    fun get(pairId: String?): Double? = DexLiquidity.key(pairId)?.let { state.value[it] }

    /** Neue Messung; null = unbekannt (Eintrag entfällt). */
    fun put(pairId: String?, liquidityUsd: Double?) {
        val key = DexLiquidity.key(pairId) ?: return
        val value = liquidityUsd?.takeIf { it.isFinite() && it >= 0.0 }
        if (state.value[key] == value) return
        prefs.edit {
            if (value == null) remove(key) else putLong(key, value.toRawBits())
        }
        state.value = if (value == null) state.value - key else state.value + (key to value)
    }

    /** Alarme dieses Paars pausieren ([DexLiquidity.blocksAlarms])? */
    fun blocksAlarms(marketKey: String, pairId: String?): Boolean =
        DexLiquidity.blocksAlarms(marketKey, get(pairId))

    private fun load(): Map<String, Double> =
        prefs.all.mapNotNull { (key, raw) -> (raw as? Long)?.let { key to Double.fromBits(it) } }.toMap()

    private companion object {
        const val PREFS = "dex_liquidity"
    }
}
