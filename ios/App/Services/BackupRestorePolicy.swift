import Foundation

/// Regeln beim Wiederherstellen — wie `BackupRestorePolicy.kt` (gleiche Fälle in den Tests).
///
/// Eine Sicherung ohne Passwort hat keine Echtheitsprüfung: Eine bearbeitete Datei im gleichen Format
/// wird angenommen. Darum schaltet sie die Portfolio-Sperre nie aus, wenn sie gerade an ist — und seit Build 99
/// auch sonst nichts in Richtung «weniger Schutz»: «Portfolio in Systemsicherung» nie ein (`systemBackup`),
/// «Beträge verbergen» nie aus (`hideAmounts`).
enum BackupRestorePolicy {
    /// Was mit «appLock» aus der Sicherung geschieht: `apply` = neuer Wert (nil = unverändert lassen);
    /// `keptOn` = die Sicherung wollte die Sperre ausschalten, sie bleibt aber an (Hinweis in der Meldung).
    struct AppLockDecision: Equatable, Sendable {
        let apply: Bool?
        let keptOn: Bool
    }

    /// `wanted` = Wert in der Sicherung (nil = fehlt), `current` = Sperre jetzt, `encrypted` = Sicherung
    /// mit Passwort, `canAuthenticate` = Gerät kann entsperren (sonst sperrte «an» das Portfolio aus).
    static func appLock(wanted: Bool?, current: Bool, encrypted: Bool, canAuthenticate: Bool) -> AppLockDecision {
        switch wanted {
        case nil:
            return AppLockDecision(apply: nil, keptOn: false)
        case true?:
            return AppLockDecision(apply: canAuthenticate ? true : nil, keptOn: false)
        case false?:
            if current && !encrypted { return AppLockDecision(apply: nil, keptOn: true) }
            return AppLockDecision(apply: false, keptOn: false)
        }
    }

    /// «Portfolio in Systemsicherung» aus der Sicherung: neuer Wert oder nil (unverändert). Ohne Echtheitsprüfung
    /// (`trusted` false) nur ausschalten — einschalten gäbe die Portfolio-Daten in die Systemsicherung.
    static func systemBackup(wanted: Bool?, current: Bool, trusted: Bool) -> Bool? {
        if wanted == true && !current && !trusted { return nil }
        return wanted
    }

    /// «Beträge verbergen» aus der Sicherung: neuer Wert oder nil (unverändert). Ohne Echtheitsprüfung
    /// (`trusted` false) nur einschalten — ausschalten zeigte die Beträge offen.
    static func hideAmounts(wanted: Bool?, current: Bool, trusted: Bool) -> Bool? {
        if wanted == false && current && !trusted { return nil }
        return wanted
    }

    /// Ungültige Einträge aus der Systemsicherung, die noch zu melden sind (aufsummiert, ohne Überlauf).
    static func noticeTotal(pending: Int, added: Int) -> Int {
        let sum = Int64(max(pending, 0)) + Int64(max(added, 0))
        return Int(min(sum, Int64(Int32.max)))
    }
}
