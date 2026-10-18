package com.cryptochecker.app.ui.features.watchlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cryptochecker.app.data.ActivityRepository
import com.cryptochecker.app.data.AddMoments
import com.cryptochecker.app.data.CoinLogoRepository
import com.cryptochecker.app.data.RefreshStats
import com.cryptochecker.app.data.SheetChartRepository
import com.cryptochecker.app.data.StarterCoinsRepository
import com.cryptochecker.app.data.TokenIdentityStore
import com.cryptochecker.app.data.WatchMove
import com.cryptochecker.app.data.WatchRepository
import com.cryptochecker.app.data.live.LivePriceStream
import com.cryptochecker.app.data.local.model.WatchEntity
import com.cryptochecker.app.data.portfolio.CurrencyConverter
import com.cryptochecker.app.data.portfolio.PortfolioAlarmRepository
import com.cryptochecker.app.domain.activity.ActivityReport
import com.cryptochecker.app.domain.activity.WhyReport
import com.cryptochecker.app.domain.convert.CurrencyConversion
import com.cryptochecker.app.domain.live.LivePair
import com.cryptochecker.app.domain.live.LiveQuote
import com.cryptochecker.app.domain.refresh.ManualRefresh
import com.cryptochecker.app.domain.refresh.PriceRefresher
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
import com.cryptochecker.app.domain.watch.WatchFilter
import com.cryptochecker.app.notification.AppNotifier
import com.cryptochecker.app.settings.SettingsRepository
import com.cryptochecker.app.util.ConnectivityMonitor
import com.cryptochecker.app.widget.WidgetPrefs
import com.cryptochecker.app.widget.WidgetUpdater
import com.cryptochecker.app.work.PriceUpdateScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel der Merkliste. Setzt sich aus Teilen mit je eigener Aufgabe zusammen, die ihren Zustand privat
 * halten und nur lesbare StateFlows und Funktionen anbieten: Start-Auswahl ([WatchlistStarterController]),
 * schreibende Aktionen ([WatchlistEditController]), Aktualisieren ([WatchlistRefreshController]) und die
 * Lese-Abfragen für Zeilen und Aktionsblatt ([WatchlistSheetData]). Hier stehen die Ableitungen aus Datenbank
 * und Einstellungen; der Bildschirm sammelt sie gebündelt als [uiState].
 */
