package com.cryptochecker.app.data

import com.cryptochecker.app.data.local.model.AlarmEntity
import com.cryptochecker.app.data.local.model.NOTE_MAX
import com.cryptochecker.app.data.local.model.WatchEntity
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
        // Ältere Sicherungen kennen Bestand und Gruppe noch nicht.
        holdings = if (o.isNull("holdings")) null
        else o.optDouble("holdings").takeIf { !it.isNaN() && it > 0.0 },
        groupName = if (o.isNull("group")) null
        else o.optString("group").trim().takeIf { it.isNotEmpty() },
        // Ältere Sicherungen haben keine Notiz
        note = if (o.isNull("note")) null
        else o.optString("note").trim().take(NOTE_MAX).takeIf { it.isNotEmpty() },
        // Ältere Sicherungen kennen «deaktiviert» nicht: Paar aktiv
        disabled = o.optBoolean("disabled", false),
    )

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
