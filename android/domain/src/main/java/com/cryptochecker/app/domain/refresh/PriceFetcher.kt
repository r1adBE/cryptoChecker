package com.cryptochecker.app.domain.refresh

import com.cryptochecker.app.data.ExchangeRepository
import com.cryptochecker.app.data.local.model.WatchEntity
import com.cryptochecker.app.data.remote.util.runCatchingCancellable
import com.cryptochecker.app.domain.exceptions.toExchangeFailure
import com.cryptochecker.app.domain.model.BulkTickers
import com.cryptochecker.app.domain.model.ExchangeInfo
import com.cryptochecker.app.domain.watch.NotTraded
import com.cryptochecker.marketdata.config.ExchangesConfig
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
import com.cryptochecker.app.util.DomainLog
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Holt die Kurse einer Börse: erst gesammelt (wo die Börse das anbietet), was fehlt, einzeln —
 * mit eigener Grenze gleichzeitiger Anfragen je Börse. Teil von [PriceRefresher].
 */
@Singleton
class PriceFetcher @Inject constructor(
    private val exchangeRepository: ExchangeRepository,
    /** Je Börse: nicht mehr gehandelte Paare der Sammelabfrage ([BulkDelisted]), dauerhaft gespeichert. */
    private val delisted: BulkDelistedStore,
) {

    /**
     * Holt die Kurse aller Paare einer Börse; Schlüssel ist die Watch-Id.
     * Dazu der Eintrag für den Bericht: was geklappt hat und wie lange es dauerte.
     * [deadlineAt] (exakter Wecker, [CheckBudget]): danach keine Abfrage mehr — was bis dahin kam,
     * zählt, der Rest gilt als Zeitüberschreitung dieses Paares.
     */
    suspend fun fetchGroup(
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
            RefreshReportLogic.classify(bulk.failure, bulk.error) == RefreshFailure.RATE_LIMIT
        // Antwort mehrheitlich unlesbar (Format der Börse geändert): Einzelabfragen mit demselben Leser brächten
        // nichts — alle Paare dieses Durchlaufs als Fehler, kein Nachhaken (BulkTickerRows)
        val bulkFormatError = BulkTickerRows.isFormatError(bulk.error)
        // Ohne Sammelabfrage: liest die Einzelabfrage selbst die vollständige Liste (Lighter), bestätigt ihr «kein
        // Eintrag» «nicht mehr gehandelt» wie im Sammelpfad (NotTraded.confirmWithoutBulk)
        val singleConfirms = NotTraded.confirmWithoutBulk(
            bulkTried, ExchangesConfig.EXCHANGES[group.first().exchangeKey]?.singleTickerReadsFullList == true,
        )

        // Eigene Grenze je Börse, damit niemand ins Rate-Limit läuft.
        val limit = Semaphore(MAX_PARALLEL_REQUESTS_PER_EXCHANGE)
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

                        bulkRateLimited || bulkFormatError -> Fetched(null, bulk.error, failure = bulk.failure)

                        singleConfirms ->
                            withinBudget(groupDeadline) { limit.withPermit { gate.send { confirmMissing(watch) } } }

                        else -> withinBudget(groupDeadline) { limit.withPermit { gate.send { fetchSingle(watch) } } }
                    }
                }
            }.awaitAll()
        }

        val notTraded = results.count { it.second.notTraded }
        val errors = results.map { it.second }.filter { (it.ticker == null || it.error != null) && !it.notTraded }
        // Am eigenen Budget gescheitert: zählt als Fehler im Bericht, aber nicht für die Pause je Börse
        val cut = errors.count { it.budgetCut }

        // Ursache je Fehler: die beim Abruf bestimmte Art, sonst aus dem Text (wie iOS)
        val failures = errors.map { RefreshReportLogic.classify(it.failure, it.error) }
        val reason = RefreshReportLogic.mostFrequent(failures)
        val entry = ExchangeRefresh(
            name = group.first().exchangeName,
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
            detail = StoredError.reportDetail(errors.map { it.error }, reason, failures),
        )
        val counted = errors.filterNot { it.budgetCut }
        val bulkCounted = bulk.error != null && !bulk.budgetCut
        val errorTexts = counted.map { it.error } + listOfNotNull(bulk.error.takeIf { bulkCounted })
        val signals = FetchedGroup(
            exchangeKey = group.first().exchangeKey,
            report = entry,
            failures = counted.map { RefreshReportLogic.classify(it.failure, it.error) } +
                listOfNotNull(RefreshReportLogic.classify(bulk.failure, bulk.error).takeIf { bulkCounted }),
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
        val exchange = ExchangeInfo(sample.exchangeKey, sample.exchangeName)
        val pairIds = group.mapNotNull { it.pairId }.distinct()
        // Nicht mehr gehandelte Paare nicht mitschicken: ein delistetes Symbol verwirft bei Binance die ganze
        // gefilterte Liste → jedes Mal die ungefilterte (~2 MB). Sie fehlen dann in der vollständigen Liste (NotTraded).
        val notTradedIds = group.filter { NotTraded.isMarker(it.lastError) }.mapNotNullTo(HashSet()) { it.pairId }
        val plan = BulkDelisted.plan(delisted[sample.exchangeKey], pairIds, notTradedIds, System.currentTimeMillis())

        var cut = false
        // Abbruch des Durchlaufs weiterreichen (nicht als gescheiterte Massenabfrage werten)
        val tickers = runCatchingCancellable {
            if (!exchangeRepository.isExchangeSupportsBulkTickers(exchange)) return BulkResult(BulkTickers())
            // Alle beobachteten Paare nicht mehr gehandelt (mit Stand, Neuprüfung nicht fällig): keine Abfrage,
            // die Liste gilt als vollständig ohne sie
            if (plan.request.isEmpty()) return BulkResult(BulkTickers(complete = true))
            withinBudget(deadline, onTimeout = { cut = true; BulkTickers(error = CheckBudget.TIMEOUT_ERROR) }) {
                exchangeRepository.getBulkTickers(exchange, plan.request)
            }.also { r ->
                delisted.put(
                    sample.exchangeKey,
                    BulkDelisted.learn(
                        delisted[sample.exchangeKey], plan, r.tickers.keys, r.failed, r.complete, System.currentTimeMillis(),
                        r.incomplete,
                    ),
                )
            }
        }
            .onFailure { DomainLog.w(it, "Massenabfrage fehlgeschlagen: %s", sample.exchangeName) }
            .onSuccess { r ->
                if (BulkTickerRows.isFormatError(r.error)) DomainLog.w("Massenabfrage Formatfehler: %s — %s", sample.exchangeName, r.error)
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
     * Paar fehlt in der vollständigen Liste (oder ohne Sammelabfrage: [NotTraded.confirmWithoutBulk]): Einzelabfrage. Kurs → normal übernehmen; antwortet die
     * Börse ohne Kurs → nicht mehr gehandelt; Netz-/Serverproblem → gewöhnlicher Fehler.
     */
    private suspend fun confirmMissing(watch: WatchEntity): Fetched {
        val single = fetchSingle(watch)
        val gotPrice = single.ticker != null && single.error == null
        return if (NotTraded.confirmedBySingle(gotPrice, single.error, single.failure)) {
            Fetched(null, NOT_TRADED_ERROR, fromSingle = true, notTraded = true)
        } else {
            single
        }
    }

    suspend fun fetchSingle(watch: WatchEntity): Fetched {
        return runCatchingCancellable {
            exchangeRepository.getExchangeTicker(
                ExchangeInfo(watch.exchangeKey, watch.exchangeName),
                watch.toPairInfo()
            )
        }.fold(
            onSuccess = { Fetched(it.ticker, it.error, fromSingle = true, failure = it.failure) },
            onFailure = { failure ->
                // Zeitüberschreitungen kommen als CancellationException an und
                // gelten nur als Fehler dieses Paares. Abbrechen nur, wenn der
                // Durchlauf selbst abgebrochen wurde.
                currentCoroutineContext().ensureActive()
                DomainLog.w(failure, "Kursabfrage fehlgeschlagen: %s", watch.displayName)
                Fetched(null, failure.message, fromSingle = true, failure = failure.toExchangeFailure())
            }
        )
    }

    private companion object {
        /** Unter so wenigen Paaren spart die Massenabfrage nichts. */
        const val MIN_WATCHES_FOR_BULK = 3

        /** Gleichzeitige Einzelabfragen je Börse. */
        const val MAX_PARALLEL_REQUESTS_PER_EXCHANGE = 4

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
 * «Zu viele Anfragen» (429) oder «gesperrt» (418) bei einer Einzelabfrage: Die übrigen, noch nicht gesendeten
 * Einzelabfragen derselben Börse in diesem Durchlauf gehen nicht mehr hinaus, sondern enden mit demselben
 * Fehlertext — samt «retry-after», die Pause je Börse ([ExchangeBackoff]) wirkt wie bisher. Schon laufende
 * Abfragen laufen zu Ende. Je Börse und Durchlauf eine Instanz. Wie `SingleRateGate` in `PriceFetcher.swift`.
 */
class SingleRateGate {
    /** Text und Art zusammen, damit beide immer vom selben Fehler stammen. */
    private class Block(val error: String, val failure: ExchangeFailure?)

    @Volatile
    private var block: Block? = null

    /** Fehlertext, mit dem noch nicht gesendete Einzelabfragen enden; null = senden. */
    val blocked: String? get() = block?.error

    /** Ergebnis einer gesendeten Abfrage melden: das erste 429/418 sperrt den Rest. */
    fun note(error: String?, failure: ExchangeFailure? = null) {
        if (block == null && error != null && isRateLimit(error, failure)) block = Block(error, failure)
    }

    /** [single] senden, ausser die Börse hat schon abgelehnt; dann derselbe Fehler ohne Anfrage. */
    suspend fun send(single: suspend () -> Fetched): Fetched {
        block?.let { return Fetched(null, it.error, failure = it.failure) }
        return single().also { if (it.ticker == null || it.error != null) note(it.error, it.failure) }
    }

    companion object {
        fun isRateLimit(error: String?, failure: ExchangeFailure? = null): Boolean =
            error != null && RefreshReportLogic.classify(failure, error) == RefreshFailure.RATE_LIMIT
    }
}

/** Ergebnis der Netzabfrage für ein Paar; ticker == null heisst gescheitert. */
class Fetched(
    val ticker: Ticker?,
    val error: String?,
    val fromSingle: Boolean = false,
    val notTraded: Boolean = false,
    /** Am Zeitbudget der Prüfung gescheitert ([CheckBudget]), nicht an der Börse. */
    val budgetCut: Boolean = false,
    /** Art des Fehlers zu [error], beim Abruf bestimmt; null = nur der Text ist bekannt. */
    val failure: ExchangeFailure? = null,
)

/** Sammelabfrage einer Börse; [budgetCut] = am Budget gescheitert ([CheckBudget]). */
private class BulkResult(val data: BulkTickers, val budgetCut: Boolean = false) {
    val tickers get() = data.tickers
    val complete get() = data.complete
    val error get() = data.error
    val failed get() = data.failed
    val incomplete get() = data.incomplete
    val failure get() = data.failure
}

/** Bericht einer Börse plus was die Pause je Börse ([ExchangeBackoff]) braucht. */
class FetchedGroup(
    val exchangeKey: String,
    val report: ExchangeRefresh,
    /** Ursachen aller Fehler (auch einer gescheiterten Sammelabfrage). */
    val failures: List<RefreshFailure>,
    val retryAfterMillis: Long?,
    /** HTTP 418 («IP gesperrt») dabei: Pause wie bei Kerzen ([ExchangeBackoff.BAN_DEFAULT_MILLIS]). */
    val banned: Boolean = false,
    /** Abfragen, die nur am Zeitbudget scheiterten ([CheckBudget.backoffOutcome]). */
    val cutByBudget: Int = 0,
)
