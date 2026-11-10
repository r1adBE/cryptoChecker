import Foundation

/// Rückfragen in Einstellungen › Portfolio (rein, testbar) — wie Android `SettingsConfirm`
/// (`Parity/settings_confirm.json`).
enum SettingsConfirm {
    /// Andere Einstandsmethode gewählt: erst «Rechnet alle Jahre neu» bestätigen (Ändern/Abbrechen); gleiche Wahl: nichts.
    static func costMethod(current: CostMethod, chosen: CostMethod) -> Bool { current != chosen }

    /// «Portfolio in Systemsicherung» einschalten: erst bestätigen (Daten gehen ins Geräte-Backup); aus geht sofort.
    static func systemBackup(enabling: Bool) -> Bool { enabling }
}
