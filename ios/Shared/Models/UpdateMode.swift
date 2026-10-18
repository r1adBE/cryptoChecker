import Foundation

/// «Wie aktuell?» (Einstellungen › Kurs-Aktualisierung) — wie `UpdateMode.kt`: eine Wahl statt
/// der früheren drei Schalter «Im Hintergrund aktualisieren», «Häufig aktualisieren» und «Live-Kurse».
///
///  - `SPARSAM` (Standard, empfohlen, seit Build 43): nur die Hintergrund-Aktualisierung im gewählten Intervall, kein WebSocket.
///  - `LIVE_OFFEN` (für Profis): Live-Kurse per WebSocket, solange die Merkliste offen ist,
///    dazu die Hintergrund-Aktualisierung bei geschlossener App.
///  - `IMMER_KURZ`: nur Android (Vordergrunddienst); iOS kennt keinen Dauerdienst → `LIVE_OFFEN`.
///
/// Gespeichert und gesichert unter dem Namen (Schlüssel «updateMode», wie Android). Übernahme aus den
/// früheren Schaltern: «Häufig aktualisieren» an → `IMMER_KURZ` (iOS → `LIVE_OFFEN`), sonst
/// «Live-Kurse» an → `LIVE_OFFEN`, sonst `SPARSAM`.
enum UpdateMode: String, Codable, CaseIterable, Sendable {
    case SPARSAM
    case LIVE_OFFEN
    case IMMER_KURZ

    static let `default`: UpdateMode = .SPARSAM

    /// Wahlen dieser Plattform: iOS ohne «Immer kurz».
    static let iosChoices: [UpdateMode] = [.SPARSAM, .LIVE_OFFEN]

    /// Live-Kurse per WebSocket, solange die Merkliste offen ist.
    var liveWhileOpen: Bool { self != .SPARSAM }

    /// Vordergrunddienst mit kurzem Intervall — nur Android; auf iOS nie gewählt.
    var frequentService: Bool { self == .IMMER_KURZ }

    /// Frühere Schalter für Sicherungen, die ältere App-Versionen lesen.
    var legacyBackgroundUpdates: Bool { true }
    var legacyLiveService: Bool { frequentService }
    var legacyLiveWebSocket: Bool { liveWhileOpen }

    /// Auf einer Plattform ohne Dauerdienst (iOS) wird `IMMER_KURZ` zu `LIVE_OFFEN`.
    func forPlatform(serviceAvailable: Bool) -> UpdateMode {
        self == .IMMER_KURZ && !serviceAvailable ? .LIVE_OFFEN : self
    }

    /// Gespeicherter Name; unbekannt (neuere Version) oder leer → nil.
    static func from(name: String?) -> UpdateMode? {
        guard let n = name?.trimmingCharacters(in: .whitespacesAndNewlines).uppercased(), !n.isEmpty else { return nil }
        return UpdateMode(rawValue: n)
    }

    /// Aus den früheren Schaltern; fehlen alle drei, nil. Ein fehlender einzelner Schalter zählt als «aus».
    static func fromLegacy(liveService: Bool?, liveWebSocket: Bool?, backgroundUpdates: Bool?,
                           serviceAvailable: Bool) -> UpdateMode? {
        if liveService == nil && liveWebSocket == nil && backgroundUpdates == nil { return nil }
        if liveService == true { return UpdateMode.IMMER_KURZ.forPlatform(serviceAvailable: serviceAvailable) }
        return liveWebSocket == true ? .LIVE_OFFEN : .SPARSAM
    }

    /// Gespeicherte Einstellung: der neue Wert, sonst die früheren Schalter, sonst bei einer bestehenden
    /// Installation `SPARSAM` (früheres Ab-Werk-Verhalten), sonst der Standard. iOS übergibt
    /// `existingInstall: false` — die gespeicherten Einstellungen enthalten die Schalter immer.
    static func resolve(stored: String?, legacyLiveService: Bool?, legacyWebSocket: Bool?, legacyBackground: Bool?,
                        existingInstall: Bool, serviceAvailable: Bool) -> UpdateMode {
        if let mode = fromBackup(stored, legacyLiveService: legacyLiveService, legacyWebSocket: legacyWebSocket,
                                 legacyBackground: legacyBackground, serviceAvailable: serviceAvailable) {
            return mode
        }
        return existingInstall ? .SPARSAM : .default
    }

    /// Aus einer Sicherung: «updateMode», sonst die früheren Schalter (ältere Sicherung); fehlt alles,
    /// nil = aktuelle Einstellung stehen lassen.
    static func fromBackup(_ stored: String?, legacyLiveService: Bool?, legacyWebSocket: Bool?, legacyBackground: Bool?,
                           serviceAvailable: Bool) -> UpdateMode? {
        if let mode = from(name: stored) { return mode.forPlatform(serviceAvailable: serviceAvailable) }
        return fromLegacy(liveService: legacyLiveService, liveWebSocket: legacyWebSocket,
                          backgroundUpdates: legacyBackground, serviceAvailable: serviceAvailable)
    }
}
