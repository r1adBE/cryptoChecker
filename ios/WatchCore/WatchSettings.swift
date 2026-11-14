import Foundation

/// Wahl der Uhr: «Wie iPhone» (`.phone`, Liste `phone` = Favoriten des iPhones) oder «Eigene Liste»
/// (`.own`, Liste `own`); `revision` = Änderungsnummer von Wahl und eigener Liste, jede Änderung zählt eins über den
/// beide bekannten Stände hinaus (`change`); die höhere Nummer gewinnt (Gleichstand: iPhone), unabhängig von der Uhrzeit
/// der Geräte. Zu `phone` die Paare der Favoriten (`pairs`, `WatchPair`: Börse und Kontrakt wie auf dem iPhone, JSON
/// «pp»); die Uhr zeigt und fragt dann genau dieses Paar (`phoneKeys`), ohne Paar bleibt es beim Kürzel.
/// Dazu zwei Werte, die nur das iPhone bestimmt (wie `phone`): `source` (Kurse vom iPhone oder immer selbst
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
    var pairs: [WatchPair] = []

    /// Schlüssel der Liste «Wie iPhone»: je Coin das Paar des iPhones (`WatchPair.key`), ohne Paar das Kürzel.
    var phoneKeys: [String] { phone.map { coin in pairs.first { $0.base == coin }?.key ?? coin } }

    /// Schlüssel, die die Uhr zeigt (Kürzel der eigenen Liste bzw. `phoneKeys`); Anzeige über `WatchPair.label(of:)`.
    var shown: [String] { mode == .phone ? phoneKeys : own }

    /// Schlüssel beider Listen: Kurse dazu behält die Uhr, das iPhone schickt sie.
    var kept: [String] { own + phoneKeys }

    /// Für eine eigene Änderung von Wahl oder eigener Liste: eine Nummer über dem bisherigen Stand.
    func nextRevision() -> WatchSettings {
        var next = self
        next.revision = revision + 1
        return next
    }

    func toJson() -> String {
        // Feste Reihenfolge wie Android (JSONObject in Einfügereihenfolge)
        func list(_ l: [String]) -> String { "[" + l.map { "\"\($0)\"" }.joined(separator: ",") + "]" }
        let head = "{\"v\":2,\"mode\":\"\(mode.rawValue)\",\"own\":\(list(own)),\"phone\":\(list(phone)),\"r\":\(revision),"
            + "\"src\":\"\(source.rawValue)\",\"i\":\(interval)"
        guard !pairs.isEmpty else { return head + "}" }
        let pp = pairs.map { p in
            ["b": p.base, "q": p.quote, "x": p.exchange, "c": p.contract]
        }
        let data = (try? JSONSerialization.data(withJSONObject: pp, options: [.sortedKeys])) ?? Data("[]".utf8)
        return head + ",\"pp\":" + (String(data: data, encoding: .utf8) ?? "[]") + "}"
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
        let phone = WatchCoins.fromFavorites(list("phone"))
        let all = (o["pp"] as? [Any] ?? []).compactMap { item -> WatchPair? in
            guard let p = item as? [String: Any] else { return nil }
            return WatchPair.of(base: p["b"] as? String ?? "", quote: p["q"] as? String ?? "",
                                exchange: p["x"] as? String ?? "", contract: p["c"] as? String ?? "")
        }
        // Je Coin das erste gültige Paar mit dieser Basis, in der Reihenfolge von `phone`
        let pairs = phone.compactMap { coin in all.first { $0.base == coin } }
        return WatchSettings(mode: mode, own: WatchCoins.cleanOwn(list("own")), phone: phone, revision: revision,
                             source: source, interval: interval, pairs: pairs)
    }

    /// Zusammenführen: Wahl und eigene Liste von der Seite mit der höheren `revision`, bei Gleichstand vom iPhone (beide
    /// Geräte landen so beim selben Stand); iPhone-Liste samt `pairs`, `source` und `interval` immer vom iPhone
    /// (`remoteIsPhone` = true, wenn `remote` vom iPhone kommt).
    static func merge(_ local: WatchSettings, _ remote: WatchSettings, remoteIsPhone: Bool) -> WatchSettings {
        let remoteWins = remote.revision > local.revision || (remote.revision == local.revision && remoteIsPhone)
        var winner = remoteWins ? remote : local
        let phoneSide = remoteIsPhone ? remote : local
        winner.phone = phoneSide.phone
        winner.source = phoneSide.source
        winner.interval = phoneSide.interval
        winner.pairs = phoneSide.pairs
        return winner
    }

    /// Kennt die Uhr (`watch`, ihr gemeldeter Stand) einen anderen iPhone-Teil als das iPhone (`phone`): Favoriten samt
    /// Paaren, Kursquelle oder Intervall? Dann hat sie den Stand des iPhones nicht — das iPhone schickt ihn noch einmal.
    /// Wie Android `phoneSideDiffers`.
    static func phoneSideDiffers(_ phone: WatchSettings, watch: WatchSettings) -> Bool {
        phone.phone != watch.phone || phone.pairs != watch.pairs || phone.source != watch.source
            || phone.interval != watch.interval
    }

    /// iPhone ohne Stand (App dort zurückgesetzt oder entfernt): wie ein iPhone mit Standardwerten (`merge` mit
    /// `WatchSettings()`) — iPhone-Liste und Paare leer, Kursquelle und Intervall Standard; Wahl und eigene Liste der Uhr
    /// bleiben, sobald sie einmal geändert wurden (Nummer > 0). Apple Watch: iPhone-App nicht mehr installiert; Wear OS:
    /// Eintrag des Handys gelöscht. Wie Android `forgetPhone`.
    static func forgetPhone(_ local: WatchSettings) -> WatchSettings {
        merge(local, WatchSettings(), remoteIsPhone: true)
    }

    /// Eigene Änderung von Wahl oder eigener Liste auf dieser Seite (`isPhone`): zuerst den neuesten bekannten Stand der
    /// anderen Seite (`remote`, nil = keiner) übernehmen (`merge`), dann `edit` anwenden, mit einer Nummer über beiden
    /// Ständen (max(lokal, andere Seite) + 1). So gewinnt die Änderung auf beiden Geräten, auch wenn die andere Seite
    /// schon weiter gezählt hatte (Uhr 5, iPhone 3 ändert → 6). `edit` ändert die Nummer nicht. Wie Android.
    static func change(_ local: WatchSettings, remote: WatchSettings?, isPhone: Bool,
                       _ edit: (inout WatchSettings) -> Void) -> WatchSettings {
        var next = remote.map { merge(local, $0, remoteIsPhone: !isPhone) } ?? local
        let top = Swift.max(local.revision, remote?.revision ?? 0, next.revision)
        edit(&next)
        next.revision = top + 1
        return next
    }
}
