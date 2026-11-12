// Schritte von [PriceRefresher.refreshAll] vor dem Auswerten: welche Paare der Live-Strom schon abdeckt, und die
// Netz-Phase (alle Börsen gleichzeitig, Pausen je Börse nachführen). Jeder Schritt tut dasselbe wie zuvor an seiner
// Stelle im Durchlauf.
package com.cryptochecker.app.domain.refresh

import com.cryptochecker.app.domain.live.LiveExchange
import com.cryptochecker.app.domain.live.LiveRules
import com.cryptochecker.app.domain.model.AlarmCondition
import com.cryptochecker.app.domain.model.WatchEntity
import com.cryptochecker.app.domain.remote.BlockedSources
import com.cryptochecker.app.domain.settings.AppSettings
import com.cryptochecker.app.domain.watch.ChangeStamp
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/** Kurse der Netz-Phase: Ergebnis je Paar, Bericht je Börse (abgefragte, dann pausierte) und die Paare pausierter Börsen. */
internal class FetchedPrices(
    val fetched: HashMap<Long, Fetched>,
    val reports: List<ExchangeRefresh>,
    val pausedIds: Set<Long>,
)

/**
 * Paare, die diesmal ohne REST-Abfrage bleiben, weil der Live-Strom sie frisch abdeckt — sie sind aktueller als jede
 * Abfrage. Nur rollende Basis mit gleichem Stempel (Tages-Basen brauchen Kerzen) und Börsen mit gleitendem 24-h-Wert
 * (LiveRules.skipRest).
 */
internal suspend fun PriceRefresher.liveCoveredIds(
    allWatches: List<WatchEntity>,
    settings: AppSettings,
    changeStamp: ChangeStamp,
    startedAt: Long,
): HashSet<Long> {
    val stampUnchanged = refreshStats.changeStamp.value == changeStamp
    val rollingBasis = !settings.changeBasis.isDay
    val liveIds = allWatches.filter { watch ->
        LiveRules.skipRest(
            liveCoverage.lastTickAt(watch.id), startedAt, rollingBasis, stampUnchanged,
            LiveExchange.fromExchangeKey(watch.exchangeKey)?.rollingChange == true,
        )
    }.mapTo(HashSet()) { it.id }
    // Volumen-, Funding- und Open-Interest-Alarme hängen nicht am Live-Kurs (applyLive prüft sie
    // nicht): solche Paare weiter per REST abfragen, sonst bliebe der Alarm bei offener Merkliste stumm
    if (liveIds.isNotEmpty()) {
        watchRepository.getAllEnabledAlarms()
            .filter { it.condition == AlarmCondition.VOLUME_SPIKE || it.condition.isDerivatives }
            .forEach { liveIds.remove(it.watchId) }
    }
    return liveIds
}

/**
 * Netz: alle Börsen gleichzeitig. Je Börse erst die Massenabfrage, was dort fehlt, parallel einzeln (nicht Börse für
 * Börse, Paar für Paar). Pausierte Börsen (zu viele Anfragen, wiederholte Zeitüberschreitungen) bleiben diesmal aussen
 * vor: keine Anfrage, ihre Paare behalten den letzten Kurs. Führt die Pausen je Börse nach und speichert sie.
 */
internal suspend fun PriceRefresher.fetchAllGroups(
    watches: List<WatchEntity>,
    settings: AppSettings,
    deadlineAt: Long?,
    alarmedIds: Set<Long>,
    startedAt: Long,
): FetchedPrices {
    val backoff = refreshStats.backoffStates().toMutableMap()
    // Gemeinsame Binance-Pause (429/418 bei Kerzen, Futures …): auch die Kurse pausieren,
    // sonst verlängerte die Kursabfrage die IP-Sperre (siehe BlockedSources)
    for (key in watches.mapTo(HashSet()) { it.exchangeKey }) {
        val until = BlockedSources.groupPausedUntil(key) ?: continue
        val state = backoff[key]
        if (state == null || state.pausedUntil < until || !ExchangeBackoff.isPaused(state, startedAt)) {
            backoff[key] = (state ?: ExchangeBackoff.State(strikes = 1))
                .copy(pausedUntil = until, reason = RefreshFailure.RATE_LIMIT, timeoutRuns = 0)
        }
    }
    val (pausedGroups, activeGroups) = watches.groupBy { it.exchangeKey }.entries
        .partition { ExchangeBackoff.isPaused(backoff[it.key], startedAt) }
    val pausedIds = pausedGroups.flatMap { group -> group.value.map { it.id } }.toSet()
    val fetched = HashMap<Long, Fetched>()
    val activeReports = coroutineScope {
        activeGroups
            // Mit Frist: je Börse eigenes Budget, eine langsame Börse hält die anderen nicht auf
            .map { group ->
                async { priceFetcher.fetchGroup(group.value, settings.includeRollingFutures, deadlineAt, alarmedIds) }
            }
            .awaitAll()
    }.map { (results, fetchedGroup) ->
        fetched.putAll(results)
        // Ergebnis je Börse → Pause beginnen, verlängern oder (nach Erfolg) aufheben
        val key = fetchedGroup.exchangeKey
        // Nur am eigenen Budget gescheitert: Pause unverändert lassen (CheckBudget.backoffOutcome)
        val outcome = CheckBudget.backoffOutcome(fetchedGroup.failures, fetchedGroup.report.updated, fetchedGroup.cutByBudget)
        val state = if (outcome == null) backoff[key] else ExchangeBackoff.next(
            backoff[key], outcome,
            System.currentTimeMillis(), fetchedGroup.retryAfterMillis,
            // 418 und «Retry-After» wie bei Kerzen (gemeinsame Binance-Pause)
            banned = fetchedGroup.banned, shared = ExchangeBackoff.sharedGroup(key) != null,
        )
        if (state == null) backoff.remove(key) else backoff[key] = state
        // 429/418 beim Ticker: Kerzen, Futures und Aktivität an dieselben Hosts pausieren mit
        if (state != null && state.reason == RefreshFailure.RATE_LIMIT &&
            ExchangeBackoff.isPaused(state, System.currentTimeMillis())
        ) {
            BlockedSources.pauseGroup(key, state.pausedUntil)
        }
        if (ExchangeBackoff.isPaused(state, System.currentTimeMillis())) {
            fetchedGroup.report.copy(pausedUntil = state?.pausedUntil, pauseReason = state?.reason)
        } else {
            fetchedGroup.report
        }
    }
    val pausedReports = pausedGroups.map { (key, group) ->
        ExchangeRefresh(
            name = group.first().exchangeName,
            millis = 0,
            pairs = group.size,
            updated = 0,
            pausedUntil = backoff[key]?.pausedUntil,
            pauseReason = backoff[key]?.reason,
        )
    }
    refreshStats.setBackoffStates(ExchangeBackoff.withHostGroupPauses(backoff, System.currentTimeMillis()))
    return FetchedPrices(fetched, activeReports + pausedReports, pausedIds)
}
