package com.cryptochecker.app.work

import androidx.core.content.edit
import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.cryptochecker.app.R
import com.cryptochecker.app.data.remote.CycleDataSource
import com.cryptochecker.app.data.remote.InsightsDataSource
import com.cryptochecker.app.domain.market.CycleModel
import com.cryptochecker.app.domain.market.MarketZone
import com.cryptochecker.app.notification.AppNotifier
import com.cryptochecker.app.settings.SettingsRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import timber.log.Timber

/**
 * Prüft zweimal täglich die Bitcoin-Marktphase und meldet einen Wechsel
 * (z. B. Neutral → Bull). Die zuletzt gesehene Zone liegt in den Prefs;
 * beim ersten Lauf wird nur gemerkt, nicht gemeldet.
 */
@HiltWorker
class ZoneCheckWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val cycleDataSource: CycleDataSource,
    private val insightsDataSource: InsightsDataSource,
    private val settingsRepository: SettingsRepository,
    private val notifier: AppNotifier,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val prefs = applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val settings = settingsRepository.current()
        checkFearGreed(settings.fearGreedBelow, settings.fearGreedAbove, prefs)
        if (!settings.zoneAlerts) {
            // Ausgeschaltet: alte Zone vergessen, damit beim Wiedereinschalten
            // nicht ein längst vergangener Wechsel gemeldet wird.
            prefs.edit { remove(KEY_ZONE) }
            return Result.success()
        }
        return try {
            val zone = CycleModel.evaluate(cycleDataSource.fetch()).zone
            val last = prefs.getString(KEY_ZONE, null)?.let { name ->
                MarketZone.entries.firstOrNull { it.name == name }
            }
            if (last != null && last != zone) {
                notifier.showZoneChange(labelOf(last), labelOf(zone))
            }
            prefs.edit { putString(KEY_ZONE, zone.name) }
            Result.success()
        } catch (e: Exception) {
            Timber.w(e, "Marktphase konnte nicht geprüft werden")
            Result.retry()
        }
    }

    /**
     * Fear & Greed: einmal melden, wenn der Index die eingestellte Grenze
     * unter- bzw. überschreitet — nicht bei jedem Lauf erneut.
     */
    private suspend fun checkFearGreed(below: Int, above: Int, prefs: android.content.SharedPreferences) {
        if (below <= 0 && above <= 0) {
            prefs.edit { remove(KEY_FNG) }
            return
        }
        val value = runCatching { insightsDataSource.fearGreed().value }
            .onFailure { Timber.w(it, "Fear & Greed nicht verfügbar") }
            .getOrNull() ?: return
        val last = if (prefs.contains(KEY_FNG)) prefs.getInt(KEY_FNG, 50) else null
        if (below > 0 && value <= below && (last == null || last > below)) {
            notifier.showFearGreed(value, below = below)
        } else if (above > 0 && value >= above && (last == null || last < above)) {
            notifier.showFearGreed(value, above = above)
        }
        prefs.edit { putInt(KEY_FNG, value) }
    }

    private fun labelOf(zone: MarketZone): Int = when (zone) {
        MarketZone.EXTREME_BEAR -> R.string.zone_extreme_bear
        MarketZone.BEAR -> R.string.zone_bear
        MarketZone.NEUTRAL -> R.string.zone_neutral
        MarketZone.BULL -> R.string.zone_bull
        MarketZone.EXTREME_BULL -> R.string.zone_extreme_bull
    }

    companion object {
        const val WORK_NAME = "zone-check"
        private const val PREFS = "market_zone"
        private const val KEY_ZONE = "last_zone"
        private const val KEY_FNG = "last_fear_greed"
    }
}