@HiltViewModel
class WatchlistViewModel @Inject constructor(
    watchRepository: WatchRepository,
    priceRefresher: PriceRefresher,
    notifier: AppNotifier,
    widgetUpdater: WidgetUpdater,
    scheduler: PriceUpdateScheduler,
    manualRefresh: ManualRefresh,
    refreshStats: RefreshStats,
    connectivity: ConnectivityMonitor,
    private val settingsRepository: SettingsRepository,
    activityRepository: ActivityRepository,
    widgetPrefs: WidgetPrefs,
    private val currencyConverter: CurrencyConverter,
    starterCoinsRepository: StarterCoinsRepository,
    private val addMoments: AddMoments,
    sheetChartRepository: SheetChartRepository,
    private val livePriceStream: LivePriceStream,
    coinLogoRepository: CoinLogoRepository,
    /** Namen der Coins je Börse: klar anderer Name als in der Alpha-Liste → kein «α» ([AssetKind.of]). */
    private val tokenIdentityStore: TokenIdentityStore,
    /** Nur für [alarmTotal] (Ziel der Glocke): gibt es Portfolio-Alarme? */
    portfolioAlarmRepository: PortfolioAlarmRepository,
    /** Daten für Aktionsblatt und Zeilen (Mini-Chart, Herkunft, Futures, Spot, Chart). */
    private val sheetData: WatchlistSheetData,
) : ViewModel() {

    private val refresh = WatchlistRefreshController(
        scope = viewModelScope,
        priceRefresher = priceRefresher,
        manualRefresh = manualRefresh,
        refreshStats = refreshStats,
        sheetChartRepository = sheetChartRepository,
        scheduler = scheduler,
    )

    private val starter = WatchlistStarterController(
        scope = viewModelScope,
        starterCoinsRepository = starterCoinsRepository,
        settingsRepository = settingsRepository,
        coinLogoRepository = coinLogoRepository,
        watchRepository = watchRepository,
        addMoments = addMoments,
        widgetUpdater = widgetUpdater,
        refreshAll = refresh::refreshAll,
    )

    private val edits = WatchlistEditController(
        scope = viewModelScope,
        watchRepository = watchRepository,
        settingsRepository = settingsRepository,
        priceRefresher = priceRefresher,
        notifier = notifier,
        widgetUpdater = widgetUpdater,
        widgetPrefs = widgetPrefs,
        addMoments = addMoments,
        groupFilter = { groupFilter.value },
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
     * Gespeicherte Merkliste OHNE Live-Kurse: ein WebSocket-Tick soll nicht die ganze Liste neu
     * ausgeben (das setzte bei jedem Tick den ganzen Bildschirm neu zusammen). Den Live-Kurs legt
     * jede Zeile selbst darüber ([livePrices], [LiveOverlay.apply]); gespeichert wird alle 10 s.
     */
    private val shownList: SharedFlow<List<WatchEntity>> = watchList

    /**
     * Live-Kurse je Watch-Id, höchstens zweimal je Sekunde neu. Nur Zeilen (und das offene
     * Aktionsblatt) lesen davon — je über `derivedStateOf` nur den Kurs ihres Paars. Bewusst nicht
     * in [uiState]: ein Tick soll nicht den ganzen Bildschirm neu zusammensetzen.
     */
    val livePrices: StateFlow<Map<Long, LiveQuote>> = livePriceStream.prices

    /** Börsen, deren Live-Strom gerade Kurse liefert («LIVE» in der Status-Pille, Bericht). */
    private val liveExchanges: StateFlow<List<String>> = livePriceStream.liveExchanges

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
        val next = ColumnSort.next(columnSort.value, key)
        viewModelScope.launch { settingsRepository.setWatchlistColumnSort(next?.encode()) }
    }

    /** Sortieren nach Spalte aus (z. B. beim manuellen Sortieren, das die eigene Reihenfolge zeigt). */
    fun clearColumnSort() {
        if (columnSort.value != null) viewModelScope.launch { settingsRepository.setWatchlistColumnSort(null) }
    }

    /** Quote-Währungen der Merkliste (Grossbuchstaben), für die Umrechnungsfaktoren unten. */
    private val quoteCurrencies = watchList
        .map { list -> list.map { CurrencyConversion.normalize(it.quoteAsset) }.filter { it.isNotEmpty() }.toSet() }
        .distinctUntilChanged()

    /**
     * Faktor je Quote-Währung in CHF, nur fürs Sortieren nach Kurs — im Hintergrund, auch ohne
     * eingeschaltete «≈ Umrechnung». Geholt nur, solange nach Kurs sortiert wird (sonst keine
     * Abfrage); danach höchstens alle 60 s neu.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val sortRates: StateFlow<Map<String, Double>> = combine(
        columnSort.map { it?.key == SortKey.PRICE }.distinctUntilChanged(),
        quoteCurrencies,
    ) { byPrice, quotes -> byPrice to quotes }
        .transformLatest { (byPrice, quotes) ->
            val wanted = quotes.filterNot { CurrencyConversion.sameCurrency(it, SORT_CURRENCY) }
            if (!byPrice || wanted.isEmpty()) {
                emit(emptyMap())
                return@transformLatest
            }
            var known = currencyConverter.cachedRates(wanted, SORT_CURRENCY)
            emit(known)
            while (true) {
                val fresh = runCatching { currencyConverter.rates(wanted, SORT_CURRENCY) }
                    .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
                    .getOrDefault(emptyMap())
                known = known + fresh
                emit(known)
                delay(CONVERT_REFRESH_MILLIS)
            }
        }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** «≈ Umrechnung» eingeschaltet und Zielwährung — ändert sich nur mit den Einstellungen. */
    private val conversionSetting = watchlistSettings
        .map { it.showConverted to it.portfolioCurrency }
        .distinctUntilChanged()

    private val convertFxTimesState = MutableStateFlow<Map<String, Long>>(emptyMap())

    /**
     * Je Quote-Währung (Grossbuchstaben) die Abrufzeit des Devisenkurses im Faktor von [convertRates],
     * nur wenn älter als 3 h ([CurrencyConversion.FX_STALE_MILLIS]) — die Zeile zeigt dann «· Kurs 08:15».
     * Aufgefrischt mit [convertRates].
     */
    private val convertFxTimes: StateFlow<Map<String, Long>> = convertFxTimesState.asStateFlow()

    /**
     * Faktor je Quote-Währung (Grossbuchstaben) in die Zielwährung, z. B.
     * «USDT» → 0.8. Zuerst aus den Zwischenspeichern, dann frisch; danach
     * höchstens alle 60 s neu — ausser die Menge der Quote-Währungen ändert sich.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val convertRates: StateFlow<Map<String, Double>> = combine(
        conversionSetting,
        quoteCurrencies,
    ) { setting, quotes -> setting to quotes }
        .transformLatest { (setting, quotes) ->
            val (show, target) = setting
            val wanted = quotes.filterNot { CurrencyConversion.sameCurrency(it, target) }
            if (!show || wanted.isEmpty()) {
                convertFxTimesState.value = emptyMap()
                emit(emptyMap())
                return@transformLatest
            }
            var known = currencyConverter.cachedRates(wanted, target)
            convertFxTimesState.value = currencyConverter.staleFxTimes(wanted, target)
            emit(known)
            while (true) {
                val fresh = runCatching { currencyConverter.rates(wanted, target) }
                    .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
                    .getOrDefault(emptyMap())
                // Fehlt ein Kurs diesmal, bleibt der letzte bekannte stehen
                known = known + fresh
                // Alter der Devisenkurse nach dem Auffrischen (älter als 3 h: Uhrzeit in der Zeile)
                convertFxTimesState.value = currencyConverter.staleFxTimes(wanted, target)
                emit(known)
                delay(CONVERT_REFRESH_MILLIS)
            }
        }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** Ergebnisse «Ungewöhnliche Aktivität» je Paar; abgelaufene Signale filtert die Oberfläche. */
    private val activity: StateFlow<Map<Long, ActivityReport>> = activityRepository.reports

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

    /**
     * null bis die Datenbank zum ersten Mal geantwortet hat — so zeigt die
     * Liste Platzhalter statt kurz «leer». Eine Quelle für beides, damit
     * «geladen» und Inhalt nie auseinanderlaufen.
     */
    private val watchesOrNull: StateFlow<List<WatchEntity>?> = shownList
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * Vorhandene Gruppen und die gewählte (null = «Alle»). Eine Gruppe gibt
     * es, solange mindestens ein Paar sie nutzt; verschwindet die gewählte,
     * gilt wieder «Alle». null, bis Liste und Einstellung geladen sind —
     * so blitzt beim Öffnen nicht kurz die ungefilterte Liste auf.
     */
    private val groupFilter: StateFlow<GroupFilter?> = combine(
        watchList,
        watchlistSettings.map { it.group }.distinctUntilChanged(),
        coinLogoRepository.tradFiPairs,
        coinLogoRepository.alphaSymbols,
        coinLogoRepository.alphaNames,
    ) { list, group, tradFiPairs, alphaSymbols, alphaNames ->
        val groups = groupsOf(list)
        val groupNames = list.map { it.groupName }
        val tokens = WatchFilter.showsTokens(groupNames)
        // Art je Paar (Symbol vor der Glocke, «Alpha»-Chip); die Listen liegen gespeichert vor
        val kinds = list.associate {
            it.id to it.assetKind(tradFiPairs, alphaSymbols, alphaNames, tokenIdentityStore.pairName(it.marketKey, it.baseAsset))
        }
        val alphaFlags = list.map { kinds[it.id] == AssetKind.ALPHA }
        val alpha = WatchFilter.showsAlpha(alphaFlags)
        // «FAV» gibt es immer (auch ohne Favoriten); «Token» nur, solange es TradFi- und andere Paare
        // gibt, «Alpha» nur mit Alpha- und anderen Paaren
        if (!WatchFilter.isAvailable(group, groupNames, alphaFlags)) {
            val renamed = group?.let { edits.renamedGroup(it) }?.takeIf { it in groups }
            settingsRepository.setWatchlistGroup(renamed)
            GroupFilter(groups, renamed, tokens, alpha, kinds)
        } else {
            GroupFilter(groups, group, tokens, alpha, kinds)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Aufschlüsselung des letzten Durchlaufs, per Tipp auf die Dauer. */
    val lastRefreshReport: StateFlow<RefreshReport?> get() = refresh.lastRefreshReport

    /** Gerät hat Internet? Ohne: ruhige Statuszeile «Offline · Stand …», keine Aktualisierung. */
    private val online: StateFlow<Boolean> = connectivity.online

    /** App-Start bis zum ersten Bild der Merkliste (zuletzt gemessen), für den Bericht. */
    val appStartMillis: StateFlow<Long?> get() = refresh.appStartMillis

    /** Erstes Bild der Merkliste nach dem Start gemessen (nur lokal gespeichert). */
    fun recordAppStart(millis: Long) = refresh.recordAppStart(millis)

    /** «Basis der %-Änderung» (Änderung, Puls, Aktionsblatt). */
    val changeBasis: StateFlow<ChangeBasis> = watchlistSettings
        .map { it.changeBasis }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, settingsRepository.cached.changeBasis)

    /** Zeitraum («24h», «heute») neben der Änderung und Puls zeigen (Einstellungen › %-Änderung). */
    val showChangePeriod: StateFlow<Boolean> = watchlistSettings
        .map { it.showChangePeriod }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, settingsRepository.cached.showChangePeriod)

    /** Basis und Tagesbeginn, mit denen die gespeicherten Veränderungen gerechnet wurden. */
    val changeStamp: StateFlow<ChangeStamp?> get() = refresh.changeStamp

    /** Alle Alarme der Datenbank (Paar-Alarme), für die Zählungen unten. */
    private val pairAlarms = watchRepository.observeAllAlarms()

    /**
     * Alle Alarme, die die Übersicht zeigen würde: Paar-Alarme auch ausgeschaltet, Portfolio-Alarme nur mit
     * eingeschaltetem Portfolio (wie [com.cryptochecker.app.ui.features.alarms.AlarmsOverviewViewModel]); null bis
     * geladen. Entscheidet das Ziel der Glocke ([com.cryptochecker.app.domain.watch.AlarmBellRoute]).
     */
    private val alarmTotal: StateFlow<Int?> = combine(
        pairAlarms,
        portfolioAlarmRepository.observeAll(),
        watchlistSettings.map { it.portfolioEnabled }.distinctUntilChanged(),
    ) { pairs, portfolioAlarms, portfolioEnabled ->
        pairs.size + if (portfolioEnabled) portfolioAlarms.size else 0
    }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Anzahl aktiver Alarme je Paar, für die Anzeige in der Liste (auch im Aktionsblatt des Markt-Tabs). */
    val alarmCounts: StateFlow<Map<Long, Int>> = pairAlarms
        .map { alarms ->
            alarms.filter { it.alarm.enabled }
                .groupingBy { it.alarm.watchId }
                .eachCount()
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /**
     * Letzte Auslösung irgendeines Alarms (Epoch-ms, 0 = nie); null bis zum ersten Stand aus
     * der Datenbank — für den kurzen Puls der Glocke ([com.cryptochecker.app.domain.watch.AlarmPulse]).
     */
    private val latestAlarmTrigger: StateFlow<Long?> = pairAlarms
        .map { alarms -> alarms.maxOfOrNull { it.alarm.lastTriggeredAt } ?: 0L }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * Letzte Auslösung je Paar (Epoch-ms; auch abgeschaltete Einmal-Alarme, die nach dem Auslösen
     * aus sind) — für «Alarm ausgelöst» im Screenreader-Satz der Zeile ([com.cryptochecker.app.util.A11ySpeech.firedRecently]).
     */
    private val alarmFiredAt: StateFlow<Map<Long, Long>> = pairAlarms
        .map { alarms ->
            alarms.filter { it.alarm.lastTriggeredAt > 0 }
                .groupBy { it.alarm.watchId }
                .mapValues { (_, list) -> list.maxOf { it.alarm.lastTriggeredAt } }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** Gespeicherte Merkliste mit Gruppen, Alarmen und Aktivität in einem Stand. */
    private val content: StateFlow<WatchlistContent> = combine(
        combine(watchesOrNull, groupFilter, alarmCounts, alarmTotal, alarmFiredAt) { watches, filter, counts, total, firedAt ->
            WatchlistContent.Initial.copy(
                watches = watches,
                groupFilter = filter,
                alarmCounts = counts,
                alarmTotal = total,
                alarmFiredAt = firedAt,
            )
        },
        latestAlarmTrigger,
        activity,
    ) { base, latest, reports -> base.copy(latestAlarmTrigger = latest, activity = reports) }
        .stateIn(
            viewModelScope, SharingStarted.WhileSubscribed(5_000),
            WatchlistContent(
                watches = watchesOrNull.value,
                groupFilter = groupFilter.value,
                alarmCounts = alarmCounts.value,
                alarmTotal = alarmTotal.value,
                alarmFiredAt = alarmFiredAt.value,
                latestAlarmTrigger = latestAlarmTrigger.value,
                activity = activity.value,
            ),
        )

    /** Aktualisierung, Netz, Live-Strom, Umrechnung und Stempel der Änderungen in einem Stand. */
    private val live: StateFlow<WatchlistLive> = combine(
        combine(refresh.refreshing, online, liveExchanges, liveWatchIds) { refreshing, online, exchanges, ids ->
            WatchlistLive(
                refreshing = refreshing,
                online = online,
                liveExchanges = exchanges,
                liveWatchIds = ids,
                convertRates = emptyMap(),
                convertFxTimes = emptyMap(),
                sortRates = emptyMap(),
                changeStamp = null,
            )
        },
        convertRates,
        convertFxTimes,
        sortRates,
        refresh.changeStamp,
    ) { base, rates, fxTimes, sorting, stamp ->
        base.copy(convertRates = rates, convertFxTimes = fxTimes, sortRates = sorting, changeStamp = stamp)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), currentLive())

    private fun currentLive() = WatchlistLive(
        refreshing = refresh.refreshing.value,
        online = online.value,
        liveExchanges = liveExchanges.value,
        liveWatchIds = liveWatchIds.value,
        convertRates = convertRates.value,
        convertFxTimes = convertFxTimes.value,
        sortRates = sortRates.value,
        changeStamp = refresh.changeStamp.value,
    )

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

    /** Würde «Aktualisieren» jetzt etwas tun? Sonst ist der Menüpunkt grau. */
    fun canRefreshNow(): Boolean = refresh.canRefreshNow()

    fun refreshOne(watchId: Long) = refresh.refreshOne(watchId)

    // ---- Änderungen an der Merkliste ([WatchlistEditController])

    fun selectGroup(group: String?) = edits.selectGroup(group)

    fun setNote(watch: WatchEntity, note: String?) = edits.setNote(watch, note)

    fun setGroup(watch: WatchEntity, group: String?) = edits.setGroup(watch, group)

    /** Mehrfachauswahl › «Gruppe»: alle [ids] in [group] (null = keine Gruppe). */
    fun setGroup(ids: Collection<Long>, group: String?) = edits.setGroup(ids, group)

    /** «Gruppe bearbeiten» übernehmen; [oldName] null für eine neue Gruppe. */
    fun saveGroup(oldName: String?, newName: String, memberIds: Set<Long>) = edits.saveGroup(oldName, newName, memberIds)

    /** Gruppe löschen: Paare bleiben in der Merkliste; die Ansicht springt auf «Alle». */
    fun deleteGroup(name: String) = edits.deleteGroup(name)

    /** Vorschau-Paar aus «Heute auffällig» in die Merkliste ([WatchlistEditController.addPreview]). */
    fun addPreview(watch: WatchEntity, onDone: (id: Long?, added: Boolean) -> Unit) = edits.addPreview(watch, onDone)

    /** Löschen mit «Rückgängig» ([undoDelete] mit der Watch-Id). */
    fun deleteWithUndo(watch: WatchEntity) = edits.deleteWithUndo(watch)

    /** «Rückgängig» für [watchId] bzw. [NOT_TRADED_UNDO_KEY] / [SELECTION_UNDO_KEY]. */
    fun undoDelete(watchId: Long) = edits.undoDelete(watchId)

    /** «Nicht gehandelte Paare entfernen»; [onDone] bekommt die Zahl der gelöschten Paare. */
    fun deleteNotTradedWithUndo(onDone: (Int) -> Unit) = edits.deleteNotTradedWithUndo(onDone)

    /** Mehrfachauswahl › «Löschen»; [onDone] bekommt die Zahl der gelöschten Paare. */
    fun deleteSelectedWithUndo(ids: Collection<Long>, onDone: (Int) -> Unit) = edits.deleteSelectedWithUndo(ids, onDone)

    /** Mehrfachauswahl › «Favorit». */
    fun setFavorite(ids: Collection<Long>, favorite: Boolean) = edits.setFavorite(ids, favorite)

    /** «Deaktivieren» bzw. «Aktivieren» (dann gleich den Kurs holen). */
    fun setDisabled(ids: Collection<Long>, disabled: Boolean) = edits.setDisabled(ids, disabled)

    fun deleteAll() = edits.deleteAll()

    fun setNotificationEnabled(watch: WatchEntity, enabled: Boolean) = edits.setNotificationEnabled(watch, enabled)

    /** Paar im Sortiermodus verschieben. */
    fun move(watch: WatchEntity, move: WatchMove) = edits.move(watch, move)

    /** Neue Reihenfolge nach dem Ziehen einer Karte. */
    fun reorder(orderedIds: List<Long>) = edits.reorder(orderedIds)

    /** Stern, Aktionen-Menü bzw. nach rechts wischen: Favorit an/aus. */
    fun toggleFavorite(watch: WatchEntity) = edits.toggleFavorite(watch)

    /** «Kurs vorlesen»; true, wenn die Sprachausgabe dafür eingeschaltet wurde (kurzer Hinweis im Blatt). */
    fun setTtsEnabled(watch: WatchEntity, enabled: Boolean): Boolean = edits.setTtsEnabled(watch, enabled)

    // ---- Daten für Zeilen und Aktionsblatt ([WatchlistSheetData])

    fun rowSource(watch: WatchEntity): DayChange.RowSource? = sheetData.rowSource(watch)

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

/** Umrechnungsfaktoren der Merkliste höchstens so oft neu holen. */
private const val CONVERT_REFRESH_MILLIS = 60_000L

/** «Rückgängig»-Schlüssel für «Nicht gehandelte Paare entfernen» (Watch-Ids sind immer positiv). */
internal const val NOT_TRADED_UNDO_KEY = -1L

/** «Rückgängig»-Schlüssel für «Löschen» in der Mehrfachauswahl. */
internal const val SELECTION_UNDO_KEY = -2L
