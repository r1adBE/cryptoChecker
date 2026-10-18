package com.cryptochecker.app.domain.refresh

import com.cryptochecker.app.data.MarketRepository
import com.cryptochecker.app.data.local.model.WatchEntity
import com.cryptochecker.app.data.remote.util.runCatchingCancellable
import com.cryptochecker.app.domain.model.BulkTickers
import com.cryptochecker.app.domain.model.MarketInfo
import com.cryptochecker.app.domain.watch.NotTraded
import com.cryptochecker.marketdata.model.FuturesContractType
import com.cryptochecker.marketdata.model.Ticker
import com.cryptochecker.marketdata.util.BulkTickerRows
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
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
    /** Je Börse: nicht mehr gehandelte Paare der Sammelabfrage ([BulkDelisted]), prozessweit. */
    private val delisted = ConcurrentHashMap<String, BulkDelisted.State>()

    /**
     * Holt die Kurse aller Paare einer Börse; Schlüssel ist die Watch-Id.
     * Dazu der Eintrag für den Bericht: was geklappt hat und wie lange es dauerte.
     * [deadlineAt] (exakter Wecker, [CheckBudget]): danach keine Abfrage mehr — was bis dahin kam,
     * zählt, der Rest gilt als Zeitüberschreitung dieses Paares.
     */
    internal suspend fun fetchGroup(
        group: List<WatchEntity>,
        includeRollingFutures: Boolean,
        deadlineAt: Long? = null,
        /** Paare mit aktivem Alarm: werden einzeln zuerst abgefragt ([FetchOrder]). */
        alarmed: Set<Long> = emptySet(),
    ): Pair<Map<Long, Fetched>, FetchedGroup> {
        val groupStartedAt = System.currentTimeMillis()
        val groupDeadline = CheckBudget.groupDeadline(groupStartedAt, deadlineAt)
        val bulk = loadBulkTickers(group, groupDeadline)
        val bulkTickers = bulk.tickers
        val bulkMillis = System.currentTimeMillis() - groupStartedAt
        val bulkTried = group.size >= MIN_WATCHES_FOR_BULK
        // Sammelabfrage mit «zu vielen Anfragen» abgelehnt: keine Einzelabfragen hinterher,
        // das verschlimmerte es nur — die Börse wird pausiert (ExchangeBackoff).
        val bulkRateLimited = bulk.error != null &&
            RefreshReportLogic.classify(bulk.error) == RefreshFailure.RATE_LIMIT
        // Antwort mehrheitlich unlesbar (Format der Börse geändert): Einzelabfragen mit demselben Leser brächten
        // nichts — alle Paare dieses Durchlaufs als Fehler, kein Nachhaken (BulkTickerRows)
        val bulkFormatError = BulkTickerRows.isFormatError(bulk.error)

        // Eigene Grenze je Börse, damit niemand ins Rate-Limit läuft.
        val limit = Semaphore(MAX_PARALLEL_REQUESTS_PER_MARKET)
        // Erste Einzelabfrage mit 429/418: die übrigen dieser Börse nicht mehr senden (SingleRateGate)
        val gate = SingleRateGate()

        val results = coroutineScope {
            FetchOrder.alarmsFirst(group, alarmed) { it.id }.map { watch ->
                async {
                    val bulkTicker = watch.pairId?.let { bulkTickers[it] }
                    watch to when {
                        bulkTicker != null -> Fetched(bulkTicker, null)

                        // Zeile da, aber unlesbar: Fehler (veralteter Kurs), nicht frisch und nicht «unverändert»
                        watch.pairId in bulk.failed -> Fetched(null, BulkTickerRows.ROW_ERROR)

                        // Vollständige Liste ohne dieses Paar: erst einmal einzeln nachfragen,
                        // «nicht mehr gehandelt» erst, wenn auch das keinen Kurs bringt. Schon so
                        // gespeicherte Paare ohne erneute Einzelabfrage (NotTraded.askSingleWhenMissing).
                        // Laufzeit-Futures nur nachprüfen, wenn so eingestellt.
                        // Teilabfrage dieses Paars gescheitert (z. B. Alpha bei Binance): keine Aussage über sein Fehlen;
                        // nach 429/418 keine Einzelabfragen zum Nachprüfen
                        bulk.complete && watch.pairId !in bulk.incomplete && !bulkRateLimited &&
                            (!includeRollingFutures || watch.contractType !in ROLLING_CONTRACTS) ->
                            if (NotTraded.askSingleWhenMissing(watch.lastError)) {
                                withinBudget(groupDeadline) { limit.withPermit { gate.send { confirmMissing(watch) } } }
                            } else {
                                Fetched(null, NOT_TRADED_ERROR, notTraded = true)
                            }

                        bulkRateLimited || bulkFormatError -> Fetched(null, bulk.error)

                        else -> withinBudget(groupDeadline) { limit.withPermit { gate.send { fetchSingle(watch) } } }
                    }
                }
            }.awaitAll()
        }

        val notTraded = results.count { it.second.notTraded }
        val errors = results.map { it.second }.filter { (it.ticker == null || it.error != null) && !it.notTraded }
        // Am eigenen Budget gescheitert: zählt als Fehler im Bericht, aber nicht für die Pause je Börse
        val cut = errors.count { it.budgetCut }

        val reason = RefreshReportLogic.reason(errors.map { it.error })
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
            reason = reason,
            detail = StoredError.reportDetail(errors.map { it.error }, reason),
        )
        val errorTexts = errors.filterNot { it.budgetCut }.map { it.error } +
            listOfNotNull(bulk.error.takeUnless { bulk.budgetCut })
        val signals = FetchedGroup(
            marketKey = group.first().marketKey,
            report = entry,
            failures = errorTexts.map(RefreshReportLogic::classify),
            retryAfterMillis = ExchangeBackoff.retryAfterMillis(errorTexts),
            banned = ExchangeBackoff.banned(errorTexts),
            cutByBudget = cut + (if (bulk.budgetCut) 1 else 0),
        )

        return results.associate { (watch, fetched) -> watch.id to fetched } to signals
    }

    /**
     * Holt die Kurse einer Börse gesammelt, sofern sie das anbietet und sich
     * der Aufwand lohnt. Wo möglich nur für die beobachteten Paare. Bei einem
     * Fehler bleibt die Karte leer und jedes Paar wird einzeln abgefragt.
     */
    private suspend fun loadBulkTickers(group: List<WatchEntity>, deadline: Long?): BulkResult {
        if (group.size < MIN_WATCHES_FOR_BULK) return BulkResult(BulkTickers())

        val sample = group.first()
        val market = MarketInfo(sample.marketKey, sample.marketName)
        val pairIds = group.mapNotNull { it.pairId }.distinct()
        // Nicht mehr gehandelte Paare nicht mitschicken: ein delistetes Symbol verwirft bei Binance die ganze
        // gefilterte Liste → jedes Mal die ungefilterte (~2 MB). Sie fehlen dann in der vollständigen Liste (NotTraded).
        val notTradedIds = group.filter { NotTraded.isMarker(it.lastError) }.mapNotNullTo(HashSet()) { it.pairId }
        val plan = BulkDelisted.plan(delisted[sample.marketKey], pairIds, notTradedIds, System.currentTimeMillis())

        var cut = false
        // Abbruch des Durchlaufs weiterreichen (nicht als gescheiterte Massenabfrage werten)
        val tickers = runCatchingCancellable {
            if (!marketRepository.isMarketSupportsBulkTickers(market)) return BulkResult(BulkTickers())
            // Alle beobachteten Paare nicht mehr gehandelt: keine Abfrage, die Liste gilt als vollständig ohne sie
            if (plan.request.isEmpty()) return BulkResult(BulkTickers(complete = true))
            withinBudget(deadline, onTimeout = { cut = true; BulkTickers(error = CheckBudget.TIMEOUT_ERROR) }) {
                marketRepository.getBulkTickers(market, plan.request)
            }.also { r ->
                delisted[sample.marketKey] = BulkDelisted.learn(
                    delisted[sample.marketKey], plan, r.tickers.keys, r.failed, r.complete, System.currentTimeMillis(),
                    r.incomplete,
                )
            }
        }
            .onFailure { Timber.w(it, "Massenabfrage fehlgeschlagen: %s", sample.marketName) }
            .onSuccess { r ->
                if (BulkTickerRows.isFormatError(r.error)) Timber.w("Massenabfrage Formatfehler: %s — %s", sample.marketName, r.error)
            }
            .getOrDefault(BulkTickers())
        return BulkResult(tickers, cut)
    }

    /**
     * [block] höchstens bis [deadline] (ohne Frist unbegrenzt); danach [onTimeout] — das Paar gilt als
     * Zeitüberschreitung ([Fetched.budgetCut]). Ein Abbruch des Laufs selbst wird weitergereicht.
     */
    private suspend fun <T> withinBudget(
        deadline: Long?,
        onTimeout: () -> T,
        block: suspend () -> T,
    ): T {
        if (deadline == null) return block()
        val wait = CheckBudget.waitMillis(deadline, System.currentTimeMillis(), cap = Long.MAX_VALUE)
        return withTimeoutOrNull(wait) { block() } ?: onTimeout()
    }

    private suspend fun withinBudget(deadline: Long?, block: suspend () -> Fetched): Fetched =
        withinBudget(deadline, { Fetched(null, CheckBudget.TIMEOUT_ERROR, fromSingle = true, budgetCut = true) }, block)

    /**
     * Paar fehlt in der vollständigen Liste: Einzelabfrage. Kurs → normal übernehmen; antwortet die
     * Börse ohne Kurs → nicht mehr gehandelt; Netz-/Serverproblem → gewöhnlicher Fehler.
     */
    private suspend fun confirmMissing(watch: WatchEntity): Fetched {
        val single = fetchSingle(watch)
        val gotPrice = single.ticker != null && single.error == null
        return if (NotTraded.confirmedBySingle(gotPrice, single.error)) {
            Fetched(null, NOT_TRADED_ERROR, fromSingle = true, notTraded = true)
        } else {
            single
        }
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
         * Laufzeit-Kontrakte (z. B. BTCUSDT_250926): Die gespeicherte Kennung gilt bis zum Verfall,
         * die App rollt NICHT auf den Folgekontrakt. Ein abgelaufener Kontrakt fehlt in der
         * Sammelliste, die Einzelabfrage bringt keinen Kurs → «nicht mehr gehandelt». Nur mit
         * `includeRollingFutures` (Standard aus) wird er stattdessen jedes Mal einzeln abgefragt.
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

/**
 * Nicht mehr gehandelte Paare aus der GEFILTERTEN Sammelabfrage heraushalten. Binance verwirft `symbols=[…]` mit
 * einem delisteten Symbol ganz (HTTP 400 «Invalid symbol») — dann lud jede Aktualisierung die ungefilterte Liste
 * (~2 MB). Welches Symbol, sagt Binance nicht: erkannt an der vollständigen Ersatzliste (angefragt, aber nicht
 * darin). Diese Paare und die schon als «nicht mehr gehandelt» gespeicherten ([NotTraded]) bleiben aus der Anfrage;
 * sie fehlen dann in der vollständigen Liste und laufen über die bestehende Logik. Alle [RECHECK_MILLIS] einmal
 * alle Paare (wieder gelistet?). Reines Kotlin, gespiegelt in `PriceFetcher.swift` (testdata/parity/bulk_delisted.json).
 */
object BulkDelisted {
    const val RECHECK_MILLIS = 6 * 60 * 60_000L

    /** [delisted]: zuletzt in der vollständigen Liste fehlende Paare; [recheckedAt]: letzter Lauf mit allen Paaren. */
    data class State(val delisted: Set<String>, val recheckedAt: Long)

    /** [all]: beobachtete Paare; [request]: davon anzufragen; [recheck]: Lauf mit allen Paaren. */
    data class Plan(val all: List<String>, val request: List<String>, val recheck: Boolean)

    fun plan(state: State?, pairIds: List<String>, notTraded: Set<String>, now: Long): Plan {
        // Ohne Stand (z. B. nach Prozess-Start) nicht gleich alles laden: die Frist läuft ab jetzt
        val recheck = state != null && now - state.recheckedAt !in 0 until RECHECK_MILLIS
        if (recheck) return Plan(pairIds, pairIds, true)
        val skip = state?.delisted.orEmpty() + notTraded
        return Plan(pairIds, pairIds.filter { it !in skip }, false)
    }

    /**
     * Stand nach der Abfrage; nur eine vollständige Liste ([complete]) sagt etwas über fehlende Paare — und nur über
     * Paare, deren Teilabfrage geklappt hat ([incomplete]: z. B. Alpha gescheitert, Spot nicht).
     */
    fun learn(
        state: State?,
        plan: Plan,
        tickers: Set<String>,
        failed: Set<String>,
        complete: Boolean,
        now: Long,
        incomplete: Set<String> = emptySet(),
    ): State {
        val base = state ?: State(emptySet(), now)
        if (!complete) return base
        val all = plan.all.toSet()
        val kept = base.delisted.filter { it in all && it !in tickers && it !in plan.request }
        val missing = plan.request.filter { it !in tickers && it !in failed && it !in incomplete }
        return State((kept + missing).toSet(), if (plan.recheck) now else base.recheckedAt)
    }
}

/**
 * «Zu viele Anfragen» (429) oder «gesperrt» (418) bei einer Einzelabfrage: Die übrigen, noch nicht gesendeten
 * Einzelabfragen derselben Börse in diesem Durchlauf gehen nicht mehr hinaus, sondern enden mit demselben
 * Fehlertext — samt «retry-after», die Pause je Börse ([ExchangeBackoff]) wirkt wie bisher. Schon laufende
 * Abfragen laufen zu Ende. Je Börse und Durchlauf eine Instanz. Wie `SingleRateGate` in `PriceFetcher.swift`.
 */
internal class SingleRateGate {
    @Volatile
    private var error: String? = null

    /** Fehlertext, mit dem noch nicht gesendete Einzelabfragen enden; null = senden. */
    val blocked: String? get() = error

    /** Ergebnis einer gesendeten Abfrage melden: das erste 429/418 sperrt den Rest. */
    fun note(error: String?) {
        if (this.error == null && isRateLimit(error)) this.error = error
    }

    /** [single] senden, ausser die Börse hat schon abgelehnt; dann derselbe Fehler ohne Anfrage. */
    internal suspend fun send(single: suspend () -> Fetched): Fetched {
        blocked?.let { return Fetched(null, it) }
        return single().also { if (it.ticker == null || it.error != null) note(it.error) }
    }

    companion object {
        fun isRateLimit(error: String?): Boolean =
            error != null && RefreshReportLogic.classify(error) == RefreshFailure.RATE_LIMIT
    }
}

/** Ergebnis der Netzabfrage für ein Paar; ticker == null heißt gescheitert. */
internal class Fetched(
    val ticker: Ticker?,
    val error: String?,
    val fromSingle: Boolean = false,
    val notTraded: Boolean = false,
    /** Am Zeitbudget der Prüfung gescheitert ([CheckBudget]), nicht an der Börse. */
    val budgetCut: Boolean = false,
)

/** Sammelabfrage einer Börse; [budgetCut] = am Budget gescheitert ([CheckBudget]). */
private class BulkResult(val data: BulkTickers, val budgetCut: Boolean = false) {
    val tickers get() = data.tickers
    val complete get() = data.complete
    val error get() = data.error
    val failed get() = data.failed
    val incomplete get() = data.incomplete
}

/** Bericht einer Börse plus was die Pause je Börse ([ExchangeBackoff]) braucht. */
internal class FetchedGroup(
    val marketKey: String,
    val report: MarketRefresh,
    /** Ursachen aller Fehler (auch einer gescheiterten Sammelabfrage). */
    val failures: List<RefreshFailure>,
    val retryAfterMillis: Long?,
    /** HTTP 418 («IP gesperrt») dabei: Pause wie bei Kerzen ([ExchangeBackoff.BAN_DEFAULT_MILLIS]). */
    val banned: Boolean = false,
    /** Abfragen, die nur am Zeitbudget scheiterten ([CheckBudget.backoffOutcome]). */
    val cutByBudget: Int = 0,
)
