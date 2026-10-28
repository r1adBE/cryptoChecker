import Foundation

/// «Wie aktuell?» (Einstellungen › Kurs-Aktualisierung) — wie `UpdateMode.kt`.
///
///  - `SPARSAM` (Standard, empfohlen): nur die Hintergrund-Aktualisierung im gewählten Intervall, kein WebSocket.
///  - `LIVE_OFFEN` (für Profis): Live-Kurse per WebSocket, solange die Merkliste offen ist,
///    dazu die Hintergrund-Aktualisierung bei geschlossener App.
///
/// Gespeichert und gesichert unter dem Namen (Schlüssel «updateMode», wie Android).
enum UpdateMode: String, Codable, CaseIterable, Sendable {
    case SPARSAM
    case LIVE_OFFEN

    static let `default`: UpdateMode = .SPARSAM

    /// Wahlen in den Einstellungen.
    static let iosChoices: [UpdateMode] = [.SPARSAM, .LIVE_OFFEN]

    /// Live-Kurse per WebSocket, solange die Merkliste offen ist.
    var liveWhileOpen: Bool { self != .SPARSAM }

    /// Gespeicherter Name; unbekannt (neuere Version) oder leer → nil.
    static func from(name: String?) -> UpdateMode? {
        guard let n = name?.trimmingCharacters(in: .whitespacesAndNewlines).uppercased(), !n.isEmpty else { return nil }
        return UpdateMode(rawValue: n)
    }
}
