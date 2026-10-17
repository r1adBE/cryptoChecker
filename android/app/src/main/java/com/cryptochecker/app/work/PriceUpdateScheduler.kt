package com.cryptochecker.app.work

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.cryptochecker.app.data.WatchRepository
import com.cryptochecker.app.data.local.model.WatchEntity
import com.cryptochecker.app.data.portfolio.PortfolioAlarmDao
import com.cryptochecker.app.settings.AppSettings
import com.cryptochecker.app.settings.SettingsRepository
import com.cryptochecker.app.widget.PortfolioWidgetProvider
import com.cryptochecker.app.widget.PriceWidgetProvider
import com.cryptochecker.app.widget.SingleWidgetProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.update
import timber.log.Timber
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Plant die Hintergrund-Aktualisierung. */
@Singleton
class PriceUpdateScheduler @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val exactScheduler: ExactCheckScheduler,
    private val settingsRepository: SettingsRepository,
    private val watchRepository: WatchRepository,
    private val portfolioAlarmDao: PortfolioAlarmDao,
) {
    /** Zähler: Widgets hinzugefügt oder entfernt ([onWidgetsChanged]). */
    private val widgetsChanged = MutableStateFlow(0)

    private val workManager get() = WorkManager.getInstance(context)

    private val constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    fun schedulePeriodic(intervalMinutes: Int) {
        val minutes = intervalMinutes
            .coerceAtLeast(AppSettings.MIN_BACKGROUND_INTERVAL_MINUTES)
            .toLong()

        val request = PeriodicWorkRequestBuilder<PriceUpdateWorker>(minutes, TimeUnit.MINUTES)
            .setConstraints(constraints)
            // Retry nur noch bei nicht lesbarem Datenbank-Schlüssel (BackgroundWorkPolicy): kurz und
            // linear statt exponentiell (Vorgabe, bis 5 h), damit Alarme nicht stundenlang ausbleiben
            .setBackoffCriteria(BackoffPolicy.LINEAR, RETRY_BACKOFF_SECONDS, TimeUnit.SECONDS)
            .build()

        workManager.enqueueUniquePeriodicWork(
            PriceUpdateWorker.PERIODIC_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
        Timber.d("Hintergrund-Aktualisierung alle %d Minuten geplant", minutes)
    }

    fun cancelPeriodic() {
        workManager.cancelUniqueWork(PriceUpdateWorker.PERIODIC_WORK_NAME)
    }

    /** Einmalige sofortige Aktualisierung, z. B. aus dem Widget heraus. */
    fun refreshNow() {
        val request = OneTimeWorkRequestBuilder<PriceUpdateWorker>()
            .setConstraints(constraints)
            // Bevorzugt ausführen, damit der Knopf im Widget spürbar reagiert.
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            // Retry nur bei nicht lesbarem Datenbank-Schlüssel: kurz und linear, solange der Kreis dreht
            .setBackoffCriteria(BackoffPolicy.LINEAR, RETRY_BACKOFF_SECONDS, TimeUnit.SECONDS)
            .setInputData(
                workDataOf(
                    PriceUpdateWorker.KEY_MANUAL to true,
                    PriceUpdateWorker.KEY_ENQUEUED_AT to System.currentTimeMillis(),
                )
            )
            .build()

        // KEEP: Ein erneuter Knopfdruck bricht eine laufende/wartende Aktualisierung nicht ab
        // (REPLACE tat das); die laufende holt ohnehin frische Kurse. APPEND_OR_REPLACE wäre
        // nur ein doppelter Durchlauf ohne Nutzen.
        workManager.enqueueUniqueWork(
            PriceUpdateWorker.ONE_TIME_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            request
        )
    }

    /**
     * true, solange eine angestoßene Aktualisierung (Widget-Knopf) wartet oder läuft. Der Knopf
     * in der App läuft direkt (ManualRefresh), nicht mehr über WorkManager.
     */
    fun observeManualRefreshRunning(): Flow<Boolean> =
        workManager.getWorkInfosForUniqueWorkFlow(PriceUpdateWorker.ONE_TIME_WORK_NAME)
            .map { infos -> infos.any { !it.state.isFinished } }
            .distinctUntilChanged()

    /** @param forceExact Wecker «Pünktlich prüfen» neu stellen (Neustart, Update, neue Berechtigung). */
    suspend fun apply(settings: AppSettings, forceExact: Boolean = false) {
        // «Wie aktuell?»: Die Hintergrund-Aktualisierung läuft in jeder Wahl (auch «Sparsam») — auch
        // mit «Pünktlich prüfen» als Netz darunter (doppelte Läufe sperrt BackgroundSchedule.shouldSkip).
        // Nur wenn es etwas zu tun gibt (BackgroundWorkPolicy.periodicNeeded); Änderungen verfolgt [followWork].
        syncPeriodic(periodicNeededNow(settings), settings.backgroundIntervalMinutes)
        // Paare unbekannt: stellen; der Wecker sagt sich beim Auslösen ab, wenn nichts zu prüfen ist
        exactScheduler.sync(settings.exactChecks, force = forceExact)

        // Fear-&-Greed-Meldung und eigene Grenzen der Bitcoin-Kennzahlen: zweimal täglich prüfen
        if (settings.fearGreedBelow > 0 || settings.fearGreedAbove > 0 || settings.indicatorAlerts.values.any { it > 0 }) {
            val request = PeriodicWorkRequestBuilder<ZoneCheckWorker>(12, TimeUnit.HOURS)
                .setConstraints(constraints)
                .build()
            workManager.enqueueUniquePeriodicWork(
                ZoneCheckWorker.WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        } else {
            workManager.cancelUniqueWork(ZoneCheckWorker.WORK_NAME)
        }

        // Wirtschaftstermine: Morgen-Meldung täglich um 08:00 Ortszeit
        if (settings.macroNotifications) MacroNotifyWorker.schedule(context) else MacroNotifyWorker.cancel(context)
    }

    private fun syncPeriodic(needed: Boolean, intervalMinutes: Int) {
        if (needed) {
            schedulePeriodic(intervalMinutes)
        } else {
            cancelPeriodic()
            Timber.d("Hintergrund-Aktualisierung abgesagt: nichts zu prüfen")
        }
    }

    /** Widgets hinzugefügt oder entfernt (Widget-Provider): Bedarf neu prüfen. */
    fun onWidgetsChanged() = widgetsChanged.update { it + 1 }

    /**
     * Läuft für die Lebensdauer des Prozesses (CryptoCheckerApp): Paare, Alarme, Einstellungen,
     * Portfolio-Alarme und Widgets beobachten und die periodische Aktualisierung planen bzw. absagen,
     * sobald sich der Bedarf ändert — egal, wo die Änderung herkommt. Kurs-Updates ändern den Bedarf
     * nicht (distinctUntilChanged), lösen also kein Neuplanen aus.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun followWork() {
        val portfolioAlarms = settingsRepository.settings
            .map { it.portfolioEnabled }
            .distinctUntilChanged()
            // Portfolio aus: Datenbank gar nicht erst öffnen
            .flatMapLatest { on -> if (on) portfolioAlarmDao.observeAll().map { list -> list.any { it.enabled } } else flowOf(false) }
        combine(
            settingsRepository.settings,
            watchRepository.observeWatches(),
            watchRepository.observeWatchIdsWithEnabledAlarms(),
            portfolioAlarms,
            // Widgets nur bei einer Änderung abfragen, nicht bei jedem Kurs-Update
            widgetsChanged.map { widgetPresence() },
        ) { settings, watches, alarmWatchIds, portfolioAlarmOn, widgets ->
            BackgroundWorkPolicy.periodicNeeded(demand(settings, watches, alarmWatchIds.toSet(), portfolioAlarmOn, widgets))
        }
            .distinctUntilChanged()
            // Lesefehler: geplant bleibt, was zuletzt galt; nach einer wachsenden Pause (höchstens 60 s)
            // neu beginnen — statt das Nachführen für die ganze Lebensdauer des Prozesses zu beenden
            .retryWhen { cause, attempt ->
                if (cause is CancellationException) return@retryWhen false
                val wait = BackgroundWorkPolicy.followRetryDelayMillis(attempt)
                Timber.w(cause, "Bedarf der Hintergrund-Aktualisierung: Lesefehler, neuer Versuch in %d ms", wait)
                delay(wait)
                true
            }
            .collect { needed -> syncPeriodic(needed, settingsRepository.current().backgroundIntervalMinutes) }
    }

    /** Bedarf jetzt (frisch gelesen). Fehler: lieber planen. */
    private suspend fun periodicNeededNow(settings: AppSettings): Boolean = try {
        val portfolioAlarmOn = settings.portfolioEnabled && portfolioAlarmDao.countEnabled() > 0
        val alarmWatchIds = watchRepository.getAllEnabledAlarms().mapTo(HashSet()) { it.watchId }
        BackgroundWorkPolicy.periodicNeeded(
            demand(settings, watchRepository.getWatches(), alarmWatchIds, portfolioAlarmOn, widgetPresence())
        )
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Timber.w(e, "Bedarf der Hintergrund-Aktualisierung unbekannt")
        true
    }

    private fun demand(
        settings: AppSettings,
        watches: List<WatchEntity>,
        alarmWatchIds: Set<Long>,
        portfolioAlarmOn: Boolean,
        widgets: WidgetPresence,
    ) = BackgroundWorkPolicy.WorkDemand(
        pairs = watches.map {
            BackgroundWorkPolicy.PairWork(
                disabled = it.disabled,
                hasEnabledAlarm = it.id in alarmWatchIds,
                notificationEnabled = it.notificationEnabled,
                ttsEnabled = it.ttsEnabled,
            )
        },
        priceNotifications = settings.priceNotifications,
        ttsPrices = settings.ttsEnabled && !settings.ttsAlarmsOnly,
        activityAlerts = settings.activityAlerts,
        pairWidgets = widgets.pairs,
        portfolioWork = portfolioAlarmOn || widgets.portfolio,
        marketAlarms = settings.gasAlertEthTenths > 0 || settings.gasAlertBtc > 0 || settings.depegAlarm,
    )

    /** Listen-/Einzel-Widgets bzw. Portfolio-Widgets auf dem Startbildschirm. */
    private data class WidgetPresence(val pairs: Boolean, val portfolio: Boolean)

    private fun widgetPresence() = WidgetPresence(
        pairs = hasWidgets(PriceWidgetProvider::class.java) || hasWidgets(SingleWidgetProvider::class.java),
        portfolio = hasWidgets(PortfolioWidgetProvider::class.java),
    )

    /** Widgets dieser Art auf dem Startbildschirm? Ohne Auskunft: ja (lieber planen). */
    private fun hasWidgets(provider: Class<*>): Boolean = runCatching {
        AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context, provider)).isNotEmpty()
    }.getOrDefault(true)

    private companion object {
        /** Linearer Backoff (Sekunden) für Retry der Aktualisierung; WorkManager-Minimum sind 10 s. */
        const val RETRY_BACKOFF_SECONDS = 30L
    }
}
