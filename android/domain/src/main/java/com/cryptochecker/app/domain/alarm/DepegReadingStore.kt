package com.cryptochecker.app.domain.alarm

import com.cryptochecker.app.util.KeyValueStores
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Letzte Messung USDT in USD ([DepegAlarm.Reading]): schreibt [DepegAlarmChecker] nach jeder erfolgreichen Messung
 * (mit Alarm oder Portfolio), liest die Bewertung als Live-Kurs USDT/USD ([liveUsdtUsd]). So rechnen Portfolio,
 * «≈ Umrechnung» und Alarm mit demselben Wert. Wie `DepegAlarmCheck.lastReading` (iOS).
 */
@Singleton
class DepegReadingStore @Inject constructor(
    private val stores: KeyValueStores,
) {
    private val prefs by lazy { stores.open(PREFS) }
    private val state by lazy { MutableStateFlow(load()) }

    val reading: StateFlow<DepegAlarm.Reading?> get() = state.asStateFlow()

    /** Live-Kurs USDT/USD ([DepegAlarm.liveUsdtUsd]: höchstens 6 Std. alt, plausibel); null = keiner (Ersatz 1). */
    fun liveUsdtUsd(now: Long = System.currentTimeMillis()): Double? = DepegAlarm.liveUsdtUsd(state.value, now)

    fun save(price: Double, atMillis: Long, viaUsdc: Boolean = false) {
        prefs.edit {
            putLong(KEY_PRICE_BITS, price.toRawBits())
            putLong(KEY_AT, atMillis)
            putBoolean(KEY_VIA_USDC, viaUsdc)
        }
        state.value = DepegAlarm.Reading(price, atMillis, viaUsdc)
    }

    private fun load(): DepegAlarm.Reading? {
        val at = prefs.getLong(KEY_AT, 0)
        if (at <= 0 || !prefs.contains(KEY_PRICE_BITS)) return null
        return DepegAlarm.Reading(Double.fromBits(prefs.getLong(KEY_PRICE_BITS, 0)), at, prefs.getBoolean(KEY_VIA_USDC, false))
    }

    private companion object {
        /** Gleiche Datei wie [DepegAlarmChecker]. */
        const val PREFS = "depeg_alarm"
        const val KEY_PRICE_BITS = "last_price_bits"
        const val KEY_AT = "last_price_at"
        /** Messung nur aus Binance USDC/USDT ([DepegAlarm.Measurement.viaUsdc]); fehlt bei älteren Messungen (false). */
        const val KEY_VIA_USDC = "last_price_via_usdc"
    }
}
