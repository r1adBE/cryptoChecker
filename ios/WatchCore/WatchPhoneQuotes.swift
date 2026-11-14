import Foundation

/// Kurse vom iPhone für die Uhr: Das iPhone schickt für die Coins der Uhr (beide Listen) die Kurse, die es ohnehin schon
/// hat — samt 24 h, Börse und Zeit seines Abrufs, ohne eigene Abfrage für die Uhr. Eigene Liste (Kürzel): Paar der
/// Merkliste gegen `quoteAsset`, Spot, nicht deaktiviert; «Wie iPhone» (`WatchPair`-Schlüssel): genau das Paar des
/// Favoriten (Börse und Kontrakt, auch Futures/Perp und andere Börsen). Die Uhr übernimmt sie und fragt selbst nur, was das iPhone nicht liefert (`selfFetch`).
/// Wie Android `WatchPhoneQuotes` (`Parity/watch_link.json`).
enum WatchPhoneQuotes {
    static let quoteAsset = "USDT"

    /// Höchstzahl je Sendung: eigene Liste und Liste des iPhones.
    static let max = 2 * WatchCoins.max

    /// Paar der Merkliste des iPhones, nur was die Uhr braucht; `time` = letzter Abruf (Epoch-ms); `contract` = Marke wie
    /// `WatchPair.contract` (leer = Spot).
    struct Row: Equatable, Sendable {
        var base: String
        var quote: String
        var spot: Bool
        var disabled: Bool
        var price: Double?
        var change24h: Double?
        var exchange: String
        var time: Int64
        var sortOrder: Int
        var contract: String = ""
    }

    /// Kurs vom iPhone; `symbol` = Schlüssel der Uhr (Kürzel oder `WatchPair.key`); `time` = Abruf des iPhones
    /// (Epoch-ms), gilt auf der Uhr als Stand des Kurses.
    struct Quote: Equatable, Sendable {
        let symbol: String
        let price: Double
        let change24h: Double?
        let exchange: String
        let time: Int64
    }

    /// Je Schlüssel aus `coins` (Reihenfolge bleibt) der neueste brauchbare Kurs; gleich alt: das Paar weiter oben.
    /// Kürzel: jedes Spot-Paar gegen USDT; `WatchPair`-Schlüssel: nur genau dieses Paar (Basis, Gegenwährung, Börse,
    /// Kontrakt).
    static func pick(_ rows: [Row], coins: [String]) -> [Quote] {
        let usable: [Candidate] = rows.compactMap { r in
            guard let price = r.price, price.isFinite, price > 0, !r.disabled, r.time > 0 else { return nil }
            let usdtSpot = r.quote.trimmingCharacters(in: .whitespaces).uppercased() == quoteAsset && r.spot
            let coin = usdtSpot ? r.base.trimmingCharacters(in: .whitespaces).uppercased() : nil
            let pair = WatchPair.of(base: r.base, quote: r.quote, exchange: r.exchange, contract: r.contract)?.key
            return Candidate(coin: coin, pair: pair, price: price, change24h: r.change24h.flatMap { $0.isFinite ? $0 : nil },
                             exchange: r.exchange, time: r.time, order: r.sortOrder)
        }
        var seen = Set<String>()
        let wanted = coins.filter { seen.insert($0).inserted }.prefix(Self.max)
        return wanted.compactMap { key in
            let newest = usable.filter { $0.coin == key || $0.pair == key }
                .min { a, b in a.time != b.time ? a.time > b.time : a.order < b.order }
            return newest.map { Quote(symbol: key, price: $0.price, change24h: $0.change24h, exchange: $0.exchange,
                                      time: $0.time) }
        }
    }

    private struct Candidate {
        let coin: String?
        let pair: String?
        let price: Double
        let change24h: Double?
        let exchange: String
        let time: Int64
        let order: Int
    }

    static func toJson(_ quotes: [Quote]) -> String {
        let list: [[String: Any]] = quotes.map { q in
            var o: [String: Any] = ["s": q.symbol, "p": q.price, "x": q.exchange, "t": q.time]
            if let c = q.change24h { o["c"] = c }
            return o
        }
        let data = (try? JSONSerialization.data(withJSONObject: ["v": 1, "q": list] as [String: Any])) ?? Data()
        return String(data: data, encoding: .utf8) ?? "{}"
    }

