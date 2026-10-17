package com.cryptochecker.app.ui.features.watchlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cryptochecker.app.data.ActivityRepository
import com.cryptochecker.app.data.RefreshStats
import com.cryptochecker.app.data.WatchRepository
import com.cryptochecker.app.data.WatchSnapshot
import com.cryptochecker.app.data.local.model.WatchEntity
import com.cryptochecker.app.data.portfolio.CurrencyConverter
import com.cryptochecker.app.domain.activity.ActivityMonitor
import com.cryptochecker.app.domain.activity.ActivityReport
import com.cryptochecker.app.domain.activity.ActivitySensitivity
import com.cryptochecker.app.domain.convert.CurrencyConversion
import com.cryptochecker.app.domain.refresh.ManualRefresh
import com.cryptochecker.app.domain.refresh.PriceRefresher
import com.cryptochecker.app.domain.refresh.RefreshReport
import com.cryptochecker.app.domain.starter.AddMoment
import com.cryptochecker.app.domain.starter.StarterPairs
import com.cryptochecker.app.domain.watch.AssetKind
import com.cryptochecker.app.domain.watch.ColumnSort
import com.cryptochecker.app.domain.watch.SortKey
import com.cryptochecker.app.domain.watch.UndoSlot
import com.cryptochecker.app.domain.watch.WatchFilter
import com.cryptochecker.app.notification.AppNotifier
import com.cryptochecker.app.settings.deviceRegionLocale
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
import kotlinx.coroutines.sync.Mutex
import javax.inject.Inject

