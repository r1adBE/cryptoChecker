package com.cryptochecker.app.work

import com.cryptochecker.app.data.RefreshStats
import com.cryptochecker.app.data.WatchRepository
import com.cryptochecker.app.domain.refresh.BackgroundSchedule
import com.cryptochecker.app.domain.refresh.CheckBudget
import com.cryptochecker.app.domain.refresh.CheckResult
import com.cryptochecker.app.domain.refresh.CheckRun
import com.cryptochecker.app.domain.refresh.CheckRunLog
import com.cryptochecker.app.domain.refresh.CheckRunStore
import com.cryptochecker.app.domain.refresh.CheckTrigger
import com.cryptochecker.app.domain.refresh.PriceRefresher
import com.cryptochecker.app.domain.refresh.RefreshSummary
import com.cryptochecker.app.tts.TtsSpeaker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
) {
    /** Nie zwei Prüfungen gleichzeitig (exakter Wecker und WorkManager können zusammenfallen). */
    private val running = Mutex()

    /** Gibt es aktive (nicht deaktivierte) Paare? Fehler: lieber ja. */
    suspend fun hasWork(): Boolean =
        runCatching { watchRepository.getWatches().any { !it.disabled } }.getOrDefault(true)

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
        return running.withLock { runLocked(trigger, networkGuaranteed, ttsWaitMillis, startedAt, deadlineAt) }
    }

    private suspend fun runLocked(
        trigger: CheckTrigger,
        networkGuaranteed: Boolean,
        ttsWaitMillis: Long,
        startedAt: Long,
        deadlineAt: Long?,
    ): RefreshSummary? {
        // Nur die letzte VOLLE Prüfung zählt (Live-Takte und Teil-Läufe nicht, Review 12)
        if (BackgroundSchedule.shouldSkip(trigger, refreshStats.lastFullCheck(), System.currentTimeMillis())) {
            Timber.d("Prüfung (%s) übersprungen: eben erst geprüft", trigger.code)
            log(trigger, CheckResult.SKIPPED, startedAt)
            return null
        }
        try {
            // Portfolio-Widget und Nebenprüfungen im Job abwarten (nicht losgelöst), siehe PriceRefresher.refreshAll
            val summary = priceRefresher.refreshAll(
                awaitPortfolioSnapshot = true, networkGuaranteed = networkGuaranteed, deadlineAt = deadlineAt,
            )
            // Ansagen dieses Durchlaufs abwarten (begrenzt, mit Frist nur die Restzeit), sonst kann
            // Android den Prozess vorher beenden
            val ttsWait = CheckBudget.waitMillis(deadlineAt, System.currentTimeMillis(), ttsWaitMillis, reserve = TTS_RESERVE_MILLIS)
            if (ttsWait > 0 && !ttsSpeaker.awaitQueued(ttsWait)) {
                Timber.d("Ansagen nach %d ms noch nicht fertig gesprochen", ttsWait)
            }
            log(trigger, CheckRunLog.result(summary.checked, summary.failed), startedAt)
            return summary
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
    }
}
