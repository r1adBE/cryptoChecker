// Kurse aktualisieren (Teil von `WatchlistViewModel`): alle bzw. ein Paar, vom Nutzer ausgelöst mit
// Entprellung oder intern.
// Eigene Datei, weil das Auslösen der Abfrage eine eigene Aufgabe ist; Erweiterungsfunktionen auf dem
// ViewModel.
package com.cryptochecker.app.ui.features.watchlist

import androidx.lifecycle.viewModelScope
import com.cryptochecker.app.data.local.model.WatchEntity
import com.cryptochecker.app.domain.refresh.RefreshDebounce
import kotlinx.coroutines.launch

/** «Aktualisieren» im Aktionsblatt: Kurs neu abfragen und den Chart des Paars neu laden. */
fun WatchlistViewModel.refreshOneByUser(watch: WatchEntity) {
    sheetChartRepository.invalidate(watch)
    refreshOne(watch.id)
}

// Beim Öffnen wird bewusst NICHT automatisch aktualisiert: Die Liste zeigt
// die gespeicherten Kurse mit Zeitstempel, nachgeladen wird per Knopf,
// durch den Live-Strom oder die Hintergrund-Aktualisierung.

/**
 * Nach unten ziehen bzw. Knopf oben: direkt in einer App-weiten Coroutine
 * ([ManualRefresh]), nicht über WorkManager — läuft auch nach dem Schliessen der
 * App zu Ende. Gesperrt, solange eine läuft oder die letzte keine 15 s her ist
 * ([RefreshDebounce]); dann zeigt die Merkliste kurz «Gerade aktualisiert».
 */
fun WatchlistViewModel.refreshAllByUser(): RefreshDebounce.Decision =
    manualRefresh.request(otherRunning = refreshing.value).also {
        // Von Hand aktualisiert: Charts im Aktionsblatt nicht mehr aus dem Zwischenspeicher
        if (it == RefreshDebounce.Decision.START) sheetChartRepository.invalidateAll()
    }

/**
 * Würde «Aktualisieren» jetzt etwas tun? Nein, solange eine läuft oder die letzte erst
 * [RefreshDebounce.WINDOW_MILLIS] her ist — dann ist der Menüpunkt grau (statt einer Meldung).
 */
fun WatchlistViewModel.canRefreshNow(): Boolean = RefreshDebounce.decide(
    running = refreshing.value,
    lastFinishedAt = refreshStats.lastRefresh(),
    now = System.currentTimeMillis(),
) == RefreshDebounce.Decision.START

/** Ohne 15-s-Sperre, z. B. gleich nach dem Hinzufügen neuer Paare. */
internal fun WatchlistViewModel.refreshAll() {
    if (refreshing.value) return
    manualRefresh.requestUnguarded()
}

fun WatchlistViewModel.refreshOne(watchId: Long) {
    viewModelScope.launch { priceRefresher.refreshOne(watchId) }
}
