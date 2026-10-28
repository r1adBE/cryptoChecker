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
object BackupJournalCodec {

    /**
     * Journal-Inhalt: der gemerkte Stand im Format der Sicherung (Merkliste, Alarme, Portfolio, Portfolio-Alarme, Adressen,
     * Favoriten, Einstellungen). Alarm-Zustand und letzte Guthaben gehen dabei nicht mit — wie bei einer Sicherung.
     */
    fun journalJson(previous: PreviousState, createdAt: Long = System.currentTimeMillis()): JSONObject = JSONObject()
        .put("format", RestoreJournal.FORMAT)
        .put("version", RestoreJournal.VERSION)
        // Alter des Journals für [RestoreRecoveryPolicy]
        .put("createdAt", createdAt)
        .put("watches", JSONArray().apply { previous.watches.forEach { put(watchToJson(it)) } })
        .put("alarms", JSONArray().apply { previous.alarms.forEach { put(alarmToJson(it)) } })
        .put("portfolio", JSONArray().apply { previous.portfolio.forEach { put(PortfolioJson.txToJson(it)) } })
        .put("portfolioAlarms", JSONArray().apply { previous.portfolioAlarms.forEach { put(PortfolioJson.alarmToJson(it)) } })
        .put(
            WatchedAddressRules.BACKUP_KEY,
            WatchedAddressRules.toJson(WatchedAddressRules.backupEntries(previous.watchedAddresses.mapNotNull { it.toDomain() })).asOrgJson(),
        )
        .put("favorites", JSONObject().apply {
            FavoriteKind.entries.forEach { kind -> put(kind.name, JSONArray(previous.favorites[kind].orEmpty().toList())) }
        })
        .put("settings", previous.settings)

    /**
     * Gegenstück zu [journalJson]: gleiches Format wie eine Sicherung, jeder Teil und jedes Feld Pflicht
     * ([BackupFields.checkParts]); sonst [IllegalArgumentException] (Journal unlesbar).
     */
    fun previousFromJournal(root: JSONObject): PreviousState {
        require(root.optString("format") == RestoreJournal.FORMAT) { "Kein Journal" }
        require((root.opt("version") as? Number)?.toDouble() == RestoreJournal.VERSION.toDouble()) { "Journal-Version" }
        BackupFields.checkParts(root)
        val favorites = root.getJSONObject("favorites")
        val watches = root.getJSONArray("watches")
        return PreviousState(
            watches = (0 until watches.length()).map { jsonToWatch(watches.getJSONObject(it)) },
            alarms = AlarmJson.parse(root.getJSONArray("alarms")).items,
            portfolio = PortfolioJson.parseTxs(root.getJSONArray("portfolio")).items,
            portfolioAlarms = PortfolioJson.parseAlarms(root.getJSONArray("portfolioAlarms")).items,
            watchedAddresses = WatchedAddressRules.merge(
                emptyList(), WatchedAddressRules.fromJson(root.getJSONArray(WatchedAddressRules.BACKUP_KEY).asDomainJson()),
            ).map { WatchedAddressEntity.from(it) },
            favorites = FavoriteKind.entries.associateWith { kind ->
                favorites.getJSONArray(kind.name).let { arr -> (0 until arr.length()).map { arr.getString(it) }.toSet() }
            },
            settings = root.getJSONObject("settings"),
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
