package com.cryptochecker.app.domain.activity

import com.cryptochecker.app.data.ActivityRepository
import com.cryptochecker.app.data.WatchRepository
import com.cryptochecker.app.data.local.model.WatchEntity
import com.cryptochecker.app.data.remote.FuturesDataSource
import com.cryptochecker.app.data.remote.FuturesInfo
import com.cryptochecker.app.data.remote.InsightsDataSource
import com.cryptochecker.app.data.remote.VolumeDataSource
import com.cryptochecker.app.domain.market.FearGreed
import com.cryptochecker.app.notification.AppNotifier
import com.cryptochecker.app.settings.SettingsRepository
import com.cryptochecker.marketdata.model.FuturesContractType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Holt die Marktdaten für «Ungewöhnliche Aktivität» und «Warum bewegt sich das?»
 * und gibt sie an den reinen [ActivityAnalyzer]. Fehler bleiben still: Ohne
 * Daten gibt es eben keine Signale.
 */
@Singleton
class ActivityMonitor @Inject constructor(
    private val volumeDataSource: VolumeDataSource,
    private val futuresDataSource: FuturesDataSource,
    private val insightsDataSource: InsightsDataSource,
    private val repository: ActivityRepository,
    private val watchRepository: WatchRepository,
    private val settingsRepository: SettingsRepository,
    private val notifier: AppNotifier,
) {
    /** Nur eine Auswertung zur Zeit; läuft schon eine, wird die neue übersprungen. */
    private val running = Mutex()

    @Volatile private var fearGreedCache: Pair<Long, FearGreed>? = null

    /**
     * Prüft alle Paare, deren letzte Prüfung über 10 Min. her ist. Läuft nach
     * dem Speichern der Kurse und ausserhalb der Sperre des PriceRefreshers;
     * höchstens [BUDGET_MILLIS] lang — was bis dahin fertig ist, wird gespeichert,
     * der Rest kommt beim nächsten Durchlauf dran.
     */
    suspend fun analyzeAll() {
        if (!running.tryLock()) return
        try {
            withContext(Dispatchers.IO) { analyzeLocked() }
        } catch (ex: CancellationException) {
            throw ex
        } catch (ex: Exception) {
            Timber.d(ex, "Aktivitätsauswertung fehlgeschlagen")
        } finally {
            running.unlock()
        }
    }

    private suspend fun analyzeLocked() {
        val watches = watchRepository.getWatches()
        repository.retain(watches.map { it.id }.toSet())
        if (watches.isEmpty()) return

        val settings = settingsRepository.current()
        val now = System.currentTimeMillis()
        val due = watches.filter { ActivityAnalyzer.isDue(repository.report(it.id), now) }
        if (due.isEmpty()) return

        val results = ConcurrentHashMap<Long, Pair<WatchEntity, ActivityAnalyzer.Merge>>()
        val permits = Semaphore(MAX_PARALLEL)
        withTimeoutOrNull(BUDGET_MILLIS) {
            coroutineScope {
                due.map { watch ->
                    async {
                        permits.withPermit {
                            val merge = runCatching { analyzeOne(watch, now) }
                                .onFailure { Timber.d(it, "Aktivität nicht prüfbar: %s", watch.displayName) }
                                .getOrNull()
                            // Abbruch des ganzen Durchlaufs (Zeitbudget) nicht verschlucken
                            currentCoroutineContext().ensureActive()
                            if (merge != null) results[watch.id] = watch to merge
                        }
                    }
                }.awaitAll()
            }
        }

        repository.putAll(results.mapValues { it.value.second.report })

        // Meldungen: höchstens eine je Paar und Stunde, nur bei neuen Signalen
        results.values.forEach { (watch, merge) ->
            val notify = ActivityAnalyzer.shouldNotify(
                enabled = settings.activityAlerts,
                newKinds = merge.newKinds,
                lastNotifiedAt = repository.lastNotifiedAt(watch.id),
                now = now,
            )
            if (!notify) return@forEach
            val top = merge.report.signals.firstOrNull { it.kind in merge.newKinds } ?: return@forEach
            notifier.showActivity(watch, top)
            repository.setNotifiedAt(watch.id, now)
        }
    }

    private suspend fun analyzeOne(watch: WatchEntity, now: Long): ActivityAnalyzer.Merge = coroutineScope {
        val candlesJob = async { timed { volumeDataSource.hourlyCandles(watch.baseAsset, watch.quoteAsset) } }
        val futuresJob = async {
            if (watch.contractType == FuturesContractType.PERPETUAL) futures { futuresDataSource.fetch(watch) } else null
        }
        val stats = candlesJob.await()?.let { ActivityAnalyzer.hourStats(it) }
        val futures = futuresJob.await()

        val oi = openInterest(watch, futures, now, store = true)
        val fresh = ActivityAnalyzer.signals(
            stats = stats,
            fundingPercent = futures?.fundingRatePercent,
            oiChangePercent = oi?.changePercent,
            oiMinutes = oi?.minutes,
            now = now,
        )
        ActivityAnalyzer.merge(repository.report(watch.id), fresh, now)
    }

    /**
     * Open Interest in Coins (USD-Wert / letzter Kurs), damit eine reine
     * Kursbewegung nicht als OI-Sprung zählt. Vergleich gegen die gespeicherte
     * Messung; [store] = neue Messung bei Bedarf ablegen.
     */
    private fun openInterest(watch: WatchEntity, futures: FuturesInfo?, now: Long, store: Boolean): OiUpdate? {
        val usd = futures?.openInterestUsd ?: return null
        val price = watch.lastPrice?.takeIf { it > 0.0 } ?: return null
        val units = usd / price
        val update = ActivityAnalyzer.oiChange(repository.oiSample(watch.id), units, now)
        if (store && update.store) repository.setOiSample(watch.id, OiSample(units, now))
        return update
    }

    // ---------------- «Warum bewegt sich das?» ----------------

    /** Lädt alles für das «Warum»-Blatt gleichzeitig, jede Quelle mit eigener Zeitgrenze. */
    suspend fun explain(watch: WatchEntity): WhyReport = withContext(Dispatchers.IO) {
        coroutineScope {
            val isBtc = watch.baseAsset.equals("BTC", ignoreCase = true)
            val candlesJob = async { timed { volumeDataSource.hourlyCandles(watch.baseAsset, watch.quoteAsset) } }
            val referenceJob = async { timed { volumeDataSource.hourlyCandles(if (isBtc) "ETH" else "BTC", "USDT") } }
            val futuresJob = async {
                futures {
                    if (watch.contractType == FuturesContractType.PERPETUAL) futuresDataSource.fetch(watch)
                    else futuresDataSource.fetchForBase(watch.baseAsset)
                }
            }
            val fearGreedJob = async { timed { fearGreed() } }

            val futures = futuresJob.await()
            val oi = if (watch.contractType == FuturesContractType.PERPETUAL)
                openInterest(watch, futures, System.currentTimeMillis(), store = false) else null
            val fearGreed = fearGreedJob.await()

            ActivityAnalyzer.explain(
                WhyInput(
                    baseAsset = watch.baseAsset,
                    candles = candlesJob.await(),
                    referenceCandles = referenceJob.await(),
                    fundingPercent = futures?.fundingRatePercent,
                    openInterestChangePercent = oi?.changePercent,
                    fearGreed = fearGreed?.value,
                    fearGreedYesterday = fearGreed?.yesterday,
                    now = System.currentTimeMillis(),
                )
            )
        }
    }

    private suspend fun fearGreed(): FearGreed {
        val now = System.currentTimeMillis()
        fearGreedCache?.let { (time, value) -> if (now - time in 0 until FNG_CACHE_MILLIS) return value }
        return insightsDataSource.fearGreed().also { fearGreedCache = now to it }
    }

    /** Futures-Abfrage mit Zeitgrenze; null bei Fehler oder ohne Kontrakt. */
    private suspend fun futures(block: suspend () -> FuturesInfo?): FuturesInfo? = timed(block)

    private suspend fun <T> timed(block: suspend () -> T?): T? {
        val result = runCatching { withTimeoutOrNull(SOURCE_TIMEOUT_MILLIS) { block() } }.getOrNull()
        // Zeitüberschreitungen einzelner Abfragen schlucken, echten Abbruch nicht
        currentCoroutineContext().ensureActive()
        return result
    }

    private companion object {
        const val MAX_PARALLEL = 4
        const val BUDGET_MILLIS = 20_000L
        const val SOURCE_TIMEOUT_MILLIS = 8_000L
        const val FNG_CACHE_MILLIS = 10 * 60_000L
    }
}
