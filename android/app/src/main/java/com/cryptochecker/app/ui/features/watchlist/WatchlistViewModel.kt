package com.cryptochecker.app.ui.features.watchlist

import com.cryptochecker.app.domain.watch.WatchFilter
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cryptochecker.app.data.RefreshStats
import com.cryptochecker.app.data.WatchMove
import com.cryptochecker.app.data.WatchRepository
import com.cryptochecker.app.data.ActivityRepository
import com.cryptochecker.app.domain.activity.ActivityMonitor
import com.cryptochecker.app.domain.activity.ActivityReport
import com.cryptochecker.app.domain.activity.ActivitySensitivity
import com.cryptochecker.app.domain.activity.WhyReport
import com.cryptochecker.app.data.local.model.WatchEntity
import com.cryptochecker.app.domain.model.MarketInfo
import com.cryptochecker.app.domain.starter.AddMoment
import com.cryptochecker.app.domain.starter.StarterPairs
import com.cryptochecker.app.domain.starter.StarterPrice
import com.cryptochecker.app.domain.starter.StarterSelection
import com.cryptochecker.app.data.WatchSnapshot
import com.cryptochecker.app.domain.watch.SheetChartRange
import com.cryptochecker.app.domain.watch.SheetChartResult
import com.cryptochecker.app.domain.watch.UndoSlot
import com.cryptochecker.app.domain.watch.isNotTraded
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.cryptochecker.app.settings.deviceRegionLocale
import com.cryptochecker.marketdata.config.MarketsConfig
import com.cryptochecker.marketdata.model.CurrencyPairInfo
import com.cryptochecker.app.domain.refresh.ManualRefresh
import com.cryptochecker.app.domain.refresh.PriceRefresher
import com.cryptochecker.app.domain.refresh.RefreshDebounce
import com.cryptochecker.app.domain.refresh.RefreshReport
import com.cryptochecker.app.data.portfolio.CurrencyConverter
import com.cryptochecker.app.domain.convert.CurrencyConversion
import com.cryptochecker.app.notification.AppNotifier
import com.cryptochecker.app.widget.WidgetUpdater
import com.cryptochecker.app.work.PriceUpdateScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class WatchlistViewModel @Inject constructor(
    private val watchRepository: WatchRepository,
    private val priceRefresher: PriceRefresher,
    private val notifier: AppNotifier,
    private val widgetUpdater: WidgetUpdater,
    private val scheduler: PriceUpdateScheduler,
    private val manualRefresh: ManualRefresh,
    private val refreshStats: RefreshStats,
    connectivity: com.cryptochecker.app.util.ConnectivityMonitor,
    private val settingsRepository: com.cryptochecker.app.settings.SettingsRepository,
    private val futuresDataSource: com.cryptochecker.app.data.remote.FuturesDataSource,
    activityRepository: ActivityRepository,
    private val activityMonitor: ActivityMonitor,
    private val widgetPrefs: com.cryptochecker.app.widget.WidgetPrefs,
    private val currencyConverter: CurrencyConverter,
    private val sparklineRepository: com.cryptochecker.app.data.SparklineRepository,
    private val starterCoinsRepository: com.cryptochecker.app.data.StarterCoinsRepository,
    private val addMoments: com.cryptochecker.app.data.AddMoments,
    private val sheetChartRepository: com.cryptochecker.app.data.SheetChartRepository,
    private val livePriceStream: com.cryptochecker.app.data.live.LivePriceStream,
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
    private val _starterCoins = MutableStateFlow(starterCoinsRepository.cached(deviceRegionLocale().country))
    val starterCoins: StateFlow<List<StarterPairs.Coin>> = _starterCoins.asStateFlow()

    private var starterJob: kotlinx.coroutines.Job? = null
    private var starterAddJob: kotlinx.coroutines.Job? = null

    /** Nur wenn die leere Merkliste gezeigt wird; höchstens ein Abruf gleichzeitig. */
    fun loadStarterCoins() {
        if (starterJob?.isActive == true) return
        starterJob = viewModelScope.launch {
            // Kurse gleich für die sofort gezeigte Liste (parallel zur frischeren Liste)
            val pricesJob = launch { loadStarterPrices(_starterCoins.value) }
            starterCoinsRepository.fresh(deviceRegionLocale().country)?.let { fresh ->
                val changed = fresh.map { it.symbol } != _starterCoins.value.map { it.symbol }
                _starterCoins.value = fresh
                if (changed) {
                    pricesJob.cancel()
                    loadStarterPrices(fresh)
                }
            }
        }
    }

    /**
     * Abgewählte Start-Coins (Symbole). Leer = alle ausgewählt (Standard); so sind auch
     * Coins einer frischeren Liste gleich ausgewählt. Siehe [StarterSelection].
     */
    private val _starterDeselected = MutableStateFlow<Set<String>>(emptySet())
    val starterDeselected: StateFlow<Set<String>> = _starterDeselected.asStateFlow()

    fun toggleStarter(symbol: String) {
        _starterDeselected.value = StarterSelection.toggle(_starterDeselected.value, symbol)
    }

    /** «Alle auswählen» bzw. «Keine auswählen». */
    fun toggleAllStarters() {
        _starterDeselected.value = StarterSelection.toggleAll(_starterCoins.value, _starterDeselected.value)
    }

    /** Quote der Start-Paare: USDT (Binance), in den USA USD (Coinbase). */
    val starterQuote: String = StarterPairs.pairFor("BTC", deviceRegionLocale().country).quote

    /** Kurse der Start-Coins; [StarterPricesState.loading] solange der Abruf läuft. */
    private val _starterPrices = MutableStateFlow(StarterPricesState(loading = true, prices = emptyMap()))
    val starterPrices: StateFlow<StarterPricesState> = _starterPrices.asStateFlow()

    private suspend fun loadStarterPrices(coins: List<StarterPairs.Coin>) {
        _starterPrices.value = _starterPrices.value.copy(loading = true)
        val prices = starterCoinsRepository.prices(coins.map { it.symbol }, deviceRegionLocale().country)
        // Fehler: Zeilen ohne Kurs (alte Kurse bleiben, falls die Coins dieselben sind)
        _starterPrices.value = StarterPricesState(
            loading = false,
            prices = prices.ifEmpty { _starterPrices.value.prices },
        )
    }

    /** Läuft das Hinzufügen der Start-Coins gerade? (Knopf gesperrt, kein Doppeltipp) */
    private val _starterAdding = MutableStateFlow(false)
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

    /** Zwischengespeicherter 24-Stunden-Verlauf, ohne Netz — für den ersten Frame einer Zeile. */
    fun cachedSparkline(baseAsset: String): List<Double>? = sparklineRepository.cached(baseAsset)

    /**
     * 24-Stunden-Verlauf eines Assets (Schlusskurse, aufsteigend); null bei Fehler.
     * Wird nur für sichtbare Zeilen angefragt, siehe WatchlistScreen.
     */
    suspend fun sparkline(baseAsset: String): List<Double>? = sparklineRepository.closes(baseAsset)

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
                emit(emptyMap())
                return@transformLatest
            }
            var known = currencyConverter.cachedRates(wanted, target)
            emit(known)
            while (true) {
                val fresh = runCatching { currencyConverter.rates(wanted, target) }
                    .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
                    .getOrDefault(emptyMap())
                // Fehlt ein Kurs diesmal, bleibt der letzte bekannte stehen
                known = known + fresh
                emit(known)
                delay(CONVERT_REFRESH_MILLIS)
            }
        }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /**
     * «1 CHF = 1’234 Sats» im Aktionsblatt eines Bitcoin-Paars: Umrechnungswährung (Hauptwährung)
     * und Faktor Quote → diese Währung — zuerst aus den Zwischenspeichern, sonst frisch; null ohne Kurs.
     */
    suspend fun satsRate(quote: String): Pair<String, Double>? {
        val target = settingsRepository.current().portfolioCurrency
        val rate = currencyConverter.cachedRate(quote, target) ?: currencyConverter.rate(quote, target)
        return rate?.let { target to it }
    }

    /**
     * Umbenannte Gruppen (alt → neu): Verschwindet die gewählte Gruppe durch
     * Umbenennen, folgt die Auswahl dem neuen Namen statt auf «Alle» zu springen.
     */
    private val renamedGroups = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** Ergebnisse «Ungewöhnliche Aktivität» je Paar; abgelaufene Signale filtert die Oberfläche. */
    val activity: StateFlow<Map<Long, ActivityReport>> = activityRepository.reports

    /** Empfindlichkeit «Ungewöhnliche Aktivität»: filtert auch gespeicherte Signale sofort neu. */
    val activitySensitivity: StateFlow<ActivitySensitivity> = settingsRepository.settings
        .map { it.activitySensitivity }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), settingsRepository.cached.activitySensitivity)

    /** Daten für «Warum bewegt sich das?»; null, wenn gar nichts geladen werden konnte. */
    suspend fun explain(watch: WatchEntity): WhyReport? =
        runCatching { activityMonitor.explain(watch) }
            .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
            .getOrNull()

    /** Funding Rate und Open Interest für Perpetuals; null ohne Daten. */
    suspend fun fetchFutures(watch: WatchEntity): com.cryptochecker.app.data.remote.FuturesInfo? =
        runCatching {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { futuresDataSource.fetch(watch) }
        }
            // Blatt geschlossen: Abbruch weiterreichen statt als «keine Daten» zu schlucken
            .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
            .getOrNull()

    /** Chart im Aktionsblatt aus dem Zwischenspeicher (ohne Netz); null = laden. */
    fun cachedSheetChart(watch: WatchEntity, range: SheetChartRange): SheetChartResult? =
        sheetChartRepository.cached(watch, range)

    /** Chart im Aktionsblatt: Kerzen wie das Einzel-Widget, abseits des Main-Threads. */
    suspend fun loadSheetChart(watch: WatchEntity, range: SheetChartRange): SheetChartResult =
        sheetChartRepository.load(watch, range)

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
     * Ab diesem Alter gilt ein Kurs als veraltet (abgeblasst): gut das
     * Doppelte des eingestellten Intervalls, mindestens fünf Minuten.
     */
    val staleAfterMillis: StateFlow<Long> = settingsRepository.settings
        .map { s ->
            val intervalMs = if (s.liveService) s.liveIntervalSeconds * 1_000L
            else s.backgroundIntervalMinutes * 60_000L
            maxOf(intervalMs * 5 / 2, 5 * 60_000L)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 40 * 60_000L)

    /**
     * Ab diesem Alter steht in der Zeile «veraltet» (ohne Fehler beim letzten Abruf):
     * im Live-Modus nach 2 Minuten (die Merkliste ist sichtbar, die App also vorne — mit
     * Live-Dienst ist das der Live-Modus), sonst dreimal das eingestellte Intervall, mindestens
     * 15 Minuten. Das Abblassen ([staleAfterMillis]) bleibt wie bisher.
     */
    val outdatedAfterMillis: StateFlow<Long> = settingsRepository.settings
        .map { s ->
            com.cryptochecker.app.domain.refresh.OutdatedRule.afterMillis(
                s.liveService, s.liveIntervalSeconds, s.backgroundIntervalMinutes, live = s.liveService
            )
        }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 45 * 60_000L)

    /**
     * Start-Merkliste: Paare der gewählten Coins anlegen (Binance …/USDT, in den USA
     * Coinbase …/USD), genau wie «Hinzufügen» (WatchRepository.addWatch, gleiche
     * Standardwerte, aber Kurs-Benachrichtigung aus). Vorhandene Paare werden nicht doppelt angelegt. Danach gleich
     * aktualisieren. Einstellungen (z. B. Umrechnung) bleiben unverändert.
     */
    private fun addStarter(symbols: List<String>) {
        // Doppeltipp: kein zweiter Durchlauf, der doppelt anlegt oder den Moment verwirft
        if (starterAddJob?.isActive == true || symbols.isEmpty()) return
        _starterAdding.value = true
        starterAddJob = viewModelScope.launch {
            try {
                addStarterPairs(symbols)
            } finally {
                _starterAdding.value = false
            }
        }
    }

    /** Die gewählten Start-Coins in der angezeigten Reihenfolge anlegen. */
    fun addSelectedStarters() {
        addStarter(StarterSelection.selected(_starterCoins.value, _starterDeselected.value))
    }

    private suspend fun addStarterPairs(symbols: List<String>) {
        val region = deviceRegionLocale().country
        val wanted = symbols.map { symbol ->
            StarterPairs.pairFor(symbol, region) { key -> MarketsConfig.MARKETS[key]?.name }
        }
        val existing = watchRepository.getWatches().map { Triple(it.marketKey, it.baseAsset, it.quoteAsset) }
        val toAdd = StarterPairs.missing(wanted, existing)
        if (toAdd.isEmpty()) return
        // Erst-Moment vor dem Speichern bereitstellen: Zeilen, Häkchen, Haptik und Banner
        addMoments.postStarter(toAdd.map { AddMoment.Added(it.marketKey, it.base, it.quote, "${it.base}/${it.quote}") })
        var added = 0
        for (pair in toAdd) {
            val id = watchRepository.addWatch(
                MarketInfo(key = pair.marketKey, name = pair.marketName),
                CurrencyPairInfo(pair.base, pair.quote, pair.pairId),
                // Ohne Kurs-Benachrichtigung: keine Dauer-Meldung, keine Berechtigungsabfrage
                notificationEnabled = false,
            )
            if (id != null) added++
        }
        if (added > 0) {
            widgetUpdater.updateAll()
            refreshAll()
        } else {
            // Nichts gespeichert: den bereitgestellten Moment verwerfen
            addMoments.pending.value?.let(addMoments::consume)
        }
    }

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
    ) { list, group ->
        val groups = groupsOf(list)
        // «FAV» gibt es immer (auch ohne Favoriten)
        if (group != null && !WatchFilter.isFavorites(group) && group !in groups) {
            val renamed = renamedGroups[group]?.takeIf { it in groups }
            settingsRepository.setWatchlistGroup(renamed)
            GroupFilter(groups, renamed)
        } else GroupFilter(groups, group)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun selectGroup(group: String?) {
        viewModelScope.launch { settingsRepository.setWatchlistGroup(group) }
    }

    /** «Zum Portfolio hinzufügen» nur, wenn der Portfolio-Bereich eingeschaltet ist. */
    val portfolioEnabled: StateFlow<Boolean> = settingsRepository.settings
        .map { it.portfolioEnabled }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), settingsRepository.cached.portfolioEnabled)

    fun setNote(watch: WatchEntity, note: String?) {
        viewModelScope.launch { watchRepository.setNote(watch.id, note) }
    }

    fun setGroup(watch: WatchEntity, group: String?) {
        viewModelScope.launch {
            watchRepository.setGroup(watch.id, group)
            widgetUpdater.updateAll()
        }
    }

    /**
     * «Gruppe bearbeiten» übernehmen (ein Vorgang): [oldName] = bisheriger
     * Name, null für eine neue Gruppe. Eine neue Gruppe ohne Paare entsteht
     * gar nicht; eine bestehende ohne Paare verschwindet.
     */
    fun saveGroup(oldName: String?, newName: String, memberIds: Set<Long>) {
        val name = newName.trim()
        if (name.isEmpty()) return
        if (oldName == null && memberIds.isEmpty()) return
        viewModelScope.launch {
            val renamed = oldName != null && oldName != name
            if (renamed && memberIds.isNotEmpty()) renamedGroups[oldName] = name
            watchRepository.applyGroupEdit(oldName, name, memberIds)
            if (renamed) widgetPrefs.replaceGroup(oldName, name.takeIf { memberIds.isNotEmpty() })
            widgetUpdater.updateAll()
        }
    }

    /** Gruppe löschen: Paare bleiben in der Merkliste; die Ansicht springt auf «Alle». */
    fun deleteGroup(name: String) {
        viewModelScope.launch {
            renamedGroups.remove(name)
            if (groupFilter.value?.selected == name) settingsRepository.setWatchlistGroup(null)
            watchRepository.clearGroup(name)
            widgetPrefs.replaceGroup(name, null)
            widgetUpdater.updateAll()
        }
    }

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

    /** «Basis der %-Änderung» (Pille, Puls, Aktionsblatt). */
    val changeBasis: StateFlow<com.cryptochecker.app.domain.watch.ChangeBasis> = settingsRepository.settings
        .map { it.changeBasis }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, settingsRepository.cached.changeBasis)

    /** Zeitraum («24h», «heute») neben Pille und Puls zeigen (Einstellungen › %-Änderung). */
    val showChangePeriod: StateFlow<Boolean> = settingsRepository.settings
        .map { it.showChangePeriod }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, settingsRepository.cached.showChangePeriod)

    /** Basis und Tagesbeginn, mit denen die gespeicherten Veränderungen gerechnet wurden. */
    val changeStamp: StateFlow<com.cryptochecker.app.domain.watch.ChangeStamp?> = refreshStats.changeStamp

    val watches: StateFlow<List<WatchEntity>> = shownList
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

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
     * Läuft, solange die per Knopf (App) angestossene Aktualisierung arbeitet oder die
     * aus dem Widget-Knopf bei WorkManager wartet oder arbeitet.
     */
    val refreshing: StateFlow<Boolean> = combine(
        manualRefresh.running,
        scheduler.observeManualRefreshRunning(),
    ) { app, widget -> app || widget }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), manualRefresh.running.value)

    // Beim Öffnen wird bewusst NICHT automatisch aktualisiert: Die Liste zeigt
    // die gespeicherten Kurse mit Zeitstempel, nachgeladen wird per Knopf,
    // durch den Live-Dienst oder die Hintergrund-Aktualisierung.

    /**
     * Nach unten ziehen bzw. Knopf oben: direkt in einer App-weiten Coroutine
     * ([ManualRefresh]), nicht über WorkManager — läuft auch nach dem Schliessen der
     * App zu Ende. Gesperrt, solange eine läuft oder die letzte keine 15 s her ist
     * ([RefreshDebounce]); dann zeigt die Merkliste kurz «Gerade aktualisiert».
     */
    fun refreshAllByUser(): RefreshDebounce.Decision =
        manualRefresh.request(otherRunning = refreshing.value)

    /** Ohne 15-s-Sperre, z. B. gleich nach dem Hinzufügen neuer Paare. */
    private fun refreshAll() {
        if (refreshing.value) return
        manualRefresh.requestUnguarded()
    }

    fun refreshOne(watchId: Long) {
        viewModelScope.launch { priceRefresher.refreshOne(watchId) }
    }

    /**
     * Löschen per Wischen bzw. Aktionen-Blatt (ohne Rückfrage), mit «Rückgängig»: Das Paar wird sofort
     * wirklich gelöscht (Alarme, Meldungen und Widgets sehen es nicht mehr), vorher
     * aber vollständig festgehalten ([WatchSnapshot]). [undoDelete] legt es mit
     * derselben Id und denselben Alarm-Ids wieder an. Nur die letzte Löschung lässt
     * sich zurückholen; Löschen und Zurückholen laufen nacheinander ([undoMutex]).
     */
    fun deleteWithUndo(watch: WatchEntity) {
        viewModelScope.launch {
            undoMutex.withLock {
                val snapshot = watchRepository.snapshot(watch.id) ?: return@withLock
                undo.put(watch.id, listOf(snapshot))
                notifier.cancelPrice(watch.id)
                notifier.cancelActivity(watch.id)
                watchRepository.deleteWatch(watch.id)
                widgetUpdater.updateAll()
            }
        }
    }

    /** «Rückgängig» für [watchId]; tut nichts, wenn inzwischen etwas anderes gelöscht wurde. */
    fun undoDelete(watchId: Long) {
        viewModelScope.launch {
            undoMutex.withLock {
                val snapshots = undo.take(watchId) ?: return@withLock
                var any = false
                for (snapshot in snapshots) {
                    if (!watchRepository.restore(snapshot)) continue
                    any = true
                    val restored = snapshot.watch
                    // Dauer-Meldung wie vorher wieder zeigen (mit dem letzten bekannten Kurs)
                    if (restored.notificationEnabled && restored.lastPrice != null && !restored.isNotTraded) {
                        notifier.showPrice(restored, ongoing = true)
                    }
                }
                if (any) widgetUpdater.updateAll()
            }
        }
    }

    /**
     * «Nicht gehandelte Paare entfernen» (Überlaufmenü, nach Rückfrage): alle Paare, die ihre
     * Börse nicht mehr führt, samt Alarmen löschen — wie [deleteWithUndo] festgehalten, damit
     * «Rückgängig» ([undoDelete] mit [NOT_TRADED_UNDO_KEY]) alle zurückholt.
     * [onDone] bekommt die Zahl der gelöschten Paare (0 = nichts geschehen).
     */
    fun deleteNotTradedWithUndo(onDone: (Int) -> Unit) {
        viewModelScope.launch {
            val count = undoMutex.withLock {
                val ids = watchRepository.getWatches().filter { it.isNotTraded }.map { it.id }
                val snapshots = ids.mapNotNull { watchRepository.snapshot(it) }
                if (snapshots.isEmpty()) return@withLock 0
                undo.put(NOT_TRADED_UNDO_KEY, snapshots)
                snapshots.forEach {
                    notifier.cancelPrice(it.watch.id)
                    notifier.cancelActivity(it.watch.id)
                }
                watchRepository.deleteWatches(snapshots.map { it.watch.id })
                widgetUpdater.updateAll()
                snapshots.size
            }
            onDone(count)
        }
    }

    private val undoMutex = Mutex()

    /** Zuletzt Gelöschtes: ein Paar (Schlüssel = seine Id) oder alle nicht gehandelten ([NOT_TRADED_UNDO_KEY]). */
    private val undo = UndoSlot<Long, List<WatchSnapshot>>()

    fun deleteAll() {
        viewModelScope.launch {
            // Erst die Benachrichtigungen wegräumen, danach die Einträge —
            // sonst fehlen hinterher die Ids, um sie zu schließen.
            notifier.cancelAllPrices(watchRepository.getWatches().map { it.id })
            watchRepository.deleteAllWatches()
            widgetUpdater.updateAll()
        }
    }

    fun setNotificationEnabled(watch: WatchEntity, enabled: Boolean) {
        viewModelScope.launch {
            watchRepository.setNotificationEnabled(watch.id, enabled)
            if (enabled) {
                // Einschalten ist eine bewusste Handlung: einmal zeigen und den
                // Bezugspunkt für die Melde-Schwelle auf den aktuellen Kurs setzen.
                watchRepository.getWatch(watch.id)?.let { updated ->
                    notifier.showPrice(updated, ongoing = true)
                    watchRepository.setNotifiedPrice(updated.id, updated.lastPrice)
                }
            } else {
                notifier.cancelPrice(watch.id)
                watchRepository.setNotifiedPrice(watch.id, null)
            }
        }
    }

    /** Paar im Sortiermodus verschieben. */
    fun move(watch: WatchEntity, move: WatchMove) {
        viewModelScope.launch {
            // In einer gefilterten Ansicht nur unter den Paaren der Gruppe
            watchRepository.move(watch.id, move, groupFilter.value?.selected)
            widgetUpdater.updateAll()
        }
    }

    /** Neue Reihenfolge nach dem Ziehen einer Karte. */
    fun reorder(orderedIds: List<Long>) {
        viewModelScope.launch {
            watchRepository.reorder(orderedIds)
            widgetUpdater.updateAll()
        }
    }

    /** Stern, Aktionen-Menü bzw. nach rechts wischen: Favorit an/aus. */
    fun toggleFavorite(watch: WatchEntity) {
        viewModelScope.launch {
            watchRepository.setFavorite(watch.id, !watch.favorite)
            widgetUpdater.updateAll()
        }
    }

    fun setTtsEnabled(watch: WatchEntity, enabled: Boolean) {
        viewModelScope.launch { watchRepository.setTtsEnabled(watch.id, enabled) }
    }
}

/** Gruppennamen der Paare, alphabetisch. */
internal fun groupsOf(watches: List<WatchEntity>): List<String> =
    watches.mapNotNull { it.groupName }.distinct().sortedWith(String.CASE_INSENSITIVE_ORDER)

/** Kurse der Start-Merkliste: [loading] = Abruf läuft; fehlende Coins haben keinen Kurs. */
data class StarterPricesState(val loading: Boolean, val prices: Map<String, StarterPrice>)

/** Gruppen der Merkliste und die aktuelle Auswahl (null = «Alle»). */
data class GroupFilter(val groups: List<String>, val selected: String?)

/** Umrechnungsfaktoren der Merkliste höchstens so oft neu holen. */
private const val CONVERT_REFRESH_MILLIS = 60_000L

/** «Rückgängig»-Schlüssel für «Nicht gehandelte Paare entfernen» (Watch-Ids sind immer positiv). */
internal const val NOT_TRADED_UNDO_KEY = -1L
