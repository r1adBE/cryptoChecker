package com.cryptochecker.app.domain.refresh

import com.cryptochecker.app.data.local.model.AlarmCondition
import com.cryptochecker.app.data.remote.BlockedSources
import com.cryptochecker.app.domain.live.LiveCoverage
import com.cryptochecker.app.domain.live.LiveExchange
import com.cryptochecker.app.domain.live.LiveRules
import com.cryptochecker.app.domain.watch.ChangeBasis
import com.cryptochecker.app.domain.watch.ChangeBasisMath
import com.cryptochecker.app.domain.watch.ChangeStamp
import com.cryptochecker.app.util.appScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.concurrent.atomic.AtomicReference

/**
 * Holt die Kurse aller beobachteten Paare und stösst danach alles an, was daran hängt:
 * Benachrichtigungen, Alarme, Sprachansagen und Widgets.
 *
 * Wird sowohl vom Vordergrunddienst als auch vom WorkManager aufgerufen, deshalb
 * verhindert die Refresh-Sperre ([RefreshLock]), dass zwei Durchläufe gleichzeitig arbeiten. Live-Kurse
 * übernimmt [LivePriceApplier], Kurse speichert [RefreshResultProcessor], Widget-Charts lädt [ChartRefresher],
 * die Nebenprüfungen startet `RefreshSideChecks`. Die App-Klassen kommen über die Schnittstellen aus
 * `PriceRefresherPorts.kt` herein (Hilt: `RefreshModule`).
 */
