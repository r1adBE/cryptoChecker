import Foundation

/// Letzter Durchgang der Logo-Bilder für den Diagnosebericht («== Coin logos =="): Ende, neu geladen, gescheitert
/// (gesperrt), gebremst, offen (gestört bzw. 403, kommen mit dem nächsten Abgleich), nicht gespeichert. Nur Zahlen, keine
/// Kürzel. Gespeichert als «at;added;failed;paused;open;unsaved». Gleich in Android `LogoPassSummary.kt`.
struct LogoPassSummary: Equatable, Sendable {
    let at: Int64
    let added: Int
    let failed: Int
    let paused: Int
    let open: Int
    let unsaved: Int

    func encode() -> String {
        [String(at), String(added), String(failed), String(paused), String(open), String(unsaved)].joined(separator: ";")
    }

    /// Gespeicherter Stand; nil bei fehlendem oder unlesbarem Text.
    static func decode(_ text: String?) -> LogoPassSummary? {
        guard let text else { return nil }
        let parts = text.trimmingCharacters(in: .whitespacesAndNewlines).split(separator: ";", omittingEmptySubsequences: false)
        guard parts.count == 6, let at = Int64(parts[0]) else { return nil }
        var n: [Int] = []
        for part in parts.dropFirst() {
            guard let value = Int(part) else { return nil }
            n.append(max(value, 0))
        }
        return LogoPassSummary(at: at, added: n[0], failed: n[1], paused: n[2], open: n[3], unsaved: n[4])
    }
}

/// Logos auf dem Gerät für den Diagnosebericht: Bilder in der Ablage, nach einem Fehlschlag gerade gesperrt und der
/// letzte Durchgang (nil = noch keiner). Wie Android `LogoDiagnostics`.
struct LogoDiagnostics: Equatable, Sendable {
    let onDevice: Int
    let locked: Int
    let lastPass: LogoPassSummary?
}
