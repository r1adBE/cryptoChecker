import Foundation

/// Wahl der Uhr: «Wie iPhone» (`.phone`, Liste `phone` = Favoriten des iPhones) oder «Eigene Liste»
/// (`.own`, Liste `own`); `time` = letzte Änderung (Epoch-ms), die neuere Seite gewinnt. Übertragen wird nur das, als
/// JSON — auf dem iPhone über WatchConnectivity (direkt zwischen iPhone und Uhr), gleich wie Android (Wear-Daten-Schicht).
struct WatchSettings: Equatable, Sendable {
    enum Mode: String, Sendable { case phone, own }

    var mode: Mode = .own
    var own: [String] = WatchCoins.defaultOwn
    var phone: [String] = []
    var time: Int64 = 0

    /// Coins, die die Uhr zeigt.
    var shown: [String] { mode == .phone ? phone : own }

    func toJson() -> String {
        // Feste Reihenfolge wie Android (JSONObject in Einfügereihenfolge)
        func list(_ l: [String]) -> String { "[" + l.map { "\"\($0)\"" }.joined(separator: ",") + "]" }
        return "{\"v\":1,\"mode\":\"\(mode.rawValue)\",\"own\":\(list(own)),\"phone\":\(list(phone)),\"t\":\(time)}"
    }

    /// Liest `text`; Unlesbares → nil, Unbekanntes → Standardwerte je Feld.
    static func fromJson(_ text: String?) -> WatchSettings? {
        guard let data = text?.data(using: .utf8),
              let o = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else { return nil }
        func list(_ name: String) -> [String] {
            (o[name] as? [Any] ?? []).compactMap { $0 as? String }.filter { !$0.isEmpty }
        }
        let mode = Mode(rawValue: o["mode"] as? String ?? "") ?? .own
        let time = (o["t"] as? NSNumber)?.int64Value ?? 0
        return WatchSettings(mode: mode, own: WatchCoins.cleanOwn(list("own")),
                             phone: WatchCoins.fromFavorites(list("phone")), time: time)
    }

    /// Zusammenführen: Wahl und eigene Liste von der neueren Seite (`time`); die iPhone-Liste immer vom iPhone
    /// (`remoteIsPhone` = true, wenn `remote` vom iPhone kommt).
    static func merge(_ local: WatchSettings, _ remote: WatchSettings, remoteIsPhone: Bool) -> WatchSettings {
        var newer = remote.time > local.time ? remote : local
        newer.phone = remoteIsPhone ? remote.phone : local.phone
        return newer
    }
}
