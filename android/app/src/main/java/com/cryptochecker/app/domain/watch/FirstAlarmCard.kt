package com.cryptochecker.app.domain.watch

/**
 * Karte «Ersten Alarm setzen» über der Merkliste: bleibt stehen (anders als das Banner nach dem
 * Hinzufügen, das nach 6 s verschwindet), solange es Paare, aber noch keinen einzigen Alarm gibt —
 * bis man sie schliesst (gemerkt in den Einstellungen). Reine Regel, Swift-Spiegel
 * `FirstAlarmCard.swift` (gleiche Testfälle in FirstAlarmCardTest / FirstAlarmCardTests).
 */
object FirstAlarmCard {
    /**
     * @param alarmCount alle Alarme wie bei [AlarmBellRoute]; null = noch nicht geladen → keine Karte
     * @param watchCount Paare der ganzen Merkliste
     * @param dismissed Karte schon geschlossen
     * @param busy Suche, Sortier- oder Auswahlmodus: die Karte stünde nur im Weg
     */
    fun shouldShow(alarmCount: Int?, watchCount: Int, dismissed: Boolean, busy: Boolean): Boolean =
        !dismissed && !busy && alarmCount == 0 && watchCount > 0
}
