package com.cryptochecker.app.domain.activity

import com.cryptochecker.app.data.ActivityRepository
import com.cryptochecker.app.data.WatchRepository
import com.cryptochecker.app.data.local.model.WatchEntity
import com.cryptochecker.app.data.remote.CandleInterval
import com.cryptochecker.app.data.remote.candles.OwnCandlePolicy
import com.cryptochecker.app.data.remote.FuturesDataSource
import com.cryptochecker.app.data.remote.FuturesInfo
import com.cryptochecker.app.data.remote.FuturesSources
import com.cryptochecker.app.data.remote.InsightsDataSource
import com.cryptochecker.app.data.remote.SpotDataSource
import com.cryptochecker.app.data.remote.VolumeDataSource
import com.cryptochecker.app.domain.market.DataFreshness
import com.cryptochecker.app.domain.market.FearGreed
import com.cryptochecker.app.domain.watch.NotTraded
import com.cryptochecker.app.domain.watch.isNotTraded
import com.cryptochecker.app.notification.AppNotifier
import com.cryptochecker.app.settings.SettingsRepository
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
    private val spotDataSource: SpotDataSource,
    private val repository: ActivityRepository,
    private val watchRepository: WatchRepository,
    private val settingsRepository: SettingsRepository,
    private val notifier: AppNotifier,
    private val candlePairs: com.cryptochecker.app.data.remote.candles.CandlePairs,
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
        val all = watchRepository.getWatches()
        // Nicht mehr gehandelte Paare: keine Auswertung, gespeicherte Signale (und ihre
        // Meldung) weg — sonst stünde ⚡ samt «Ungewöhnliche Bewegung» auf alten Kerzen da.
        // Deaktivierte Paare ebenso: keine Kerzen-Abfragen, keine ⚡-Signale, kein «Warum?»
        val notTraded = NotTraded.ids(all, { it.id }, { it.lastError }) + all.filter { it.disabled }.map { it.id }
        notTraded.filter { repository.report(it) != null }.forEach { notifier.cancelActivity(it) }
        val watches = all.filter { it.id !in notTraded }
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
                            val merge = runCatching { analyzeOne(watch, now, settings.activitySensitivity) }
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

        // Meldungen: höchstens eine je Paar und Stunde, nur bei neuen Signalen. Kommt die Meldung
        // nicht an (Erlaubnis, Mitteilungen aus, Kanal stumm), gilt nichts als gemeldet: Die Arten
        // bleiben offen und melden sich beim nächsten Lauf (Nachtruhe: lautlos wie bisher)
        var deliverable: Boolean? = null
        results.values.forEach { (watch, merge) ->
            val pending = repository.pendingKinds(watch.id)
            val kinds = ActivityAnalyzer.noticeKinds(merge.newKinds, pending, merge.report, now)
            val notify = ActivityAnalyzer.shouldNotify(
                enabled = settings.activityAlerts,
                newKinds = kinds,
                lastNotifiedAt = repository.lastNotifiedAt(watch.id),
                now = now,
            )
            val top = if (notify) merge.report.signals.firstOrNull { it.kind in kinds } else null
            val canShow = top != null && (deliverable ?: notifier.canShowMarketNotice().also { deliverable = it })
            val posted = top != null && canShow && notifier.showActivity(watch, top)
            if (posted) repository.setNotifiedAt(watch.id, now)
            val after = ActivityAnalyzer.pendingAfter(kinds, notify = top != null, posted = posted)
            if (after != pending) repository.setPendingKinds(watch.id, after)
        }
    }

    private suspend fun analyzeOne(
        watch: WatchEntity,
        now: Long,
        sensitivity: ActivitySensitivity,
    ): ActivityAnalyzer.Merge = coroutineScope {
        // Wie der Chart: eigene Börse, sonst Ausweich-Kette nur bei gleichem Token (nie für Laufzeit-Kontrakte)
        val candlesJob = async {
            timed {
                volumeDataSource.pairCandles(candlePairs.of(watch), watch.lastPrice, CandleInterval.H1, VolumeDataSource.LIMIT)?.value
            }
        }
        val futuresJob = async {
            if (watch.contractType.isPerpetual) futures { futuresDataSource.fetch(watch) } else null
        }
        // Veraltete oder unplausible Reihen (z. B. Spot nach Delisting) liefern keine Signale
        val stats = CandleSeries.usable(candlesJob.await(), now, rolling24h(watch))?.let { ActivityAnalyzer.hourStats(it) }
        val futures = futuresJob.await()

        val oi = openInterest(watch, futures, now, store = true)
        val fresh = ActivityAnalyzer.signals(
            stats = stats,
            fundingPercent = futures?.fundingRatePercent,
            // ⚡ nur bei einheitlicher Richtung gegenüber den älteren Messungen
            oiChangePercent = oi?.let { if (it.consistent) it.changePercent else null },
            oiMinutes = oi?.minutes,
            now = now,
            sensitivity = sensitivity,
            // Stündliches Funding (Hyperliquid) zählt für die Schwellen achtfach
            fundingSource = futures?.source,
            fundingIntervalHours = futures?.fundingIntervalHours,
        )
        ActivityAnalyzer.merge(repository.report(watch.id), fresh, now, sensitivity)
    }

    /**
     * Open Interest in Coins (USD-Wert / letzter Kurs), damit eine reine Kursbewegung nicht als
     * OI-Sprung zählt. Vergleich gegen den gespeicherten Verlauf ([OiSeries]: Median der älteren
     * Messungen derselben Quelle); [store] = Messung anhängen (höchstens alle 30 Min.).
     */
    private fun openInterest(watch: WatchEntity, futures: FuturesInfo?, now: Long, store: Boolean): OiSeries.Result? {
        val usd = futures?.openInterestUsd ?: return null
        val price = watch.lastPrice?.takeIf { it > 0.0 } ?: return null
        val units = usd / price
        val series = repository.oiSeries(watch.id)
        val result = OiSeries.change(series, units, futures.source, now)
        if (store) {
            val updated = OiSeries.append(series, OiSample(units, now, futures.source), now)
            if (updated != series) repository.setOiSeries(watch.id, updated)
        }
        return result
    }

    // ---------------- «Warum bewegt sich das?» ----------------

    /**
     * Lädt alles für das «Warum»-Blatt gleichzeitig, jede Quelle mit eigener Zeitgrenze:
     * Stundenkerzen des Paars, von BTC (bei Bitcoin ETH) und ETH (nicht für BTC/ETH selbst),
     * den breiten Markt (CoinGecko-Top-40), Futures-Kennzahlen samt Funding-Verlauf,
     * Fear & Greed und Tageskerzen (30-Tage-Hoch/-Tief).
     */
    suspend fun explain(watch: WatchEntity): WhyReport = withContext(Dispatchers.IO) {
        // Nicht mehr gehandelt bzw. deaktiviert: nichts laden, nichts einordnen (die Merkliste bietet «Warum?» dann nicht an)
        if (watch.isNotTraded || watch.disabled) return@withContext ActivityAnalyzer.notLive(System.currentTimeMillis())
        coroutineScope {
            val base = watch.baseAsset.trim().uppercase()
            val isBtc = base == "BTC"
            val isEth = base == "ETH"
            val limit = ActivityAnalyzer.WHY_HOURLY_LIMIT
            // Kerzen der eigenen Börse des Paars; sonst Ausweich-Kette (Futures-Paar: USDⓈ-M zuerst) nur
            // bei gleichem Token wie der Chart ([VolumeDataSource.pairCandles])
            val pair = candlePairs.of(watch)
            val candlesJob = async {
                timed { volumeDataSource.pairCandles(pair, watch.lastPrice, CandleInterval.H1, limit) }
            }
            // Stablecoins: kein Markt-Vergleich (Abweichung von 1:1), also auch keine Vergleichsdaten dafür
            val stable = StablePeg.isPegged(watch.baseAsset, watch.quoteAsset)
            val compare = !isBtc && !stable
            val referenceJob = async {
                if (stable) {
                    null
                } else {
                    timed { volumeDataSource.hourlyCandlesSourced(if (isBtc) "ETH" else "BTC", "USDT", limit = limit) }
                }
            }
            // Zweite Markt-Referenz ETH (für BTC ist ETH schon die Referenz, für ETH ist BTC sie)
            val ethJob = async {
                if (!compare || isEth) {
                    null
                } else {
                    timed { volumeDataSource.hourlyCandlesSourced("ETH", "USDT", limit = limit) }
                }
            }
            // Breiter Markt aus der CoinGecko-Top-40 (dieselbe Abfrage wie der Spot-Tab)
            val broadJob = async {
                if (compare) {
                    timed { spotDataSource.broadMarket(base) }
                } else {
                    null
                }
            }
            val futuresJob = async {
                futures {
                    if (watch.contractType.isPerpetual) futuresDataSource.fetch(watch)
                    else futuresDataSource.fetchForBase(watch.baseAsset)
                }
            }
            val historyJob = async { timed { futuresDataSource.fundingHistory(watch) } }
            val fearGreedJob = async { timed { fearGreed() } }
            // Tageskerzen für «Nähe zum Hoch/Tief» (30 Tage); fehlen sie, entfallen nur diese Zeilen
            val dailyJob = async {
                timed { volumeDataSource.pairCandles(pair, watch.lastPrice, CandleInterval.D1, DAILY_LIMIT)?.value }
            }

            val futures = futuresJob.await()
            val oi = if (watch.contractType.isPerpetual) {
                openInterest(watch, futures, System.currentTimeMillis(), store = true)
            } else {
                null
            }
            val fearGreed = fearGreedJob.await()
            val candles = candlesJob.await()
            val reference = referenceJob.await()
            val broad = broadJob.await()
            val now = System.currentTimeMillis()

            ActivityAnalyzer.explain(
                WhyInput(
                    baseAsset = watch.baseAsset,
                    candles = candles?.value,
                    referenceCandles = reference?.value,
                    fundingPercent = futures?.fundingRatePercent,
                    openInterestChangePercent = oi?.changePercent,
                    fearGreed = fearGreed?.value,
                    fearGreedYesterday = fearGreed?.yesterday,
                    now = now,
                    // Dieselbe 24-h-Veränderung wie Änderung und Merkliste (bei «seit 00:00» aus den Kerzen)
                    tickerChange24h = rolling24h(watch),
                    marketLive = !watch.isNotTraded,
                    dailyCandles = dailyJob.await(),
                    quoteAsset = watch.quoteAsset,
                    ethCandles = ethJob.await()?.value,
                    broadMarket = broad,
                    openInterestMinutes = oi?.minutes,
                    openInterestConsistent = oi?.consistent ?: true,
                    fundingHistory = historyJob.await(),
                    fundingSource = futures?.source,
                    fundingIntervalHours = futures?.fundingIntervalHours,
                    sources = WhySources(
                        candles = candles?.provider,
                        // Eigene Börse ist nie Ersatzquelle; Binance-Kerzen eines Phemex-Paars schon
                        candlesFallback = WhyFreshness.candlesFallback(listOf(watch.marketKey, watch.marketName), candles?.provider),
                        reference = reference?.provider?.takeIf { it != candles?.provider },
                        futures = futures?.source,
                        futuresNote = futures?.let { futuresNote(watch, it.source) },
                        fearGreedTime = fearGreed?.time,
                        broadTime = broad?.time,
                    ),
                )
            )
        }
    }

    /**
     * Wie die Futures-Quelle zum Paar steht ([FuturesSources.note]): Daten der beobachteten Börse,
     * Ausweichquelle oder Richtwert einer anderen Börse (Spot-Paare, Börsen ohne eigene Daten).
     */
    private fun futuresNote(watch: WatchEntity, source: String): FuturesSourceNote? =
        FuturesSources.note(watch.contractType.isPerpetual, watch.marketKey, source, watch.pairId)

    /**
     * Gespeicherte Veränderung des Paars, wenn sie rollend über 24 Stunden gilt; bei einer
     * Tages-Basis der %-Änderung («seit 00:00») null — «Warum?» rechnet dann aus den Kerzen.
     */
    private fun rolling24h(watch: WatchEntity): Double? =
        watch.change24h.takeIf { !settingsRepository.cached.changeBasis.isDay }

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

        /** 30 Tage + laufender Tag (Hoch und Tief). */
        const val DAILY_LIMIT = ActivityAnalyzer.HIGH_DAYS + 1
    }
}
