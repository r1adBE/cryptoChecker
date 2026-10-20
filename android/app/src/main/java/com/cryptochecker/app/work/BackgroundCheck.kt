package com.cryptochecker.app.work

import com.cryptochecker.app.data.RefreshStats
import com.cryptochecker.app.data.WatchRepository
import com.cryptochecker.app.data.local.AppDatabase
import com.cryptochecker.app.data.local.DatabaseEncryptionNotice
import com.cryptochecker.app.data.local.DatabaseKeyUnavailableException
import com.cryptochecker.app.data.remote.util.runCatchingCancellable
import com.cryptochecker.app.domain.refresh.BackgroundSchedule
import com.cryptochecker.app.domain.refresh.CheckBudget
import com.cryptochecker.app.domain.refresh.CheckOrigin
import com.cryptochecker.app.domain.refresh.CheckResult
import com.cryptochecker.app.domain.refresh.CheckRun
import com.cryptochecker.app.domain.refresh.CheckRunLog
import com.cryptochecker.app.domain.refresh.CheckRunStore
import com.cryptochecker.app.domain.refresh.CheckTrigger
import com.cryptochecker.app.domain.refresh.PriceRefresher
import com.cryptochecker.app.domain.refresh.RefreshSummary
import com.cryptochecker.app.tts.TtsSpeaker
import com.cryptochecker.app.widget.PulseWidgetRenderer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Eine Kurs- und Alarmprüfung im Hintergrund (Build 46): gemeinsamer Weg für WorkManager
 * ([PriceUpdateWorker]), Widget-Knopf und den exakten Wecker ([ExactCheckReceiver]). Überspringt
 * geplante Läufe kurz nach einer Prüfung ([BackgroundSchedule.shouldSkip]) und vermerkt jeden Lauf
 * in «Letzte Prüfungen» ([CheckRunStore]).
 */
