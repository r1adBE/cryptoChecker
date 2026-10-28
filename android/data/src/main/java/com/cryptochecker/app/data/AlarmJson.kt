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
 * (aus «RSI über 70» würde sonst «BTC über 70», das sofort auslöst). Ebenso eine Schwelle ≤ 0 bei
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

    /** Obergrenze (ausschliesslich) fallender Prozent-Alarme; wie Editor `MAX_FALL_PERCENT` und iOS. */
    private const val MAX_FALL_PERCENT = 100.0

    /**
     * Fallende Prozent-Alarme («fällt um x %», Open Interest fällt) ab 100 % lösen nie aus — der Editor
     * nimmt nur Werte unter 100 an. Aus einer Sicherung kommen sie AUSGESCHALTET (nicht als ungültig
     * gezählt) und werden in der Alarmliste gekennzeichnet («Schwelle ungültig – bitte anpassen»). Wie iOS
     * `BackupManager.exceedsFall`.
     */
    fun exceedsFall(condition: AlarmCondition, threshold: Double): Boolean =
        (condition == AlarmCondition.CHANGE_PERCENT_DOWN || condition == AlarmCondition.OI_DOWN) &&
            threshold >= MAX_FALL_PERCENT

    /**
     * Schalter auf [enable] stellen erlaubt? Einschalten eines [exceedsFall]-Alarms
     * nie (er löst nie aus, die Karte zeigt «Schwelle ungültig») — erst im Editor anpassen; ausschalten immer. Wie iOS
     * `BackupManager.toggleAllowed` (Fälle `alarm_toggle.json`).
     */
    fun toggleAllowed(condition: AlarmCondition, threshold: Double, enable: Boolean): Boolean =
        !enable || !exceedsFall(condition, threshold)

    /** Alle Alarme einer Liste (Felder geprüft, [BackupFields.ALARM]); ungültige werden gezählt. */
    fun parse(array: JSONArray): PortfolioJson.Parsed<AlarmEntity> {
        val items = ArrayList<AlarmEntity>(array.length())
        var invalid = 0
        for (i in 0 until array.length()) {
            val alarm = fromJson(array.getJSONObject(i))
            if (alarm == null) invalid++ else items += alarm
        }
        return PortfolioJson.Parsed(items, invalid)
    }

    /**
     * Ein Alarm (Felder geprüft, [BackupFields.ALARM], sonst [org.json.JSONException]); null = ungültig (Alarmart
     * unbekannt, Schwelle nicht endlich bzw. ≤ 0, wo sie positiv sein muss). Fallend ≥ 100 %: übernommen, aber
     * ausgeschaltet ([exceedsFall]).
     */
    fun fromJson(o: JSONObject): AlarmEntity? {
        val condition = AlarmCondition.entries.firstOrNull { it.name == o.getString("condition") }
        val threshold = o.getDouble("threshold")
        if (condition == null || !threshold.isFinite()) return null
        if (needsPositiveThreshold(condition) && threshold <= 0.0) return null
        return AlarmEntity(
            id = o.getLong("id"),
            watchId = o.getLong("watchId"),
            condition = condition,
            threshold = threshold,
            enabled = o.getBoolean("enabled") && !exceedsFall(condition, threshold),
            repeating = o.getBoolean("repeating"),
            sound = o.getBoolean("sound"),
            vibrate = o.getBoolean("vibrate"),
            speak = o.getBoolean("speak"),
            referencePrice = if (o.isNull("referencePrice")) null else o.getDouble("referencePrice").takeIf { it.isFinite() },
            // Auf 1 … 365 gekürzt (getInt würde grosse Zahlen still umbrechen), wie iOS `Alarm.clampWindowHours`
            windowHours = o.getLong("windowHours").coerceIn(1L, MAX_WINDOW_HOURS.toLong()).toInt(),
            // null = Quote-Währung
            currency = if (o.isNull("currency")) null
            else o.getString("currency").trim().uppercase().takeIf { c -> c.length == 3 && c.all { it in 'A'..'Z' } },
        )
    }
}
