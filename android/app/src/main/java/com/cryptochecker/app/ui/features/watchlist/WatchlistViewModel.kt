package com.cryptochecker.app.ui.features.watchlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cryptochecker.app.data.ActivityRepository
import com.cryptochecker.app.data.AddMoments
import com.cryptochecker.app.data.CoinLogoRepository
import com.cryptochecker.app.data.TokenIdentityStore
import com.cryptochecker.app.data.WatchMove
import com.cryptochecker.app.data.WatchRepository
import com.cryptochecker.app.data.live.LivePriceStream
import com.cryptochecker.app.data.local.model.WatchEntity
import com.cryptochecker.app.data.portfolio.CurrencyConverter
import com.cryptochecker.app.data.portfolio.PortfolioAlarmRepository
import com.cryptochecker.app.domain.activity.WhyReport
import com.cryptochecker.app.domain.live.LivePair
import com.cryptochecker.app.domain.live.LiveQuote
import com.cryptochecker.app.domain.refresh.RefreshDebounce
import com.cryptochecker.app.domain.refresh.RefreshReport
import com.cryptochecker.app.domain.starter.AddMoment
import com.cryptochecker.app.domain.watch.AssetKind
import com.cryptochecker.app.domain.watch.ChangeBasis
import com.cryptochecker.app.domain.watch.ChangeStamp
import com.cryptochecker.app.domain.watch.ColumnSort
import com.cryptochecker.app.domain.watch.DayChange
import com.cryptochecker.app.domain.watch.SheetChartRange
import com.cryptochecker.app.domain.watch.SheetChartResult
import com.cryptochecker.app.domain.watch.SortKey
import com.cryptochecker.app.settings.SettingsRepository
import com.cryptochecker.app.util.ConnectivityMonitor
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel der Merkliste. Setzt sich aus Teilen mit je eigener Aufgabe zusammen, die ihren Zustand privat
 * halten und nur lesbare StateFlows und Funktionen anbieten: Start-Auswahl ([WatchlistStarterController]),
 * schreibende Aktionen ([WatchlistEditController]), Aktualisieren ([WatchlistRefreshController]), die
 * Lese-Abfragen für Zeilen und Aktionsblatt ([WatchlistSheetData]), der gespeicherte Inhalt mit Gruppen und
 * Alarmen ([WatchlistContentFlows]) und der Live-Stand mit Umrechnung ([WatchlistLiveFlows]). Hier stehen die
 * Ableitungen aus den Einstellungen; der Bildschirm sammelt alles gebündelt als [uiState].
 */
