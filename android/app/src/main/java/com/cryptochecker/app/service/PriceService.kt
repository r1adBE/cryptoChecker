package com.cryptochecker.app.service

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import androidx.core.content.ContextCompat
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.ServiceCompat
import com.cryptochecker.app.R
import com.cryptochecker.app.domain.refresh.LiveInterval
import com.cryptochecker.app.domain.refresh.PriceRefresher
import com.cryptochecker.app.notification.AppNotifier
import com.cryptochecker.app.settings.AppSettings
import com.cryptochecker.app.settings.SettingsRepository
import com.cryptochecker.app.util.AppVisibility
import com.cryptochecker.app.util.PriceFormat
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import javax.inject.Inject

/**
 * Hält die Kurse in kurzen Abständen aktuell, solange der Live-Modus an ist; bei ausgeschaltetem
 * Bildschirm höchstens alle 5 Minuten ([LiveInterval.SCREEN_OFF_MIN_SECONDS]).
 * WorkManager kann frühestens alle 15 Minuten laufen – für eine dauerhafte
 * Kursanzeige in der Statusleiste ist das zu träge.
 */
@AndroidEntryPoint
class PriceService : Service() {

    @Inject lateinit var priceRefresher: PriceRefresher

    @Inject lateinit var notifier: AppNotifier

    @Inject lateinit var settingsRepository: SettingsRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loopJob: Job? = null

    /** Zuletzt gezeigter Statustext; ein erneuter Start (App geöffnet) behält ihn. */
    @Volatile
    private var lastText: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        // Läuft die Schleife schon (z. B. erneuter Start aus MainActivity.onStart),
        // nicht auf «Wird gestartet …» zurückfallen
        val running = loopJob?.isActive == true
        if (!startInForeground(lastText?.takeIf { running } ?: getString(R.string.notification_service_starting))) {
            // Vom System abgelehnt (z. B. Start aus dem Hintergrund): Dienst beenden, die
            // geplante Hintergrund-Aktualisierung (WorkManager) übernimmt — sie lässt ihre
            // Durchläufe nur aus, solange der Dienst läuft (LiveServiceGate).
            stopLive()
            return START_NOT_STICKY
        }

        if (!running) {
            LiveServiceGate.beat()
            loopJob = scope.launch { runLoop() }
        }