    /// Liest `text`; Unlesbares → nil, ungültige Einträge (Schlüssel weder Kürzel noch `WatchPair.key`, Kurs ≤ 0, ohne
    /// Zeit) fallen weg, höchstens `max`.
    static func fromJson(_ text: String?) -> [Quote]? {
        guard let data = text?.data(using: .utf8),
              let o = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
              let list = o["q"] as? [Any] else { return nil }
        var out: [Quote] = []
        for item in list {
            guard let q = item as? [String: Any],
                  let symbol = q["s"] as? String,
                  symbol.range(of: "^[A-Z0-9]{2,12}$", options: .regularExpression) != nil || WatchPair.parse(symbol) != nil,
                  let price = (q["p"] as? NSNumber)?.doubleValue, price.isFinite, price > 0,
                  let time = (q["t"] as? NSNumber)?.int64Value, time > 0,
                  !out.contains(where: { $0.symbol == symbol }) else { continue }
            let change = (q["c"] as? NSNumber)?.doubleValue
            out.append(Quote(symbol: symbol, price: price, change24h: change.flatMap { $0.isFinite ? $0 : nil },
                             exchange: q["x"] as? String ?? "", time: time))
            if out.count == Self.max { break }
        }
        return out
    }

    /// Spielraum über das Intervall hinaus, bevor das iPhone als still gilt (`phoneSilentAfterMillis`).
    private static let silentGraceMinutes = 5

    /// Ab diesem Alter der letzten Lieferung vom iPhone (neueste Zeit der gelieferten Kurse) gilt das iPhone als still:
    /// Intervall + 5 Min. (15 → 20 Min., 60 → 65 Min.) — das iPhone liefert im Intervall neue Kurse, solange es
    /// erreichbar ist und selbst aktualisiert.
    static func phoneSilentAfterMillis(_ intervalMinutes: Int) -> Int64 {
        Int64(WatchInterval.clamp(intervalMinutes) + silentGraceMinutes) * 60_000
    }

    /// Kurse vom iPhone nutzen (`selfFetch` `usePhone`)? Nur mit «Kurse vom Handy, sonst selbst» (`source`), verbunden
    /// (`connected`; die Komplikation kennt die Verbindung nicht und gibt true) und mit einer Lieferung, die nicht älter
    /// ist als `phoneSilentAfterMillis` (`lastDelivery` ≤ 0 = noch keine). Gleiche Grenze auf beiden Uhren: iPhone weg →
    /// die Uhr holt spätestens Intervall + 5 Min. nach der letzten Lieferung selbst. Wie Android `usePhone`.
    static func usePhone(source: WatchSettings.Source, connected: Bool, lastDelivery: Int64, now: Int64,
                         intervalMinutes: Int) -> Bool {
        source == .phone && connected && lastDelivery > 0 && now - lastDelivery <= phoneSilentAfterMillis(intervalMinutes)
    }

    /// Stand einer Lieferung vom iPhone: neueste Zeit der Kurse; 0 = keine Kurse.
    static func deliveryTime(_ quotes: [Quote]) -> Int64 { quotes.map(\.time).max() ?? 0 }

    /// Coins aus `shown`, die die Uhr selbst abfragt. Ohne Kurs immer. Mit Kursen vom iPhone (`usePhone`: Wahl «Kurse vom
    /// Handy» und iPhone verbunden) nie für einen Coin, den das iPhone liefert (`phoneCoins`), solange dessen Kurs nicht
    /// veraltet ist (`WatchFreshness`) — auch nicht auf «Aktualisieren». Sonst auf Wunsch (`force`) oder ab
    /// `WatchInterval.refetchAfterMillis`. `times` = Stand je Coin auf der Uhr. Nie für ein Paar, das die Uhr nicht
    /// selbst abfragen kann (`WatchPair.selfFetchable`: Pool, Laufzeit-Kontrakt) — das kommt nur vom iPhone.
    static func selfFetch(_ shown: [String], times: [String: Int64], phoneCoins: Set<String>, usePhone: Bool,
                          intervalMinutes: Int, now: Int64, force: Bool) -> [String] {
        shown.filter { coin in
            if WatchPair.parse(coin)?.selfFetchable == false { return false }
            let time = times[coin] ?? 0
            if time <= 0 { return true }
            if usePhone && phoneCoins.contains(coin) {
                return WatchFreshness.isOutdated(time, now: now, intervalMinutes: intervalMinutes)
            }
            if force { return true }
            return now - time > WatchInterval.refetchAfterMillis(intervalMinutes)
        }
    }
}
