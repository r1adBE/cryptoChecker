// Grenzen der schreibenden Merklisten-Aktionen ([WatchlistEditController]): Rückgängig-Schlüssel und wie viele Paare
// nach dem Aktivieren bzw. Zurückholen einzeln behandelt werden.
package com.cryptochecker.app.ui.features.watchlist

/** «Rückgängig»-Schlüssel für «Merkliste leeren» (wie [NOT_TRADED_UNDO_KEY]; Watch-Ids sind immer positiv). */
internal const val CLEAR_ALL_UNDO_KEY = -3L

/** Bis zu so vielen wieder aktivierten Paaren einzeln abfragen, darüber ein ganzer Durchlauf. */
internal const val ENABLE_SINGLE_REFRESH_MAX = 3

/** Bis zu so vielen zurückgeholten Paaren zeigt «Rückgängig» die Kurs-Meldung gleich wieder, darüber keine. */
internal const val UNDO_PRICE_POST_MAX = 3

/** Kurs-Meldungen beim «Rückgängig» von [restored] Paaren gleich wieder zeigen ([UNDO_PRICE_POST_MAX])? */
internal fun restoresPriceNotifications(restored: Int): Boolean = restored in 1..UNDO_PRICE_POST_MAX
