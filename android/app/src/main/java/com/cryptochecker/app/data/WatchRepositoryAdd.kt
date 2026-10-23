// Paare anlegen (Teil von `WatchRepository`): einzeln, gesammelt, mit Dublettenprüfung und Obergrenze der Merkliste
// ([WatchlistLimits]). Erweiterungsfunktionen; Datenbank und DAO in der Klasse. Wie `AppData+WatchEdits.swift` (iOS).
package com.cryptochecker.app.data

import androidx.room.withTransaction
import com.cryptochecker.app.data.local.model.WatchEntity
import com.cryptochecker.app.domain.model.MarketInfo
import com.cryptochecker.app.domain.watch.AutoGroup
import com.cryptochecker.app.domain.watch.WatchIdentity
import com.cryptochecker.app.domain.watch.WatchlistLimits
import com.cryptochecker.marketdata.model.FuturesContractType
import com.cryptochecker.marketdata.model.MarketPair

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
    market: MarketInfo,
    pair: MarketPair,
    group: String? = null,
    notificationEnabled: Boolean = WatchRepository.DEFAULT_NOTIFICATION_ENABLED,
): Long? = (tryAddWatch(market, pair, group, notificationEnabled) as? AddWatchOutcome.Added)?.id

/**
 * Legt ein Paar in der Watchlist an.
 * @param notificationEnabled Kurs-Benachrichtigung des Paares (Standard: [WatchRepository.DEFAULT_NOTIFICATION_ENABLED];
 *   einschalten bzw. ausschalten im Paar-Menü)
 * @return angelegt (mit Id), schon vorhanden, Merkliste voll ([WatchlistLimits.MAX_PAIRS]) oder gesperrt.
 */
suspend fun WatchRepository.tryAddWatch(
    market: MarketInfo,
    pair: MarketPair,
    group: String? = null,
    notificationEnabled: Boolean = WatchRepository.DEFAULT_NOTIFICATION_ENABLED,
): AddWatchOutcome = if (userWriteRefused()) AddWatchOutcome.Locked else database.withTransaction {
    // Suchen, Zählen und Einfügen in EINEM Vorgang: Zwei gleichzeitige Aufnahmen (Doppeltipp, Sammelaufnahme)
    // legen sonst dasselbe Paar zweimal an (bei Börsen ist die Kennung null, der Index greift dort nicht)
    // Paar aus einer älteren, noch nicht neu geladenen Paarliste: inverse Perpetuals wie heute gespeichert
    val contractType = FuturesContractType.normalized(market.key, pair.pairId, pair.contractType)
    val existing = watchDao.findWatchId(
        market.key,
        pair.base,
        pair.quote,
        contractType.name,
        WatchIdentity.pool(market.key, pair.pairId),
    )
    if (existing != null) return@withTransaction AddWatchOutcome.Duplicate
    // Schon vorhanden geht vor «voll»: ein bekanntes Paar ist kein Fehler
    if (!WatchlistLimits.canAddPair(watchDao.countWatches())) return@withTransaction AddWatchOutcome.Full

    val id = watchDao.insertWatch(
        WatchEntity(
            marketKey = market.key,
            marketName = market.name,
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
 * ([userWriteRefused]): nichts angelegt ([BulkAddResult.locked]), ein einziger Hinweis.
 */
suspend fun WatchRepository.addWatches(
    market: MarketInfo,
    pairs: List<MarketPair>,
    group: String? = null,
): BulkAddResult {
    if (userWriteRefused()) return BulkAddResult(added = 0, skipped = 0, locked = true)
    var added = 0
    var skipped = 0
    var refused = 0

    for (pair in pairs) {
        when (tryAddWatch(market, pair, group)) {
            is AddWatchOutcome.Added -> added++
            AddWatchOutcome.Duplicate -> skipped++
            AddWatchOutcome.Full -> refused++
            // Erst während der Aufnahme gesperrt (kommt praktisch nicht vor): die übrigen nicht mehr versuchen
            AddWatchOutcome.Locked -> return BulkAddResult(added = added, skipped = skipped, refused = refused, locked = true)
        }
    }

    return BulkAddResult(added = added, skipped = skipped, refused = refused)
}

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
                watch.marketKey, watch.baseAsset, watch.quoteAsset, watch.contractType.name,
                WatchIdentity.pool(watch.marketKey, watch.pairId),
            ) != null
        when {
            blocked -> RestoreOutcome.BLOCKED
            // Schon vorhanden geht vor «voll» (wie beim Anlegen)
            !WatchlistLimits.canAddPair(watchDao.countWatches()) -> RestoreOutcome.FULL
            watchDao.insertWatch(watch) == watch.id -> {
                snapshot.alarms.forEach { watchDao.insertAlarm(it.copy(watchId = watch.id)) }
                RestoreOutcome.RESTORED
            }
            else -> RestoreOutcome.BLOCKED
        }
    }
