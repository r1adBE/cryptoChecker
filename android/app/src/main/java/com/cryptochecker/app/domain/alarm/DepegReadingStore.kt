package com.cryptochecker.app.domain.alarm

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Letzte Messung des USDT-Depeg-Alarms ([DepegAlarm.Reading]): schreibt [DepegAlarmChecker] nach jeder
 * erfolgreichen Prüfung, liest die Portfolio-Karte (Warnzeile, [DepegAlarm.cardWarning]). So zeigen
 * Karte und Alarm denselben Wert. Wie `DepegAlarmCheck.lastReading` (iOS).
 */
@Singleton
class DepegReadingStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private val prefs by lazy { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    private val state by lazy { MutableStateFlow(load()) }

    val reading: StateFlow<DepegAlarm.Reading?> get() = state.asStateFlow()

    fun save(price: Double, atMillis: Long) {
        prefs.edit {
            putLong(KEY_PRICE_BITS, price.toRawBits())
            putLong(KEY_AT, atMillis)
        }
        state.value = DepegAlarm.Reading(price, atMillis)
    }

    private fun load(): DepegAlarm.Reading? {
        val at = prefs.getLong(KEY_AT, 0)
        if (at <= 0 || !prefs.contains(KEY_PRICE_BITS)) return null
        return DepegAlarm.Reading(Double.fromBits(prefs.getLong(KEY_PRICE_BITS, 0)), at)
    }

    private companion object {
        /** Gleiche Datei wie [DepegAlarmChecker]. */
        const val PREFS = "depeg_alarm"
        const val KEY_PRICE_BITS = "last_price_bits"
        const val KEY_AT = "last_price_at"
    }
}
