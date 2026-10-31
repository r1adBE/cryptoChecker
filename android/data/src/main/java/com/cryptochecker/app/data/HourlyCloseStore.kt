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
 * Paare (Watch-Ids), deren letzter Kurs der Schluss einer schon abgeschlossenen Stundenkerze war
 * ([com.cryptochecker.marketdata.model.Ticker.hourlyClose], WOO X Spot ohne letzten Abschluss). Zeile und Aktionsblatt
 * nennen den Kurs dann «Stundenkurs». Kleine Einstellungsdatei wie [MarkFallbackStore] — abgeleiteter Marktwert, nicht
 * in der Sicherung; jede Abfrage setzt ihn neu. Wie `HourlyCloseStore` (iOS).
 */
@Singleton
class HourlyCloseStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private val prefs by lazy { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    private val state by lazy { MutableStateFlow(load()) }

    /** Watch-Ids mit Stundenkurs als Kurs. */
    val ids: StateFlow<Set<Long>> get() = state.asStateFlow()

    fun contains(watchId: Long): Boolean = watchId in state.value

    /** Ergebnis der letzten Abfrage von [watchId]. */
    fun put(watchId: Long, hourlyClose: Boolean) {
        if (contains(watchId) == hourlyClose) return
        val next = if (hourlyClose) state.value + watchId else state.value - watchId
        prefs.edit { putStringSet(KEY, next.mapTo(HashSet()) { it.toString() }) }
        state.value = next
    }

    private fun load(): Set<Long> =
        prefs.getStringSet(KEY, emptySet()).orEmpty().mapNotNullTo(HashSet()) { it.toLongOrNull() }

    private companion object {
        const val PREFS = "hourly_close"
        const val KEY = "watch_ids"
    }
}
