package com.cryptochecker.app.work

import com.cryptochecker.app.settings.MarketAlertReset
import com.cryptochecker.app.settings.ZoneCheckPrefs
import androidx.core.content.edit
import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.cryptochecker.app.data.remote.InsightsDataSource
import com.cryptochecker.app.domain.market.BitcoinIndicatorModel
import com.cryptochecker.app.domain.market.IndicatorAlertKind
import com.cryptochecker.app.domain.market.IndicatorAlertRules
import com.cryptochecker.app.domain.market.MarketNoticeGate
import com.cryptochecker.app.notification.IndicatorTexts
import com.cryptochecker.app.notification.AppNotifier
import com.cryptochecker.app.settings.SettingsRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import com.cryptochecker.app.notification.canShowMarketNotice
import com.cryptochecker.app.notification.showIndicatorAlert
import com.cryptochecker.app.notification.showFearGreed

/**
 * Prüft zweimal täglich den Fear & Greed Index und die eigenen Grenzen der Bitcoin-Kennzahlen und meldet,
 * wenn ein Wert die eingestellte Grenze unter- bzw. überschreitet (reine Zahl, keine Deutung). Der zuletzt gesehene Wert liegt in
 * den Prefs.
 */
@HiltWorker
class ZoneCheckWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val insightsDataSource: InsightsDataSource,
    private val cycleDataSource: com.cryptochecker.app.data.remote.CycleDataSource,
    private val settingsRepository: SettingsRepository,
    private val notifier: AppNotifier,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val prefs = applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // Unter der Sperre des Zurücksetzens (MarketAlertReset): Wird eine Grenze während des Abrufs ausgeschaltet,
        // wartet das «Aus», bis diese Prüfung geschrieben hat — sie überschreibt es also nicht mit dem alten Stand
        val failed = ZoneCheckPrefs.lock.withLock {
            val settings = settingsRepository.current()
            val fearGreedFailed = !checkFearGreed(settings.fearGreedBelow, settings.fearGreedAbove, prefs)
            val indicatorsFailed = !checkIndicators(settings.indicatorAlerts, prefs)
            fearGreedFailed || indicatorsFailed
        }
        // Abruf gescheitert: mit dem Backoff von WorkManager (MarketNoticeGate.retryDelayMillis) erneut statt
        // erst in 12 h. Keine Doppelmeldung: Gemeldetes ist in den Prefs vermerkt (KEY_FNG bzw.
        // «crossed»), der nächste Versuch meldet es nicht noch einmal.
        if (failed && runAttemptCount < MarketNoticeGate.MAX_RETRIES) {
            Timber.i("Marktmeldungen: Abruf gescheitert, Versuch %d — erneut mit Backoff", runAttemptCount + 1)
            return Result.retry()
        }
        return Result.success()
    }

    /**
     * Eigene Grenzen der Bitcoin-Kennzahlen ([IndicatorAlertRules]): einmal melden, wenn ein Wert die Grenze
     * erreicht; wieder scharf, sobald er zurück ist. Kam die Meldung nicht an, bleibt sie offen.
     * @return false, wenn die Kennzahlen nicht abrufbar waren (dann erneuter Versuch)
     */
    private suspend fun checkIndicators(stored: Map<IndicatorAlertKind, Int>, prefs: android.content.SharedPreferences): Boolean {
        val active = IndicatorAlertRules.active(stored)
        // Ausgeschaltete Grenzen vergessen (beim Wiedereinschalten neu bewerten)
        IndicatorAlertKind.entries.filter { it !in active }.forEach { kind -> MarketAlertReset.indicatorOff(prefs, kind) }
        if (active.isEmpty()) return true
        val report = runCatching { BitcoinIndicatorModel.evaluate(cycleDataSource.fetch()) }
            .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
            .onFailure { Timber.w(it, "Bitcoin-Kennzahlen nicht verfügbar") }
            .getOrNull() ?: return false
        for ((kind, threshold) in active) {
            val value = report.indicators.firstOrNull { it.id == kind.indicator }?.value
            // Gemerkter Zustand gilt nur für die Grenze, zu der er gehört: eine neue Grenze bewertet neu
            val storedThreshold = stored[kind] ?: 0
            val wasCrossed = IndicatorAlertRules.wasCrossed(
                prefs.getBoolean(crossedKey(kind), false), storedInt(prefs, thresholdKey(kind)), storedThreshold,
            )
            val decision = IndicatorAlertRules.decide(kind, value, threshold, wasCrossed)
            var crossed = decision.crossed
            if (decision.notify) {
                val posted = notifier.canShowMarketNotice() && value != null && notifier.showIndicatorAlert(
                    kind,
                    name = IndicatorTexts.name(applicationContext, kind.indicator),
                    value = IndicatorTexts.value(kind.indicator, value),
                    threshold = IndicatorTexts.value(kind.indicator, threshold),
                )
                // Nicht angekommen: später erneut versuchen
                if (!posted) crossed = null
            }
            crossed?.let { c -> prefs.edit { putBoolean(crossedKey(kind), c); putInt(thresholdKey(kind), storedThreshold) } }
        }
        return true
    }


    /**
     * Fear & Greed: einmal melden, wenn der Index die eingestellte Grenze
     * unter- bzw. überschreitet — nicht bei jedem Lauf erneut.
     * @return false, wenn der Index nicht abrufbar war (dann erneuter Versuch)
     */
    private suspend fun checkFearGreed(below: Int, above: Int, prefs: android.content.SharedPreferences): Boolean {
        if (below <= 0 && above <= 0) {
            prefs.edit { remove(KEY_FNG); remove(KEY_FNG_BELOW); remove(KEY_FNG_ABOVE) }
            return true
        }
        val value = runCatching { insightsDataSource.fearGreed().value }
            .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
            .onFailure { Timber.w(it, "Fear & Greed nicht verfügbar") }
            .getOrNull() ?: return false
        val last = if (prefs.contains(KEY_FNG)) prefs.getInt(KEY_FNG, 50) else null
        // Grenze seit dem gemerkten Stand geändert: für sie zählt der alte Stand nicht
        val belowChanged = MarketNoticeGate.boundChanged(storedInt(prefs, KEY_FNG_BELOW), below)
        val aboveChanged = MarketNoticeGate.boundChanged(storedInt(prefs, KEY_FNG_ABOVE), above)
        fun decide(canNotify: Boolean) =
            MarketNoticeGate.fearGreed(value, last, below, above, canNotify, belowChanged, aboveChanged)
        // Erlaubnis/Kanal nur prüfen, wenn es etwas zu melden gibt
        val pending = decide(canNotify = true)
        val decision = if (pending.step == MarketNoticeGate.Step.NOTIFY) {
            decide(canNotify = notifier.canShowMarketNotice())
        } else pending
        val posted = when (decision.kind.takeIf { decision.step == MarketNoticeGate.Step.NOTIFY }) {
            MarketNoticeGate.FearGreedKind.BELOW -> notifier.showFearGreed(value, below = below)
            MarketNoticeGate.FearGreedKind.ABOVE -> notifier.showFearGreed(value, above = above)
            null -> false
        }
        // Nicht angekommen: alten Wert behalten, damit die Grenze später noch gemeldet wird
        if (MarketNoticeGate.storeAfterNotify(decision.step, posted)) {
            prefs.edit { putInt(KEY_FNG, value); putInt(KEY_FNG_BELOW, below); putInt(KEY_FNG_ABOVE, above) }
        }
        return true
    }

    private fun storedInt(prefs: android.content.SharedPreferences, key: String): Int? =
        if (prefs.contains(key)) prefs.getInt(key, 0) else null

    companion object {
        const val WORK_NAME = "zone-check"
        /** Gemerkter Stand; beim Ausschalten in den Einstellungen zurückgesetzt (MarketAlertReset). */
        internal const val PREFS = ZoneCheckPrefs.PREFS
        internal const val KEY_FNG = ZoneCheckPrefs.KEY_FNG
        /** Grenzen, zu denen der gemerkte Stand gehört. */
        internal const val KEY_FNG_BELOW = ZoneCheckPrefs.KEY_FNG_BELOW
        internal const val KEY_FNG_ABOVE = ZoneCheckPrefs.KEY_FNG_ABOVE

        internal fun crossedKey(kind: IndicatorAlertKind) = ZoneCheckPrefs.crossedKey(kind)

        /** Grenze (gespeicherte Ganzzahl), zu der der gemerkte Zustand «jenseits» gehört. */
        internal fun thresholdKey(kind: IndicatorAlertKind) = ZoneCheckPrefs.thresholdKey(kind)
    }
}
