package com.cryptochecker.app.ui.features.info

import android.content.res.Resources
import androidx.compose.runtime.Composable
import com.cryptochecker.app.R
import com.cryptochecker.app.domain.watch.WatchlistLimits
import com.cryptochecker.app.ui.components.CollectScreenEvents
import com.cryptochecker.app.ui.components.HapticEvent
import com.cryptochecker.app.ui.components.HapticPlayer
import com.cryptochecker.app.ui.components.NotificationAsk
import com.cryptochecker.app.ui.components.rememberNotificationPermissionRequest
import com.cryptochecker.app.ui.features.watchlist.WatchUiEvent
import com.cryptochecker.app.ui.features.watchlist.WatchUiEventsHolder
import com.cryptochecker.app.ui.features.watchlist.WatchlistBanner
import com.cryptochecker.app.ui.features.watchlist.WatchlistViewModel
import com.cryptochecker.app.ui.features.watchlist.handleShared
import com.cryptochecker.app.util.LocaleNumbers

/** Schlüssel des Markt-Tabs für `rememberWatchUiEvents` (eigener Empfänger neben der Merkliste). */
internal const val MARKET_COIN_UI_EVENTS = "market_coin_ui_events"

/**
 * Seit Build 107 (Review ARC5-R01): Rückmeldungen der Aktionen aus «Heute auffällig» ([MarketCoinSheets]) nach der Rückfrage
 * F-08 im GERADE gezeigten Bildschirm — Erlaubnis über die Launcher dieser Komposition (nie über einen abgemeldeten),
 * Vorschau «hinzugefügt» und Blatt in ihrem [state]. Ablauf wie vorher in den Rückrufen.
 */
@Composable
internal fun MarketCoinUiEffects(
    holder: WatchUiEventsHolder,
    state: MarketCoinState,
    watchlist: WatchlistViewModel,
    banner: WatchlistBanner,
    haptics: HapticPlayer,
    resources: Resources,
) {
    val requestNotifications = rememberNotificationPermissionRequest()
    // Paar aus der Vorschau übernommen: erst kurz erklären, dann die System-Abfrage
    val requestNotificationsAdded = rememberNotificationPermissionRequest(NotificationAsk.PAIRS_ADDED)
    CollectScreenEvents(holder.events) { event ->
        if (holder.handleShared(event, watchlist, banner, haptics, resources)) return@CollectScreenEvents
        when (event) {
            WatchUiEvent.RequestNotifications -> requestNotifications()
            is WatchUiEvent.CloseActions -> if (state.actionsFor == event.watchId) state.actionsFor = null
            WatchUiEvent.PreviewFull -> {
                // Merkliste voll: nichts angelegt, sagen warum (Zahl im Format der App-Sprache)
                state.addPending = false
                haptics.play(HapticEvent.WARNING)
                banner.show(
                    resources.getString(
                        R.string.watchlist_limit_reached,
                        LocaleNumbers.integer(WatchlistLimits.MAX_PAIRS, grouping = true),
                    )
                )
            }
            is WatchUiEvent.PreviewAdded -> {
                state.addPending = false
                // «Abbrechen», gesperrt bzw. nichts gefunden: Knopf gleich wieder bereit
                val id = event.id ?: return@CollectScreenEvents
                // Wie der Hinzufügen-Tab: neue Paare haben Meldungen an — Erlaubnis jetzt fragen. Stand es schon in der
                // Merkliste: gleich dessen Blatt (ohne Halten)
                state.adding = event.added
                if (event.added) {
                    haptics.play(HapticEvent.SUCCESS)
                    requestNotificationsAdded()
                }
                state.actionsFor = id
            }
            else -> Unit
        }
    }
}
