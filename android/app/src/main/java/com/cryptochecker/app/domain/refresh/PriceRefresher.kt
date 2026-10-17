package com.cryptochecker.app.domain.refresh

import com.cryptochecker.app.data.ErrorWrite
import com.cryptochecker.app.data.PairIdentity
import com.cryptochecker.app.data.PriceWrite
import com.cryptochecker.app.data.RefreshStats
import com.cryptochecker.app.data.SparklineRepository
import com.cryptochecker.app.data.WatchRepository
import com.cryptochecker.app.data.pairIdentity
import com.cryptochecker.app.data.local.model.AlarmCondition
import com.cryptochecker.app.data.local.model.AlarmEntity
import com.cryptochecker.app.data.local.model.WatchEntity
import com.cryptochecker.app.data.remote.BlockedSources
import com.cryptochecker.app.domain.activity.ActivityAnalysisGate
import com.cryptochecker.app.domain.activity.ActivityMonitor
import com.cryptochecker.app.domain.alarm.DepegAlarmChecker
import com.cryptochecker.app.domain.gas.GasAlertChecker
import com.cryptochecker.app.domain.live.LiveCoverage
import com.cryptochecker.app.domain.live.LiveExchange
import com.cryptochecker.app.domain.live.LiveQuote
import com.cryptochecker.app.domain.live.LiveRules
import com.cryptochecker.app.domain.watch.ChangeBasis
import com.cryptochecker.app.domain.watch.ChangeBasisMath
import com.cryptochecker.app.domain.watch.ChangeStamp
import com.cryptochecker.app.domain.watch.PriceFreshness
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
    /** USDT-Depeg-Alarm (nur wenn eingeschaltet, höchstens alle 5 Minuten). */
    private val depegAlarmChecker: DepegAlarmChecker,
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

    /**
     * [block] unter derselben Sperre wie Aktualisierung, Live-Speichern und späte Tages-Bezüge:
     * Paar bearbeiten, Sicherung wiederherstellen — kein Durchlauf schreibt dazwischen Kurse mit
     * den Daten von vorher. [block] darf selbst keine Aktualisierung aufrufen ([refreshAll],
     * [refreshOne], [applyLive]): die Sperre ist nicht wiedereintrittsfähig (Verklemmung).
     */
    suspend fun <T> withRefreshLock(block: suspend () -> T): T = mutex.withLock { block() }

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
     * Android den Prozess beenden, bevor sie fertig ist. Ebenso die Aktivitäts- und die
     * Gas-Prüfung (je höchstens [SIDE_CHECK_TIMEOUT_MILLIS]), damit ihre Meldungen nicht verloren gehen.
     * @param networkGuaranteed true im WorkManager-Job: Die Bedingung CONNECTED garantiert Netz;
     * der zwischengespeicherte Status von [ConnectivityMonitor] kann in einem eingefrorenen
     * Prozess veraltet («offline») sein und würde den Job sonst grundlos überspringen.
     * @param deadlineAt harte Frist (exakter Wecker, absolut in ms): je Börse höchstens
     * [CheckBudget.GROUP_MILLIS], Warten auf Bezüge und Nebenprüfungen gekürzt — gespeichert und
     * ausgewertet wird, was bis dahin kam. null = ohne Budget (WorkManager, Vordergrund).
     */
    suspend fun refreshAll(
        awaitPortfolioSnapshot: Boolean = false,
        networkGuaranteed: Boolean = false,
        deadlineAt: Long? = null,
    ): RefreshSummary {
        // Kein Netz: gar nicht erst versuchen — die Paare behalten Kurs und Zustand, die Liste
        // zeigt «Offline · Stand …». Kommt das Netz zurück, aktualisiert ManualRefresh einmal.
        // Nur für Vordergrund-/Live-Wege; der Job vertraut seiner Netz-Bedingung.
        if (!networkGuaranteed && !connectivity.isOnline()) {
            Timber.i("Aktualisierung übersprungen: offline")
            return RefreshSummary()
        }
        val summary = refreshAllGuarded(deadlineAt)
        // Ungewöhnliche Aktivität: erst nach gespeicherten Kursen und gezeichneten
        // Widgets, ausserhalb der Sperre — bremst den nächsten Durchlauf nicht,
        // Fehler bleiben still. Prüft jedes Paar höchstens alle 10 Minuten.
        // Nur mit Abnehmer: Meldung eingeschaltet oder App sichtbar (Karte, ⚡, «Warum»).
        val activityCheck: suspend () -> Unit = {
            val alerts = runCatching { settingsRepository.current().activityAlerts }.getOrDefault(false)
            if (ActivityAnalysisGate.shouldRun(alertsEnabled = alerts, appVisible = AppVisibility.visible)) {
                activityMonitor.analyzeAll()
            }
        }
        // Gas-Alarm (#167): höchstens alle 10 Minuten, nur wenn eingestellt
        val gasCheck: suspend () -> Unit = { gasAlertChecker.checkIfDue() }
        // USDT-Depeg-Alarm: nur wenn eingeschaltet (sonst keine Anfrage)
        val depegCheck: suspend () -> Unit = { depegAlarmChecker.checkIfDue() }
        if (awaitPortfolioSnapshot) {
            // WorkManager-Job: Nach doWork() friert Android den Prozess ein — losgelöste Jobs
            // (und ihre Meldungen) gingen verloren. Darum hier abwarten, parallel und mit
            // Zeitgrenze; die Portfolio-Momentaufnahme wie bisher (Fehler fängt refresh() selbst).
            // Mit Frist: nur die verbleibende Zeit (Reserve für Ansagen und Vermerk)
            val sideTimeout = CheckBudget.waitMillis(
                deadlineAt, System.currentTimeMillis(), SIDE_CHECK_TIMEOUT_MILLIS, reserve = SIDE_CHECK_RESERVE_MILLIS
            )
            coroutineScope {
                launch { awaitSideCheck("Aktivität", activityCheck, sideTimeout) }
                launch { awaitSideCheck("Gas-Alarm", gasCheck, sideTimeout) }
                launch { awaitSideCheck("Depeg-Alarm", depegCheck, sideTimeout) }
                // Auch die Momentaufnahme mit Zeitgrenze: sonst hielte eine hängende Abfrage den Job (und die Frist des Weckers) auf
                launch { awaitSideCheck("Portfolio", { portfolioSnapshotUpdater.refreshIfWidgets() }, sideTimeout) }
            }
        } else {
            // Eigene Hintergrund-Jobs: Die Aktualisierung (und ihr Spinner) wartet nicht auf
            // die Auswertung, die bis zu 20 s dauern kann. Fehler bleiben still.
            activityScope.launch { runCatching { activityCheck() } }
            activityScope.launch { runCatching { gasCheck() } }
            activityScope.launch { runCatching { depegCheck() } }
            // Portfolio-Widget: Momentaufnahme auch ohne geöffnete App (nur mit Widget, Fehler still)
            activityScope.launch { runCatching { portfolioSnapshotUpdater.refreshIfWidgets() } }
        }
        return summary
    }

    /**
     * Prüfung im WorkManager-Job abwarten, höchstens [timeoutMillis]; Fehler und
     * Zeitüberschreitung bleiben still, ein Abbruch des Jobs wird weitergereicht.
     */
    private suspend fun awaitSideCheck(name: String, check: suspend () -> Unit, timeoutMillis: Long) {
        try {
            if (withTimeoutOrNull(timeoutMillis) { check() } == null) {
                Timber.d("%s: Zeitgrenze im Hintergrund-Job erreicht", name)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.d(e, "%s fehlgeschlagen", name)
        }
    }

    private suspend fun refreshAllGuarded(deadlineAt: Long?): RefreshSummary = mutex.withLock {
        _fullRefreshRunning.value = true
        try {
            refreshOnIo(deadlineAt)
        } finally {
            _fullRefreshRunning.value = false
        }
    }

    private suspend fun refreshOnIo(deadlineAt: Long?): RefreshSummary =
        withContext(Dispatchers.IO) {
            try {
                refreshAllLocked(deadlineAt)
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
                        // Ohne rohen Ausnahmetext (Hosts, URLs): Klassenname + kurzer Code (StoredError)
                        aborted = listOfNotNull(ex.javaClass.simpleName, StoredError.sanitize(ex.message)).joinToString(": "),
                    )
                )
                throw ex
            }
        }

    private suspend fun refreshAllLocked(deadlineAt: Long?): RefreshSummary {
        val startedAt = System.currentTimeMillis()

        val settings = settingsRepository.current()
        // Deaktivierte Paare: keine Abfrage, keine Alarme, keine Meldungen — sie behalten Kurs und Zustand
        val allWatches = watchRepository.getWatches().filterNot { it.disabled }

        if (allWatches.isEmpty()) {
            refreshStats.setLastRefresh(System.currentTimeMillis() - startedAt)
            liveWidgetGate.drawAll(deferWhenOff = false)
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
        // Alarm-Zusatzdaten ebenfalls parallel zu den Kursen (Doze: Netz-Fenster nach den Kursen oft zu)
        val prefetchBackoff = refreshStats.backoffStates()
        val alarmLoads = effects.startAlarmPrefetch(
            watches.filterNot { ExchangeBackoff.isPaused(prefetchBackoff[it.marketKey], startedAt) },
            watchRepository.getAllEnabledAlarms(),
            startedAt,
        )

        // 1) Netz: alle Börsen gleichzeitig. Je Börse erst die Massenabfrage,
        //    was dort fehlt, parallel einzeln. Früher lief das strikt
        //    nacheinander — Börse für Börse, Paar für Paar.
        //    Pausierte Börsen (zu viele Anfragen, wiederholte Zeitüberschreitungen) bleiben
        //    diesmal aussen vor: keine Anfrage, ihre Paare behalten den letzten Kurs.
        val backoff = refreshStats.backoffStates().toMutableMap()
        // Gemeinsame Binance-Pause (429/418 bei Kerzen, Futures …): auch die Kurse pausieren,
        // sonst verlängerte die Kursabfrage die IP-Sperre (siehe BlockedSources)
        for (key in watches.mapTo(HashSet()) { it.marketKey }) {
            val until = BlockedSources.groupPausedUntil(key) ?: continue
            val state = backoff[key]
            if (state == null || state.pausedUntil < until || !ExchangeBackoff.isPaused(state, startedAt)) {
                backoff[key] = (state ?: ExchangeBackoff.State(strikes = 1))
                    .copy(pausedUntil = until, reason = RefreshFailure.RATE_LIMIT, timeoutRuns = 0)
            }
        }
        val (pausedGroups, activeGroups) = watches.groupBy { it.marketKey }.entries
            .partition { ExchangeBackoff.isPaused(backoff[it.key], startedAt) }
        val pausedIds = pausedGroups.flatMap { group -> group.value.map { it.id } }.toSet()
        val fetched = HashMap<Long, Fetched>()
        val activeReports = coroutineScope {
            activeGroups
                // Mit Frist: je Börse eigenes Budget, eine langsame Börse hält die anderen nicht auf
                .map { group -> async { priceFetcher.fetchGroup(group.value, settings.includeRollingFutures, deadlineAt) } }
                .awaitAll()
        }.map { (results, fetchedGroup) ->
            fetched.putAll(results)
            // Ergebnis je Börse → Pause beginnen, verlängern oder (nach Erfolg) aufheben
            val key = fetchedGroup.marketKey
            // Nur am eigenen Budget gescheitert: Pause unverändert lassen (CheckBudget.backoffOutcome)
            val outcome = CheckBudget.backoffOutcome(fetchedGroup.failures, fetchedGroup.report.updated, fetchedGroup.cutByBudget)
            val state = if (outcome == null) backoff[key] else ExchangeBackoff.next(
                backoff[key], outcome,
                System.currentTimeMillis(), fetchedGroup.retryAfterMillis,
                // 418 und «Retry-After» wie bei Kerzen (gemeinsame Binance-Pause)
                banned = fetchedGroup.banned, shared = ExchangeBackoff.sharedGroup(key) != null,
            )
            if (state == null) backoff.remove(key) else backoff[key] = state
            // 429/418 beim Ticker: Kerzen, Futures und Aktivität an dieselben Hosts pausieren mit
            if (state != null && state.reason == RefreshFailure.RATE_LIMIT &&
                ExchangeBackoff.isPaused(state, System.currentTimeMillis())
            ) {
                BlockedSources.pauseGroup(key, state.pausedUntil)
            }
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
        // Mit Frist: nur so lange, dass das Auswerten noch Platz hat (fehlende Bezüge aus dem Zwischenspeicher)
        dayReferences.awaitDayReferenceLoads(
            dayLoads + alarmLoads,
            CheckBudget.waitMillis(
                deadlineAt, System.currentTimeMillis(), Long.MAX_VALUE, reserve = CheckBudget.EVALUATE_RESERVE_MILLIS,
            ),
        )
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
        if (dayStart != null) fillLateDayChanges(processed.missingDayChange, dayLoads, changeStamp)

        // Uhrzeit und Dauer ZUERST speichern, dann die Widgets zeichnen —
        // sonst zeigt die Widget-Kopfzeile neue Kurse mit der alten Uhrzeit.
        val duration = System.currentTimeMillis() - startedAt
        // Nur wenn mindestens ein Kurs kam: Ohne Verbindung bleibt im Widget die
        // Zeit der letzten ERFOLGREICHEN Aktualisierung stehen.
        if (processed.failed < processed.checked) refreshStats.setLastRefresh(duration)
        // Sperre gegen doppelte Hintergrund-Läufe: nur nach einer vollen Prüfung (nicht live, nicht Teil-Lauf)
        if (BackgroundSchedule.isFullCheck(processed.checked, processed.failed)) refreshStats.setLastFullCheck()

        val widgetStartedAt = System.currentTimeMillis()
        // Voller Durchlauf zeichnet immer (bei ausgeschaltetem Bildschirm ist er die einzige Aktualisierung)
        val drawWidgets = liveWidgetGate.drawAll(deferWhenOff = false)
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
            // Deaktiviert, kein Netz oder Börse pausiert: nichts anfragen, Kurs und Zustand bleiben
            if (watch.disabled || !connectivity.isOnline() ||
                ExchangeBackoff.isPaused(refreshStats.backoffStates()[watch.marketKey], startedAt) ||
                BlockedSources.groupPausedUntil(watch.marketKey) != null
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
     *
     * [pairs]: für welches Paar jeder Kurs abonniert war. Ein Kurs, dessen Eintrag inzwischen ein
     * anderes Paar ist («Paar bearbeiten», Sicherung), wird verworfen — auch beim Speichern
     * ([WatchDao.updatePriceForPair]). null = ohne diese Prüfung (nur Tests).
     */
    suspend fun applyLive(
        quotes: Map<Long, LiveQuote>,
        redrawWidgets: Boolean,
        pairs: Map<Long, PairIdentity>? = null,
    ): Boolean {
        if (quotes.isEmpty()) return true
        if (!mutex.tryLock()) return false
        try {
            withContext(Dispatchers.IO) {
                val now = System.currentTimeMillis()
                val settings = settingsRepository.current()
                val rollingBasis = !settings.changeBasis.isDay
                val stampCurrent = refreshStats.changeStamp.value == ChangeBasisMath.stamp(settings.changeBasis, now)
                // Nicht mehr gehandelte und deaktivierte Paare bleiben, wie sie sind (abonniert werden
                // sie ohnehin nicht; ein später Takt nach dem Deaktivieren wird verworfen)
                val watches = watchRepository.getWatches().filter {
                    it.id in quotes && !it.isNotTraded && !it.disabled &&
                        // Abo für ein anderes Paar (Eintrag inzwischen bearbeitet): alter Kurs, verwerfen
                        (pairs == null || pairs[it.id] == it.pairIdentity)
                }
                val fetched = HashMap<Long, Fetched>()
                for (watch in watches) {
                    val quote = quotes.getValue(watch.id)
                    val ticker = SimpleTicker().apply {
                        last = quote.price
                        timestamp = quote.time
                        exchangeTime = LiveRules.exchangeTime(quote, watch.lastPrice, watch.exchangeTime)
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
                !price.isFinite() || price <= Ticker.MISSING || price <= 0.0
            ) {
                errorWrites += ErrorWrite(watch.id, result?.error, now, watch.pairIdentity)
                continue
            }

            val time = ticker.timestamp.takeIf { it > 0 } ?: now
            val dayChange = if (live) {
                // Live-Wert aus dem Ticker: kein Kerzen-Hinweis — aber nur, wenn er die Veränderung ersetzt hat.
                // Bleibt die bisherige ([LiveRules.chooseChange], z. B. Tages-Basis), bleibt auch ihr Anbieter:
                // sonst stünde ein Wert aus fremden Kerzen ohne Hinweis da, als käme er von der eigenen Börse
                if (ticker.change24hPercent != watch.change24h) dayReferences.notePillSource(watch.id, null)
                ticker.change24hPercent
            } else {
                dayReferences.change24h(watch, price, ticker, settings.changeBasis, dayStart)
            }
            // Frische (PriceFreshness): letzte echte Kursänderung und Zeit laut Börse (null = keine).
            // Live: nur eine im Strom belegte Handelszeit ([LiveRules.exchangeTime], in [applyLive] gesetzt).
            val changedAt = PriceFreshness.nextPriceChangedAt(watch.lastPrice, watch.priceChangedAt, price, time)
            val exchangeTime = ticker.exchangeTime
            // Mit dem Paar, für das der Kurs geholt wurde: ist der Eintrag inzwischen ein anderes, wird nichts gespeichert
            priceWrites += PriceWrite(watch.id, price, time, dayChange, changedAt, exchangeTime, watch.pairIdentity)
            // Entspricht dem, was das UPDATE in der Datenbank setzt.
            updatedWatches += watch.copy(
                previousPrice = watch.lastPrice,
                lastPrice = price,
                lastUpdate = time,
                lastError = null,
                change24h = dayChange,
                priceChangedAt = changedAt,
                exchangeTime = exchangeTime,
            )
        }

        val dbStartedAt = System.currentTimeMillis()
        val written = watchRepository.applyRefresh(priceWrites, errorWrites)
        // Nicht gespeichert (Eintrag ist inzwischen ein anderes Paar): weder Alarme noch Meldungen mit diesem Kurs
        updatedWatches.retainAll { it.id in written }
        val dbMillis = System.currentTimeMillis() - dbStartedAt

        val effectsStartedAt = System.currentTimeMillis()

        // Gezeigte Meldungen EINMAL abfragen: Bei hunderten Paaren ohne Kurs-Meldung kostete
        // sonst jedes Paar je Durchlauf einen Aufruf an den Systemdienst (cancel).
        val shownNotifications = notifier.activeNotificationIds()

        // Ohne Kurs gibt es nichts zu melden (entfernt wird gesammelt nach den Alarmen).
        val priceCancels = errorWrites.mapTo(ArrayList()) { it.id }
        val pricePosts = ArrayList<WatchEntity>()

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

            when (effects.priceUpdate(watch, settings)) {
                RefreshEffects.PriceUpdate.SHOW -> pricePosts += watch
                RefreshEffects.PriceUpdate.CANCEL -> priceCancels += watch.id
                RefreshEffects.PriceUpdate.NONE -> Unit
            }
            if (!live) effects.speakPriceIfWanted(watch, price, settings, spokenAlready = triggered > 0)
        }

        // Kurs-Mitteilungen NACH den Alarmen und gesammelt: höchstens 20 einzeln, der Rest in einer
        // Sammelmeldung (Android verwirft ab rund 50 offenen Mitteilungen je App weitere still).
        // Neuer Bezugspunkt nur für Paare, deren Kurs jetzt gezeigt wird.
        notifier.cancelPrices(priceCancels, shownNotifications)
        if (pricePosts.isNotEmpty()) {
            val shownPrices = notifier.showPrices(pricePosts, ongoing = settings.ongoingNotifications)
            for (watch in pricePosts) {
                val price = watch.lastPrice ?: continue
                if (watch.id in shownPrices) notifiedPrices += watch.id to price
            }
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
     * geblieben sind; danach die Widgets neu zeichnen. Bricht nichts ab und meldet nichts.
     */
    private fun fillLateDayChanges(missing: List<WatchEntity>, loads: List<Job>, stamp: ChangeStamp) {
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
                if (filled > 0) liveWidgetGate.drawAll(deferWhenOff = false)
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

        /** Höchstens so lange wartet der WorkManager-Job auf Aktivitäts- und Gas-Prüfung. */
        const val SIDE_CHECK_TIMEOUT_MILLIS = 30_000L

        /** Mit Frist: so viel bleibt nach den Nebenprüfungen (Ansagen abwarten, Lauf vermerken). */
        const val SIDE_CHECK_RESERVE_MILLIS = 2_000L
    }
}

/**
 * Gespeicherter «Fehler» für Paare, die die Börse nicht mehr führt. Das ist
 * kein Fehler, sondern ein Zustand: Die Merkliste zeigt ihn neutral an und
 * zählt ihn weder als veraltet noch als «ohne Kurs».
 */
const val NOT_TRADED_MARKER = com.cryptochecker.app.domain.watch.NotTraded.MARKER
