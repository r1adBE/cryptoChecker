package com.cryptochecker.app.ui.features.watchlist

import javax.inject.Inject

/**
 * Fabriken der drei Controller hinter [WatchlistViewModel]; jede erzeugt ihren Controller mit dem viewModelScope des
 * Besitzers. Ohne eigenen Zustand.
 */
internal class WatchlistControllers @Inject constructor(
    /** Aktualisieren ([WatchlistRefreshController]): Abfrage, Entprellung, Bericht. */
    val refresh: WatchlistRefreshController.Factory,
    /** Start-Auswahl ([WatchlistStarterController]). */
    val starter: WatchlistStarterController.Factory,
    /** Schreibende Aktionen ([WatchlistEditController]). */
    val edits: WatchlistEditController.Factory,
)
