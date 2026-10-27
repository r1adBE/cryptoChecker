package com.cryptochecker.app.data

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Paare (Watch-Ids), deren letzter Kurs der Mark-Preis statt des Mittelkurses war ([com.cryptochecker.marketdata.model.Ticker.markFallback],
 * Hyperliquid bei dünnem Orderbuch). Zeile und Aktionsblatt nennen den Kurs dann «Mark», die Alarm-Karte «Wartet auf
 * aktuellen Kurs». Kleine Einstellungsdatei statt einer Spalte (wie [DexLiquidityStore]) — abgeleiteter Marktwert, nicht
 * in der Sicherung; jede Abfrage setzt ihn neu. Wie `MarkFallbackStore` (iOS).
 */
@Singleton
class MarkFallbackStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private val prefs by lazy { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    private val state by lazy { MutableStateFlow(load()) }

    /** Watch-Ids mit Mark-Preis als Kurs. */
    val ids: StateFlow<Set<Long>> get() = state.asStateFlow()

    fun contains(watchId: Long): Boolean = watchId in state.value

    /** Ergebnis der letzten Abfrage von [watchId]. */
    fun put(watchId: Long, markFallback: Boolean) {
        if (contains(watchId) == markFallback) return
        val next = if (markFallback) state.value + watchId else state.value - watchId
        prefs.edit { putStringSet(KEY, next.mapTo(HashSet()) { it.toString() }) }
        state.value = next
    }

    private fun load(): Set<Long> =
        prefs.getStringSet(KEY, emptySet()).orEmpty().mapNotNullTo(HashSet()) { it.toLongOrNull() }

    private companion object {
        const val PREFS = "mark_fallback"
        const val KEY = "watch_ids"
    }
}
