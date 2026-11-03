import Foundation

/// Kurse vom iPhone für die Uhr: Das iPhone schickt für die Coins der Uhr (beide Listen) die Kurse, die es ohnehin schon
/// hat (Merkliste, Paar gegen `quoteAsset`, Spot, nicht deaktiviert) — samt 24 h, Börse und Zeit seines Abrufs, ohne
/// eigene Abfrage für die Uhr. Die Uhr übernimmt sie und fragt selbst nur, was das iPhone nicht liefert (`selfFetch`).
/// Wie Android `WatchPhoneQuotes` (`Parity/watch_link.json`).
enum WatchPhoneQuotes {
    static let quoteAsset = "USDT"

    /// Höchstzahl je Sendung: eigene Liste und Liste des iPhones.
    static let max = 2 * WatchCoins.max

    /// Paar der Merkliste des iPhones, nur was die Uhr braucht; `time` = letzter Abruf (Epoch-ms).
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
    }

    /// Kurs eines Coins vom iPhone; `time` = Abruf des iPhones (Epoch-ms), gilt auf der Uhr als Stand des Kurses.
    struct Quote: Equatable, Sendable {
        let symbol: String
        let price: Double
        let change24h: Double?
        let exchange: String
        let time: Int64
    }

    /// Je Coin aus `coins` (Reihenfolge bleibt) der neueste brauchbare Kurs; gleich alt: das Paar weiter oben.
    static func pick(_ rows: [Row], coins: [String]) -> [Quote] {
        let usable: [(quote: Quote, order: Int)] = rows.compactMap { r in
            guard let price = r.price, price.isFinite, price > 0,
                  r.quote.trimmingCharacters(in: .whitespaces).uppercased() == quoteAsset,
                  r.spot, !r.disabled, r.time > 0 else { return nil }
            let change = r.change24h.flatMap { $0.isFinite ? $0 : nil }
            let symbol = r.base.trimmingCharacters(in: .whitespaces).uppercased()
            return (Quote(symbol: symbol, price: price, change24h: change, exchange: r.exchange, time: r.time),
                    r.sortOrder)
        }
        var seen = Set<String>()
        let wanted = coins.filter { seen.insert($0).inserted }.prefix(Self.max)
        return wanted.compactMap { coin in
            let newest = usable.filter { $0.quote.symbol == coin }
                .min { a, b in a.quote.time != b.quote.time ? a.quote.time > b.quote.time : a.order < b.order }
            return newest?.quote
        }
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

    /// Liest `text`; Unlesbares → nil, ungültige Einträge (Kürzel, Kurs ≤ 0, ohne Zeit) fallen weg, höchstens `max`.
    static func fromJson(_ text: String?) -> [Quote]? {
        guard let data = text?.data(using: .utf8),
              let o = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
              let list = o["q"] as? [Any] else { return nil }
        var out: [Quote] = []
        for item in list {
            guard let q = item as? [String: Any],
                  let symbol = q["s"] as? String, symbol.range(of: "^[A-Z0-9]{2,12}$", options: .regularExpression) != nil,
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

    /// Coins aus `shown`, die die Uhr selbst abfragt. Ohne Kurs immer. Mit Kursen vom iPhone (`usePhone`: Wahl «Kurse vom
    /// Handy» und iPhone verbunden) nie für einen Coin, den das iPhone liefert (`phoneCoins`), solange dessen Kurs nicht
    /// veraltet ist (`WatchFreshness`) — auch nicht auf «Aktualisieren». Sonst auf Wunsch (`force`) oder ab
    /// `WatchInterval.refetchAfterMillis`. `times` = Stand je Coin auf der Uhr.
    static func selfFetch(_ shown: [String], times: [String: Int64], phoneCoins: Set<String>, usePhone: Bool,
                          intervalMinutes: Int, now: Int64, force: Bool) -> [String] {
        shown.filter { coin in
            let time = times[coin] ?? 0
            if time <= 0 { return true }
            if usePhone && phoneCoins.contains(coin) { return WatchFreshness.isOutdated(time, now: now) }
            if force { return true }
            return now - time > WatchInterval.refetchAfterMillis(intervalMinutes)
        }
    }
}
