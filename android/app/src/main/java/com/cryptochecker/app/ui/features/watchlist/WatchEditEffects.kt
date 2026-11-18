package com.cryptochecker.app.ui.features.watchlist

/**
 * Was «Paar bearbeiten» ausser dem Schreiben in die Merkliste auslöst: Refresh-Sperre, gepufferte Live-Kurse und Signale
 * des alten Paars vergessen, dessen Mitteilungen entfernen, Widgets neu zeichnen und den neuen Kurs holen.
 */
interface WatchEditEffects {
    /** [block] unter der Refresh-Sperre (kein Durchlauf schreibt währenddessen Kurse). */
    suspend fun <T> withRefreshLock(block: suspend () -> T): T

    fun forgetLivePrices(watchIds: Collection<Long>)

    fun forgetActivity(watchId: Long)

    fun cancelActivityNotification(watchId: Long)

    suspend fun cancelPriceNotification(watchId: Long)

    suspend fun updateWidgets()

    /** Kurs eines Paars sofort holen; nie unter der Refresh-Sperre aufrufen. */
    suspend fun refreshOne(watchId: Long)
}
