import Foundation

/// Karte «Ersten Alarm setzen» über der Merkliste: bleibt stehen (anders als das Banner nach dem Hinzufügen,
/// das nach 6 s verschwindet), solange es Paare, aber noch keinen einzigen Alarm gibt — bis man sie schliesst
/// (gemerkt in den Einstellungen). Reine Regel — wie Android `FirstAlarmCard.kt` (gleiche Testfälle in
/// FirstAlarmCardTests / FirstAlarmCardTest).
enum FirstAlarmCard {
    /// - Parameters:
    ///   - alarmCount: alle Alarme wie bei `AlarmBellRoute`; nil = noch nicht geladen → keine Karte
    ///   - watchCount: Paare der ganzen Merkliste
    ///   - dismissed: Karte schon geschlossen
    ///   - busy: Suche, Sortier- oder Auswahlmodus: die Karte stünde nur im Weg
    static func shouldShow(alarmCount: Int?, watchCount: Int, dismissed: Bool, busy: Bool) -> Bool {
        !dismissed && !busy && alarmCount == 0 && watchCount > 0
    }
}
