package com.cryptochecker.app.domain.refresh

import com.cryptochecker.app.data.MarketRepository
import com.cryptochecker.app.data.RefreshStats
import com.cryptochecker.app.data.ErrorWrite
import com.cryptochecker.app.data.PriceWrite
import com.cryptochecker.app.data.SparklineRepository
import com.cryptochecker.app.data.portfolio.FxRateSource
import com.cryptochecker.app.domain.watch.ChangeBasis
import com.cryptochecker.app.domain.watch.ChangeBasisMath
import com.cryptochecker.app.domain.watch.ChangeStamp
import com.cryptochecker.app.domain.watch.DayChange
import com.cryptochecker.app.domain.watch.isNotTraded
import com.cryptochecker.app.data.WatchRepository
import com.cryptochecker.app.data.local.model.AlarmCondition
import com.cryptochecker.app.data.local.model.AlarmEntity
import com.cryptochecker.app.data.local.model.convertCurrency
import com.cryptochecker.app.data.portfolio.CurrencyConverter
import com.cryptochecker.app.data.remote.VolumeDataSource
import com.cryptochecker.app.data.remote.VolumeSpike
import com.cryptochecker.app.data.remote.NearExtremeDataSource
import com.cryptochecker.app.data.remote.WindowRanges
import com.cryptochecker.app.domain.alarm.NearExtreme
import com.cryptochecker.app.data.local.model.WatchEntity
import com.cryptochecker.app.domain.activity.ActivityMonitor
import com.cryptochecker.app.domain.gas.GasAlertChecker
import com.cryptochecker.app.domain.alarm.AlarmEvaluator
import com.cryptochecker.app.domain.alarm.DerivativesAlarm
import com.cryptochecker.app.domain.alarm.DerivativesAlarmData
import com.cryptochecker.app.domain.alarm.QuietHours
import com.cryptochecker.app.domain.model.MarketInfo
import com.cryptochecker.app.notification.AppNotifier
import com.cryptochecker.app.settings.AppSettings
import com.cryptochecker.app.settings.SettingsRepository
import com.cryptochecker.app.tts.SpokenText
import com.cryptochecker.app.tts.TtsSpeaker
import com.cryptochecker.app.widget.PortfolioSnapshotUpdater
import com.cryptochecker.app.widget.LiveWidgetGate
import com.cryptochecker.app.widget.WidgetUpdater
import com.cryptochecker.app.domain.activity.ActivityAnalysisGate
import com.cryptochecker.app.domain.live.LiveCoverage
import com.cryptochecker.app.domain.live.LiveExchange
import com.cryptochecker.app.domain.live.LiveQuote
import com.cryptochecker.app.domain.live.LiveRules
import com.cryptochecker.app.util.AppVisibility
import com.cryptochecker.app.util.ConnectivityMonitor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.cryptochecker.app.domain.model.BulkTickers
import com.cryptochecker.marketdata.model.FuturesContractType
import com.cryptochecker.marketdata.model.SimpleTicker
import com.cryptochecker.marketdata.model.Ticker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import timber.log.Timber
import kotlin.math.abs
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Holt die Kurse aller beobachteten Paare und stößt danach alles an, was daran hängt:
 * Benachrichtigungen, Alarme, Sprachansagen und Widgets.
 *
 * Wird sowohl vom Vordergrunddienst als auch vom WorkManager aufgerufen, deshalb
 * verhindert eine Mutex, dass zwei Durchläufe gleichzeitig arbeiten.
 */
