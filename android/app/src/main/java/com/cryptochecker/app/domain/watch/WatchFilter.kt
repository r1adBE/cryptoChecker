package com.cryptochecker.app.domain.watch

/**
 * Ansichten der Merkliste über den Gruppen-Chips: «Alle» (null), «Favoriten» ([FAVORITES]) und die
 * eigenen Gruppen. FAV ist keine Gruppe, sondern ein Filter auf das Favoriten-Kennzeichen: Ein
 * Paar bleibt in seiner Gruppe und steht als Favorit zusätzlich unter FAV — Favorit an, gleich
 * drin; aus, gleich draussen. Gilt auch für Listen-Widgets. Wie `WatchFilter.swift` (iOS).
 */
object WatchFilter {
    /** Gespeicherter Wert für «FAV» (Steuerzeichen, kann kein Gruppenname sein). */
    const val FAVORITES = "\u0001FAV"

    fun isFavorites(selection: String?): Boolean = selection == FAVORITES

    /** Steht ein Paar ([groupName], [favorite]) in der Ansicht [selection]? */
    fun matches(selection: String?, groupName: String?, favorite: Boolean): Boolean = when (selection) {
        null -> true
        FAVORITES -> favorite
        else -> groupName == selection
    }
}
