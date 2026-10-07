package com.cryptochecker.app.domain.refresh

import android.content.Context
import com.cryptochecker.app.R
import com.cryptochecker.app.data.MarketRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import com.cryptochecker.app.data.RefreshStats
import com.cryptochecker.app.data.ErrorWrite
import com.cryptochecker.app.data.PriceWrite
import com.cryptochecker.app.data.SparklineRepository
import com.cryptochecker.app.data.portfolio.FxRateSource
import com.cryptochecker.app.domain.watch.DayChange
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
import com.cryptochecker.app.domain.alarm.QuietHours
import com.cryptochecker.app.domain.model.MarketInfo
import com.cryptochecker.app.notification.AppNotifier
import com.cryptochecker.app.settings.AppSettings
import com.cryptochecker.app.settings.SettingsRepository
import com.cryptochecker.app.tts.SpokenText
import com.cryptochecker.app.tts.TtsSpeaker
import com.cryptochecker.app.widget.PortfolioSnapshotUpdater
import com.cryptochecker.app.widget.WidgetUpdater
import com.cryptochecker.app.domain.model.BulkTickers
import com.cryptochecker.marketdata.model.FuturesContractType
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
    /** Für den Bericht in der Sprache der App (siehe AppLanguages.wrap). */
    @param:ApplicationContext private val context: Context,
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
) {
    private val mutex = Mutex()

    /** Läuft unabhängig vom Aufrufer weiter (App-weit, ein Singleton). */
    private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Läuft komplett auf [Dispatchers.IO]: Aus dem ViewModel aufgerufen lief
     * bisher alles — Antwort lesen, JSON zerlegen — auf dem Main-Thread.
     */
    /**
     * @param awaitPortfolioSnapshot true im WorkManager-Job: Die Momentaufnahme für das
     * Portfolio-Widget wird im Job abgewartet, nicht in einem losgelösten Job — sonst kann
     * Android den Prozess beenden, bevor sie fertig ist.
     */
    suspend fun refreshAll(awaitPortfolioSnapshot: Boolean = false): RefreshSummary {
        val summary = refreshAllGuarded()
        // Ungewöhnliche Aktivität: erst nach gespeicherten Kursen und gezeichneten
        // Widgets, ausserhalb der Sperre — bremst den nächsten Durchlauf nicht,
        // Fehler bleiben still. Prüft jedes Paar höchstens alle 10 Minuten.
        // Eigener Hintergrund-Job: Die Aktualisierung (und ihr Spinner) wartet
        // nicht auf die Auswertung, die bis zu 20 s dauern kann.
        activityScope.launch { activityMonitor.analyzeAll() }
        // Gas-Alarm (#167): eigener Job, höchstens alle 10 Minuten, nur wenn eingestellt
        activityScope.launch { runCatching { gasAlertChecker.checkIfDue() } }
        // Portfolio-Widget: Momentaufnahme auch ohne geöffnete App (nur mit Widget, Fehler still)
        if (awaitPortfolioSnapshot) {
            // Fehler bleiben still (refresh() fängt sie selbst), Abbruch nicht
            portfolioSnapshotUpdater.refreshIfWidgets()
        } else {
            activityScope.launch { runCatching { portfolioSnapshotUpdater.refreshIfWidgets() } }
        }
        return summary
    }

    private suspend fun refreshAllGuarded(): RefreshSummary = mutex.withLock {
        withContext(Dispatchers.IO) {
            try {
                refreshAllLocked()
            } catch (ex: CancellationException) {
                throw ex
            } catch (ex: Exception) {
                // Sichtbar machen statt still zu schlucken: Sonst bleibt in der
                // App die alte Dauer stehen und niemand merkt, dass es scheitert.
                refreshStats.setLastReport(
                    context.getString(R.string.refresh_report_aborted) +
                        "\n${ex.javaClass.simpleName}: ${ex.message}\n" +
                        ex.stackTrace.take(6).joinToString("\n") { "  at $it" }
                )
                throw ex
            }
        }
    }

    private suspend fun refreshAllLocked(): RefreshSummary {
        val startedAt = System.currentTimeMillis()

        val settings = settingsRepository.current()
        val watches = watchRepository.getWatches()

        if (watches.isEmpty()) {
            refreshStats.setLastRefresh(System.currentTimeMillis() - startedAt)
            widgetUpdater.updateAll()
            return RefreshSummary(
                durationMillis = System.currentTimeMillis() - startedAt
            )
        }

        // 24-h-Bezüge (Kerzen) nur noch als Ausweich-Weg: parallel zu den Kursen nur für
        // Paare, deren Ticker beim letzten Mal keinen 24-h-Wert hatte; der Rest nach den Kursen.
        sparklineRepository.awaitRestored()
        val startedKeys = HashSet<Pair<String, String>>()
        val earlyLoads = startDayReferenceLoads(watches.filter { it.id in candleWatchIds }, startedKeys)

        // 1) Netz: alle Börsen gleichzeitig. Je Börse erst die Massenabfrage,
        //    was dort fehlt, parallel einzeln. Früher lief das strikt
        //    nacheinander — Börse für Börse, Paar für Paar.
        val fetched = HashMap<Long, Fetched>()
        val groupReports = coroutineScope {
            watches.groupBy { it.marketKey }.values
                .map { group -> async { fetchGroup(group, settings.includeRollingFutures) } }
                .awaitAll()
        }.map { (results, report) ->
            fetched.putAll(results)
            report
        }
        val lateLoads = startDayReferenceLoads(watchesNeedingCandles(watches, fetched, remember = true), startedKeys)
        awaitDayReferenceLoads(earlyLoads + lateLoads)
        val networkMillis = System.currentTimeMillis() - startedAt

        // 2) Auswerten: erst alle Kurse in EINEM Datenbank-Vorgang speichern,
        //    dann Alarme, Benachrichtigungen und Ansagen.
        val processed = processResults(watches, fetched, settings)

        // Uhrzeit und Dauer ZUERST speichern, dann die Widgets zeichnen —
        // sonst zeigt die Widget-Kopfzeile neue Kurse mit der alten Uhrzeit.
        val duration = System.currentTimeMillis() - startedAt
        // Nur wenn mindestens ein Kurs kam: Ohne Verbindung bleibt im Widget die
        // Zeit der letzten ERFOLGREICHEN Aktualisierung stehen.
        if (processed.failed < processed.checked) refreshStats.setLastRefresh(duration)

        val widgetStartedAt = System.currentTimeMillis()
        widgetUpdater.updateAll()
        val widgetMillis = System.currentTimeMillis() - widgetStartedAt

        val report = buildString {
            appendLine(context.resources.getQuantityString(R.plurals.refresh_report_total, watches.size, secs(duration), watches.size))
            appendLine(context.getString(R.string.refresh_report_network, secs(networkMillis)))
            groupReports.forEach { appendLine("  • $it") }
            appendLine(context.getString(R.string.refresh_report_database, secs(processed.dbMillis)))
            appendLine(
                context.resources.getQuantityString(
                    R.plurals.refresh_report_effects, processed.notificationsShown,
                    secs(processed.effectsMillis), processed.notificationsShown
                )
            )
            append(context.getString(R.string.refresh_report_widgets, secs(widgetMillis)))
        }
        Timber.i("Aktualisierung:\n%s", report)
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
     * Dazu eine Zeile für den Bericht, wie lange was gedauert hat.
     */
    private suspend fun fetchGroup(
        group: List<WatchEntity>,
        includeRollingFutures: Boolean,
    ): Pair<Map<Long, Fetched>, String> {
        val groupStartedAt = System.currentTimeMillis()
        val bulk = loadBulkTickers(group)
        val bulkTickers = bulk.tickers
        val bulkMillis = System.currentTimeMillis() - groupStartedAt
        val bulkTried = group.size >= MIN_WATCHES_FOR_BULK

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

                        else -> limit.withPermit { fetchSingle(watch) }
                    }
                }
            }.awaitAll()
        }

        val singles = results.filter { it.second.fromSingle }
        val slowest = singles.maxByOrNull { it.second.millis }
        val notTraded = results.count { it.second.notTraded }
        val errors = results.count { (it.second.ticker == null || it.second.error != null) && !it.second.notTraded }

        val line = buildString {
            append(
                context.resources.getQuantityString(
                    R.plurals.refresh_report_market, group.size,
                    group.first().marketName, group.size, secs(System.currentTimeMillis() - groupStartedAt)
                )
            )
            if (bulkTried) {
                append(" · ")
                append(
                    if (bulkTickers.isEmpty()) context.getString(R.string.refresh_report_bulk_failed, secs(bulkMillis))
                    else context.resources.getQuantityString(R.plurals.refresh_report_bulk_ok, bulkTickers.size, secs(bulkMillis), bulkTickers.size)
                )
            }
            if (singles.isNotEmpty()) {
                append(" · ").append(context.getString(R.string.refresh_report_singles, singles.size))
                slowest?.let { (w, f) ->
                    append(", ").append(context.getString(R.string.refresh_report_slowest, w.displayName, secs(f.millis)))
                }
            }
            if (notTraded > 0) append(" · ").append(context.resources.getQuantityString(R.plurals.refresh_report_not_traded, notTraded, notTraded))
            if (errors > 0) append(" · ").append(context.resources.getQuantityString(R.plurals.refresh_report_errors, errors, errors))
        }

        return results.associate { (watch, fetched) -> watch.id to fetched } to line
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

    private suspend fun fetchSingle(watch: WatchEntity): Fetched {
        val startedAt = System.currentTimeMillis()
        fun elapsed() = System.currentTimeMillis() - startedAt

        return runCatching {
            marketRepository.getMarketTicker(
                MarketInfo(watch.marketKey, watch.marketName),
                watch.toPairInfo()
            )
        }.fold(
            onSuccess = { Fetched(it.ticker, it.error, fromSingle = true, millis = elapsed()) },
            onFailure = { failure ->
                // Zeitüberschreitungen kommen als CancellationException an und
                // gelten nur als Fehler dieses Paares. Abbrechen nur, wenn der
                // Durchlauf selbst abgebrochen wurde.
                currentCoroutineContext().ensureActive()
                Timber.w(failure, "Kursabfrage fehlgeschlagen: %s", watch.displayName)
                Fetched(null, failure.message, fromSingle = true, millis = elapsed())
            }
        )
    }

    suspend fun refreshOne(watchId: Long): RefreshSummary = mutex.withLock {
        withContext(Dispatchers.IO) {
            val startedAt = System.currentTimeMillis()

            val watch = watchRepository.getWatch(watchId) ?: return@withContext RefreshSummary()
            val settings = settingsRepository.current()
            sparklineRepository.awaitRestored()
            val single = fetchSingle(watch)
            // Kerzen nur, wenn der Ticker keinen 24-h-Wert liefert
            awaitDayReferenceLoads(startDayReferenceLoads(watchesNeedingCandles(listOf(watch), mapOf(watch.id to single), remember = false), HashSet()))
            val processed = processResults(listOf(watch), mapOf(watch.id to single), settings)

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
            val dayChange = change24h(watch, price, ticker)
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
                alarmsByWatch[watch.id].orEmpty(), settings, now
            )
            alarms += triggered

            if (updateNotification(watch, settings, shownNotifications)) notifiedPrices += watch.id to price
            speakPriceIfWanted(watch, price, settings, spokenAlready = triggered > 0)
        }

        watchRepository.setNotifiedPrices(notifiedPrices, now)

        return Processed(
            checked = watches.size,
            failed = errorWrites.size,
            alarms = alarms,
            notificationsShown = notifiedPrices.size,
            dbMillis = dbMillis,
            effectsMillis = System.currentTimeMillis() - effectsStartedAt,
        )
    }

    private suspend fun checkAlarms(
        watch: WatchEntity,
        price: Double,
        previousPrice: Double?,
        enabledAlarms: List<AlarmEntity>,
        settings: AppSettings,
        now: Long,
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

        for (alarm in enabledAlarms) {
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
     * Paare mit Kurs, deren Ticker keinen brauchbaren 24-h-Wert hat ([DayChange.needsCandles]).
     * [remember]: Menge für den nächsten Durchlauf merken (nur bei vollen Durchläufen,
     * damit [refreshOne] sie nicht verkleinert).
     */
    private fun watchesNeedingCandles(
        watches: List<WatchEntity>,
        fetched: Map<Long, Fetched>,
        remember: Boolean,
    ): List<WatchEntity> {
        val needing = watches.filter { watch ->
            val ticker = fetched[watch.id]?.ticker ?: return@filter false
            DayChange.needsCandles(ticker.change24hPercent)
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
     */
    private fun startDayReferenceLoads(watches: List<WatchEntity>, started: MutableSet<Pair<String, String>>): List<Job> =
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
                activityScope.launch { runCatching { sparklineRepository.dayReference(base, quote) } }
            }

    /** Wartet höchstens [DAY_REFERENCE_WAIT_MILLIS]; was dann fehlt, kommt aus dem Zwischenspeicher. */
    private suspend fun awaitDayReferenceLoads(jobs: List<Job>) {
        if (jobs.isEmpty()) return
        withTimeoutOrNull(DAY_REFERENCE_WAIT_MILLIS) { jobs.joinAll() }
    }

    /**
     * Veränderung über 24 Stunden zum neuen Kurs: zuerst der rollende 24-h-Wert aus dem
     * Ticker (gilt für das Paar selbst, also schon in seiner Quote — auch bei Fiat-Quotes),
     * sonst aus Kerzen ([DayChange.select], mit Kursabstand-Prüfung); null ohne beides —
     * nie die Veränderung seit der letzten Abfrage.
     */
    private fun change24h(watch: WatchEntity, price: Double, ticker: Ticker): Double? =
        DayChange.choose(ticker.change24hPercent) {
            val quote = DayChange.candleQuote(watch.quoteAsset)
            DayChange.select(
                price = price,
                pairReference = sparklineRepository.cachedDayReference(watch.baseAsset, quote),
                usdtReference = sparklineRepository.cachedDayReference(watch.baseAsset, DAY_QUOTE),
                quoteIsFiat = isFiat(watch.quoteAsset),
            )
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
        val millis: Long = 0,
        val notTraded: Boolean = false,
    )

    private class Processed(
        val checked: Int,
        val failed: Int,
        val alarms: Int,
        val notificationsShown: Int,
        val dbMillis: Long,
        val effectsMillis: Long,
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

private fun secs(millis: Long): String = "%.1f s".format(java.util.Locale.ROOT, millis / 1000.0)

/**
 * Gespeicherter «Fehler» für Paare, die die Börse nicht mehr führt. Das ist
 * kein Fehler, sondern ein Zustand: Die Merkliste zeigt ihn neutral an und
 * zählt ihn weder als veraltet noch als «ohne Kurs».
 */
const val NOT_TRADED_MARKER = com.cryptochecker.app.domain.watch.NotTraded.MARKER
