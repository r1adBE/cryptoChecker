package com.cryptochecker.app.data

import androidx.core.content.edit
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Welche Auswahlliste: Börsen, Coins oder Gegenwerte. */
enum class FavoriteKind { MARKET, COIN, QUOTE }

/**
 * Favoriten in den Auswahllisten des Börsen-Tabs (langes Drücken auf einen
 * Eintrag). Favoriten stehen oben in der Liste. Coins gelten börsenübergreifend:
 * wer BTC markiert, findet es bei jeder Börse oben.
 */
@Singleton
class FavoritesRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private val prefs get() = context.getSharedPreferences("favorites", Context.MODE_PRIVATE)

    private val flows: Map<FavoriteKind, MutableStateFlow<Set<String>>> =
        FavoriteKind.entries.associateWith { kind ->
            MutableStateFlow(prefs.getStringSet(kind.key, emptySet())?.toSet() ?: emptySet())
        }

    fun favorites(kind: FavoriteKind): StateFlow<Set<String>> = flows.getValue(kind).asStateFlow()

    fun toggle(kind: FavoriteKind, item: String) {
        val flow = flows.getValue(kind)
        val updated = if (item in flow.value) flow.value - item else flow.value + item
        flow.value = updated
        // Kopie speichern: SharedPreferences darf die übergebene Menge nicht behalten.
        prefs.edit { putStringSet(kind.key, HashSet(updated)) }
    }

    /** Ersetzt alle Favoriten einer Art (Wiederherstellen einer Sicherung). */
    fun setAll(kind: FavoriteKind, items: Set<String>) {
        flows.getValue(kind).value = items
        prefs.edit { putStringSet(kind.key, HashSet(items)) }
    }

    private val FavoriteKind.key: String get() = "fav_" + name.lowercase()
}
