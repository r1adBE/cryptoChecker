package com.cryptochecker.app.domain.refresh

import com.cryptochecker.app.data.ErrorWrite
import com.cryptochecker.app.data.PriceWrite
import com.cryptochecker.app.data.RefreshStats
import com.cryptochecker.app.data.SparklineRepository
import com.cryptochecker.app.data.WatchRepository
import com.cryptochecker.app.data.local.model.AlarmCondition
import com.cryptochecker.app.data.local.model.AlarmEntity
import com.cryptochecker.app.data.local.model.WatchEntity
import com.cryptochecker.app.domain.activity.ActivityAnalysisGate
import com.cryptochecker.app.domain.activity.ActivityMonitor
import com.cryptochecker.app.domain.gas.GasAlertChecker
import com.cryptochecker.app.domain.live.LiveCoverage
import com.cryptochecker.app.domain.live.LiveExchange
import com.cryptochecker.app.domain.live.LiveQuote
import com.cryptochecker.app.domain.live.LiveRules
import com.cryptochecker.app.domain.watch.ChangeBasis
import com.cryptochecker.app.domain.watch.ChangeBasisMath
import com.cryptochecker.app.domain.watch.ChangeStamp
import com.cryptochecker.app.domain.watch.isNotTraded
import com.cryptochecker.app.notification.AppNotifier
import com.cryptochecker.app.settings.AppSettings
import com.cryptochecker.app.settings.SettingsRepository
import com.cryptochecker.app.util.AppVisibility
import com.cryptochecker.app.util.ConnectivityMonitor
import com.cryptochecker.app.widget.LiveWidgetGate
import com.cryptochecker.app.widget.PortfolioSnapshotUpdater
import com.cryptochecker.app.widget.WidgetUpdater
import com.cryptochecker.marketdata.model.SimpleTicker
import com.cryptochecker.marketdata.model.Ticker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
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
    private val settingsRepository: SettingsRepository,
    private val notifier: AppNotifier,
    private val widgetUpdater: WidgetUpdater,
    private val refreshStats: RefreshStats,
    private val activityMonitor: ActivityMonitor,
    private val gasAlertChecker: GasAlertChecker,
    private val portfolioSnapshotUpdater: PortfolioSnapshotUpdater,
    /** 24-h-Bezug aus Kerzen — nur Ausweich-Weg, wenn der Ticker keinen 24-h-Wert liefert. */
    private val sparklineRepository: SparklineRepository,
    /** Ohne Netz keine Aktualisierung (keine Fehlerzustände, siehe [OfflineGate]). */
    private val connectivity: ConnectivityMonitor,
    /** Paare mit frischem Live-Kurs (WebSocket, Merkliste offen) lässt die REST-Abfrage aus. */
    private val liveCoverage: LiveCoverage,
    /** Live-Takte zeichnen die Widgets nur über dieses Tor (Bildschirm aus: aufschieben). */
    private val liveWidgetGate: LiveWidgetGate,
    /** Kurse holen (gesammelt oder einzeln, je Börse). */
    private val priceFetcher: PriceFetcher,
    /** 24-h- und Tages-Bezüge aus Kerzen. */
    private val dayReferences: DayReferences,
    /** Alarme, Kurs-Meldungen und Ansagen nach einem neuen Kurs. */
    private val effects: RefreshEffects,
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
        val earlyWatches = if (dayStart != null) watches else watches.filter { it.id in dayReferences.candleWatchIds }
        val earlyLoads = dayReferences.startDayReferenceLoads(earlyWatches, startedKeys, dayStart)

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
                .map { group -> async { priceFetcher.fetchGroup(group.value, settings.includeRollingFutures) } }
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
        val lateLoads = dayReferences.startDayReferenceLoads(
            dayReferences.watchesNeedingCandles(watches, fetched, settings.changeBasis, remember = true), startedKeys, dayStart
        )
        val dayLoads = earlyLoads + lateLoads
        dayReferences.awaitDayReferenceLoads(dayLoads)
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
            val single = priceFetcher.fetchSingle(watch)
            // Kerzen nur, wenn der Ticker keinen 24-h-Wert liefert (Tages-Basen: immer)
            val dayStart = ChangeBasisMath.dayStart(settings.changeBasis, startedAt)
            dayReferences.awaitDayReferenceLoads(
                dayReferences.startDayReferenceLoads(
                    dayReferences.watchesNeedingCandles(listOf(watch), mapOf(watch.id to single), settings.changeBasis, remember = false),
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
                // NaN/∞ aus einer Antwort nie als Kurs übernehmen (∞ löste sonst «über» aus)
                !price.isFinite() || price <= Ticker.NO_DATA.toDouble() || price <= 0.0
            ) {
                errorWrites += ErrorWrite(watch.id, result?.error, now)
                continue
            }

            val time = ticker.timestamp.takeIf { it > 0 } ?: now
            val dayChange = if (live) {
                // Live-Wert aus dem Ticker: kein Kerzen-Hinweis
                dayReferences.notePillSource(watch.id, null)
                ticker.change24hPercent
            } else {
                dayReferences.change24h(watch, price, ticker, settings.changeBasis, dayStart)
            }
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
            // Ein Paar, dessen Alarme scheitern (Datenbank, Daten einer Quelle), hält die übrigen nicht auf
            val triggered = try {
                effects.checkAlarms(
                    watch, price, watch.previousPrice,
                    alarmsByWatch[watch.id].orEmpty(), settings, now, live,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "Alarme für %s nicht geprüft", watch.displayName)
                0
            }
            alarms += triggered

            if (effects.updateNotification(watch, settings, shownNotifications)) notifiedPrices += watch.id to price
            if (!live) effects.speakPriceIfWanted(watch, price, settings, spokenAlready = triggered > 0)
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

    /**
     * Tages-Basen: Bezüge, die nach dem Warten ([DayReferences.awaitDayReferenceLoads]) noch kommen,
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
                        val change = dayReferences.candleChange(watch, price, stamp.dayStart)?.takeIf { it.isFinite() } ?: continue
                        if (watchRepository.fillChange(watch.id, watch.lastUpdate, change)) count++
                    }
                    count
                }
                if (filled > 0) liveWidgetGate.drawAll(deferWidgets)
            }.onFailure { if (it is CancellationException) throw it }
        }
    }

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
        /** So lange werden späte Tages-Bezüge im Hintergrund noch nachgetragen. */
        const val LATE_FILL_MILLIS = 90_000L
    }
}

/**
 * Gespeicherter «Fehler» für Paare, die die Börse nicht mehr führt. Das ist
 * kein Fehler, sondern ein Zustand: Die Merkliste zeigt ihn neutral an und
 * zählt ihn weder als veraltet noch als «ohne Kurs».
 */
const val NOT_TRADED_MARKER = com.cryptochecker.app.domain.watch.NotTraded.MARKER
