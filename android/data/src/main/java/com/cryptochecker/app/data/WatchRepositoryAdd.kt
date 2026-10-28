// Paare anlegen (Teil von `WatchRepository`): einzeln, gesammelt, mit Dublettenprüfung und Obergrenze der Merkliste
// ([WatchlistLimits]). Erweiterungsfunktionen; Datenbank und DAO in der Klasse. Wie `AppData+WatchEdits.swift` (iOS).
package com.cryptochecker.app.data

import androidx.room.withTransaction
import com.cryptochecker.app.data.local.model.WatchEntity
import com.cryptochecker.app.domain.model.ExchangeInfo
import com.cryptochecker.app.domain.watch.AutoGroup
import com.cryptochecker.app.domain.watch.WatchIdentity
import com.cryptochecker.app.domain.watch.WatchlistLimits
import com.cryptochecker.marketdata.model.FuturesContractType
import com.cryptochecker.marketdata.model.ExchangePair

/** Ausgang von [tryAddWatch]. */
sealed interface AddWatchOutcome {
    data class Added(val id: Long) : AddWatchOutcome
    /** Paar stand schon in der Merkliste. */
    data object Duplicate : AddWatchOutcome
    /** Merkliste hat schon [WatchlistLimits.MAX_PAIRS] Paare. */
    data object Full : AddWatchOutcome
    /** Merkliste gesperrt ([userWriteRefused]): nichts angelegt, der kurze Hinweis ist schon ausgelöst. */
    data object Locked : AddWatchOutcome
}

/**
 * Legt ein Paar in der Watchlist an — wie [tryAddWatch], ohne Grund für «nicht angelegt».
 * @return die Id des Eintrags, oder null, wenn er bereits vorhanden war bzw. die Merkliste voll oder gesperrt ist.
 */
suspend fun WatchRepository.addWatch(
    exchange: ExchangeInfo,
    pair: ExchangePair,
    group: String? = null,
    notificationEnabled: Boolean = WatchRepository.DEFAULT_NOTIFICATION_ENABLED,
): Long? = (tryAddWatch(exchange, pair, group, notificationEnabled) as? AddWatchOutcome.Added)?.id

/**
 * Legt ein Paar in der Watchlist an.
 * @param notificationEnabled Kurs-Benachrichtigung des Paares (Standard: [WatchRepository.DEFAULT_NOTIFICATION_ENABLED];
 *   einschalten bzw. ausschalten im Paar-Menü)
 * @return angelegt (mit Id), schon vorhanden, Merkliste voll ([WatchlistLimits.MAX_PAIRS]) oder gesperrt.
 */
suspend fun WatchRepository.tryAddWatch(
    exchange: ExchangeInfo,
    pair: ExchangePair,
    group: String? = null,
    notificationEnabled: Boolean = WatchRepository.DEFAULT_NOTIFICATION_ENABLED,
): AddWatchOutcome = if (userWriteRefused()) AddWatchOutcome.Locked else database.withTransaction {
    // Suchen, Zählen und Einfügen in EINEM Vorgang: Zwei gleichzeitige Aufnahmen (Doppeltipp, Sammelaufnahme)
    // legen sonst dasselbe Paar zweimal an (bei Börsen ist die Kennung null, der Index greift dort nicht)
    // Paar aus einer älteren, noch nicht neu geladenen Paarliste: inverse Perpetuals wie heute gespeichert
    val contractType = FuturesContractType.normalized(exchange.key, pair.pairId, pair.contractType)
    val existing = watchDao.findWatchId(
        exchange.key,
        pair.base,
        pair.quote,
        contractType.name,
        WatchIdentity.pool(exchange.key, pair.pairId),
    )
    addBlocker(existing != null) { watchDao.countWatches() }?.let { return@withTransaction it }

    val id = watchDao.insertWatch(
        WatchEntity(
            exchangeKey = exchange.key,
            exchangeName = exchange.name,
            baseAsset = pair.base,
            quoteAsset = pair.quote,
            contractType = contractType,
            pairId = pair.pairId,
            sortOrder = watchDao.nextSortOrder(),
            // Nur neue Paare erhalten die gewählte Gruppe; bestehende bleiben unverändert.
            // Ohne Wahl: TradFi-Futures nach «TradFi», Laufzeit-Futures nach «QTLY».
            groupName = AutoGroup.resolve(group, pair),
            notificationEnabled = notificationEnabled,
        )
    )
    if (id > 0) AddWatchOutcome.Added(id) else AddWatchOutcome.Duplicate
}

/**
 * Legt mehrere Paare auf einmal an. Bereits vorhandene werden übersprungen, nicht doppelt eingetragen; ist die
 * Merkliste voll ([WatchlistLimits.MAX_PAIRS]), kommen die übrigen nicht mehr dazu ([BulkAddResult.refused]). Gesperrt
 * ([userWriteRefused]): nichts angelegt ([BulkAddResult.locked]), ein einziger Hinweis. [onProgress] erhält nach
 * jedem Paar die Zahl der verarbeiteten ([BulkAddRunner]: Fortschrittsbalken).
 */
suspend fun WatchRepository.addWatches(
    exchange: ExchangeInfo,
    pairs: List<ExchangePair>,
    group: String? = null,
    onProgress: (Int) -> Unit = {},
): BulkAddResult =
    collectBulkAdd(pairs, refused = { userWriteRefused() }, add = { tryAddWatch(exchange, it, group) }, onProgress)

/**
 * Ablauf von [addWatches] ohne Room ([refused] = Sperre und Sicherung F-08, [add] = ein Paar anlegen): testbar mit
 * Fälschungen (`BulkAddRunnerTest`, `WatchlistWriteConsentTest`).
 */
