package com.cryptochecker.app.ui.features.explorer

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * «Zuletzt gesucht» unter dem leeren Suchfeld: die letzten Suchbegriffe, mit denen ein Paar
 * hinzugefügt oder die Suche bestätigt wurde. Reine Listenlogik (testbar), wie
 * `ExplorerRecentSearches` in iOS (`ExplorerPairSearch.swift`).
 */
object RecentSearches {
    /** Höchstens so viele Begriffe. */
    const val MAX = 8

    /** Getrimmt, Leerräume zusammengefasst, gross geschrieben (wie das Suchfeld); leer = null. */
    fun normalize(query: String): String? =
        query.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")
            .uppercase().takeIf { it.isNotEmpty() }

    /** Neuer Begriff vorne; derselbe Begriff (auch anders geschrieben) nur einmal; höchstens [MAX]. */
    fun add(list: List<String>, query: String): List<String> {
        val q = normalize(query) ?: return list.take(MAX)
        return (listOf(q) + list.filter { normalize(it) != q }).take(MAX)
    }

    /** Gespeicherter Text (ein Begriff pro Zeile) → Liste. */
    fun decode(text: String?): List<String> =
        text.orEmpty().lines().mapNotNull { normalize(it) }.distinct().take(MAX)

    fun encode(list: List<String>): String = list.joinToString("\n")
}

/**
 * Speicher für «Zuletzt gesucht»: nur auf dem Gerät (eigene SharedPreferences «recent_searches»).
 * Nicht in der Systemsicherung — die Sicherungsregeln (backup_rules.xml,
 * data_extraction_rules.xml) nehmen nur ausdrücklich Genanntes auf — und nicht in der
 * App-Sicherung; wird nirgends hin gesendet.
 */
@Singleton
class RecentSearchStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private val prefs get() = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _items = MutableStateFlow(RecentSearches.decode(prefs.getString(KEY, null)))
    val items: StateFlow<List<String>> = _items.asStateFlow()

    fun add(query: String) {
        val updated = RecentSearches.add(_items.value, query)
        if (updated == _items.value) return
        _items.value = updated
        prefs.edit { putString(KEY, RecentSearches.encode(updated)) }
    }

    fun clear() {
        _items.value = emptyList()
        prefs.edit { remove(KEY) }
    }

    private companion object {
        const val PREFS = "recent_searches"
        const val KEY = "queries"
    }
}
