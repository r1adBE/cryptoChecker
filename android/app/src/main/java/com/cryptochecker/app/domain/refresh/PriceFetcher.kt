package com.cryptochecker.app.domain.refresh

import com.cryptochecker.app.data.MarketRepository
import com.cryptochecker.app.data.local.model.WatchEntity
import com.cryptochecker.app.domain.model.BulkTickers
import com.cryptochecker.app.domain.model.MarketInfo
import com.cryptochecker.marketdata.model.FuturesContractType
import com.cryptochecker.marketdata.model.Ticker
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Holt die Kurse einer Börse: erst gesammelt (wo die Börse das anbietet), was fehlt, einzeln —
 * mit eigener Grenze gleichzeitiger Anfragen je Börse. Teil von [PriceRefresher].
 */
@Singleton
class PriceFetcher @Inject constructor(
    private val marketRepository: MarketRepository,
) {
    /**
     * Holt die Kurse aller Paare einer Börse; Schlüssel ist die Watch-Id.
     * Dazu der Eintrag für den Bericht: was geklappt hat und wie lange es dauerte.
     */
    internal suspend fun fetchGroup(
        group: List<WatchEntity>,
        includeRollingFutures: Boolean,
    ): Pair<Map<Long, Fetched>, FetchedGroup> {
        val groupStartedAt = System.currentTimeMillis()
        val bulk = loadBulkTickers(group)
        val bulkTickers = bulk.tickers
        val bulkMillis = System.currentTimeMillis() - groupStartedAt
        val bulkTried = group.size >= MIN_WATCHES_FOR_BULK
        // Sammelabfrage mit «zu vielen Anfragen» abgelehnt: keine Einzelabfragen hinterher,
        // das verschlimmerte es nur — die Börse wird pausiert (ExchangeBackoff).
        val bulkRateLimited = bulk.error != null &&
            RefreshReportLogic.classify(bulk.error) == RefreshFailure.RATE_LIMIT

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

                        bulkRateLimited -> Fetched(null, bulk.error)

                        else -> limit.withPermit { fetchSingle(watch) }
                    }
                }
            }.awaitAll()
        }

        val notTraded = results.count { it.second.notTraded }
        val errors = results.map { it.second }.filter { (it.ticker == null || it.error != null) && !it.notTraded }

        val entry = MarketRefresh(
            name = group.first().marketName,
            millis = System.currentTimeMillis() - groupStartedAt,
            pairs = group.size,
            updated = group.size - notTraded - errors.size,
            notTraded = notTraded,
            failed = errors.size,
            bulkTried = bulkTried,
            bulkMillis = bulkMillis,
            bulkPrices = bulkTickers.size,
            singles = results.count { it.second.fromSingle },
            reason = RefreshReportLogic.reason(errors.map { it.error }),
        )
        val errorTexts = errors.map { it.error } + listOfNotNull(bulk.error)
        val signals = FetchedGroup(
            marketKey = group.first().marketKey,
            report = entry,
            failures = errorTexts.map(RefreshReportLogic::classify),
            retryAfterMillis = ExchangeBackoff.retryAfterMillis(errorTexts),
        )

        return results.associate { (watch, fetched) -> watch.id to fetched } to signals
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

    internal suspend fun fetchSingle(watch: WatchEntity): Fetched {
        return runCatching {
            marketRepository.getMarketTicker(
                MarketInfo(watch.marketKey, watch.marketName),
                watch.toPairInfo()
            )
        }.fold(
            onSuccess = { Fetched(it.ticker, it.error, fromSingle = true) },
            onFailure = { failure ->
                // Zeitüberschreitungen kommen als CancellationException an und
                // gelten nur als Fehler dieses Paares. Abbrechen nur, wenn der
                // Durchlauf selbst abgebrochen wurde.
                currentCoroutineContext().ensureActive()
                Timber.w(failure, "Kursabfrage fehlgeschlagen: %s", watch.displayName)
                Fetched(null, failure.message, fromSingle = true)
            }
        )
    }

    private companion object {
        /** Unter so wenigen Paaren spart die Massenabfrage nichts. */
        const val MIN_WATCHES_FOR_BULK = 3

        /** Gleichzeitige Einzelabfragen je Börse. */
        const val MAX_PARALLEL_REQUESTS_PER_MARKET = 4

        const val NOT_TRADED_ERROR = NOT_TRADED_MARKER

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

/** Ergebnis der Netzabfrage für ein Paar; ticker == null heißt gescheitert. */
internal class Fetched(
    val ticker: Ticker?,
    val error: String?,
    val fromSingle: Boolean = false,
    val notTraded: Boolean = false,
)

/** Bericht einer Börse plus was die Pause je Börse ([ExchangeBackoff]) braucht. */
internal class FetchedGroup(
    val marketKey: String,
    val report: MarketRefresh,
    /** Ursachen aller Fehler (auch einer gescheiterten Sammelabfrage). */
    val failures: List<RefreshFailure>,
    val retryAfterMillis: Long?,
)