suspend fun collectBulkAdd(
    pairs: List<ExchangePair>,
    refused: suspend () -> Boolean,
    add: suspend (ExchangePair) -> AddWatchOutcome,
    onProgress: (Int) -> Unit,
): BulkAddResult {
    if (refused()) return BulkAddResult(added = 0, skipped = 0, locked = true)
    var added = 0
    var skipped = 0
    var refused = 0
    val addedIds = ArrayList<Long>()

    for (pair in pairs) {
        when (val outcome = add(pair)) {
            is AddWatchOutcome.Added -> {
                added++
                addedIds += outcome.id
            }
            AddWatchOutcome.Duplicate -> skipped++
            AddWatchOutcome.Full -> refused++
            // Erst während der Aufnahme gesperrt (kommt praktisch nicht vor): die übrigen nicht mehr versuchen
            AddWatchOutcome.Locked ->
                return BulkAddResult(added = added, skipped = skipped, refused = refused, locked = true, addedIds = addedIds)
        }
        onProgress(added + skipped + refused)
    }

    return BulkAddResult(added = added, skipped = skipped, refused = refused, addedIds = addedIds)
}

/**
 * «Rückgängig» nach «Mehrere Paare» ([BulkAddRunner.undo]): die neu angelegten Paare [ids] löschen, ausser denen, die
 * inzwischen einen Alarm haben (auch ausgeschaltet) — der Alarm ginge sonst per Fremdschlüssel ohne Hinweis mit.
 * Prüfen und Löschen in einem Vorgang. Rückgabe wie [WatchRepository.deleteWatches].
 */
suspend fun WatchRepository.deleteWatchesWithoutAlarms(ids: Collection<Long>): Boolean {
    if (ids.isEmpty() || userWriteRefused()) return false
    database.withTransaction {
        // Gelöscht werden nur Paare ohne Alarm: das Alarm-Protokoll bleibt unberührt
        withoutAlarms(ids) { chunk -> watchDao.getWatchIdsWithAlarms(chunk) }.forEach { watchDao.deleteWatch(it) }
    }
    return true
}

/**
 * Die Ids aus [ids] ohne Alarm; [withAlarms] liefert zu einem Teil (höchstens [ALARM_QUERY_CHUNK] Ids, Grenze der
 * SQL-Variablen) die Ids mit Alarm. Ohne Room testbar (`BulkAddRunnerTest`).
 */
suspend fun withoutAlarms(
    ids: Collection<Long>,
    withAlarms: suspend (List<Long>) -> Collection<Long>,
): List<Long> {
    val unique = ids.distinct()
    val taken = HashSet<Long>()
    for (chunk in unique.chunked(ALARM_QUERY_CHUNK)) taken += withAlarms(chunk)
    return unique.filter { it !in taken }
}

private const val ALARM_QUERY_CHUNK = 500

/** Ausgang von [restore]. */
enum class RestoreOutcome {
    RESTORED,
    /** Paar besteht noch, wurde inzwischen neu angelegt, Id vergeben oder Merkliste gesperrt. */
    BLOCKED,
    /** Merkliste hat schon [WatchlistLimits.MAX_PAIRS] Paare: nicht zurückgeholt. */
    FULL,
}

/**
 * Gelöschtes Paar unverändert zurückholen: gleiche Id (Ids werden nie neu vergeben,
 * Room-Primärschlüssel mit AUTOINCREMENT), gleicher Platz, Gruppe, Notiz, Favorit,
 * Einstellungen und alle Alarme mit ihren Ids — in einem Vorgang. Wie das Anlegen nur, solange die
 * Merkliste nicht voll ist ([WatchlistLimits.canAddPair]); «Rückgängig» umging sonst die Obergrenze. Wie iOS
 * `AppData.restore`.
 */
suspend fun WatchRepository.restore(snapshot: WatchSnapshot): RestoreOutcome =
    if (userWriteRefused()) RestoreOutcome.BLOCKED else database.withTransaction {
        val watch = snapshot.watch
        val blocked = watchDao.getWatch(watch.id) != null ||
            watchDao.findWatchId(
                watch.exchangeKey, watch.baseAsset, watch.quoteAsset, watch.contractType.name,
                WatchIdentity.pool(watch.exchangeKey, watch.pairId),
            ) != null
        restoreBlocker(blocked) { watchDao.countWatches() } ?: if (watchDao.insertWatch(watch) == watch.id) {
            snapshot.alarms.forEach { watchDao.insertAlarm(it.copy(watchId = watch.id)) }
            RestoreOutcome.RESTORED
        } else {
            RestoreOutcome.BLOCKED
        }
    }

/**
 * Vor dem Anlegen ([tryAddWatch]): schon vorhanden ([exists]) geht vor «voll» — ein bekanntes Paar ist kein Fehler;
 * null = anlegen. [count] (Paare jetzt) wird nur ohne Dublette gelesen. Rein, testbar (`WatchAddRulesTest`).
 */
inline fun addBlocker(exists: Boolean, count: () -> Int): AddWatchOutcome? = when {
    exists -> AddWatchOutcome.Duplicate
    !WatchlistLimits.canAddPair(count()) -> AddWatchOutcome.Full
    else -> null
}

/**
 * Vor dem Zurückholen ([restore], Review FUN-03): Paar noch bzw. wieder da ([present]) geht vor «voll» (wie beim
 * Anlegen); null = zurückholen. «Rückgängig» umgeht so die Obergrenze nicht. Rein, testbar (`WatchAddRulesTest`).
 */
inline fun restoreBlocker(present: Boolean, count: () -> Int): RestoreOutcome? = when {
    present -> RestoreOutcome.BLOCKED
    !WatchlistLimits.canAddPair(count()) -> RestoreOutcome.FULL
    else -> null
}