@Singleton
class PriceRefresher @Inject constructor(
    private val watchRepository: WatchRepository,
    private val marketRepository: MarketRepository,
    private val settingsRepository: SettingsRepository,
    private val alarmEvaluator: AlarmEvaluator,
    private val notifier: AppNotifier,
    private val ttsSpeaker: TtsSpeaker,
    private val spokenText: SpokenText,
    private val widgetUpdater: WidgetUpdater,
    private val refreshStats: RefreshStats,
    private val volumeDataSource: VolumeDataSource,
    private val activityMonitor: ActivityMonitor,
    private val gasAlertChecker: GasAlertChecker,
    private val currencyConverter: CurrencyConverter,
    private val portfolioSnapshotUpdater: PortfolioSnapshotUpdater,
    private val nearExtremeDataSource: NearExtremeDataSource,
    /** 24-h-Bezug aus Kerzen — nur Ausweich-Weg, wenn der Ticker keinen 24-h-Wert liefert. */
    private val sparklineRepository: SparklineRepository,
    /** Ohne Netz keine Aktualisierung (keine Fehlerzustände, siehe [OfflineGate]). */
    private val connectivity: ConnectivityMonitor,
    /** Paare mit frischem Live-Kurs (WebSocket, Merkliste offen) lässt die REST-Abfrage aus. */
    private val liveCoverage: LiveCoverage,
    /** Funding/Open Interest für die Futures-Alarme (je Paar höchstens alle 5 Min.). */
    private val derivativesAlarmData: DerivativesAlarmData,
    /** Live-Takte zeichnen die Widgets nur über dieses Tor (Bildschirm aus: aufschieben). */
    private val liveWidgetGate: LiveWidgetGate,
) {
    private val mutex = Mutex()

    private val _fullRefreshRunning = MutableStateFlow(false)

    /** true, solange eine vollständige Aktualisierung arbeitet (gleich welcher Herkunft). */
    val fullRefreshRunning: StateFlow<Boolean> = _fullRefreshRunning.asStateFlow()

    /** Läuft unabhängig vom Aufrufer weiter (App-weit, ein Singleton). */
    private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Läuft komplett auf [Dispatchers.IO]: Aus dem ViewModel aufgerufen lief
     * bisher alles — Antwort lesen, JSON zerlegen — auf dem Main-Thread.
     *
     * @param awaitPortfolioSnapshot true im WorkManager-Job: Die Momentaufnahme für das
     * Portfolio-Widget wird im Job abgewartet, nicht in einem losgelösten Job — sonst kann
     * Android den Prozess beenden, bevor sie fertig ist.
     * @param deferWidgetsWhenScreenOff true im Live-Dienst: Bei ausgeschaltetem Bildschirm
     * werden die Widgets nicht bei jedem Takt neu gezeichnet, sondern einmal, sobald er
     * wieder an ist ([LiveWidgetGate]) — auch das Portfolio-Widget (die Momentaufnahme wird
     * trotzdem aufgenommen). Alarme und Meldungen laufen normal.
     */
    suspend fun refreshAll(
        awaitPortfolioSnapshot: Boolean = false,
        deferWidgetsWhenScreenOff: Boolean = false,
    ): RefreshSummary {
        // Kein Netz: gar nicht erst versuchen — die Paare behalten Kurs und Zustand, die Liste
        // zeigt «Offline · Stand …». Kommt das Netz zurück, aktualisiert ManualRefresh einmal.
        if (!connectivity.isOnline()) {
            Timber.i("Aktualisierung übersprungen: offline")
            return RefreshSummary()
        }
        val summary = refreshAllGuarded(deferWidgetsWhenScreenOff)
        // Ungewöhnliche Aktivität: erst nach gespeicherten Kursen und gezeichneten
        // Widgets, ausserhalb der Sperre — bremst den nächsten Durchlauf nicht,
        // Fehler bleiben still. Prüft jedes Paar höchstens alle 10 Minuten.
        // Eigener Hintergrund-Job: Die Aktualisierung (und ihr Spinner) wartet
        // nicht auf die Auswertung, die bis zu 20 s dauern kann.
        // Nur mit Abnehmer: Meldung eingeschaltet oder App sichtbar (Karte, ⚡, «Warum»).
        activityScope.launch {
            val alerts = runCatching { settingsRepository.current().activityAlerts }.getOrDefault(false)
            if (ActivityAnalysisGate.shouldRun(alertsEnabled = alerts, appVisible = AppVisibility.visible)) {
                activityMonitor.analyzeAll()
            }
        }
        // Gas-Alarm (#167): eigener Job, höchstens alle 10 Minuten, nur wenn eingestellt
        activityScope.launch { runCatching { gasAlertChecker.checkIfDue() } }
        // Portfolio-Widget: Momentaufnahme auch ohne geöffnete App (nur mit Widget, Fehler still)
        if (awaitPortfolioSnapshot) {
            // Fehler bleiben still (refresh() fängt sie selbst), Abbruch nicht
            portfolioSnapshotUpdater.refreshIfWidgets(deferWidgetsWhenScreenOff)
        } else {
            activityScope.launch { runCatching { portfolioSnapshotUpdater.refreshIfWidgets(deferWidgetsWhenScreenOff) } }
        }
        return summary
    }

    private suspend fun refreshAllGuarded(deferWidgets: Boolean): RefreshSummary = mutex.withLock {
        _fullRefreshRunning.value = true
        try {
            refreshOnIo(deferWidgets)
        } finally {
            _fullRefreshRunning.value = false
        }
    }

    private suspend fun refreshOnIo(deferWidgets: Boolean): RefreshSummary =
        withContext(Dispatchers.IO) {
            try {
                refreshAllLocked(deferWidgets)
            } catch (ex: CancellationException) {
                throw ex
            } catch (ex: Exception) {
                // Sichtbar machen statt still zu schlucken: Sonst bleibt in der
                // App die alte Dauer stehen und niemand merkt, dass es scheitert.
                refreshStats.setLastReport(
                    RefreshReport(
                        at = System.currentTimeMillis(),
                        totalMillis = 0,
                        pairs = 0,
                        networkMillis = 0,
                        markets = emptyList(),
                        aborted = listOfNotNull(ex.javaClass.simpleName, ex.message).joinToString(": "),
                    )
                )
                throw ex
            }
        }

    private suspend fun refreshAllLocked(deferWidgets: Boolean): RefreshSummary {
        val startedAt = System.currentTimeMillis()

        val settings = settingsRepository.current()
        val allWatches = watchRepository.getWatches()

        if (allWatches.isEmpty()) {
            refreshStats.setLastRefresh(System.currentTimeMillis() - startedAt)
            liveWidgetGate.drawAll(deferWidgets)
            return RefreshSummary(
                durationMillis = System.currentTimeMillis() - startedAt
            )
        }

        // %-Basis: rollend (Ticker, Kerzen nur als Ausweich-Weg) oder seit Tagesbeginn (immer Kerzen)
        val changeStamp = ChangeBasisMath.stamp(settings.changeBasis, startedAt)

        // Live-Kurse (WebSocket, Merkliste offen): Paare mit frischem Kurs aus dem Strom diesmal
        // auslassen — sie sind aktueller als jede Abfrage. Nur rollende Basis mit gleichem Stempel
        // (Tages-Basen brauchen Kerzen) und Börsen mit gleitendem 24-h-Wert (LiveRules.skipRest).
        val stampUnchanged = refreshStats.changeStamp.value == changeStamp
        val rollingBasis = !settings.changeBasis.isDay
        val liveIds = allWatches.filter { watch ->
            LiveRules.skipRest(
                liveCoverage.lastTickAt(watch.id), startedAt, rollingBasis, stampUnchanged,
                LiveExchange.fromMarketKey(watch.marketKey)?.rollingChange == true,
            )
        }.mapTo(HashSet()) { it.id }
        // Volumen-, Funding- und Open-Interest-Alarme hängen nicht am Live-Kurs (applyLive prüft sie
        // nicht): solche Paare weiter per REST abfragen, sonst bliebe der Alarm bei offener Merkliste stumm
        if (liveIds.isNotEmpty()) {
            watchRepository.getAllEnabledAlarms()
                .filter { it.condition == AlarmCondition.VOLUME_SPIKE || it.condition.isDerivatives }
                .forEach { liveIds.remove(it.watchId) }
        }
        val watches = allWatches.filterNot { it.id in liveIds }
        if (watches.isEmpty()) {
            // Alles kommt live: Kurse sind aktuell — Zeit (Widget-Kopfzeile) wie nach einer Aktualisierung
            Timber.i("Aktualisierung: alle %d Paare live", allWatches.size)
            refreshStats.setLastRefresh(refreshStats.lastDurationMillis.value)
            return RefreshSummary(durationMillis = System.currentTimeMillis() - startedAt)
        }
        val dayStart = changeStamp.dayStart.takeIf { settings.changeBasis.isDay }

        // Bezüge (Kerzen) parallel zu den Kursen für Paare, die beim letzten Mal Kerzen brauchten
        // (rollend: Ticker ohne 24-h-Wert; Tages-Basen: alle); der Rest nach den Kursen.
        sparklineRepository.awaitRestored()
        val startedKeys = HashSet<Pair<String, String>>()
        val earlyWatches = if (dayStart != null) watches else watches.filter { it.id in candleWatchIds }
        val earlyLoads = startDayReferenceLoads(earlyWatches, startedKeys, dayStart)

        // 1) Netz: alle Börsen gleichzeitig. Je Börse erst die Massenabfrage,
        //    was dort fehlt, parallel einzeln. Früher lief das strikt
        //    nacheinander — Börse für Börse, Paar für Paar.
        //    Pausierte Börsen (zu viele Anfragen, wiederholte Zeitüberschreitungen) bleiben
        //    diesmal aussen vor: keine Anfrage, ihre Paare behalten den letzten Kurs.
        val backoff = refreshStats.backoffStates().toMutableMap()
        val (pausedGroups, activeGroups) = watches.groupBy { it.marketKey }.entries
            .partition { ExchangeBackoff.isPaused(backoff[it.key], startedAt) }
        val pausedIds = pausedGroups.flatMap { group -> group.value.map { it.id } }.toSet()
        val fetched = HashMap<Long, Fetched>()
        val activeReports = coroutineScope {
            activeGroups
                .map { group -> async { fetchGroup(group.value, settings.includeRollingFutures) } }
                .awaitAll()
        }.map { (results, fetchedGroup) ->
            fetched.putAll(results)
            // Ergebnis je Börse → Pause beginnen, verlängern oder (nach Erfolg) aufheben
            val key = fetchedGroup.marketKey
            val state = ExchangeBackoff.next(
                backoff[key], ExchangeBackoff.outcome(fetchedGroup.failures, fetchedGroup.report.updated),
                System.currentTimeMillis(), fetchedGroup.retryAfterMillis,
            )
            if (state == null) backoff.remove(key) else backoff[key] = state
            if (ExchangeBackoff.isPaused(state, System.currentTimeMillis())) {
                fetchedGroup.report.copy(pausedUntil = state?.pausedUntil, pauseReason = state?.reason)
            } else {
                fetchedGroup.report
            }
        }
        val pausedReports = pausedGroups.map { (key, group) ->
            MarketRefresh(
                name = group.first().marketName,
                millis = 0,
                pairs = group.size,
                updated = 0,
                pausedUntil = backoff[key]?.pausedUntil,
                pauseReason = backoff[key]?.reason,
            )
        }
        refreshStats.setBackoffStates(backoff)
        val groupReports = activeReports + pausedReports
        val activeWatches = watches.filter { it.id !in pausedIds }
        val lateLoads = startDayReferenceLoads(
            watchesNeedingCandles(watches, fetched, settings.changeBasis, remember = true), startedKeys, dayStart
        )
        val dayLoads = earlyLoads + lateLoads
        awaitDayReferenceLoads(dayLoads)
        val networkMillis = System.currentTimeMillis() - startedAt

        // 2) Auswerten: erst alle Kurse in EINEM Datenbank-Vorgang speichern,
        //    dann Alarme, Benachrichtigungen und Ansagen.
        // Paare pausierter Börsen nicht anfassen: kein Fehler, letzter Kurs bleibt
        val processed = processResults(activeWatches, fetched, settings, dayStart)
        // Neue Basis oder neuer Tag: alte Werte von Paaren ohne neuen Kurs gehören nicht mehr dazu
        // (ohne Stempel: Werte von vorher, also rollend)
        val previousStamp = refreshStats.changeStamp.value ?: ChangeStamp(ChangeBasis.ROLLING_24H, 0L)
        if (previousStamp != changeStamp) watchRepository.clearChanges(processed.failedIds + pausedIds)
        // Mit dieser Basis (und diesem Tagesbeginn) gerechnet — die Anzeige prüft das
        refreshStats.setChangeStamp(changeStamp)
        // Tages-Basen: Bezüge, die nach dem Warten noch kamen, gleich nachtragen statt «—» bis zum nächsten Mal
        if (dayStart != null) fillLateDayChanges(processed.missingDayChange, dayLoads, changeStamp, deferWidgets)

        // Uhrzeit und Dauer ZUERST speichern, dann die Widgets zeichnen —
        // sonst zeigt die Widget-Kopfzeile neue Kurse mit der alten Uhrzeit.
        val duration = System.currentTimeMillis() - startedAt
        // Nur wenn mindestens ein Kurs kam: Ohne Verbindung bleibt im Widget die
        // Zeit der letzten ERFOLGREICHEN Aktualisierung stehen.
        if (processed.failed < processed.checked) refreshStats.setLastRefresh(duration)

        val widgetStartedAt = System.currentTimeMillis()
        // Live-Dienst bei ausgeschaltetem Bildschirm: nur merken, beim Einschalten nachziehen
        val drawWidgets = liveWidgetGate.drawAll(deferWidgets)
        val widgetMillis = if (drawWidgets) System.currentTimeMillis() - widgetStartedAt else null

        val report = RefreshReport(
            at = System.currentTimeMillis(),
            totalMillis = duration,
            pairs = watches.size,
            networkMillis = networkMillis,
            markets = groupReports,
            dbMillis = processed.dbMillis,
            effectsMillis = processed.effectsMillis,
            alarms = processed.alarms,
            notifications = processed.notificationsShown,
            widgetMillis = widgetMillis,
        )
        Timber.i("Aktualisierung: %s", report)
        refreshStats.setLastReport(report)

        return RefreshSummary(
            checked = processed.checked,
            failed = processed.failed,
            alarmsTriggered = processed.alarms,
            durationMillis = duration
        )
    }

    /**
     * Holt die Kurse aller Paare einer Börse; Schlüssel ist die Watch-Id.
     * Dazu der Eintrag für den Bericht: was geklappt hat und wie lange es dauerte.
     */
    private suspend fun fetchGroup(
        group: List<WatchEntity>,
        includeRollingFutures: Boolean,
    ): Pair<Map<Long, Fetched>, FetchedGroup> {
        val groupStartedAt = System.currentTimeMillis()
        val bulk = loadBulkTickers(group)
        val bulkTickers = bulk.tickers
        val bulkMillis = System.currentTimeMillis() - groupStartedAt
        val bulkTried = group.size >= MIN_WATCHES_FOR_BULK
        // Sammelabfrage mit «zu vielen Anfragen» abgelehnt: keine Einzelabfragen hinterher,
        // das verschlimmerte es nur — die Börse wird pausiert (ExchangeBackoff).
        val bulkRateLimited = bulk.error != null &&
            RefreshReportLogic.classify(bulk.error) == RefreshFailure.RATE_LIMIT

        // Eigene Grenze je Börse, damit niemand ins Rate-Limit läuft.
        val limit = Semaphore(MAX_PARALLEL_REQUESTS_PER_MARKET)

        val results = coroutineScope {
            group.map { watch ->
                async {
                    val bulkTicker = watch.pairId?.let { bulkTickers[it] }
                    watch to when {
                        bulkTicker != null -> Fetched(bulkTicker, null)

                        // Vollständige Liste ohne dieses Paar: nicht mehr gehandelt.
                        // Früher kostete das je Paar eine Einzelabfrage, die
                        // ohnehin scheiterte — bei 131 Paaren rund 11 s.
                        // Laufzeit-Futures nur nachprüfen, wenn so eingestellt.
                        bulk.complete &&
                            (!includeRollingFutures || watch.contractType !in ROLLING_CONTRACTS) ->
                            Fetched(null, NOT_TRADED_ERROR, notTraded = true)

                        bulkRateLimited -> Fetched(null, bulk.error)

                        else -> limit.withPermit { fetchSingle(watch) }
                    }
                }
            }.awaitAll()
        }

        val notTraded = results.count { it.second.notTraded }
        val errors = results.map { it.second }.filter { (it.ticker == null || it.error != null) && !it.notTraded }

        val entry = MarketRefresh(
            name = group.first().marketName,
            millis = System.currentTimeMillis() - groupStartedAt,
            pairs = group.size,
            updated = group.size - notTraded - errors.size,
            notTraded = notTraded,
            failed = errors.size,
            bulkTried = bulkTried,
            bulkMillis = bulkMillis,
            bulkPrices = bulkTickers.size,
            singles = results.count { it.second.fromSingle },
            reason = RefreshReportLogic.reason(errors.map { it.error }),
        )
        val errorTexts = errors.map { it.error } + listOfNotNull(bulk.error)
        val signals = FetchedGroup(
            marketKey = group.first().marketKey,
            report = entry,
            failures = errorTexts.map(RefreshReportLogic::classify),
            retryAfterMillis = ExchangeBackoff.retryAfterMillis(errorTexts),
        )

        return results.associate { (watch, fetched) -> watch.id to fetched } to signals
    }

    /**
     * Holt die Kurse einer Börse gesammelt, sofern sie das anbietet und sich
     * der Aufwand lohnt. Wo möglich nur für die beobachteten Paare. Bei einem
     * Fehler bleibt die Karte leer und jedes Paar wird einzeln abgefragt.
     */
    private suspend fun loadBulkTickers(group: List<WatchEntity>): BulkTickers {
        if (group.size < MIN_WATCHES_FOR_BULK) return BulkTickers()

        val sample = group.first()
        val market = MarketInfo(sample.marketKey, sample.marketName)
        val pairIds = group.mapNotNull { it.pairId }.distinct()

        return runCatching {
            if (!marketRepository.isMarketSupportsBulkTickers(market)) return BulkTickers()
            marketRepository.getBulkTickers(market, pairIds)
        }
            .onFailure { Timber.w(it, "Massenabfrage fehlgeschlagen: %s", sample.marketName) }
            .getOrDefault(BulkTickers())
    }

    /** Bericht einer Börse plus was die Pause je Börse ([ExchangeBackoff]) braucht. */
    private class FetchedGroup(
        val marketKey: String,
        val report: MarketRefresh,
        /** Ursachen aller Fehler (auch einer gescheiterten Sammelabfrage). */
        val failures: List<RefreshFailure>,
        val retryAfterMillis: Long?,
    )

    private suspend fun fetchSingle(watch: WatchEntity): Fetched {
        return runCatching {
            marketRepository.getMarketTicker(
                MarketInfo(watch.marketKey, watch.marketName),
                watch.toPairInfo()
            )
        }.fold(
            onSuccess = { Fetched(it.ticker, it.error, fromSingle = true) },
            onFailure = { failure ->
                // Zeitüberschreitungen kommen als CancellationException an und
                // gelten nur als Fehler dieses Paares. Abbrechen nur, wenn der
                // Durchlauf selbst abgebrochen wurde.
                currentCoroutineContext().ensureActive()
                Timber.w(failure, "Kursabfrage fehlgeschlagen: %s", watch.displayName)
                Fetched(null, failure.message, fromSingle = true)
            }
        )
    }

    suspend fun refreshOne(watchId: Long): RefreshSummary = mutex.withLock {
        withContext(Dispatchers.IO) {
            val startedAt = System.currentTimeMillis()

            val watch = watchRepository.getWatch(watchId) ?: return@withContext RefreshSummary()
            // Kein Netz oder Börse pausiert: nichts anfragen, Kurs und Zustand bleiben
            if (!connectivity.isOnline() ||
                ExchangeBackoff.isPaused(refreshStats.backoffStates()[watch.marketKey], startedAt)
            ) {
                return@withContext RefreshSummary()
            }
            val settings = settingsRepository.current()
            sparklineRepository.awaitRestored()
            val single = fetchSingle(watch)
            // Kerzen nur, wenn der Ticker keinen 24-h-Wert liefert (Tages-Basen: immer)
            val dayStart = ChangeBasisMath.dayStart(settings.changeBasis, startedAt)
            awaitDayReferenceLoads(
                startDayReferenceLoads(
                    watchesNeedingCandles(listOf(watch), mapOf(watch.id to single), settings.changeBasis, remember = false),
                    HashSet(),
                    dayStart,
                )
            )
            val processed = processResults(listOf(watch), mapOf(watch.id to single), settings, dayStart)

            // Ein einzelnes Paar sagt nichts über die Dauer eines vollen Durchlaufs,
            // deshalb bleibt die angezeigte Zeit im Widget hier unverändert.
            // Nur die Widgets, die dieses Paar zeigen (Liste mit dem Paar, Einzel-Widget).
            widgetUpdater.updateForWatch(watch.id)

            RefreshSummary(
                checked = processed.checked,
                failed = processed.failed,
                alarmsTriggered = processed.alarms,
                durationMillis = System.currentTimeMillis() - startedAt
            )
        }
    }

    /**
     * Live-Kurse aus dem WebSocket-Strom (gesammelt, höchstens alle 10 s) speichern und wie eine
     * Aktualisierung auswerten: derselbe Weg für Alarme (Kreuzung zum zuletzt gespeicherten Kurs,
     * Abklingzeit, «erst wieder scharf») und Kurs-Meldungen — also keine doppelten Meldungen.
     * Keine Kursansagen (sonst alle 10 s) und keine Volumen-Alarme (hängen an Stundenkerzen, nicht
     * am Kurs; die REST-Abfrage prüft sie). 24-h-Veränderung nach [LiveRules.chooseChange].
     *
     * Läuft gerade eine Aktualisierung, wird nichts gespeichert: Rückgabe false, der Aufrufer
     * versucht es beim nächsten Mal wieder. [redrawWidgets]: danach die Widgets neu zeichnen
     * (Bildschirm aus: nur vormerken, [LiveWidgetGate] zeichnet beim Einschalten einmal).
     */
    suspend fun applyLive(quotes: Map<Long, LiveQuote>, redrawWidgets: Boolean): Boolean {
        if (quotes.isEmpty()) return true
        if (!mutex.tryLock()) return false
        try {
            withContext(Dispatchers.IO) {
                val now = System.currentTimeMillis()
                val settings = settingsRepository.current()
                val rollingBasis = !settings.changeBasis.isDay
                val stampCurrent = refreshStats.changeStamp.value == ChangeBasisMath.stamp(settings.changeBasis, now)
                // Nicht mehr gehandelte Paare bleiben, wie sie sind (abonniert werden sie ohnehin nicht)
                val watches = watchRepository.getWatches().filter { it.id in quotes && !it.isNotTraded }
                val fetched = HashMap<Long, Fetched>()
                for (watch in watches) {
                    val quote = quotes.getValue(watch.id)
                    val ticker = SimpleTicker().apply {
                        last = quote.price
                        timestamp = quote.time
                        change24hPercent = LiveRules.chooseChange(
                            rollingBasis, stampCurrent,
                            LiveExchange.fromMarketKey(watch.marketKey)?.rollingChange == true,
                            quote.change24h, watch.change24h,
                        )
                    }
                    fetched[watch.id] = Fetched(ticker, null)
                }
                if (watches.isNotEmpty()) {
                    processResults(watches, fetched, settings, dayStart = null, live = true)
                    if (redrawWidgets) {
                        // Zeit zuerst (Widget-Kopfzeile), die Dauer des letzten Durchlaufs bleibt
                        refreshStats.setLastRefresh(refreshStats.lastDurationMillis.value)
                        // Bildschirm aus: nur merken, beim Einschalten einmal nachzeichnen
                        liveWidgetGate.drawAll(deferWhenOff = true)
                    }
                }
            }
        } finally {
            mutex.unlock()
        }
        return true
    }

    /**
     * Wertet die geholten Kurse aus.
     *
     * Früher lief das Paar für Paar mit je vier Datenbankzugriffen (Kurs
     * schreiben, Paar neu lesen, Alarme lesen, Meldekurs schreiben). Bei
     * mehreren hundert Paaren waren das über tausend einzelne Schreibvorgänge,
     * nach jedem lud die Watchlist alle Paare neu. Jetzt: ein Schreibvorgang
     * für alle Kurse, eine Abfrage für alle Alarme, ein Schreibvorgang für
     * die Meldekurse.
     */
    private suspend fun processResults(
        watches: List<WatchEntity>,
        fetched: Map<Long, Fetched>,
        settings: AppSettings,
        /** Tagesbeginn der %-Basis; null = rollende 24 Stunden. */
        dayStart: Long?,
        /** Live-Kurse ([applyLive]): Veränderung steht schon im Ticker, keine Ansagen, keine Volumen-Alarme. */
        live: Boolean = false,
    ): Processed {
        val now = System.currentTimeMillis()

        val priceWrites = ArrayList<PriceWrite>()
        val errorWrites = ArrayList<ErrorWrite>()
        val updatedWatches = ArrayList<WatchEntity>()

        for (watch in watches) {
            val result = fetched[watch.id]
            val ticker = result?.ticker
            val price = ticker?.last ?: 0.0

            if (ticker == null || result.error != null ||
                price <= Ticker.NO_DATA.toDouble() || price <= 0.0
            ) {
                errorWrites += ErrorWrite(watch.id, result?.error, now)
                continue
            }

            val time = ticker.timestamp.takeIf { it > 0 } ?: now
            val dayChange = if (live) ticker.change24hPercent else change24h(watch, price, ticker, settings.changeBasis, dayStart)
            priceWrites += PriceWrite(watch.id, price, time, dayChange)
            // Entspricht dem, was das UPDATE in der Datenbank setzt.
            updatedWatches += watch.copy(
                previousPrice = watch.lastPrice,
                lastPrice = price,
                lastUpdate = time,
                lastError = null,
                change24h = dayChange,
            )
        }

        val dbStartedAt = System.currentTimeMillis()
        watchRepository.applyRefresh(priceWrites, errorWrites)
        val dbMillis = System.currentTimeMillis() - dbStartedAt

        val effectsStartedAt = System.currentTimeMillis()

        // Gezeigte Meldungen EINMAL abfragen: Bei hunderten Paaren ohne Kurs-Meldung kostete
        // sonst jedes Paar je Durchlauf einen Aufruf an den Systemdienst (cancel).
        val shownNotifications = notifier.activeNotificationIds()

        // Ohne Kurs gibt es nichts zu melden.
        errorWrites.forEach { notifier.cancelPriceIfShown(it.id, shownNotifications) }

        val alarmsByWatch: Map<Long, List<AlarmEntity>> = if (updatedWatches.isEmpty()) emptyMap()
        else watchRepository.getAllEnabledAlarms().groupBy { it.watchId }

        var alarms = 0
        val notifiedPrices = ArrayList<Pair<Long, Double>>()

        for (watch in updatedWatches) {
            val price = watch.lastPrice ?: continue
            val triggered = checkAlarms(
                watch, price, watch.previousPrice,
                alarmsByWatch[watch.id].orEmpty(), settings, now, live,
            )
            alarms += triggered

            if (updateNotification(watch, settings, shownNotifications)) notifiedPrices += watch.id to price
            if (!live) speakPriceIfWanted(watch, price, settings, spokenAlready = triggered > 0)
        }

        watchRepository.setNotifiedPrices(notifiedPrices, now)

        return Processed(
            checked = watches.size,
            failed = errorWrites.size,
            alarms = alarms,
            notificationsShown = notifiedPrices.size,
            dbMillis = dbMillis,
            effectsMillis = System.currentTimeMillis() - effectsStartedAt,
            missingDayChange = updatedWatches.filter { it.change24h == null },
            failedIds = errorWrites.map { it.id },
        )
    }

    private suspend fun checkAlarms(
        watch: WatchEntity,
        price: Double,
        previousPrice: Double?,
        enabledAlarms: List<AlarmEntity>,
        settings: AppSettings,
        now: Long,
        /**
         * Live-Kurse: Volumen-, Funding- und Open-Interest-Alarme auslassen (die REST-Abfrage prüft
         * sie mit Stundenkerzen bzw. Futures-Daten).
         */
        live: Boolean = false,
    ): Int {
        var count = 0

        // Nicht abwarten: Die Ansage läuft in der Warteschlange der Sprachausgabe (Reihenfolge bleibt)
        fun speakIfWanted(alarm: AlarmEntity) {
            // Nachtruhe: Alarm kommt lautlos, also auch ohne Sprachausgabe
            if (settings.ttsEnabled && alarm.speak && !isQuiet(settings)) {
                ttsSpeaker.enqueue(
                    spokenText.alarm(watch, alarm.condition, price),
                    speechRate = settings.ttsSpeechRate,
                    flush = true
                )
            }
        }

        // Volumendaten nur holen, wenn dieses Paar einen Volumen-Alarm hat — und dann nur einmal.
        var volume: VolumeSpike? = null
        var volumeLoaded = false

        // Umrechnungsfaktoren für Kursalarme in einer anderen Währung: je Währung einmal pro Durchlauf.
        val rates = HashMap<String, Double?>()
        suspend fun rateFor(currency: String): Double? {
            val key = currency.uppercase()
            if (rates.containsKey(key)) return rates[key]
            val rate = try {
                currencyConverter.rate(watch.quoteAsset, key)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.d(e, "Alarm: Umrechnung %s → %s fehlgeschlagen", watch.quoteAsset, key)
                null
            }
            rates[key] = rate
            return rate
        }

        // «Nahe am Hoch/Tief»: Hoch/Tief der Zeiträume nur bei Bedarf, je Paar einmal (6 h zwischengespeichert)
        var nearRanges: WindowRanges? = null
        var nearLoaded = false

        // Funding/Open Interest: nur für Paare mit solchen Alarmen, je Paar höchstens alle 5 Min. abgefragt
        var derivatives: DerivativesAlarmData.Values? = null
        var derivativesLoaded = false

        for (alarm in enabledAlarms) {
            if (alarm.condition.isDerivatives) {
                // Nicht bei Live-Kursen (WebSocket): die normale Aktualisierung prüft sie
                if (live) continue
                if (!derivativesLoaded) {
                    derivatives = derivativesAlarmData.values(watch, price, now)
                    derivativesLoaded = true
                }
                val values = derivatives ?: continue
                val value = if (alarm.condition.isFunding) values.fundingPercent
                else derivativesAlarmData.oiChange(watch, values, DerivativesAlarm.oiWindowHours(alarm.windowHours), now)
                when (val decision = DerivativesAlarm.decide(
                    condition = alarm.condition,
                    threshold = alarm.threshold,
                    value = value,
                    armed = alarm.referenceAt <= 0L,
                    enabled = alarm.enabled,
                    lastTriggeredAt = alarm.lastTriggeredAt,
                    now = now,
                    cooldownMinutes = settings.alarmCooldownMinutes,
                )) {
                    DerivativesAlarm.Decision.None -> Unit
                    DerivativesAlarm.Decision.Rearm -> watchRepository.rearmAlarm(alarm.id)
                    is DerivativesAlarm.Decision.Fire -> {
                        notifier.showAlarm(watch, alarm, price, derivativesValue = decision.value)
                        watchRepository.markAlarmTriggered(alarm, price, now)
                        count++
                        speakIfWanted(alarm)
                    }
                }
                continue
            }
            if (alarm.condition.isNearExtreme) {
                if (!nearLoaded) {
                    nearRanges = try {
                        nearExtremeDataSource.ranges(watch.baseAsset, watch.quoteAsset)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Timber.d(e, "Alarm: Hoch/Tief für %s nicht verfügbar", watch.displayName)
                        null
                    }
                    nearLoaded = true
                }
                val ranges = nearRanges ?: continue
                val days = NearExtreme.windowDays(alarm.windowHours)
                val raw = ranges.ranges[days] ?: continue
                // Nur USDT-Kerzen: Hoch/Tief in die Quote des Paars umrechnen; ohne Faktor diesmal auslassen
                val range = if (ranges.currency.equals(watch.quoteAsset, ignoreCase = true)) raw else {
                    // rateFor = Faktor Quote → Kerzenwährung; Hoch/Tief also dadurch teilen
                    val rate = rateFor(ranges.currency)?.takeIf { it > 0.0 } ?: continue
                    raw.scaled(1.0 / rate)
                }
                val decision = NearExtreme.decide(
                    side = if (alarm.condition == AlarmCondition.NEAR_HIGH) NearExtreme.Side.HIGH else NearExtreme.Side.LOW,
                    price = price,
                    range = range,
                    thresholdPercent = alarm.threshold,
                    armed = alarm.referenceAt <= 0L,
                    lastLevel = alarm.referencePrice,
                    inCooldown = NearExtreme.inCooldown(alarm.lastTriggeredAt, now, settings.alarmCooldownMinutes),
                    lastTriggeredAt = alarm.lastTriggeredAt,
                    now = now,
                )
                when (decision) {
                    NearExtreme.Decision.None -> Unit
                    NearExtreme.Decision.Rearm -> watchRepository.rearmAlarm(alarm.id)
                    is NearExtreme.Decision.Fire -> {
                        notifier.showAlarm(watch, alarm, price, nearFire = decision)
                        watchRepository.markAlarmTriggered(alarm, price, now, nearLevel = decision.level)
                        count++
                        speakIfWanted(alarm)
                    }
                }
                continue
            }
            if (alarm.condition == AlarmCondition.VOLUME_SPIKE) {
                if (live) continue
                if (!volumeLoaded) {
                    volume = volumeDataSource.hourlySpike(watch.baseAsset, watch.quoteAsset)
                    volumeLoaded = true
                }
                val spike = volume ?: continue
                val spikeFires = alarmEvaluator.shouldTriggerVolumeSpike(
                    alarm = alarm,
                    ratio = spike.ratio,
                    candleOpenTime = spike.candleOpenTime,
                    now = now,
                    cooldownMinutes = settings.alarmCooldownMinutes
                )
                if (!spikeFires) continue

                notifier.showAlarm(watch, alarm, price, volumeRatio = spike.ratio)
                watchRepository.markAlarmTriggered(alarm, price, now, candleOpenTime = spike.candleOpenTime)
                count++
                speakIfWanted(alarm)
                continue
            }

            // Bewegungs-Alarm: abgelaufenes Fenster neu beginnen, ohne auszulösen
            if (alarmEvaluator.needsWindowReset(alarm, now)) {
                watchRepository.setAlarmReference(alarm.id, price, now)
                continue
            }
            // Schwellwert in anderer Währung: Kurs umrechnen; ohne Faktor diesmal auslassen
            val convertTo = alarm.convertCurrency
            val comparePrice = if (convertTo == null) price else {
                val rate = rateFor(convertTo) ?: continue
                price * rate
            }
            // Gemeldeter Kursalarm: erst wieder scharf, wenn der Kurs auf die andere Seite zurück ist
            if (alarmEvaluator.shouldRearmLevel(alarm, comparePrice)) {
                watchRepository.rearmAlarm(alarm.id)
                continue
            }
            val fires = alarmEvaluator.shouldTrigger(
                alarm = alarm,
                price = comparePrice,
                previousPrice = previousPrice,
                now = now,
                cooldownMinutes = settings.alarmCooldownMinutes
            )
            if (!fires) continue

            notifier.showAlarm(watch, alarm, price)
            watchRepository.markAlarmTriggered(alarm, price, now)
            count++
            speakIfWanted(alarm)
        }
        return count
    }

    /**
     * Zeigt die Kurs-Benachrichtigung — je nach Einstellung nur dann, wenn sich
     * der Kurs seit der letzten Meldung deutlich genug bewegt hat.
     * @return true, wenn gemeldet wurde; der Aufrufer setzt dann den neuen
     *   Bezugspunkt (gesammelt für alle Paare in einem Schreibvorgang).
     */
    private fun updateNotification(watch: WatchEntity, settings: AppSettings, shown: Set<Int>?): Boolean {
        if (!settings.priceNotifications || !watch.notificationEnabled) {
            notifier.cancelPriceIfShown(watch.id, shown)
            return false
        }

        val price = watch.lastPrice
        if (price == null || price <= 0.0) {
            notifier.cancelPriceIfShown(watch.id, shown)
            return false
        }

        val threshold = settings.notificationChangePercent
        val reference = watch.notifiedPrice

        if (threshold > 0 && reference != null && reference > 0.0) {
            val change = abs((price - reference) / reference * 100.0)
            if (change < threshold) return false
        }

        // Erst melden — die Benachrichtigung zeigt den Vergleich zur vorigen —
        // danach wird der neue Bezugspunkt gesetzt.
        notifier.showPrice(watch, ongoing = settings.ongoingNotifications)
        return true
    }

    /** Kursansage in die Warteschlange der Sprachausgabe — die Aktualisierung wartet nicht darauf. */
    private fun speakPriceIfWanted(
        watch: WatchEntity,
        price: Double,
        settings: AppSettings,
        spokenAlready: Boolean,
    ) {
        if (spokenAlready) return
        if (!settings.ttsEnabled || settings.ttsAlarmsOnly) return
        if (!watch.ttsEnabled) return
        // Nachtruhe: auch normale Kursansagen schweigen
        if (isQuiet(settings)) return

        ttsSpeaker.enqueue(spokenText.price(watch, price), speechRate = settings.ttsSpeechRate, key = watch.id)
    }

    /**
     * Paare, die beim letzten vollen Durchlauf Kerzen brauchten (Ticker ohne 24-h-Wert) —
     * deren Bezüge werden gleich zu Beginn parallel zu den Kursen angestossen.
     */
    @Volatile
    private var candleWatchIds: Set<Long> = emptySet()

    /**
     * Paare mit Kurs, die Kerzen brauchen ([ChangeBasisMath.needsCandles]): rollend nur ohne
     * brauchbaren 24-h-Wert im Ticker, Tages-Basen alle.
     * [remember]: Menge für den nächsten Durchlauf merken (nur bei vollen Durchläufen,
     * damit [refreshOne] sie nicht verkleinert).
     */
    private fun watchesNeedingCandles(
        watches: List<WatchEntity>,
        fetched: Map<Long, Fetched>,
        basis: ChangeBasis,
        remember: Boolean,
    ): List<WatchEntity> {
        val needing = watches.filter { watch ->
            val ticker = fetched[watch.id]?.ticker ?: return@filter false
            ChangeBasisMath.needsCandles(basis, ticker.change24hPercent)
        }
        if (remember) candleWatchIds = needing.mapTo(HashSet()) { it.id }
        return needing
    }

    /**
     * Stösst das Laden der 24-h-Bezüge an: je Basis-Asset die Reihe in der Quote des Paars
     * (USD-artige teilen sich die USDT-Reihe des Mini-Charts), bei Fiat-Quotes zusätzlich
     * die USDT-Reihe als Ausweich. Läuft im eigenen Scope, damit ein Abruf nach dem
     * Warten ([awaitDayReferenceLoads]) fertig wird und beim nächsten Durchlauf bereitliegt.
     * Schlüssel in [started] werden übersprungen und ergänzt (kein doppelter Start).
     * [dayStart]: Tages-Basis — Bezug seit diesem Tagesbeginn statt rollend.
     */
    private fun startDayReferenceLoads(
        watches: List<WatchEntity>,
        started: MutableSet<Pair<String, String>>,
        dayStart: Long?,
    ): List<Job> =
        watches.flatMap { watch ->
            val quote = DayChange.candleQuote(watch.quoteAsset)
            if (quote != DAY_QUOTE && isFiat(watch.quoteAsset)) {
                listOf(watch.baseAsset to quote, watch.baseAsset to DAY_QUOTE)
            } else {
                listOf(watch.baseAsset to quote)
            }
        }
            .map { (base, quote) -> base.trim().uppercase() to quote }
            .filter { started.add(it) }
            .map { (base, quote) ->
                activityScope.launch {
                    runCatching {
                        if (dayStart != null) sparklineRepository.dayStartReference(base, quote, dayStart)
                        else sparklineRepository.dayReference(base, quote)
                    }
                }
            }

    /** Wartet höchstens [DAY_REFERENCE_WAIT_MILLIS]; was dann fehlt, kommt aus dem Zwischenspeicher. */
    private suspend fun awaitDayReferenceLoads(jobs: List<Job>) {
        if (jobs.isEmpty()) return
        withTimeoutOrNull(DAY_REFERENCE_WAIT_MILLIS) { jobs.joinAll() }
    }

    /**
     * Veränderung zum neuen Kurs gemäss %-Basis ([ChangeBasisMath.choose]). Rollend: zuerst der
     * 24-h-Wert aus dem Ticker (gilt für das Paar selbst, also schon in seiner Quote — auch bei
     * Fiat-Quotes), sonst aus Kerzen. Tages-Basen ([dayStart]): nur aus Kerzen seit Tagesbeginn.
     * Kerzen mit [DayChange.select] (Kursabstand-Prüfung); null ohne Bezug — nie die
     * Veränderung seit der letzten Abfrage.
     */
    private fun change24h(watch: WatchEntity, price: Double, ticker: Ticker, basis: ChangeBasis, dayStart: Long?): Double? =
        ChangeBasisMath.choose(basis, ticker.change24hPercent) { candleChange(watch, price, dayStart) }

    /** Veränderung aus gemerkten Kerzen: rollend ([dayStart] null) oder seit Tagesbeginn. */
    private fun candleChange(watch: WatchEntity, price: Double, dayStart: Long?): Double? {
        val quote = DayChange.candleQuote(watch.quoteAsset)
        fun reference(q: String) = if (dayStart != null) sparklineRepository.cachedDayStartReference(watch.baseAsset, q, dayStart)
        else sparklineRepository.cachedDayReference(watch.baseAsset, q)
        return DayChange.select(
            price = price,
            pairReference = reference(quote),
            usdtReference = reference(DAY_QUOTE),
            quoteIsFiat = isFiat(watch.quoteAsset),
        )
    }

    /**
     * Tages-Basen: Bezüge, die nach dem Warten ([DAY_REFERENCE_WAIT_MILLIS]) noch kommen,
     * im Hintergrund nachtragen — nur die Veränderung, nur solange Kurs und Basis gleich
     * geblieben sind; danach die Widgets neu zeichnen (Live-Dienst bei ausgeschaltetem
     * Bildschirm: aufgeschoben, [deferWidgets]). Bricht nichts ab und meldet nichts.
     */
    private fun fillLateDayChanges(missing: List<WatchEntity>, loads: List<Job>, stamp: ChangeStamp, deferWidgets: Boolean) {
        if (missing.isEmpty() || loads.all { it.isCompleted }) return
        activityScope.launch {
            runCatching {
                withTimeoutOrNull(LATE_FILL_MILLIS) { loads.joinAll() }
                // Unter dem Refresh-Mutex: kein Durchlauf schreibt dazwischen (neuere Kurse,
                // andere Basis); der Stempel muss noch derjenige dieses Durchlaufs sein
                val filled = mutex.withLock {
                    val settings = settingsRepository.current()
                    if (ChangeBasisMath.stamp(settings.changeBasis, System.currentTimeMillis()) != stamp ||
                        refreshStats.changeStamp.value != stamp
                    ) return@withLock 0
                    var count = 0
                    for (watch in missing) {
                        val price = watch.lastPrice ?: continue
                        val change = candleChange(watch, price, stamp.dayStart)?.takeIf { it.isFinite() } ?: continue
                        if (watchRepository.fillChange(watch.id, watch.lastUpdate, change)) count++
                    }
                    count
                }
                if (filled > 0) liveWidgetGate.drawAll(deferWidgets)
            }.onFailure { if (it is CancellationException) throw it }
        }
    }

    private fun isFiat(quote: String): Boolean = quote.trim().uppercase() in FxRateSource.CURRENCIES

    /** Nachtruhe in diesem Moment (Ortszeit des Geräts)? */
    private fun isQuiet(settings: AppSettings): Boolean =
        QuietHours.isQuiet(
            settings.quietHoursEnabled,
            settings.quietHoursStart,
            settings.quietHoursEnd,
            QuietHours.minuteOfDay(),
        )

    /** Ergebnis der Netzabfrage für ein Paar; ticker == null heißt gescheitert. */
    private class Fetched(
        val ticker: Ticker?,
        val error: String?,
        val fromSingle: Boolean = false,
        val notTraded: Boolean = false,
    )

    private class Processed(
        val checked: Int,
        val failed: Int,
        val alarms: Int,
        val notificationsShown: Int,
        val dbMillis: Long,
        val effectsMillis: Long,
        /** Paare mit neuem Kurs, aber ohne Veränderung (kein Bezug). */
        val missingDayChange: List<WatchEntity> = emptyList(),
        /** Paare ohne neuen Kurs (Fehler, nicht mehr gehandelt). */
        val failedIds: List<Long> = emptyList(),
    )

    private companion object {
        /** Unter so wenigen Paaren spart die Massenabfrage nichts. */
        const val MIN_WATCHES_FOR_BULK = 3

        /** Gleichzeitige Einzelabfragen je Börse. */
        const val MAX_PARALLEL_REQUESTS_PER_MARKET = 4

        const val NOT_TRADED_ERROR = NOT_TRADED_MARKER

        /** Kerzen-Quote der Mini-Charts (Ausweich-Reihe für Fiat-Quotes). */
        const val DAY_QUOTE = "USDT"

        /** So lange wartet ein Durchlauf nach den Kursen noch auf fehlende 24-h-Bezüge. */
        const val DAY_REFERENCE_WAIT_MILLIS = 5_000L

        /** So lange werden späte Tages-Bezüge im Hintergrund noch nachgetragen. */
        const val LATE_FILL_MILLIS = 90_000L

        /**
         * Laufzeit-Kontrakte wechseln ihre Kennung beim Verfall (z. B.
         * BTCUSDT_250926 → BTCUSDT_251226). Die gespeicherte Kennung fehlt dann
         * in der Liste, die Einzelabfrage findet aber den aktuellen Kontrakt.
         */
        val ROLLING_CONTRACTS = setOf(
            FuturesContractType.WEEKLY,
            FuturesContractType.BIWEEKLY,
            FuturesContractType.MONTHLY,
            FuturesContractType.BIMONTHLY,
            FuturesContractType.QUARTERLY,
            FuturesContractType.BIQUARTERLY,
        )
    }
}

/**
 * Gespeicherter «Fehler» für Paare, die die Börse nicht mehr führt. Das ist
 * kein Fehler, sondern ein Zustand: Die Merkliste zeigt ihn neutral an und
 * zählt ihn weder als veraltet noch als «ohne Kurs».
 */
const val NOT_TRADED_MARKER = com.cryptochecker.app.domain.watch.NotTraded.MARKER