@Singleton
class BackgroundCheck @Inject constructor(
    private val priceRefresher: PriceRefresher,
    private val refreshStats: RefreshStats,
    private val ttsSpeaker: TtsSpeaker,
    private val watchRepository: WatchRepository,
    private val checkRuns: CheckRunStore,
    private val pulseWidgets: PulseWidgetRenderer,
) {
    /** Nie zwei Prüfungen gleichzeitig (exakter Wecker und WorkManager können zusammenfallen). */
    private val running = Mutex()

    /** Gibt es aktive (nicht deaktivierte) Paare? Fehler: lieber ja. */
    suspend fun hasWork(): Boolean {
        // Merkliste nur leer im Arbeitsspeicher (Schlüssel gerade nicht lesbar): unbekannt, also lieber ja — sonst
        // nähme der Abgleich den Wecker «Pünktlich prüfen» weg, obwohl die Datei Paare hat
        if (DatabaseEncryptionNotice.isStoredKept(AppDatabase.DB_NAME)) return true
        return runCatchingCancellable { watchRepository.getWatches().any { !it.disabled } }.getOrDefault(true)
    }

    /** Lauf ohne Prüfung vermerken (z. B. offline beim Wecker). */
    fun log(trigger: CheckTrigger, result: CheckResult, startedAt: Long) {
        val now = System.currentTimeMillis()
        checkRuns.add(CheckRun(now, trigger, result, (now - startedAt).coerceAtLeast(0)))
    }

    /**
     * Prüfen. @return null = übersprungen (eben erst geprüft); sonst das Ergebnis.
     * Fehler und Abbruch werden als «fehlgeschlagen» vermerkt und weitergereicht.
     * [deadlineAt]: harte Frist (exakter Wecker), siehe [PriceRefresher.refreshAll]; null = ohne Budget.
     */
    suspend fun run(
        trigger: CheckTrigger,
        networkGuaranteed: Boolean,
        ttsWaitMillis: Long,
        deadlineAt: Long? = null,
    ): RefreshSummary? {
        val startedAt = System.currentTimeMillis()
        // Geplanter Lauf, während schon einer läuft: überspringen statt doppelt abfragen.
        if (trigger.scheduled && running.isLocked) {
            Timber.d("Prüfung (%s) übersprungen: läuft bereits", trigger.code)
            log(trigger, CheckResult.SKIPPED, startedAt)
            return null
        }
        // Wie die Merkliste in diesem Prozess liegt, steht erst nach dem Öffnen fest — ein Hintergrund-Prozess öffnet sie
        // oft erst hier (seit Build 92 vor den beiden Prüfungen darunter)
        withContext(Dispatchers.IO) { watchRepository.openDatabase() }
        // Merkliste in diesem Prozess nur leer im Arbeitsspeicher (Schlüssel war gerade nicht lesbar): eine Prüfung sähe
        // keine Paare und keine Alarme — wie «Schlüssel nicht verfügbar» behandeln, die Einstiege versuchen es später
        if (DatabaseEncryptionNotice.isStoredKept(AppDatabase.DB_NAME)) {
            throw DatabaseKeyUnavailableException(AppDatabase.DB_NAME, IllegalStateException("nur im Arbeitsspeicher"))
        }
        // Kopie einer alten unverschlüsselten Datei (kein Schlüssel, seit Build 92): «ausgelöst» ginge mit dem Prozess
        // verloren, jeder neue Hintergrund-Prozess meldete dieselben Alarme erneut. Überspringen statt erneut versuchen —
        // das bleibt so, bis ein Start mit Schlüssel die Datei überführt; im Vordergrund prüft die App weiter
        if (DatabaseEncryptionNotice.isVolatileCopy(AppDatabase.DB_NAME)) {
            Timber.d("Prüfung (%s) übersprungen: Merkliste nur als Kopie im Arbeitsspeicher", trigger.code)
            log(trigger, CheckResult.SKIPPED, startedAt)
            return null
        }
        val summary = running.withLock { runLocked(trigger, networkGuaranteed, startedAt, deadlineAt) } ?: return null
        // Warten auf Pulse-Widget und Ansagen erst nach Freigabe der Prüf-Sperre: sonst hielte es die Sperre bis zu
        // 20 s und mehr, und ein geplanter Lauf in dieser Zeit fiele als «läuft bereits» weg
        try {
            awaitSideEffects(ttsWaitMillis, deadlineAt)
        } catch (e: CancellationException) {
            log(trigger, CheckResult.FAILED, startedAt)
            throw e
        } catch (e: Exception) {
            log(trigger, CheckResult.FAILED, startedAt)
            throw e
        }
        log(trigger, CheckRunLog.result(summary.checked, summary.failed), startedAt)
        return summary
    }

    /**
     * Pulse-Widget: Abruf läuft losgelöst (WidgetUpdater.updateAll) — begrenzt abwarten, sonst beendet Android den Prozess
     * mittendrin, und wer nur dieses Widget hat (keine Paare), sähe bloss den Zwischenspeicher. Danach die Ansagen dieses
     * Durchlaufs (begrenzt, mit Frist nur die Restzeit), sonst kann Android den Prozess vorher beenden.
     */
    private suspend fun awaitSideEffects(ttsWaitMillis: Long, deadlineAt: Long?) {
        val pulseWait = CheckBudget.waitMillis(deadlineAt, System.currentTimeMillis(), PULSE_WAIT_MILLIS, reserve = TTS_RESERVE_MILLIS)
        if (!pulseWidgets.awaitBackgroundFetch(pulseWait)) Timber.d("Pulse-Widget nach %d ms noch nicht geladen", pulseWait)
        val ttsWait = CheckBudget.waitMillis(deadlineAt, System.currentTimeMillis(), ttsWaitMillis, reserve = TTS_RESERVE_MILLIS)
        if (ttsWait > 0 && !ttsSpeaker.awaitQueued(ttsWait)) {
            Timber.d("Ansagen nach %d ms noch nicht fertig gesprochen", ttsWait)
        }
    }

    /** Die Prüfung selbst unter der Sperre; null = übersprungen (schon vermerkt). Fehler werden vermerkt und weitergereicht. */
    private suspend fun runLocked(
        trigger: CheckTrigger,
        networkGuaranteed: Boolean,
        startedAt: Long,
        deadlineAt: Long?,
    ): RefreshSummary? {
        // Nur die letzte VOLLE Prüfung zählt (Live-Takte und Teil-Läufe nicht: sie prüfen nicht alle Alarme)
        if (BackgroundSchedule.shouldSkip(trigger, refreshStats.lastFullCheck(), System.currentTimeMillis())) {
            Timber.d("Prüfung (%s) übersprungen: eben erst geprüft", trigger.code)
            log(trigger, CheckResult.SKIPPED, startedAt)
            return null
        }
        try {
            // Portfolio-Widget und Nebenprüfungen im Job abwarten (nicht losgelöst), siehe PriceRefresher.refreshAll
            // Herkunft fürs Alarm-Protokoll («im Hintergrund», «pünktlich», «über Widget»), siehe CheckOrigin
            return withContext(CheckOrigin(trigger)) {
                priceRefresher.refreshAll(
                    awaitPortfolioSnapshot = true, networkGuaranteed = networkGuaranteed, deadlineAt = deadlineAt,
                )
            }
        } catch (e: CancellationException) {
            log(trigger, CheckResult.FAILED, startedAt)
            throw e
        } catch (e: Exception) {
            log(trigger, CheckResult.FAILED, startedAt)
            throw e
        }
    }

    private companion object {
        /** Mit Frist: so viel bleibt nach dem Warten auf Ansagen (Lauf vermerken, finish()). */
        const val TTS_RESERVE_MILLIS = 300L

        /** Höchstens so lange auf den Pulse-Abruf warten (eigene Grenze der Anfrage 12 s, dazu Warten auf die Sperre). */
        const val PULSE_WAIT_MILLIS = 20_000L
    }
}
