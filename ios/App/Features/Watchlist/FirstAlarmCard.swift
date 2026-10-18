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

    /// Paar, dessen Alarme die Karte öffnet (und das sie nennt): das erste in der gespeicherten Reihenfolge der Merkliste
    /// (`stored`, Favoriten zuerst, dann eigene Reihenfolge) — nicht das erste der gerade sortierten Ansicht, die je nach
    /// Spalte (Kurs, Änderung) springt. Deaktivierte Paare (`disabled`) nur, wenn es keine anderen gibt. Wie Android.
    static func target<T>(stored: [T], disabled: (T) -> Bool) -> T? {
        stored.first { !disabled($0) } ?? stored.first
    }
}
