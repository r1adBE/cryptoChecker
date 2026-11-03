import Foundation

/// Wahl der Uhr: «Wie iPhone» (`.phone`, Liste `phone` = Favoriten des iPhones) oder «Eigene Liste»
/// (`.own`, Liste `own`); `revision` = Änderungsnummer von Wahl und eigener Liste, jede Änderung zählt eins über den
/// bekannten Stand hinaus (`nextRevision`); die höhere Nummer gewinnt (Gleichstand: iPhone), unabhängig von der Uhrzeit
/// der Geräte. Dazu zwei Werte, die nur das iPhone bestimmt (wie `phone`): `source` (Kurse vom iPhone oder immer selbst
/// abrufen) und `interval` (Intervall der Kurs-Aktualisierung des iPhones, `WatchInterval.clamp`). Übertragen wird das
/// als JSON — auf dem iPhone über WatchConnectivity (direkt zwischen iPhone und Uhr), gleich wie Android
/// (Wear-Daten-Schicht).
struct WatchSettings: Equatable, Sendable {
    enum Mode: String, Sendable { case phone, own }

    /// «Kurse vom Handy, sonst selbst» (Standard) oder «Immer selbst abrufen»; Rohwerte wie Android.
    enum Source: String, Sendable { case phone, alwaysSelf = "self" }

    var mode: Mode = .own
    var own: [String] = WatchCoins.defaultOwn
    var phone: [String] = []
    var revision: Int64 = 0
    var source: Source = .phone
    var interval: Int = WatchInterval.defaultMinutes

    /// Coins, die die Uhr zeigt.
    var shown: [String] { mode == .phone ? phone : own }

    /// Für eine eigene Änderung von Wahl oder eigener Liste: eine Nummer über dem bisherigen Stand.
    func nextRevision() -> WatchSettings {
        var next = self
        next.revision = revision + 1
        return next
    }

    func toJson() -> String {
        // Feste Reihenfolge wie Android (JSONObject in Einfügereihenfolge)
        func list(_ l: [String]) -> String { "[" + l.map { "\"\($0)\"" }.joined(separator: ",") + "]" }
        return "{\"v\":2,\"mode\":\"\(mode.rawValue)\",\"own\":\(list(own)),\"phone\":\(list(phone)),\"r\":\(revision),"
            + "\"src\":\"\(source.rawValue)\",\"i\":\(interval)}"
    }

    /// Liest `text`; Unlesbares → nil, Unbekanntes → Standardwerte je Feld.
    static func fromJson(_ text: String?) -> WatchSettings? {
        guard let data = text?.data(using: .utf8),
              let o = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else { return nil }
        func list(_ name: String) -> [String] {
            (o[name] as? [Any] ?? []).compactMap { $0 as? String }.filter { !$0.isEmpty }
        }
        let mode = Mode(rawValue: o["mode"] as? String ?? "") ?? .own
        let revision = (o["r"] as? NSNumber)?.int64Value ?? 0
        let source = Source(rawValue: o["src"] as? String ?? "") ?? .phone
        let interval = WatchInterval.clamp((o["i"] as? NSNumber)?.intValue ?? WatchInterval.defaultMinutes)
        return WatchSettings(mode: mode, own: WatchCoins.cleanOwn(list("own")),
                             phone: WatchCoins.fromFavorites(list("phone")), revision: revision,
                             source: source, interval: interval)
    }

    /// Zusammenführen: Wahl und eigene Liste von der Seite mit der höheren `revision`, bei Gleichstand vom iPhone (beide
    /// Geräte landen so beim selben Stand); iPhone-Liste, `source` und `interval` immer vom iPhone (`remoteIsPhone` =
    /// true, wenn `remote` vom iPhone kommt).
    static func merge(_ local: WatchSettings, _ remote: WatchSettings, remoteIsPhone: Bool) -> WatchSettings {
        let remoteWins = remote.revision > local.revision || (remote.revision == local.revision && remoteIsPhone)
        var winner = remoteWins ? remote : local
        let phoneSide = remoteIsPhone ? remote : local
        winner.phone = phoneSide.phone
        winner.source = phoneSide.source
        winner.interval = phoneSide.interval
        return winner
    }
}
