package com.cryptochecker.app.domain.alarm

import com.cryptochecker.app.util.KeyValueStores
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Weitere Stablecoins des Depeg-Alarms ([StableDepeg]): zuletzt geprüfte Liste und letzte Messung je Stablecoin in USD.
 * Schreibt `StableDepegChecker` nach jeder Prüfung; liest die Zeile «Überwacht» der Einstellungen. Nur Anzeige — die
 * Bewertung rechnet weiter allein mit USDT ([DepegReadingStore]). Wie die `stable`-Schlüssel in `StableDepegCheck` (iOS).
 */
@Singleton
class StableDepegStore @Inject constructor(
    private val stores: KeyValueStores,
) {
    private val prefs by lazy { stores.open(PREFS) }
    private val watchedState by lazy { MutableStateFlow(loadWatched()) }
    private val readingsState by lazy { MutableStateFlow(loadReadings()) }

    /** Zuletzt geprüfte Stablecoins ohne USDT; vor der ersten Prüfung USDC. */
    val watched: StateFlow<List<String>> get() = watchedState.asStateFlow()

    /** Letzte Messung je Stablecoin (Kürzel → Messung). */
    val readings: StateFlow<Map<String, DepegAlarm.Reading>> get() = readingsState.asStateFlow()

    /** Nach einer Prüfung: geprüfte Liste und die neuen Messungen (übrige bleiben, bis sie veralten). */
    fun save(watched: List<String>, measured: Map<String, Double>, atMillis: Long) {
        prefs.edit {
            putString(KEY_WATCHED, watched.joinToString(","))
            measured.forEach { (coin, price) ->
                putLong(KEY_PRICE_BITS + coin, price.toRawBits())
                putLong(KEY_AT + coin, atMillis)
            }
        }
        watchedState.value = watched
        readingsState.value = readingsState.value + measured.mapValues { (_, p) -> DepegAlarm.Reading(p, atMillis) }
    }

    private fun loadWatched(): List<String> {
        val raw = prefs.getString(KEY_WATCHED) ?: return listOf(StableDepeg.USDC)
        return raw.split(',').filter { it in StableDepeg.COINS }
    }

    private fun loadReadings(): Map<String, DepegAlarm.Reading> = StableDepeg.COINS.mapNotNull { coin ->
        val at = prefs.getLong(KEY_AT + coin, 0)
        if (at <= 0 || !prefs.contains(KEY_PRICE_BITS + coin)) return@mapNotNull null
        coin to DepegAlarm.Reading(Double.fromBits(prefs.getLong(KEY_PRICE_BITS + coin, 0)), at)
    }.toMap()

    private companion object {
        /** Gleiche Datei wie [DepegReadingStore]. */
        const val PREFS = "depeg_alarm"
        const val KEY_WATCHED = "stable_watched"
        const val KEY_PRICE_BITS = "stable_price_bits_"
        const val KEY_AT = "stable_price_at_"
    }
}