        // Nicht STICKY: Ein Neustart durch das System käme aus dem Hintergrund, wo ab
        // Android 12 kein Vordergrunddienst starten darf. Der Live-Modus startet nur mit
        // sichtbarer App (MainActivity.onStart); bis dahin läuft die Hintergrund-Aktualisierung.
        return START_NOT_STICKY
    }

    private suspend fun runLoop() {
        while (scope.isActive) {
            val settings = runCatching { settingsRepository.current() }.getOrDefault(AppSettings())

            // Bildschirm aus: Widgets nicht bei jedem Takt zeichnen, nur beim Einschalten nachziehen
            val summary = runCatching { priceRefresher.refreshAll(deferWidgetsWhenScreenOff = true) }
                .onFailure { Timber.w(it, "Live-Aktualisierung fehlgeschlagen") }
                .getOrNull()
            // Beendet (onTimeout/onDestroy): runCatching hat den Abbruch geschluckt — nicht
            // mehr startForeground aufrufen (nach dem Zeitlimit wirft das ab Android 15)
            currentCoroutineContext().ensureActive()

            val text = when {
                summary == null -> getString(R.string.notification_service_error)
                summary.checked == 0 -> getString(R.string.notification_service_empty)
                else -> resources.getQuantityString(
                    R.plurals.notification_service_running,
                    summary.checked,
                    summary.checked,
                    PriceFormat.time(System.currentTimeMillis())
                )
            }
            lastText = text
            LiveServiceGate.beat()
            if (!startInForeground(text)) {
                stopLive()
                return
            }

            awaitNextRun(System.currentTimeMillis(), settings.liveIntervalSeconds)
        }
    }

    /**
     * Wartet bis zum nächsten Durchlauf ([LiveInterval]): mit sichtbarer App das gewählte
     * Intervall, bei geschlossener App höchstens jede Minute. Wechselt die Sichtbarkeit, wird
     * neu gerechnet — beim Öffnen geht es also sofort weiter, wenn der letzte Durchlauf schon
     * älter als das gewählte Intervall ist.
     */
    private suspend fun awaitNextRun(lastRunAt: Long, chosenSeconds: Int) {
        while (true) {
            val visible = AppVisibility.visible
            val screenOn = screenOn.value
            val wait = LiveInterval.waitMillis(
                lastRunAt, System.currentTimeMillis(), chosenSeconds, visible, AppSettings.MIN_LIVE_INTERVAL_SECONDS,
                screenOn = screenOn,
            )
            if (wait <= 0L) return
            // Abgelaufen (null): Zeit für den nächsten Durchlauf; sonst Sichtbarkeit oder Bildschirm gewechselt
            withTimeoutOrNull(wait) {
                kotlinx.coroutines.flow.merge(
                    AppVisibility.flow.filter { it != visible }.map { },
                    this@PriceService.screenOn.filter { it != screenOn }.map { },
                ).first()
            } ?: return
        }
    }

    /** Bildschirm an? (Ausgeschaltet: höchstens alle 5 Minuten, siehe [LiveInterval].) */
    private val screenOn = MutableStateFlow(true)

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_ON -> screenOn.value = true
                Intent.ACTION_SCREEN_OFF -> screenOn.value = false
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        screenOn.value = getSystemService(PowerManager::class.java)?.isInteractive ?: true
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        // System-Broadcasts: ohne Export-Flag erlaubt, aber ab Android 14 muss eines angegeben sein
        ContextCompat.registerReceiver(this, screenReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    /**
     * Ab Android 14 muss der Diensttyp beim Start angegeben werden, sonst
     * lehnt das System den Vordergrunddienst ab.
     */
    private fun startInForeground(contentText: String): Boolean = try {
        ServiceCompat.startForeground(
            this,
            AppNotifier.SERVICE_NOTIFICATION_ID,
            notifier.serviceNotification(contentText),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
        true
    } catch (e: IllegalStateException) {
        // ForegroundServiceStartNotAllowedException (ab Android 12, Start aus dem Hintergrund
        // oder Zeitlimit erreicht) und InvalidForegroundServiceTypeException (ab Android 14)
        // sind IllegalStateException
        Timber.w(e, "Live-Dienst: Vordergrund abgelehnt, Hintergrund-Aktualisierung übernimmt")
        false
    } catch (e: SecurityException) {
        // Fehlende Berechtigung FOREGROUND_SERVICE_DATA_SYNC o. Ä.
        Timber.w(e, "Live-Dienst: Vordergrund nicht erlaubt, Hintergrund-Aktualisierung übernimmt")
        false
    }

    /** Schleife beenden und Dienst stoppen; der Hintergrund-Job läuft danach wieder normal. */
    private fun stopLive() {
        LiveServiceGate.stopped()
        loopJob?.cancel()
        loopJob = null
        runCatching { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE) }
        stopSelf()
    }

    /**
     * Ab Android 15 darf ein dataSync-Vordergrunddienst im Hintergrund höchstens
     * 6 Stunden in 24 Stunden laufen. Danach ruft das System onTimeout auf; der
     * Dienst muss sich innerhalb weniger Sekunden beenden, sonst stürzt die App ab.
     * Die Hintergrund-Aktualisierung (WorkManager) läuft weiter; der Live-Modus
     * startet wieder, sobald die App geöffnet wird (MainActivity.onStart).
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        Timber.i("Live-Dienst: Zeitlimit des Systems erreicht, wird beendet")
        stopLive()
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(screenReceiver) }
        LiveServiceGate.stopped()
        loopJob = null
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.cryptochecker.app.action.SERVICE_START"
        const val ACTION_STOP = "com.cryptochecker.app.action.SERVICE_STOP"
    }
}
