package com.cryptochecker.app.data

import com.cryptochecker.app.data.local.model.AlarmEntity
import com.cryptochecker.app.data.local.model.GROUP_NAME_MAX
import com.cryptochecker.app.data.local.model.NOTE_MAX
import com.cryptochecker.app.data.local.model.WatchEntity
import com.cryptochecker.app.domain.watch.WatchIdentity
import com.cryptochecker.app.util.TextLimit
import com.cryptochecker.marketdata.model.FuturesContractType
import org.json.JSONObject

/**
 * Format von Merkliste und Paar-Alarmen in der Sicherung ([BackupManager]) und im Journal
 * ([BackupJournalCodec]). Paar-Alarme lesen: [AlarmJson]; Portfolio: [com.cryptochecker.app.data.portfolio.PortfolioJson].
 */
object BackupWatchlistCodec {

    fun watchToJson(w: WatchEntity) = JSONObject()
        .put("id", w.id)
        .put("exchangeKey", w.exchangeKey)
        .put("exchangeName", w.exchangeName)
        .put("baseAsset", w.baseAsset)
        .put("quoteAsset", w.quoteAsset)
        .put("contractType", w.contractType.name)
        .put("pairId", w.pairId ?: JSONObject.NULL)
        .put("sortOrder", w.sortOrder)
        .put("notificationEnabled", w.notificationEnabled)
        .put("ttsEnabled", w.ttsEnabled)
        .put("favorite", w.favorite)
        .put("group", w.groupName ?: JSONObject.NULL)
        .put("note", w.note ?: JSONObject.NULL)
        .put("disabled", w.disabled)

    /** Ein Paar; Felder geprüft ([BackupFields.WATCH]), sonst [org.json.JSONException]. Unbekannte Vertragsart: keine. */
    fun jsonToWatch(o: JSONObject) = WatchEntity(
        id = o.getLong("id"),
        exchangeKey = o.getString("exchangeKey"),
        exchangeName = o.getString("exchangeName"),
        baseAsset = o.getString("baseAsset"),
        quoteAsset = o.getString("quoteAsset"),
        contractType = FuturesContractType.entries.firstOrNull { it.name == o.getString("contractType") }
            ?: FuturesContractType.NONE,
        pairId = if (o.isNull("pairId")) null else o.getString("pairId"),
        sortOrder = o.getInt("sortOrder"),
        notificationEnabled = o.getBoolean("notificationEnabled"),
        ttsEnabled = o.getBoolean("ttsEnabled"),
        favorite = o.getBoolean("favorite"),
        // Gruppe und Notiz mit denselben Grenzen wie die Eingabe (wie iOS `Watch.validGroupName`/`validNote`)
        groupName = if (o.isNull("group")) null else TextLimit.clean(o.getString("group"), GROUP_NAME_MAX),
        note = if (o.isNull("note")) null else TextLimit.clean(o.getString("note"), NOTE_MAX),
        disabled = o.getBoolean("disabled"),
    )

    /**
     * Merkliste und Paar-Alarme einer Sicherung, so wie sie übernommen würden; [invalidAlarms] = ungültige Alarme
     * ([AlarmJson]), [droppedPairs]/[droppedAlarms] = wegen [BackupRestoreLimits] weggelassen.
     */
    class Watchlist(
        val watches: List<WatchEntity>,
        val alarms: List<AlarmEntity>,
        val invalidAlarms: Int,
        val droppedPairs: Int,
        val droppedAlarms: Int,
    )

    /**
     * Lesen, bereinigen und begrenzen — für Rückfrage ([BackupManager.preview]) und Wiederherstellen gleich, damit die
     * Rückfrage genau die Zahlen nennt, die danach wegfallen. Paare mit unzulässigen Kennungen fallen weg, ihre Alarme
     * mit ihnen ([BackupRestoreLimits.accept]); dann [WatchlistRestoreCleanup], dann [BackupRestoreLimits.limit].
     */
    fun readWatchlist(root: JSONObject): Watchlist {
        val parsedWatches = root.getJSONArray("watches").let { a -> (0 until a.length()).map { jsonToWatch(a.getJSONObject(it)) } }
        val alarmsRead = AlarmJson.parse(root.getJSONArray("alarms"))
        val accepted = BackupRestoreLimits.accept(
            parsedWatches, alarmsRead.items, BackupRestoreLimits::acceptsWatch,
            watchId = { it.id }, alarmWatchId = { it.watchId },
        )
        val (cleanWatches, cleanAlarms) = WatchlistRestoreCleanup.clean(
            watches = accepted.watches,
            alarms = accepted.alarms,
            watchId = { it.id },
            pairKey = { WatchIdentity.key(it.exchangeKey, it.baseAsset, it.quoteAsset, it.contractType.name, it.pairId) },
            alarmId = { it.id },
            alarmWatchId = { it.watchId },
            withWatchId = { a, id -> a.copy(watchId = id) },
        )
        val limited = BackupRestoreLimits.limit(cleanWatches, cleanAlarms, watchId = { it.id }, alarmWatchId = { it.watchId })
        return Watchlist(
            limited.watches, limited.alarms, invalidAlarms = alarmsRead.invalid,
            droppedPairs = accepted.droppedPairs + limited.droppedPairs,
            droppedAlarms = accepted.droppedAlarms + limited.droppedAlarms,
        )
    }

    // Portfolio: Format in PortfolioJson (auch für die Kopie der Systemsicherung)

    fun alarmToJson(a: AlarmEntity) = JSONObject()
        .put("id", a.id)
        .put("watchId", a.watchId)
        .put("condition", a.condition.name)
        .put("threshold", a.threshold)
        .put("enabled", a.enabled)
        .put("repeating", a.repeating)
        .put("sound", a.sound)
        .put("vibrate", a.vibrate)
        .put("speak", a.speak)
        .put("referencePrice", a.referencePrice ?: JSONObject.NULL)
        .put("windowHours", a.windowHours)
        .put("currency", a.currency ?: JSONObject.NULL)
}
