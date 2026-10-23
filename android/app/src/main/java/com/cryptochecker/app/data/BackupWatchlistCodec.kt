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
internal object BackupWatchlistCodec {

    fun watchToJson(w: WatchEntity) = JSONObject()
        .put("id", w.id)
        .put("marketKey", w.marketKey)
        .put("marketName", w.marketName)
        .put("baseAsset", w.baseAsset)
        .put("quoteAsset", w.quoteAsset)
        .put("contractType", w.contractType.name)
        .put("pairId", w.pairId ?: JSONObject.NULL)
        .put("sortOrder", w.sortOrder)
        .put("notificationEnabled", w.notificationEnabled)
        .put("ttsEnabled", w.ttsEnabled)
        .put("favorite", w.favorite)
        .put("holdings", w.holdings ?: JSONObject.NULL)
        .put("group", w.groupName ?: JSONObject.NULL)
        .put("note", w.note ?: JSONObject.NULL)
        .put("disabled", w.disabled)

    fun jsonToWatch(o: JSONObject) = WatchEntity(
        id = o.getLong("id"),
        marketKey = o.getString("marketKey"),
        marketName = o.getString("marketName"),
        baseAsset = o.getString("baseAsset"),
        quoteAsset = o.getString("quoteAsset"),
        contractType = FuturesContractType.entries.firstOrNull { it.name == o.optString("contractType") }
            ?: FuturesContractType.NONE,
        pairId = if (o.isNull("pairId")) null else o.optString("pairId"),
        sortOrder = o.optInt("sortOrder"),
        // Ohne Angabe: an wie in der Version der Sicherung (nicht der Standard neuer Paare)
        notificationEnabled = o.optBoolean("notificationEnabled", WatchRepository.LEGACY_NOTIFICATION_ENABLED),
        ttsEnabled = o.optBoolean("ttsEnabled", false),
        favorite = o.optBoolean("favorite", false),
        // Ältere Sicherungen kennen Bestand und Gruppe noch nicht. Nur endlich (kein ∞), wie iOS `Watch.validHoldings`.
        holdings = if (o.isNull("holdings")) null
        else o.optDouble("holdings").takeIf { it.isFinite() && it > 0.0 },
        // Gruppe und Notiz mit denselben Grenzen wie die Eingabe (wie iOS `Watch.validGroupName`/`validNote`)
        groupName = if (o.isNull("group")) null else TextLimit.clean(o.optString("group"), GROUP_NAME_MAX),
        // Ältere Sicherungen haben keine Notiz
        note = if (o.isNull("note")) null else TextLimit.clean(o.optString("note"), NOTE_MAX),
        // Ältere Sicherungen kennen «deaktiviert» nicht: Paar aktiv
        disabled = o.optBoolean("disabled", false),
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
        val parsedWatches = root.optJSONArray("watches")?.let { a -> (0 until a.length()).map { jsonToWatch(a.getJSONObject(it)) } }
            .orEmpty()
        val alarmsRead = root.optJSONArray("alarms")?.let(AlarmJson::parse)
        val accepted = BackupRestoreLimits.accept(
            parsedWatches, alarmsRead?.items.orEmpty(), BackupRestoreLimits::acceptsWatch,
            watchId = { it.id }, alarmWatchId = { it.watchId },
        )
        val (cleanWatches, cleanAlarms) = WatchlistRestoreCleanup.clean(
            watches = accepted.watches,
            alarms = accepted.alarms,
            watchId = { it.id },
            pairKey = { WatchIdentity.key(it.marketKey, it.baseAsset, it.quoteAsset, it.contractType.name, it.pairId) },
            alarmId = { it.id },
            alarmWatchId = { it.watchId },
            withWatchId = { a, id -> a.copy(watchId = id) },
        )
        val limited = BackupRestoreLimits.limit(cleanWatches, cleanAlarms, watchId = { it.id }, alarmWatchId = { it.watchId })
        return Watchlist(
            limited.watches, limited.alarms, invalidAlarms = alarmsRead?.invalid ?: 0,
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
