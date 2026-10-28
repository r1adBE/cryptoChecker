// Kurse aktualisieren (Teil von `WatchlistViewModel`): alle bzw. ein Paar, vom Nutzer ausgelöst mit
// Entprellung oder intern, dazu ob gerade eine Aktualisierung läuft und der Bericht des letzten Durchlaufs.
// Eigene Klasse, weil das Auslösen der Abfrage eine eigene Aufgabe ist; das ViewModel erzeugt sie über [Factory]
// (Hilt setzt die Abhängigkeiten ein) und gibt [scope] (seinen viewModelScope) mit.
package com.cryptochecker.app.ui.features.watchlist

import com.cryptochecker.app.data.RefreshStats
import com.cryptochecker.app.data.SheetChartRepository
import com.cryptochecker.app.data.local.model.WatchEntity
import com.cryptochecker.app.domain.refresh.ManualRefresh
import com.cryptochecker.app.domain.refresh.PriceRefresher
import com.cryptochecker.app.domain.refresh.RefreshDebounce
import com.cryptochecker.app.domain.refresh.RefreshReport
import com.cryptochecker.app.domain.watch.ChangeStamp
import com.cryptochecker.app.work.PriceUpdateScheduler
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

internal class WatchlistRefreshController @AssistedInject constructor(
    @Assisted private val scope: CoroutineScope,
    private val priceRefresher: PriceRefresher,
    private val manualRefresh: ManualRefresh,
    private val refreshStats: RefreshStats,
    private val sheetChartRepository: SheetChartRepository,
    scheduler: PriceUpdateScheduler,
) {
    /** Erzeugt den Controller mit dem viewModelScope des Besitzers; die übrigen Abhängigkeiten setzt Hilt ein. */
    @AssistedFactory
    interface Factory {
        fun create(scope: CoroutineScope): WatchlistRefreshController
    }

    /**
     * Läuft, solange die per Knopf (App) angestossene Aktualisierung arbeitet oder die
     * aus dem Widget-Knopf bei WorkManager wartet oder arbeitet.
     */
    val refreshing: StateFlow<Boolean> = combine(
        manualRefresh.running,
        scheduler.observeManualRefreshRunning(),
    ) { app, widget -> app || widget }
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), manualRefresh.running.value)

    /**
     * Nächster Versuch der Aktualisierung aus dem Widget, solange sie nach «erneut versuchen» wartet
     * (Datenbank-Schlüssel noch nicht lesbar); null = keiner. Die Statuszeile zählt bis dahin herunter.
     */
    val widgetRetryAt: StateFlow<Long?> = scheduler.observeManualRetryAt()
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), null)

    /** Aufschlüsselung des letzten Durchlaufs, per Tipp auf die Dauer. */
    val lastRefreshReport: StateFlow<RefreshReport?> = refreshStats.lastReport

    /** Ende der Pause je Börsen-Schlüssel (zu viele Anfragen, Zeitüberschreitungen) für die Zeitzeile der Paare. */
    val exchangePauses: StateFlow<Map<String, Long>> = refreshStats.exchangePauses

    /** App-Start bis zum ersten Bild der Merkliste (zuletzt gemessen), für den Bericht. */
    val appStartMillis: StateFlow<Long?> = refreshStats.appStartMillis

    /** Basis und Tagesbeginn, mit denen die gespeicherten Veränderungen gerechnet wurden. */
    val changeStamp: StateFlow<ChangeStamp?> = refreshStats.changeStamp

    /** Erstes Bild der Merkliste nach dem Start gemessen (nur lokal gespeichert). */
    fun recordAppStart(millis: Long) = refreshStats.setAppStartMillis(millis)

    /** «Aktualisieren» im Aktionsblatt: Kurs neu abfragen und den Chart des Paars neu laden. */
    fun refreshOneByUser(watch: WatchEntity) {
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
     * ([RefreshDebounce]); dann zeigt die Merkliste kurz «Wieder möglich in N s».
     */
    fun refreshAllByUser(): RefreshDebounce.Decision =
        manualRefresh.request(otherRunning = refreshing.value).also {
            // Von Hand aktualisiert: Charts im Aktionsblatt nicht mehr aus dem Zwischenspeicher
            if (it == RefreshDebounce.Decision.START) sheetChartRepository.invalidateAll()
        }

    /**
     * Ende der letzten vollständigen Aktualisierung (0 = noch keine): Daraus zählt «Aktualisieren»
     * im Menü die Sekunden der Sperre herunter ([RefreshDebounce.remainingSeconds]).
     */
    fun lastFullRefreshAt(): Long = refreshStats.lastFullRefresh()

    /** Ohne 15-s-Sperre, z. B. gleich nach dem Hinzufügen neuer Paare. */
    fun refreshAll() {
        if (refreshing.value) return
        manualRefresh.requestUnguarded()
    }

    fun refreshOne(watchId: Long) {
        scope.launch { priceRefresher.refreshOne(watchId) }
    }
}
