package com.cryptochecker.app.domain.watch

import kotlinx.coroutines.flow.StateFlow

/** Favoriten der Auswahllisten (Börse, Coin, Gegenwert): lesen und umschalten. Umsetzung in :data. */
interface FavoriteStore {
    fun favorites(kind: FavoriteKind): StateFlow<Set<String>>

    fun toggle(kind: FavoriteKind, item: String)
}