class PriceRefresher(
    val watchRepository: RefresherStore,
    val settingsRepository: RefreshSettings,
    val widgetUpdater: RefresherWatchWidgets,
    val refreshStats: RefresherStats,
    /** 24-h-Bezug aus Kerzen — nur Ausweich-Weg, wenn der Ticker keinen 24-h-Wert liefert. */
    val sparklineRepository: RefresherSparklines,
    /** Ohne Netz keine Aktualisierung (keine Fehlerzustände, siehe [OfflineGate]). */
    val connectivity: RefresherOnline,
    /** Paare mit frischem Live-Kurs (WebSocket, Merkliste offen) lässt die REST-Abfrage aus. */
    private val liveCoverage: LiveCoverage,
    /** Widgets zeichnen (unter der Sperre ohne Kerzen-Abruf). */
    val liveWidgetGate: RefreshWidgets,
    /** Kurse holen (gesammelt oder einzeln, je Börse). */
    val priceFetcher: PriceFetcher,
    /** 24-h- und Tages-Bezüge aus Kerzen. */
    val dayReferences: RefresherDayReferences,
    /** Alarm-Zusatzdaten parallel zu den Kursen vorladen. */
    private val effects: RefreshEffects,
    /** Die Sperre aller Kurs-Schreiber (auch [LivePriceApplier]). */
    val refreshLock: RefreshLock,
    /** Kurse speichern und auswerten (unter der Sperre). */
    val resultProcessor: RefreshResultProcessor,
    /** Widget-Charts ausserhalb der Sperre nachladen. */
    val chartRefresher: RefresherCharts,
    /** Aktivität, Gas, Depeg und Portfolio-Momentaufnahme nach dem Durchlauf. */
    val sideChecks: RefresherSideChecks,
) {
    /**
     * [block] unter derselben Sperre wie Aktualisierung, Live-Speichern und späte Tages-Bezüge:
     * Paar bearbeiten, Sicherung wiederherstellen — kein Durchlauf schreibt dazwischen Kurse mit
     * den Daten von vorher. [block] bekommt den Beweis der Sperre ([RefreshLockScope]); eine Aktualisierung
     * ([refreshAll], [refreshOne], Live-Speichern) darin bricht mit [IllegalStateException] ab, statt zu
     * verklemmen ([RefreshLock]).
     */
    suspend fun <T> withRefreshLock(block: suspend RefreshLockScope.() -> T): T = refreshLock.withLock { it.block() }

    private val _fullRefreshRunning = MutableStateFlow(false)

    /** true, solange eine vollständige Aktualisierung arbeitet (gleich welcher Herkunft). */
    val fullRefreshRunning: StateFlow<Boolean> = _fullRefreshRunning.asStateFlow()

    /**
     * Beginn der laufenden vollständigen Aktualisierung (ms); 0 = keine. Das Merkliste-Widget unterscheidet
     * so eine neu begonnene von der eben fertigen, die noch unter der Sperre zeichnet ([WidgetRefreshState.blockedUntil]).
     */
    @Volatile
    var fullRefreshStartedAt: Long = 0L
        private set

    /** Späte Tages-Bezüge: läuft unabhängig vom Aufrufer weiter (App-weit, ein Singleton). */
    val lateFillScope = appScope(Dispatchers.IO, "PriceRefresher")

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
        val (summary, prices) = refreshAllGuarded(deadlineAt)
        afterRefresh(prices, awaitPortfolioSnapshot, deadlineAt)
        return summary
    }

    /**
     * Durchlauf unter der Sperre; die zurückgestellten Kurs-Meldungen laufen danach als Nacharbeit, die die Sperre
     * weiter hält ([RefreshLock.withLockThen]) — vorgemerkt gleich nach dem Speichern, also auch nach einem Fehler oder
     * Abbruch im Rest des Durchlaufs. Rückgabe: Ergebnis und Job der Kurs-Meldungen (null = keine).
     */
    private suspend fun refreshAllGuarded(deadlineAt: Long?): Pair<RefreshSummary, Job?> =
        refreshLock.withLockThen(lateFillScope) { lock ->
            fullRefreshStartedAt = System.currentTimeMillis()
            _fullRefreshRunning.value = true
            try {
                // Die Kurs-Meldungen merkt refreshAllLocked gleich nach dem Speichern vor (RefreshLock.stageTail)
                refreshOnIo(lock, deadlineAt) to null
            } finally {
                _fullRefreshRunning.value = false
                fullRefreshStartedAt = 0L
            }
        }

    private suspend fun refreshOnIo(lock: RefreshLockScope, deadlineAt: Long?): RefreshSummary =
        withContext(Dispatchers.IO) {
            try {
                refreshAllLocked(lock, deadlineAt)
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

    private suspend fun refreshAllLocked(lock: RefreshLockScope, deadlineAt: Long?): RefreshSummary {
        val startedAt = System.currentTimeMillis()

        val settings = settingsRepository.current()
        // Deaktivierte Paare: keine Abfrage, keine Alarme, keine Meldungen — sie behalten Kurs und Zustand
        val allWatches = watchRepository.getWatches().filterNot { it.disabled }

        if (allWatches.isEmpty()) {
            refreshStats.setLastFullRefresh(System.currentTimeMillis() - startedAt)
            // Unter der Sperre ohne Netz zeichnen, Charts danach (refreshAll)
            if (liveWidgetGate.drawAll(deferWhenOff = false, fetch = false)) chartRefresher.markIfStale()
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
            refreshStats.setLastFullRefresh(refreshStats.lastDurationMillis.value)
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
        val enabledAlarms = watchRepository.getAllEnabledAlarms()
        val alarmLoads = effects.startAlarmPrefetch(
            watches.filterNot { ExchangeBackoff.isPaused(prefetchBackoff[it.marketKey], startedAt) },
            enabledAlarms,
            startedAt,
        )
        // Je Börse Paare mit aktivem Alarm zuerst abfragen (FetchOrder): im kurzen Netz-Fenster des Ruhemodus
        // kommen so die Alarme durch
        val alarmedIds = enabledAlarms.mapTo(HashSet()) { it.watchId }

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
                .map { group ->
                    async { priceFetcher.fetchGroup(group.value, settings.includeRollingFutures, deadlineAt, alarmedIds) }
                }
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
        refreshStats.setBackoffStates(ExchangeBackoff.withHostGroupPauses(backoff, System.currentTimeMillis()))
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
        val processed = resultProcessor.process(lock, activeWatches, fetched, settings, dayStart, deferPrices = true)
        // Kurs-Meldungen gleich vormerken: Sie laufen nach dem Durchlauf (gedrosselt bis einige Sekunden, die Sperre
        // bleibt gehalten) — auch wenn danach etwas scheitert, denn die Kurse sind gespeichert. Die Zahl der gezeigten
        // trägt die Nacharbeit in den Bericht dieses Durchlaufs nach (sofern es ihn gibt)
        val report = AtomicReference<RefreshReport?>(null)
        refreshLock.stageTail(
            lock, resultProcessor.pricesTail(processed) { shown -> report.get()?.let { noteShownPrices(it, shown) } },
        )
        // Neue Basis oder neuer Tag: alte Werte von Paaren ohne neuen Kurs gehören nicht mehr dazu
        // (ohne Stempel: Werte von vorher, also rollend)
        val previousStamp = refreshStats.changeStamp.value ?: ChangeStamp(ChangeBasis.ROLLING_24H, 0L)
        if (previousStamp != changeStamp) watchRepository.clearChanges(processed.failedIds + pausedIds)
        // Mit dieser Basis (und diesem Tagesbeginn) gerechnet — die Anzeige prüft das
        refreshStats.setChangeStamp(changeStamp)
        // Mit Frist: Kerzen-Nachlader nicht über die Frist laufen lassen; sonst Tages-Basen: späte Bezüge nachtragen («—»)
        if (deadlineAt != null) dayLoads.forEach { it.cancel() }
        else if (dayStart != null) fillLateDayChanges(processed.missingDayChange, dayLoads, changeStamp)
        // Uhrzeit und Dauer ZUERST speichern, dann die Widgets zeichnen —
        // sonst zeigt die Widget-Kopfzeile neue Kurse mit der alten Uhrzeit.
        val duration = System.currentTimeMillis() - startedAt
        // Nur wenn mindestens ein Kurs kam: Ohne Verbindung bleibt im Widget die
        // Zeit der letzten ERFOLGREICHEN Aktualisierung stehen.
        if (processed.failed < processed.checked) refreshStats.setLastFullRefresh(duration)
        // Sperre gegen doppelte Hintergrund-Läufe: nur nach einer vollen Prüfung (nicht live, nicht Teil-Lauf)
        if (BackgroundSchedule.isFullCheck(processed.checked, processed.failed)) refreshStats.setLastFullCheck()

        val widgetStartedAt = System.currentTimeMillis()
        // Voller Durchlauf zeichnet immer (bei ausgeschaltetem Bildschirm ist er die einzige Aktualisierung).
        // Unter der Sperre nur aus dem Zwischenspeicher (kein Kerzen-Abruf): die Charts lädt refreshAll
        // danach ausserhalb der Sperre nach, sofern die Frist es erlaubt
        val drawWidgets = liveWidgetGate.drawAll(deferWhenOff = false, fetch = false)
        if (drawWidgets) chartRefresher.markIfStale()
        val widgetMillis = if (drawWidgets) System.currentTimeMillis() - widgetStartedAt else null

        val finished = RefreshReport(
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
        Timber.i("Aktualisierung: %s", finished)
        refreshStats.setLastReport(finished)
        report.set(finished)

        return RefreshSummary(
            checked = processed.checked,
            failed = processed.failed,
            alarmsTriggered = processed.alarms,
            durationMillis = duration
        )
    }

    suspend fun refreshOne(watchId: Long): RefreshSummary {
        val summary = refreshOneLocked(watchId)
        // Chart des Einzel-Widgets ausserhalb der Sperre nachladen (unter der Sperre: Zwischenspeicher).
        // Alle Einzel-Widgets: der Merker sagt nicht, welches Kerzen vermisste
        chartRefresher.launchIfPending(CheckBudget.CHART_TIMEOUT_MILLIS)
        return summary
    }

    companion object {
        /** So lange werden späte Tages-Bezüge im Hintergrund noch nachgetragen. */
        const val LATE_FILL_MILLIS = 90_000L

        /** Höchstens so lange wartet der WorkManager-Job auf Aktivitäts- und Gas-Prüfung. */
        const val SIDE_CHECK_TIMEOUT_MILLIS = 30_000L

        /** Mit Frist: so viel bleibt nach den Nebenprüfungen (Ansagen abwarten, Lauf vermerken). */
        const val SIDE_CHECK_RESERVE_MILLIS = 2_000L
    }
}
