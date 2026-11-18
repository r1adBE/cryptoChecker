package com.cryptochecker.app.domain.alarm

import com.cryptochecker.app.domain.model.AlarmCondition

/**
 * Fallende Prozent-Alarme («fällt um x %», Open Interest fällt) ab 100 % lösen nie aus — der Editor
 * nimmt nur Werte unter 100 an. Aus einer Sicherung kommen sie AUSGESCHALTET (nicht als ungültig
 * gezählt) und werden in der Alarmliste gekennzeichnet («Schwelle ungültig – bitte anpassen»). Wie iOS
 * `BackupManager.exceedsFall`.
 */
object AlarmFallLimit {
    /** Obergrenze (ausschliesslich) fallender Prozent-Alarme; wie Editor `MAX_FALL_PERCENT` und iOS. */
    private const val MAX_FALL_PERCENT = 100.0

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
}