@HiltViewModel
class WatchlistViewModel @Inject internal constructor(
    watchRepository: WatchRepository,
    connectivity: ConnectivityMonitor,
    private val settingsRepository: SettingsRepository,
    activityRepository: ActivityRepository,
    private val currencyConverter: CurrencyConverter,
    private val addMoments: AddMoments,
    private val livePriceStream: LivePriceStream,
    coinLogoRepository: CoinLogoRepository,
    /** Namen der Coins je Börse: klar anderer Name als in der Alpha-Liste → kein «α» ([AssetKind.of]). */
    private val tokenIdentityStore: TokenIdentityStore,
    /** Nur für [WatchlistContentFlows.alarmTotal] (Ziel der Glocke): gibt es Portfolio-Alarme? */
    portfolioAlarmRepository: PortfolioAlarmRepository,
    /** Daten für Aktionsblatt und Zeilen (Mini-Chart, Herkunft, Futures, Spot, Chart). */
    private val sheetData: WatchlistSheetData,
    /** Aktualisieren ([WatchlistRefreshController]): Abfrage, Entprellung, Bericht. */
    refreshFactory: WatchlistRefreshController.Factory,
    /** Start-Auswahl ([WatchlistStarterController]). */
    starterFactory: WatchlistStarterController.Factory,
    /** Schreibende Aktionen ([WatchlistEditController]). */
    editsFactory: WatchlistEditController.Factory,
) : ViewModel() {

    private val refresh = refreshFactory.create(viewModelScope)

    private val starter = starterFactory.create(viewModelScope, refresh::refreshAll)

    private val edits: WatchlistEditController = editsFactory.create(
        scope = viewModelScope,
        groupFilter = { contentFlows.groupFilter.value },
        refreshAll = refresh::refreshAll,
    )

    /** Was die Merkliste aus den Einstellungen liest; gibt nur bei Änderungen dieses Teils neu aus. */
    private val watchlistSettings = settingsRepository.watchlistSettings

    /**
     * EINE Datenbank-Beobachtung der Merkliste für alle Ableitungen unten (bisher vier):
     * Room führte nach jedem Schreibvorgang dieselbe Abfrage über alle Paare sonst
     * mehrfach aus — bei hunderten Paaren und jeder Aktualisierung spürbar.
     */
    private val watchList: SharedFlow<List<WatchEntity>> = watchRepository.observeWatches()
        .distinctUntilChanged()
        .shareIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), replay = 1)

    /**
     * Live-Kurse je Watch-Id, höchstens zweimal je Sekunde neu. Nur Zeilen (und das offene
     * Aktionsblatt) lesen davon — je über `derivedStateOf` nur den Kurs ihres Paars. Bewusst nicht
     * in [uiState]: ein Tick soll nicht den ganzen Bildschirm neu zusammensetzen.
     */
    val livePrices: StateFlow<Map<Long, LiveQuote>> = livePriceStream.prices

    /** Watch-Ids der Paare, deren Börse gerade per WebSocket liefert (Frische 2 Min., «Live» nur mit allen). */
    val liveWatchIds: StateFlow<Set<Long>> = livePriceStream.liveWatchIds

    /** Paare der angezeigten Merkliste (gewählte Gruppe) für die Live-Kurse. */
    fun setLivePairs(pairs: List<LivePair>) = livePriceStream.setPairs(pairs)

    /** Merkliste zu sehen bzw. verlassen (anderer Tab, App im Hintergrund): Live-Kurse an/aus. */
    fun setLiveVisible(visible: Boolean) = livePriceStream.setVisible(visible)

    override fun onCleared() {
        livePriceStream.setVisible(false)
    }

    /** Noch nicht abgespielter Erst-Moment (Start-Merkliste, allererstes Paar). */
    val addMoment: StateFlow<AddMoment?> = addMoments.pending

    fun consumeAddMoment(moment: AddMoment) = addMoments.consume(moment)

    /** Sortieren nach Spalte («Name ⇅ · Kurs ⇅ · 24h ⇅»); null = eigene Reihenfolge. */
    private val columnSort: StateFlow<ColumnSort?> = watchlistSettings
        .map { ColumnSort.decode(it.columnSort) }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, ColumnSort.decode(settingsRepository.cached.watchlistColumnSort))

    /** Tipp auf eine Spalte: absteigend/aufsteigend/eigene Reihenfolge ([ColumnSort.next]). */
    fun tapColumn(key: SortKey) {
        // Vom gespeicherten Stand aus, nicht vom angezeigten (der kann bei schnellen Tipps noch nachhinken)
        viewModelScope.launch {
            settingsRepository.updateWatchlistColumnSort { ColumnSort.next(ColumnSort.decode(it), key)?.encode() }
        }
    }

    /** Sortieren nach Spalte aus (z. B. beim manuellen Sortieren, das die eigene Reihenfolge zeigt). */
    fun clearColumnSort() {
        if (columnSort.value != null) viewModelScope.launch { settingsRepository.setWatchlistColumnSort(null) }
    }

    /** Chart-Art im Aktionsblatt: zuletzt gewählt, für alle Paare gleich (Standard Kerzen). */
    val sheetChartLine: StateFlow<Boolean> = watchlistSettings
        .map { it.sheetChartLine }
        .stateIn(viewModelScope, SharingStarted.Eagerly, settingsRepository.cached.sheetChartLine)

    fun setSheetChartLine(line: Boolean) {
        viewModelScope.launch { settingsRepository.setSheetChartLine(line) }
    }

    fun dismissGestureHint() {
        viewModelScope.launch { settingsRepository.setGestureHintSeen(true) }
    }

    fun dismissFirstAlarmCard() {
        viewModelScope.launch { settingsRepository.setFirstAlarmCardDismissed(true) }
    }

    /** Öffnen heute zählen (lokaler Kalendertag), für [com.cryptochecker.app.domain.watch.WatchlistOnboarding]. */
    fun recordUsageDay() {
        viewModelScope.launch { settingsRepository.recordUsageDay(java.time.LocalDate.now().toEpochDay()) }
    }

    /**
     * Darstellung aus den Einstellungen ([WatchlistDisplay]), darunter EINE Frische-Schwelle für alles, was
     * «veraltet» zeigt: Status-Pille (Zählung), abgeblasste Zeile, rote Zeit, das Wort «veraltet» und der
     * vorgelesene Satz ([OutdatedRule], wie die Widgets): Schwelle der abgefragten Paare, dreimal das
     * Hintergrund-Intervall, mindestens 15 Minuten ([com.cryptochecker.app.domain.watch.LiveStatus.pollingStaleAfterMillis],
     * beide Modi). Paare am Live-Strom ([liveWatchIds]) gelten je Paar schon nach 2 Minuten als veraltet
     * ([com.cryptochecker.app.domain.watch.LiveStatus.staleAfterMillis]). Früher zählte die Änderung nach einer
     * eigenen Regel — Sehende bekamen dann in der Lücke nur Farbe.
     */
    private val display: StateFlow<WatchlistDisplay> = watchlistSettings
        .map { WatchlistDisplay.of(it) }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WatchlistDisplay.initial(settingsRepository.cached))

    /** Aufschlüsselung des letzten Durchlaufs, per Tipp auf die Dauer. */
    val lastRefreshReport: StateFlow<RefreshReport?> get() = refresh.lastRefreshReport

    /** Ende der Pause je Börse: «Binance · pausiert bis 19:45» in der Zeitzeile ihrer Paare ([LocalExchangePauses]). */
    val exchangePauses: StateFlow<Map<String, Long>> get() = refresh.exchangePauses

    /** App-Start bis zum ersten Bild der Merkliste (zuletzt gemessen), für den Bericht. */
    val appStartMillis: StateFlow<Long?> get() = refresh.appStartMillis

    /** Erstes Bild der Merkliste nach dem Start gemessen (nur lokal gespeichert). */
    fun recordAppStart(millis: Long) = refresh.recordAppStart(millis)

    /** «Basis der %-Änderung» (Änderung, Puls, Aktionsblatt). */
    val changeBasis: StateFlow<ChangeBasis> = watchlistSettings
        .map { it.changeBasis }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, settingsRepository.cached.changeBasis)

    /** Basis und Tagesbeginn, mit denen die gespeicherten Veränderungen gerechnet wurden. */
    val changeStamp: StateFlow<ChangeStamp?> get() = refresh.changeStamp

    /** Gespeicherte Merkliste mit Gruppen, Alarmen und Aktivität ([WatchlistContentFlows]). */
    private val contentFlows: WatchlistContentFlows = WatchlistContentFlows(
        scope = viewModelScope,
        watchList = watchList,
        watchlistSettings = watchlistSettings,
        watchRepository = watchRepository,
        settingsRepository = settingsRepository,
        coinLogoRepository = coinLogoRepository,
        tokenIdentityStore = tokenIdentityStore,
        portfolioAlarmRepository = portfolioAlarmRepository,
        activity = activityRepository.reports,
        renamedGroup = edits::renamedGroup,
    )

    /** Anzahl aktiver Alarme je Paar, für die Anzeige in der Liste (auch im Aktionsblatt des Markt-Tabs). */
    val alarmCounts: StateFlow<Map<Long, Int>> = contentFlows.alarmCounts

    private val content: StateFlow<WatchlistContent> = contentFlows.content

    /** Aktualisierung, Netz, Live-Strom, Umrechnung und Stempel der Änderungen ([WatchlistLiveFlows]). */
    private val liveFlows: WatchlistLiveFlows = WatchlistLiveFlows(
        scope = viewModelScope,
        watchList = watchList,
        columnSort = columnSort,
        watchlistSettings = watchlistSettings,
        currencyConverter = currencyConverter,
        refresh = refresh,
        online = connectivity.online,
        liveExchanges = livePriceStream.liveExchanges,
        liveWatchIds = liveWatchIds,
    )

    private val live: StateFlow<WatchlistLive> = liveFlows.live

    /**
     * Alles, was der Merkliste-Bildschirm liest, in einem Stand — gesammelt mit EINEM
     * collectAsStateWithLifecycle. Die Live-Kurse ([livePrices]) bleiben getrennt.
     */
    val uiState: StateFlow<WatchlistUiState> = combine(content, display, live, starter.state) { c, d, l, s ->
        WatchlistUiState(content = c, display = d, live = l, starter = s)
    }.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000),
        WatchlistUiState(content.value, display.value, live.value, starter.state.value),
    )

    // ---- Start-Auswahl der leeren Merkliste ([WatchlistStarterController])

    /** Nur wenn die leere Merkliste gezeigt wird; höchstens ein Abruf gleichzeitig. */
    fun loadStarterCoins() = starter.loadStarterCoins()

    fun toggleStarter(symbol: String) = starter.toggleStarter(symbol)

    /** «Alle auswählen» bzw. «Keine auswählen». */
    fun toggleAllStarters() = starter.toggleAllStarters()

    /** Die gewählten Start-Coins in der angezeigten Reihenfolge anlegen. */
    fun addSelectedStarters() = starter.addSelectedStarters()

    // ---- Aktualisieren ([WatchlistRefreshController])

    /** «Aktualisieren» im Aktionsblatt: Kurs neu abfragen und den Chart des Paars neu laden. */
    fun refreshOneByUser(watch: WatchEntity) = refresh.refreshOneByUser(watch)

    /** Nach unten ziehen bzw. Knopf oben, mit 15-s-Sperre ([RefreshDebounce]). */
    fun refreshAllByUser(): RefreshDebounce.Decision = refresh.refreshAllByUser()

    /** Ende der letzten vollständigen Aktualisierung, für den Countdown der 15-s-Sperre im Menü. */
    fun lastFullRefreshAt(): Long = refresh.lastFullRefreshAt()

    fun refreshOne(watchId: Long) = refresh.refreshOne(watchId)

    // ---- Änderungen an der Merkliste ([WatchlistEditController])

    fun selectGroup(group: String?) = edits.selectGroup(group)

    fun setNote(watch: WatchEntity, note: String?, onProceed: () -> Unit = {}, cancelled: () -> Unit = {}) = edits.setNote(watch, note, onProceed, cancelled)
    fun setGroup(watch: WatchEntity, group: String?, onProceed: () -> Unit = {}, cancelled: () -> Unit = {}) =
        edits.setGroup(watch, group, onProceed, cancelled)

    /** Mehrfachauswahl › «Gruppe»: alle [ids] in [group] (null = keine Gruppe). */
    fun setGroup(ids: Collection<Long>, group: String?, onDone: (Boolean) -> Unit = {}) = edits.setGroup(ids, group, onDone)

    /** «Gruppe bearbeiten» übernehmen; [oldName] null für eine neue Gruppe. */
    fun saveGroup(oldName: String?, newName: String, memberIds: Set<Long>, onDone: () -> Unit = {}, cancelled: () -> Unit = {}) = edits.saveGroup(oldName, newName, memberIds, onDone, cancelled)

    /** Gruppe löschen: Paare bleiben in der Merkliste; die Ansicht springt auf «Alle». */
    fun deleteGroup(name: String, onDone: () -> Unit = {}, cancelled: () -> Unit = {}) = edits.deleteGroup(name, onDone, cancelled)

    /** Vorschau-Paar aus «Heute auffällig» in die Merkliste ([WatchlistEditController.addPreview]); [onFull]: Merkliste voll. */
    fun addPreview(watch: WatchEntity, onFull: () -> Unit, onDone: (Long?, Boolean) -> Unit) = edits.addPreview(watch, onFull, onDone)

    /** Löschen mit «Rückgängig»; [onDeleted] erst nach dem Löschen (Banner, liefert den Rückgängig-Schlüssel, [RemovedBatch]). */
    fun deleteWithUndo(watch: WatchEntity, onDeleted: () -> Long = { watch.id }) = edits.deleteWithUndo(watch, onDeleted)

    /** «Rückgängig» für [watchId] bzw. [NOT_TRADED_UNDO_KEY] / [SELECTION_UNDO_KEY]; [onFull]: Merkliste voll. */
    fun undoDelete(watchId: Long, onFull: (Int) -> Unit = {}) = edits.undoDelete(watchId, onFull)

    /** «Nicht gehandelte Paare entfernen»; [onDone] bekommt die Zahl der gelöschten Paare. */
    fun deleteNotTradedWithUndo(onDone: (Int) -> Unit) = edits.deleteNotTradedWithUndo(onDone)

    /** Mehrfachauswahl › «Löschen»; [onDone] bekommt die Zahl der gelöschten Paare. */
    fun deleteSelectedWithUndo(ids: Collection<Long>, onDone: (Int) -> Unit) = edits.deleteSelectedWithUndo(ids, onDone)

    /** Mehrfachauswahl › «Favorit»; [onDone] nach dem Schreiben (true = geschrieben). */
    fun setFavorite(ids: Collection<Long>, favorite: Boolean, onDone: (Boolean) -> Unit = {}) = edits.setFavorite(ids, favorite, onDone)

    /** «Deaktivieren» bzw. «Aktivieren» (dann gleich den Kurs holen). */
    fun setDisabled(ids: Collection<Long>, disabled: Boolean, onDone: (Boolean) -> Unit = {}) = edits.setDisabled(ids, disabled, onDone)

    fun deleteAllWithUndo(onDone: (Int) -> Unit = {}) = edits.deleteAllWithUndo(onDone)
    fun setNotificationEnabled(watch: WatchEntity, enabled: Boolean, onDone: (Boolean) -> Unit = {}) =
        edits.setNotificationEnabled(watch, enabled, onDone)

    /** Paar im Sortiermodus verschieben. */
    fun move(watch: WatchEntity, move: WatchMove) = edits.move(watch, move)

    /** Neue Reihenfolge nach dem Ziehen einer Karte. */
    fun reorder(orderedIds: List<Long>) = edits.reorder(orderedIds)

    /** Stern, Aktionen-Menü bzw. nach rechts wischen: Favorit an/aus. */
    fun toggleFavorite(watch: WatchEntity, onDone: (Boolean) -> Unit = {}) = edits.toggleFavorite(watch, onDone)

    /** «Kurs vorlesen»; true, wenn die Sprachausgabe dafür eingeschaltet wurde (kurzer Hinweis im Blatt). */
    fun setTtsEnabled(watch: WatchEntity, enabled: Boolean): Boolean = edits.setTtsEnabled(watch, enabled)

    // ---- Daten für Zeilen und Aktionsblatt ([WatchlistSheetData])

    fun rowSource(watch: WatchEntity): DayChange.RowSource? = sheetData.rowSource(watch)

    /** Watch-Ids, deren letzter Kurs der Mark-Preis statt des Mittelkurses war («Mark» in Zeile und Blatt). */
    val markFallbackIds: StateFlow<Set<Long>> get() = sheetData.markFallbackIds

    fun foreignSparklineSource(watch: WatchEntity): String? = sheetData.foreignSparklineSource(watch)

    fun foreignCandleSource(watch: WatchEntity): String? = sheetData.foreignCandleSource(watch)

    fun cachedSparkline(watch: WatchEntity): List<Double>? = sheetData.cachedSparkline(watch)

    suspend fun sparkline(watch: WatchEntity): List<Double>? = sheetData.sparkline(watch)

    suspend fun satsRate(quote: String): Pair<String, Double>? = sheetData.satsRate(quote)

    suspend fun explain(watch: WatchEntity): WhyReport? = sheetData.explain(watch)

    suspend fun fetchFutures(watch: WatchEntity): com.cryptochecker.app.data.remote.FuturesInfo? = sheetData.fetchFutures(watch)

    suspend fun fetchSpot(watch: WatchEntity): com.cryptochecker.app.domain.spot.SpotData? = sheetData.fetchSpot(watch)

    fun cachedSheetChart(watch: WatchEntity, range: SheetChartRange): SheetChartResult? = sheetData.cachedSheetChart(watch, range)

    suspend fun loadSheetChart(watch: WatchEntity, range: SheetChartRange): SheetChartResult = sheetData.loadSheetChart(watch, range)
}

/** Gemeinsame Währung fürs Sortieren nach Kurs (nur intern, nie angezeigt). */
internal const val SORT_CURRENCY = "CHF"


/** «Rückgängig»-Schlüssel für «Nicht gehandelte Paare entfernen» (Watch-Ids sind immer positiv). */
internal const val NOT_TRADED_UNDO_KEY = -1L

/** «Rückgängig»-Schlüssel für «Löschen» in der Mehrfachauswahl. */
internal const val SELECTION_UNDO_KEY = -2L
