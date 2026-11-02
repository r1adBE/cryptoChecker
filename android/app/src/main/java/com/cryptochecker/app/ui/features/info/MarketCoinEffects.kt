package com.cryptochecker.app.ui.features.info

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalResources
import com.cryptochecker.app.ui.components.CollectScreenEvents
import com.cryptochecker.app.ui.components.rememberHaptics
import com.cryptochecker.app.ui.features.watchlist.WatchlistBanner
import com.cryptochecker.app.ui.features.watchlist.WatchlistViewModel
import com.cryptochecker.app.ui.features.watchlist.handleShared
import com.cryptochecker.app.ui.features.watchlist.rememberWatchUiEvents

/** Schlüssel des Markt-Tabs für `rememberWatchUiEvents` (eigener Empfänger neben der Merkliste). */
internal const val MARKET_COIN_UI_EVENTS = "market_coin_ui_events"

/**
 * Rückmeldungen für den Markt-Tab, die eine von hier geöffnete Paarseite schickt — heute «… entfernt» mit
 * «Rückgängig» nach «Löschen» (die Seite geht danach zurück hierher). Gesammelt im GERADE gezeigten Bildschirm, auch
 * nach dem Neuerstellen der Activity; «Rückgängig» über [watchlist] (dieselbe Instanz wie die Merkliste).
 */
@Composable
internal fun MarketCoinUiEffects(watchlist: WatchlistViewModel, banner: WatchlistBanner) {
    val holder = rememberWatchUiEvents(MARKET_COIN_UI_EVENTS)
    val haptics = rememberHaptics()
    val resources = LocalResources.current
    CollectScreenEvents(holder.events) { event ->
        holder.handleShared(event, watchlist, banner, haptics, resources)
    }
}
