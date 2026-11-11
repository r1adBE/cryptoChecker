import Foundation

/// Regeln beim Wiederherstellen — wie `BackupRestorePolicy.kt` (gleiche Fälle in den Tests).
///
/// Eine Sicherung ohne Passwort hat keine Echtheitsprüfung: Eine bearbeitete Datei im gleichen Format
/// wird angenommen. Darum schaltet sie die Portfolio-Sperre nie aus, wenn sie gerade an ist — und
/// auch sonst nichts in Richtung «weniger Schutz»: «Portfolio in Systemsicherung» nie ein (`systemBackup`),
/// «Beträge verbergen» nie aus (`hideAmounts`), eigener Bitcoin-Server nie gesetzt, ersetzt oder gelöscht
/// (`AddressServer.restored`, Hinweis `addressServerNote`).
///
/// Eine Sicherung mit Passwort darf das alles — ein Passwort beweist aber keine Herkunft (jeder kann eine Datei samt
/// Passwort weitergeben). Darum nie still: Schaltet sie Schutz ab, nennt die Meldung jeden Fall (`loweredProtection`,
/// neu gesetzter Server mit Hostnamen: `AddressServerNote.SET`, `addressServerSetHost`).
enum BackupRestorePolicy {
    /// Was mit «appLock» aus der Sicherung geschieht: `apply` = neuer Wert (nil = unverändert lassen);
    /// `keptOn` = die Sicherung wollte die Sperre ausschalten, sie bleibt aber an (Hinweis in der Meldung).
    struct AppLockDecision: Equatable, Sendable {
        let apply: Bool?
        let keptOn: Bool
    }

    /// `wanted` = Wert in der Sicherung, `current` = Sperre jetzt, `encrypted` = Sicherung
    /// mit Passwort, `canAuthenticate` = Gerät kann entsperren (sonst sperrte «an» das Portfolio aus).
    static func appLock(wanted: Bool, current: Bool, encrypted: Bool, canAuthenticate: Bool) -> AppLockDecision {
        if wanted { return AppLockDecision(apply: canAuthenticate ? true : nil, keptOn: false) }
        if current && !encrypted { return AppLockDecision(apply: nil, keptOn: true) }
        return AppLockDecision(apply: false, keptOn: false)
    }

    /// «Portfolio in Systemsicherung» aus der Sicherung: neuer Wert oder nil (unverändert). Ohne Echtheitsprüfung
    /// (`trusted` false) nur ausschalten — einschalten gäbe die Portfolio-Daten in die Systemsicherung.
    static func systemBackup(wanted: Bool, current: Bool, trusted: Bool) -> Bool? {
        if wanted && !current && !trusted { return nil }
        return wanted
    }

    /// «Beträge verbergen» aus der Sicherung: neuer Wert oder nil (unverändert). Ohne Echtheitsprüfung
    /// (`trusted` false) nur einschalten — ausschalten zeigte die Beträge offen.
    static func hideAmounts(wanted: Bool, current: Bool, trusted: Bool) -> Bool? {
        if !wanted && current && !trusted { return nil }
        return wanted
    }

    /// Hinweis zum eigenen Bitcoin-Server in der Meldung nach dem Wiederherstellen (`addressServerNote`, wie Android).
    enum AddressServerNote: String, Equatable, Sendable {
        /// Sicherung ohne Passwort hatte einen anderen gültigen Wert (oder keinen): nicht übernommen.
        case KEPT
        /// Sicherung mit Passwort ohne eigenen Server: der bisherige ist weg, wieder öffentliche Knoten.
        case PUBLIC
        /// Sicherung mit Passwort mit anderem Server: der bisherige ist ersetzt.
        case CHANGED
        /// Sicherung mit Passwort setzt einen Server, wo keiner war: Bitcoin-Adressen gehen ab jetzt nur dorthin.
        case SET
    }

    /// Hinweis zum eigenen Bitcoin-Server (`AddressServer.restored`, gleiche Fälle in address_server.json «restore»):
    /// nil = nichts zu sagen (Wert gleich oder ungültig).
    static func addressServerNote(value: String?, current: String?, trusted: Bool) -> AddressServerNote? {
        let wanted = AddressServer.restored(value: value, current: current, trusted: true)
        if wanted == current { return nil }
        if !trusted { return .KEPT }
        if current == nil { return .SET }
        return wanted == nil ? .PUBLIC : .CHANGED
    }

    /// Hostname des neu gesetzten Servers für die Meldung (`AddressServerNote.SET`, «knoten.example» ohne Port und Pfad);
    /// nil in jedem anderen Fall.
    static func addressServerSetHost(value: String?, current: String?, trusted: Bool) -> String? {
        guard addressServerNote(value: value, current: current, trusted: trusted) == .SET else { return nil }
        return AddressServer.restored(value: value, current: current, trusted: trusted).map { AddressServer.host($0) }
    }

    /// Schutz, den die Sicherung abgeschaltet hat: `systemBackupOn` = «Portfolio in Systemsicherung» war aus und ist jetzt
    /// an, `amountsShown` = «Beträge verbergen» war an und ist jetzt aus. Beides nennt die Meldung nach dem Wiederherstellen.
    struct LoweredProtection: Equatable, Sendable {
        var systemBackupOn = false
        var amountsShown = false
        var any: Bool { systemBackupOn || amountsShown }
    }

    /// Was die Sicherung an Schutz abschaltet (`systemBackup`, `hideAmounts`) — ohne Passwort (`trusted` false) nie etwas.
    static func loweredProtection(wantedSystemBackup: Bool, currentSystemBackup: Bool, wantedHideAmounts: Bool,
                                  currentHideAmounts: Bool, trusted: Bool) -> LoweredProtection {
        LoweredProtection(
            systemBackupOn: !currentSystemBackup
                && systemBackup(wanted: wantedSystemBackup, current: currentSystemBackup, trusted: trusted) == true,
            amountsShown: currentHideAmounts
                && hideAmounts(wanted: wantedHideAmounts, current: currentHideAmounts, trusted: trusted) == false)
    }

    /// Ungültige Einträge aus der Systemsicherung, die noch zu melden sind (aufsummiert, ohne Überlauf).
    static func noticeTotal(pending: Int, added: Int) -> Int {
        let sum = Int64(max(pending, 0)) + Int64(max(added, 0))
        return Int(min(sum, Int64(Int32.max)))
    }
}
