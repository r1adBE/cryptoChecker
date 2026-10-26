package com.cryptochecker.app.data.portfolio

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * «Unbekannt lassen» bei Einträgen ohne Einstand ([com.cryptochecker.app.domain.portfolio.PortfolioCostGaps]): Ids der
 * Transaktionen, deren Einstand der Nutzer bewusst offen lässt — die Karte «N Einträge ohne Einstand» zählt sie nicht
 * mehr (gespiegelt in `PortfolioCostGapUi.unknownKey`, PortfolioCostGapUi.swift). Nur Ids, keine Beträge; gehört zu diesem Gerät (eigene Datei
 * [PREFS], nicht in den Sicherungsregeln). Merker ohne Lücke mehr räumt [prune] weg.
 */
@Singleton
class PortfolioCostGapStore @Inject constructor(@ApplicationContext private val context: Context) {

    private val prefs by lazy { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    private val _acknowledged = MutableStateFlow(read())

    /** Bewusst «unbekannt gelassene» Transaktionen. */
    val acknowledged: StateFlow<Set<Long>> = _acknowledged.asStateFlow()

    private fun read(): Set<Long> = runCatching {
        prefs.getStringSet(KEY, emptySet()).orEmpty().mapNotNull { it.toLongOrNull() }.toSet()
    }.getOrDefault(emptySet())

    private fun write(ids: Set<Long>) {
        _acknowledged.value = ids
        runCatching { prefs.edit { putStringSet(KEY, ids.map { it.toString() }.toSet()) } }
    }

    fun acknowledge(id: Long) = write(_acknowledged.value + id)

    /** Wieder als offen zählen (z. B. «Einstand erfassen» nach «Unbekannt lassen»). */
    fun reopen(id: Long) = write(_acknowledged.value - id)

    /** Nur noch [keep] behalten (Lücken, die es noch gibt). */
    fun prune(keep: Set<Long>) {
        if (keep != _acknowledged.value) write(keep)
    }

    private companion object {
        const val PREFS = "portfolio_cost_gaps"
        const val KEY = "unknown"
    }
}
