package com.cryptochecker.app.data

import com.cryptochecker.app.data.BackupWatchlistCodec.alarmToJson
import com.cryptochecker.app.data.BackupWatchlistCodec.jsonToWatch
import com.cryptochecker.app.data.BackupWatchlistCodec.watchToJson
import com.cryptochecker.app.data.local.model.AlarmEntity
import com.cryptochecker.app.data.local.model.WatchEntity
import com.cryptochecker.app.data.portfolio.PortfolioAlarmEntity
import com.cryptochecker.app.data.portfolio.PortfolioJson
import com.cryptochecker.app.data.portfolio.PortfolioTxEntity
import com.cryptochecker.app.data.portfolio.WatchedAddressEntity
import com.cryptochecker.app.data.portfolio.toDomain
import com.cryptochecker.app.domain.portfolio.WatchedAddressRules
import org.json.JSONArray
import org.json.JSONObject

/** Stand vor dem Wiederherstellen ([BackupManager.restore]) und sein Format im [RestoreJournal]. */
internal object BackupJournalCodec {

    /**
     * Journal-Inhalt: der gemerkte Stand im Format der Sicherung (Merkliste, Alarme, Portfolio, Portfolio-Alarme, Adressen,
     * Favoriten, Einstellungen). Alarm-Zustand und letzte Guthaben gehen dabei nicht mit — wie bei einer Sicherung.
     */
    fun journalJson(previous: PreviousState, createdAt: Long = System.currentTimeMillis()): JSONObject = JSONObject()
        .put("format", RestoreJournal.FORMAT)
        .put("version", RestoreJournal.VERSION)
        // Alter des Journals für [RestoreRecoveryPolicy] (ältere Journale: Änderungszeit der Datei)
        .put("createdAt", createdAt)
        .put("watches", JSONArray().apply { previous.watches.forEach { put(watchToJson(it)) } })
        .put("alarms", JSONArray().apply { previous.alarms.forEach { put(alarmToJson(it)) } })
        .put("portfolio", JSONArray().apply { previous.portfolio.forEach { put(PortfolioJson.txToJson(it)) } })
        .put("portfolioAlarms", JSONArray().apply { previous.portfolioAlarms.forEach { put(PortfolioJson.alarmToJson(it)) } })
        .put(
            WatchedAddressRules.BACKUP_KEY,
            WatchedAddressRules.toJson(WatchedAddressRules.backupEntries(previous.watchedAddresses.mapNotNull { it.toDomain() })),
        )
        .put("favorites", JSONObject().apply {
            previous.favorites.forEach { (kind, items) -> put(kind.name, JSONArray(items.toList())) }
        })
        .put("settings", previous.settings)

    /** Gegenstück zu [journalJson]. */
    fun previousFromJournal(root: JSONObject): PreviousState {
        require(root.optString("format") == RestoreJournal.FORMAT) { "Kein Journal" }
        val favorites = root.optJSONObject("favorites")
        return PreviousState(
            watches = root.optJSONArray("watches")?.let { a -> (0 until a.length()).map { jsonToWatch(a.getJSONObject(it)) } }.orEmpty(),
            alarms = root.optJSONArray("alarms")?.let(AlarmJson::parse)?.items.orEmpty(),
            portfolio = root.optJSONArray("portfolio")?.let(PortfolioJson::parseTxs)?.items.orEmpty(),
            portfolioAlarms = root.optJSONArray("portfolioAlarms")?.let(PortfolioJson::parseAlarms)?.items.orEmpty(),
            watchedAddresses = WatchedAddressRules.merge(emptyList(), WatchedAddressRules.fromJson(root.optJSONArray(WatchedAddressRules.BACKUP_KEY)))
                .map { WatchedAddressEntity.from(it) },
            favorites = FavoriteKind.entries.associateWith { kind ->
                favorites?.optJSONArray(kind.name)?.let { arr -> (0 until arr.length()).mapNotNull { arr.opt(it) as? String }.toSet() }.orEmpty()
            },
            settings = root.optJSONObject("settings") ?: JSONObject(),
        )
    }

    /** Was vor dem Wiederherstellen da war. */
    class PreviousState(
        val watches: List<WatchEntity>,
        val alarms: List<AlarmEntity>,
        val portfolio: List<PortfolioTxEntity>,
        val portfolioAlarms: List<PortfolioAlarmEntity>,
        val watchedAddresses: List<WatchedAddressEntity>,
        val favorites: Map<FavoriteKind, Set<String>>,
        val settings: JSONObject,
    )
}
