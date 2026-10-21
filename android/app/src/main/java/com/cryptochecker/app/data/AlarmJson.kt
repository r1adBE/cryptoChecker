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
 * (aus «RSI über 70» würde sonst «BTC über 70», das sofort auslöst). Seit Build 102 ebenso eine Schwelle ≤ 0 bei
 * Kursmarke, Prozent, Bewegung, Volumen und Open Interest ([needsPositiveThreshold]): «Kurs über 0» oder «Bewegung um 0 %» löste bei jeder
 * Prüfung aus; die Eingabe lässt solche Werte nie zu (ThresholdParser).
 */
object AlarmJson {

    /** Grösster sinnvoller Zeitraum (365 Tage bei «Nahe am Hoch/Tief»); wie iOS `Alarm.maxWindowHours`. */
    const val MAX_WINDOW_HOURS = 365

    /**
     * Alarmarten, deren Schwelle grösser als 0 sein muss: Kursmarke, Prozent seit Referenz, Bewegung im Zeitraum,
     * Volumen-Sprung (Faktor) und Open Interest (Prozent). Nicht
     * Funding (darf negativ sein) und «Nahe am Hoch/Tief» (0 % = nur neues Hoch/Tief). Wie iOS `BackupManager.needsPositiveThreshold`.
     */
    fun needsPositiveThreshold(condition: AlarmCondition): Boolean = when (condition) {
        AlarmCondition.PRICE_ABOVE, AlarmCondition.PRICE_BELOW,
        AlarmCondition.CHANGE_PERCENT_UP, AlarmCondition.CHANGE_PERCENT_DOWN,
        AlarmCondition.MOVE_PERCENT_WINDOW,
        AlarmCondition.VOLUME_SPIKE, AlarmCondition.OI_UP, AlarmCondition.OI_DOWN -> true
        else -> false
    }

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

    /** Ein Alarm; null = ungültig (Id/Paar fehlt, Alarmart unbekannt, Schwelle nicht endlich bzw. ≤ 0, wo sie positiv sein muss). */
    fun fromJson(o: JSONObject): AlarmEntity? {
        if (!o.has("id") || !o.has("watchId")) return null
        val condition = AlarmCondition.entries.firstOrNull { it.name == o.optString("condition") } ?: return null
        val threshold = o.optDouble("threshold").takeIf { it.isFinite() } ?: return null
        if (needsPositiveThreshold(condition) && threshold <= 0.0) return null
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
