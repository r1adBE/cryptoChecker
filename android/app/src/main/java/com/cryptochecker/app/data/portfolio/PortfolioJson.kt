package com.cryptochecker.app.data.portfolio

import com.cryptochecker.app.domain.alarm.PortfolioAlarmKind
import com.cryptochecker.app.domain.alarm.PortfolioAlarmLogic
import com.cryptochecker.app.domain.portfolio.PortfolioCalculator
import com.cryptochecker.app.domain.portfolio.PortfolioTxType
import org.json.JSONObject

/**
 * JSON-Form von Portfolio-Transaktionen und -Alarmen — gleiches Format wie in der eigenen
 * Sicherung (BackupManager, auch iOS) und in der Kopie für die Systemsicherung ([PortfolioBackupMirror]).
 */
object PortfolioJson {

    fun txToJson(t: PortfolioTxEntity): JSONObject = JSONObject()
        .put("id", t.id)
        .put("coin", t.coin)
        .put("type", t.type.name)
        .put("amount", t.amount)
        .put("priceUsdt", t.priceUsdt ?: JSONObject.NULL)
        .put("time", t.time)
        .put("note", t.note ?: JSONObject.NULL)

    /** Unvollständige oder ungültige Einträge werden übersprungen. */
    fun jsonToTx(o: JSONObject): PortfolioTxEntity? {
        val coin = PortfolioCalculator.normalizeCoin(o.optString("coin"))
        val type = PortfolioTxType.entries.firstOrNull { it.name == o.optString("type") } ?: return null
        val amount = o.optDouble("amount").takeIf { !it.isNaN() && !it.isInfinite() && it > 0.0 } ?: return null
        if (coin.isEmpty()) return null
        return PortfolioTxEntity(
            id = o.optLong("id", 0L).coerceAtLeast(0L),
            coin = coin,
            type = type,
            amount = amount,
            priceUsdt = if (o.isNull("priceUsdt")) null
            else o.optDouble("priceUsdt").takeIf { !it.isNaN() && !it.isInfinite() && it >= 0.0 },
            time = o.optLong("time", System.currentTimeMillis()),
            note = if (o.isNull("note")) null else o.optString("note").trim().takeIf { it.isNotEmpty() },
        )
    }

    /** Portfolio-Alarm (gleiches Format wie iOS); Zustand (gemeldet, zuletzt) wird nicht gesichert. */
    fun alarmToJson(a: PortfolioAlarmEntity): JSONObject = JSONObject()
        .put("id", a.id)
        .put("kind", a.kind.name)
        .put("threshold", a.threshold)
        .put("currency", a.currency ?: JSONObject.NULL)
        .put("enabled", a.enabled)
        .put("repeating", a.repeating)

    /** Unbekannte Art (neuere Version) oder ungültiger Schwellwert: überspringen. */
    fun jsonToAlarm(o: JSONObject): PortfolioAlarmEntity? {
        val kind = PortfolioAlarmKind.fromName(o.optString("kind")) ?: return null
        val threshold = o.optDouble("threshold").takeIf { PortfolioAlarmLogic.isValidThreshold(kind, it) } ?: return null
        val currency = if (o.isNull("currency")) null else o.optString("currency").trim().uppercase()
            .takeIf { code -> code.length == 3 && code.all { it in 'A'..'Z' } }
        if (kind.isValue && currency == null) return null
        return PortfolioAlarmEntity(
            id = o.optLong("id", 0L).coerceAtLeast(0L),
            kind = kind,
            threshold = threshold,
            currency = currency.takeIf { kind.isValue },
            enabled = o.optBoolean("enabled", true),
            repeating = o.optBoolean("repeating", false),
        )
    }
}
