package com.cryptochecker.app.data

import com.cryptochecker.app.data.local.model.AlarmCondition
import com.cryptochecker.app.data.local.model.AlarmEntity
import com.cryptochecker.app.data.portfolio.PortfolioJson
import org.json.JSONArray
import org.json.JSONObject

/**
 * Paar-Alarme aus der Sicherung lesen (reines Kotlin; Gemeinsame Fälle `testdata/parity/backup_alarms.json`,
 * Swift: `BackupManager.alarmFromJson`). Eine unbekannte Alarmart (neuere App-Version) oder eine Schwelle
 * ohne endlichen Wert ist UNGÜLTIG und wird gezählt — nie zu «Kurs über …» mit fremder Schwelle umgedeutet
 * (aus «RSI über 70» würde sonst «BTC über 70», das sofort auslöst).
 */
object AlarmJson {

    /** Grösster sinnvoller Zeitraum (365 Tage bei «Nahe am Hoch/Tief»); wie iOS `Alarm.maxWindowHours`. */
    const val MAX_WINDOW_HOURS = 365

    /** Alle Alarme einer Liste; ungültige (auch Nicht-Objekte) werden gezählt. */
    fun parse(array: JSONArray): PortfolioJson.Parsed<AlarmEntity> {
        val items = ArrayList<AlarmEntity>(array.length())
        var invalid = 0
        for (i in 0 until array.length()) {
            val alarm = array.optJSONObject(i)?.let(::fromJson)
            if (alarm == null) invalid++ else items += alarm
        }
        return PortfolioJson.Parsed(items, invalid)
    }

    /** Ein Alarm; null = ungültig (Id/Paar fehlt, Alarmart unbekannt, Schwelle nicht endlich). */
    fun fromJson(o: JSONObject): AlarmEntity? {
        if (!o.has("id") || !o.has("watchId")) return null
        val condition = AlarmCondition.entries.firstOrNull { it.name == o.optString("condition") } ?: return null
        val threshold = o.optDouble("threshold").takeIf { it.isFinite() } ?: return null
        return AlarmEntity(
            id = o.optLong("id"),
            watchId = o.optLong("watchId"),
            condition = condition,
            threshold = threshold,
            enabled = o.optBoolean("enabled", true),
            repeating = o.optBoolean("repeating", false),
            sound = o.optBoolean("sound", true),
            vibrate = o.optBoolean("vibrate", true),
            speak = o.optBoolean("speak", false),
            referencePrice = if (o.isNull("referencePrice")) null else o.optDouble("referencePrice").takeIf { it.isFinite() },
            // Auf 1 … 365 gekürzt (optInt würde grosse Zahlen still umbrechen), wie iOS `Alarm.clampWindowHours`
            windowHours = o.optLong("windowHours", 1L).coerceIn(1L, MAX_WINDOW_HOURS.toLong()).toInt(),
            // Ältere Sicherungen kennen die Alarmwährung nicht (= Quote-Währung)
            currency = if (o.isNull("currency")) null
            else o.optString("currency").trim().uppercase().takeIf { c -> c.length == 3 && c.all { it in 'A'..'Z' } },
        )
    }
}