@HiltViewModel
class WatchlistViewModel @Inject constructor(
    internal val watchRepository: WatchRepository,
    internal val priceRefresher: PriceRefresher,
    internal val notifier: AppNotifier,
    internal val widgetUpdater: WidgetUpdater,
    private val scheduler: PriceUpdateScheduler,
    internal val manualRefresh: ManualRefresh,
    internal val refreshStats: RefreshStats,
    connectivity: com.cryptochecker.app.util.ConnectivityMonitor,
    internal val settingsRepository: com.cryptochecker.app.settings.SettingsRepository,
    internal val futuresDataSource: com.cryptochecker.app.data.remote.FuturesDataSource,
    internal val spotDataSource: com.cryptochecker.app.data.remote.SpotDataSource,
    activityRepository: ActivityRepository,
    internal val activityMonitor: ActivityMonitor,
    internal val widgetPrefs: com.cryptochecker.app.widget.WidgetPrefs,
    internal val currencyConverter: CurrencyConverter,
    internal val sparklineRepository: com.cryptochecker.app.data.SparklineRepository,
    internal val starterCoinsRepository: com.cryptochecker.app.data.StarterCoinsRepository,
    internal val addMoments: com.cryptochecker.app.data.AddMoments,
    internal val sheetChartRepository: com.cryptochecker.app.data.SheetChartRepository,
    private val livePriceStream: com.cryptochecker.app.data.live.LivePriceStream,
    internal val coinLogoRepository: com.cryptochecker.app.data.CoinLogoRepository,
    internal val dayReferences: com.cryptochecker.app.domain.refresh.DayReferences,
    internal val ownHourly: com.cryptochecker.app.data.remote.candles.OwnHourlyCandles,
    internal val candlePairs: com.cryptochecker.app.data.remote.candles.CandlePairs,
    /** Namen der Coins je Börse: klar anderer Name als in der Alpha-Liste → kein «α» ([AssetKind.of]). */
    internal val tokenIdentityStore: com.cryptochecker.app.data.TokenIdentityStore,
    /** Nur für [alarmTotal] (Ziel der Glocke): gibt es Portfolio-Alarme? */
    portfolioAlarmRepository: com.cryptochecker.app.data.portfolio.PortfolioAlarmRepository,
) : ViewModel() {

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
     * Aktionsblatt) lesen davon — je über `derivedStateOf` nur den Kurs ihres Paars.
     */
    val livePrices: StateFlow<Map<Long, com.cryptochecker.app.domain.live.LiveQuote>> = livePriceStream.prices

    /** Börsen, deren Live-Strom gerade Kurse liefert («LIVE» in der Status-Pille, Bericht). */
    val liveExchanges: StateFlow<List<String>> = livePriceStream.liveExchanges

    /** Watch-Ids der Paare, deren Börse gerade per WebSocket liefert (Frische 2 Min., «Live» nur mit allen). */
    val liveWatchIds: StateFlow<Set<Long>> = livePriceStream.liveWatchIds

    /** Paare der angezeigten Merkliste (gewählte Gruppe) für die Live-Kurse. */
    fun setLivePairs(pairs: List<com.cryptochecker.app.domain.live.LivePair>) = livePriceStream.setPairs(pairs)

    /** Merkliste zu sehen bzw. verlassen (anderer Tab, App im Hintergrund): Live-Kurse an/aus. */
    fun setLiveVisible(visible: Boolean) = livePriceStream.setVisible(visible)

    override fun onCleared() {
        livePriceStream.setVisible(false)
    }

    /**
     * Coins der Start-Merkliste: sofort aus dem Zwischenspeicher bzw. die
     * Ausweich-Liste, frischere Daten ersetzen sie still ([loadStarterCoins]).
     */
    internal val _starterCoins = MutableStateFlow(starterCoinsRepository.cached(starterRegion()))
    val starterCoins: StateFlow<List<StarterPairs.Coin>> = _starterCoins.asStateFlow()

    internal var starterJob: kotlinx.coroutines.Job? = null
    internal var starterAddJob: kotlinx.coroutines.Job? = null
    /** Prüfung der Binance-Standort-Sperre ([StarterCoinsRepository.probeBinance]); das Hinzufügen wartet darauf. */
    internal var starterProbeJob: kotlinx.coroutines.Job? = null

    /** Region der Startpaare: Geräte-Region, bei Binance-Standort-Sperre «US» (Binance.US). */
    internal fun starterRegion(): String? = starterCoinsRepository.starterRegion(deviceRegionLocale().country)

    /**
     * Logos der Start-Coins geladen (oder Logos aus bzw. nach [STARTER_LOGO_WAIT_MILLIS] aufgegeben)?
     * Bis dahin zeigt die Start-Auswahl statt der Logos einen ruhigen Platzhalter — kein Umspringen
     * von Initialen auf Logos ein paar Sekunden nach dem Installieren.
     */
    internal val _starterLogosReady = MutableStateFlow(!settingsRepository.cached.coinLogos)
    val starterLogosReady: StateFlow<Boolean> = _starterLogosReady.asStateFlow()

    /**
     * Abgewählte Start-Coins (Symbole). Leer = alle ausgewählt (Standard); so sind auch
     * Coins einer frischeren Liste gleich ausgewählt. Siehe [StarterSelection].
     */
    internal val _starterDeselected = MutableStateFlow<Set<String>>(emptySet())
    val starterDeselected: StateFlow<Set<String>> = _starterDeselected.asStateFlow()

    /** Quote der Start-Paare: USDT (Binance), in den USA USD (Coinbase). */
    val starterQuote: String = StarterPairs.startQuote(starterRegion())

    /** Kurse der Start-Coins; [StarterPricesState.loading] solange der Abruf läuft. */
    internal val _starterPrices = MutableStateFlow(StarterPricesState(loading = true, prices = emptyMap()))
    val starterPrices: StateFlow<StarterPricesState> = _starterPrices.asStateFlow()

    /** Läuft das Hinzufügen der Start-Coins gerade? (Knopf gesperrt, kein Doppeltipp) */
    internal val _starterAdding = MutableStateFlow(false)
    val starterAdding: StateFlow<Boolean> = _starterAdding.asStateFlow()

    /** Noch nicht abgespielter Erst-Moment (Start-Merkliste, allererstes Paar). */
    val addMoment: StateFlow<AddMoment?> = addMoments.pending

    fun consumeAddMoment(moment: AddMoment) = addMoments.consume(moment)

    /** Mini-Chart in den Zeilen zeigen (Einstellung, Standard an). */
    val sparklineEnabled: StateFlow<Boolean> = settingsRepository.settings
        .map { it.watchlistSparkline }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), settingsRepository.cached.watchlistSparkline)

    /** Karte «Hier passiert gerade etwas» zeigen (Einstellung, Standard an). */
    val activityCardEnabled: StateFlow<Boolean> = settingsRepository.settings
        .map { it.watchlistActivityCard }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), settingsRepository.cached.watchlistActivityCard)

    /** Sortieren nach Spalte («Name ⇅ · Kurs ⇅ · 24h ⇅»); null = eigene Reihenfolge. */
    val columnSort: StateFlow<ColumnSort?> = settingsRepository.settings
        .map { ColumnSort.decode(it.watchlistColumnSort) }
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

    /**
     * Faktor je Quote-Währung in CHF, nur fürs Sortieren nach Kurs — im Hintergrund, auch ohne
     * eingeschaltete «≈ Umrechnung». Geholt nur, solange nach Kurs sortiert wird (sonst keine
     * Abfrage); danach höchstens alle 60 s neu.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val sortRates: StateFlow<Map<String, Double>> = combine(
        columnSort.map { it?.key == SortKey.PRICE }.distinctUntilChanged(),
        watchList
            .map { list -> list.map { CurrencyConversion.normalize(it.quoteAsset) }.filter { it.isNotEmpty() }.toSet() }
            .distinctUntilChanged(),
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
    private val conversionSetting = settingsRepository.settings
        .map { it.showConverted to it.portfolioCurrency }
        .distinctUntilChanged()

    /** Zielwährung der umgerechneten Kurse; null = ausgeschaltet. */
    val convertTarget: StateFlow<String?> = conversionSetting
        .map { (show, currency) -> currency.takeIf { show } }
        .stateIn(
            viewModelScope, SharingStarted.WhileSubscribed(5_000),
            settingsRepository.cached.let { s -> s.portfolioCurrency.takeIf { s.showConverted } },
        )

    private val convertFxTimesState = MutableStateFlow<Map<String, Long>>(emptyMap())

    /**
     * Je Quote-Währung (Grossbuchstaben) die Abrufzeit des Devisenkurses im Faktor von [convertRates],
     * nur wenn älter als 3 h ([CurrencyConversion.FX_STALE_MILLIS]) — die Zeile zeigt dann «· Kurs 08:15».
     * Aufgefrischt mit [convertRates].
     */
    val convertFxTimes: StateFlow<Map<String, Long>> = convertFxTimesState.asStateFlow()

    /**
     * Faktor je Quote-Währung (Grossbuchstaben) in die Zielwährung, z. B.
     * «USDT» → 0.8. Zuerst aus den Zwischenspeichern, dann frisch; danach
     * höchstens alle 60 s neu — ausser die Menge der Quote-Währungen ändert sich.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val convertRates: StateFlow<Map<String, Double>> = combine(
        conversionSetting,
        watchList
            .map { list -> list.map { CurrencyConversion.normalize(it.quoteAsset) }.filter { it.isNotEmpty() }.toSet() }
            .distinctUntilChanged(),
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

    /**
     * Umbenannte Gruppen (alt → neu): Verschwindet die gewählte Gruppe durch
     * Umbenennen, folgt die Auswahl dem neuen Namen statt auf «Alle» zu springen.
     */
    internal val renamedGroups = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** Ergebnisse «Ungewöhnliche Aktivität» je Paar; abgelaufene Signale filtert die Oberfläche. */
    val activity: StateFlow<Map<Long, ActivityReport>> = activityRepository.reports

    /** Empfindlichkeit «Ungewöhnliche Aktivität»: filtert auch gespeicherte Signale sofort neu. */
    val activitySensitivity: StateFlow<ActivitySensitivity> = settingsRepository.settings
        .map { it.activitySensitivity }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), settingsRepository.cached.activitySensitivity)

    /** Chart-Art im Aktionsblatt: zuletzt gewählt, für alle Paare gleich (Standard Kerzen). */
    val sheetChartLine: StateFlow<Boolean> = settingsRepository.settings
        .map { it.sheetChartLine }
        .stateIn(viewModelScope, SharingStarted.Eagerly, settingsRepository.cached.sheetChartLine)

    fun setSheetChartLine(line: Boolean) {
        viewModelScope.launch { settingsRepository.setSheetChartLine(line) }
    }

    /** Gesten-Hinweis noch zeigen? (bis er weggeklickt wird) */
    val showGestureHint: StateFlow<Boolean> = settingsRepository.settings
        .map { !it.gestureHintSeen }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun dismissGestureHint() {
        viewModelScope.launch { settingsRepository.setGestureHintSeen(true) }
    }

    /**
     * Eine Frische-Schwelle für alles, was «veraltet» zeigt: Status-Pille (Zählung), abgeblasste
     * Zeile, rote Zeit, das Wort «veraltet» und der vorgelesene Satz ([OutdatedRule], wie die
     * Widgets): Schwelle der abgefragten Paare, dreimal das Hintergrund-Intervall, mindestens
     * 15 Minuten ([LiveStatus.pollingStaleAfterMillis], beide Modi). Paare am Live-Strom
     * ([liveWatchIds]) gelten je Paar schon nach 2 Minuten als veraltet ([LiveStatus.staleAfterMillis]).
     * Früher zählte die Änderung nach einer eigenen Regel — Sehende bekamen dann in der Lücke nur Farbe.
     */
    val staleAfterMillis: StateFlow<Long> = settingsRepository.settings
        .map { s -> com.cryptochecker.app.domain.watch.LiveStatus.pollingStaleAfterMillis(s.backgroundIntervalMinutes) }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 45 * 60_000L)

    /** Gleiche Schwelle wie [staleAfterMillis] (eine Regel für Änderung, Zeile und Screenreader). */
    val outdatedAfterMillis: StateFlow<Long> get() = staleAfterMillis

    /**
     * null bis die Datenbank zum ersten Mal geantwortet hat — so zeigt die
     * Liste Platzhalter statt kurz «leer». Eine Quelle für beides, damit
     * «geladen» und Inhalt nie auseinanderlaufen.
     */
    val watchesOrNull: StateFlow<List<WatchEntity>?> = shownList
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * Vorhandene Gruppen und die gewählte (null = «Alle»). Eine Gruppe gibt
     * es, solange mindestens ein Paar sie nutzt; verschwindet die gewählte,
     * gilt wieder «Alle». null, bis Liste und Einstellung geladen sind —
     * so blitzt beim Öffnen nicht kurz die ungefilterte Liste auf.
     */
    val groupFilter: StateFlow<GroupFilter?> = combine(
        watchList,
        settingsRepository.settings.map { it.watchlistGroup }.distinctUntilChanged(),
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
            val renamed = group?.let { renamedGroups[it] }?.takeIf { it in groups }
            settingsRepository.setWatchlistGroup(renamed)
            GroupFilter(groups, renamed, tokens, alpha, kinds)
        } else {
            GroupFilter(groups, group, tokens, alpha, kinds)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Dauer des letzten vollständigen Durchlaufs, für die Kopfzeile. */
    val lastRefreshMillis: StateFlow<Long> = refreshStats.lastDurationMillis

    /** Aufschlüsselung des letzten Durchlaufs, per Tipp auf die Dauer. */
    val lastRefreshReport: StateFlow<RefreshReport?> = refreshStats.lastReport

    /** Gerät hat Internet? Ohne: ruhige Statuszeile «Offline · Stand …», keine Aktualisierung. */
    val online: StateFlow<Boolean> = connectivity.online

    /** App-Start bis zum ersten Bild der Merkliste (zuletzt gemessen), für den Bericht. */
    val appStartMillis: StateFlow<Long?> = refreshStats.appStartMillis

    /** Erstes Bild der Merkliste nach dem Start gemessen (nur lokal gespeichert). */
    fun recordAppStart(millis: Long) = refreshStats.setAppStartMillis(millis)

    /** «Basis der %-Änderung» (Änderung, Puls, Aktionsblatt). */
    val changeBasis: StateFlow<com.cryptochecker.app.domain.watch.ChangeBasis> = settingsRepository.settings
        .map { it.changeBasis }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, settingsRepository.cached.changeBasis)

    /** Zeitraum («24h», «heute») neben der Änderung und Puls zeigen (Einstellungen › %-Änderung). */
    val showChangePeriod: StateFlow<Boolean> = settingsRepository.settings
        .map { it.showChangePeriod }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, settingsRepository.cached.showChangePeriod)

    /** Hauptschalter «Kursmitteilungen»: aus = jede Zeile zeigt die durchgestrichene Glocke. */
    val priceNotificationsOn: StateFlow<Boolean> = settingsRepository.settings
        .map { it.priceNotifications }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, settingsRepository.cached.priceNotifications)

    /** Basis und Tagesbeginn, mit denen die gespeicherten Veränderungen gerechnet wurden. */
    val changeStamp: StateFlow<com.cryptochecker.app.domain.watch.ChangeStamp?> = refreshStats.changeStamp

    val watches: StateFlow<List<WatchEntity>> = shownList
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Alle Alarme, die die Übersicht zeigen würde: Paar-Alarme auch ausgeschaltet, Portfolio-Alarme nur mit
     * eingeschaltetem Portfolio (wie [com.cryptochecker.app.ui.features.alarms.AlarmsOverviewViewModel]); null bis
     * geladen. Entscheidet das Ziel der Glocke ([com.cryptochecker.app.domain.watch.AlarmBellRoute]).
     */
    val alarmTotal: StateFlow<Int?> = combine(
        watchRepository.observeAllAlarms(),
        portfolioAlarmRepository.observeAll(),
        settingsRepository.settings,
    ) { pairAlarms, portfolioAlarms, settings ->
        pairAlarms.size + if (settings.portfolioEnabled) portfolioAlarms.size else 0
    }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Anzahl aktiver Alarme je Paar, für die Anzeige in der Liste. */
    val alarmCounts: StateFlow<Map<Long, Int>> = watchRepository.observeAllAlarms()
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
    val latestAlarmTrigger: StateFlow<Long?> = watchRepository.observeAllAlarms()
        .map { alarms -> alarms.maxOfOrNull { it.alarm.lastTriggeredAt } ?: 0L }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * Letzte Auslösung je Paar (Epoch-ms; auch abgeschaltete Einmal-Alarme, die nach dem Auslösen
     * aus sind) — für «Alarm ausgelöst» im Screenreader-Satz der Zeile ([com.cryptochecker.app.util.A11ySpeech.firedRecently]).
     */
    val alarmFiredAt: StateFlow<Map<Long, Long>> = watchRepository.observeAllAlarms()
        .map { alarms ->
            alarms.filter { it.alarm.lastTriggeredAt > 0 }
                .groupBy { it.alarm.watchId }
                .mapValues { (_, list) -> list.maxOf { it.alarm.lastTriggeredAt } }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /**
     * Läuft, solange die per Knopf (App) angestossene Aktualisierung arbeitet oder die
     * aus dem Widget-Knopf bei WorkManager wartet oder arbeitet.
     */
    val refreshing: StateFlow<Boolean> = combine(
        manualRefresh.running,
        scheduler.observeManualRefreshRunning(),
    ) { app, widget -> app || widget }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), manualRefresh.running.value)

    internal val undoMutex = Mutex()

    /**
     * Zuletzt Gelöschtes: ein Paar (Schlüssel = seine Id), alle nicht gehandelten
     * ([NOT_TRADED_UNDO_KEY]) oder die Mehrfachauswahl ([SELECTION_UNDO_KEY]).
     */
    internal val undo = UndoSlot<Long, List<WatchSnapshot>>()
}

/** Gemeinsame Währung fürs Sortieren nach Kurs (nur intern, nie angezeigt). */
internal const val SORT_CURRENCY = "CHF"

/** Umrechnungsfaktoren der Merkliste höchstens so oft neu holen. */
private const val CONVERT_REFRESH_MILLIS = 60_000L

/** «Rückgängig»-Schlüssel für «Nicht gehandelte Paare entfernen» (Watch-Ids sind immer positiv). */
internal const val NOT_TRADED_UNDO_KEY = -1L

/** «Rückgängig»-Schlüssel für «Löschen» in der Mehrfachauswahl. */
internal const val SELECTION_UNDO_KEY = -2L
