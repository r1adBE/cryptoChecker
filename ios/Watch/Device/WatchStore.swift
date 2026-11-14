import Foundation

/// Speicher der Uhr (App-Gruppe, damit die Komplikation mitliest; nichts davon im Backup): die Wahl der Liste in einer
/// eigenen Datei (`watch_settings.json` — die Standard-Einstellungen der App-Gruppe liessen sich nicht vom Backup ausnehmen, X4-03; Wear
/// OS: `allowBackup="false"`) und die zuletzt geladenen Kurse, je Coin mit dem Start seines Abrufs (`WatchCached.time`),
/// in einer Datei, die Uhr-App und Komplikation nur über `NSFileCoordinator` lesen und zusammenführen (`WatchQuoteFile`)
/// — die Komplikation zeigt sie auch ohne Netz. Dazu die Coins der letzten Kurse vom iPhone (`phoneCoins`, `phoneTime`:
/// `watch_phone.json`) und der Portfolio-Wert vom iPhone (`portfolio`, eigene Datei). Alle Dateien atomar geschrieben und
/// vom Backup ausgenommen. Die Wahl schreibt nur die Uhr-App, auf dem MainActor (`WatchModel`). Wie Android `WatchStore`.
enum WatchStore {
    private static let group = "group.com.cryptochecker.app"
    private static let settingsFileName = "watch_settings.json"
    private static let phoneFileName = "watch_phone.json"
    private static let quotesFileName = "watch_quotes.json"
    private static let portfolioFileName = "watch_portfolio.json"

    private static var directory: URL {
        FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: group)
            ?? FileManager.default.temporaryDirectory
    }

    /// `data` atomar als `name` in den Container schreiben, vom Backup ausgenommen. false = nicht geschrieben.
    @discardableResult
    private static func writeExcluded(_ data: Data, _ name: String) -> Bool {
        var url = directory.appendingPathComponent(name)
        guard (try? data.write(to: url, options: .atomic)) != nil else { return false }
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        try? url.setResourceValues(values)
        return true
    }

    /// Coins und Stand der letzten Lieferung vom iPhone (`watch_phone.json`).
    private struct PhoneDelivery: Codable {
        var coins: [String]
        var time: Int64
    }

    private static var phoneDelivery: PhoneDelivery? {
        guard let data = try? Data(contentsOf: directory.appendingPathComponent(phoneFileName)) else { return nil }
        return try? JSONDecoder().decode(PhoneDelivery.self, from: data)
    }

    /// Datei der Kurse im Container der App-Gruppe (ohne Gruppe: eigener temporärer Ordner, dann ohne Komplikation).
    private static var quoteFile: WatchQuoteFile {
        WatchQuoteFile(url: directory.appendingPathComponent(quotesFileName))
    }

    /// Wahl der Uhr («Wie iPhone» oder «Eigene Liste»), mit dem iPhone abgeglichen (`WatchLink`, `WatchModel`).
    static var settings: WatchSettings {
        get {
            let text = try? String(contentsOf: directory.appendingPathComponent(settingsFileName), encoding: .utf8)
            return WatchSettings.fromJson(text) ?? WatchSettings()
        }
        set { writeExcluded(Data(newValue.toJson().utf8), settingsFileName) }
    }

    /// Zuletzt geladene Kurse je Coin; Einträge ohne Zeit fallen weg.
    static func quotes() -> [String: WatchCached] {
        quoteFile.read() ?? [:]
    }

    /// Neue Kurse eines Abrufs, der zur Zeit `startedAt` begann, je Coin übernehmen (`WatchCache.merge`: ein Coin, den
    /// ein später gestarteter Abruf schon geschrieben hat, bleibt) und speichern; Ergebnis = neuer Stand, nil = nicht
    /// gespeichert.
    @discardableResult
    static func mergeQuotes(_ fresh: [String: WatchQuote], startedAt: Int64) -> [String: WatchCached]? {
        let choice = settings
        return quoteFile.merge(fresh, startedAt: startedAt, keep: choice.kept)
    }

    /// Schlüssel (Coins bzw. Paare) der letzten Kurse vom iPhone (`WatchPhoneQuotes.selfFetch`).
    static var phoneCoins: Set<String> {
        Set(phoneDelivery?.coins ?? [])
    }

    /// Stand der letzten Kurse vom iPhone (`WatchPhoneQuotes.deliveryTime`); 0 = keine (`WatchPhoneQuotes.usePhone`).
    static var phoneTime: Int64 {
        phoneDelivery?.time ?? 0
    }

    /// Kurse vom iPhone übernehmen (`WatchCache.mergeTimed`, je Coin mit der Zeit des iPhones), die gelieferten Coins
    /// und den Stand der Lieferung (`phoneTime`) merken; Ergebnis = neuer Stand, nil = nicht gespeichert.
    @discardableResult
    static func takePhoneQuotes(_ quotes: [WatchPhoneQuotes.Quote]) -> [String: WatchCached]? {
        let choice = settings
        let fresh = Dictionary(quotes.map { q in
            (q.symbol, WatchCached(quote: WatchQuote(symbol: q.symbol, price: q.price, change24h: q.change24h,
                                                     source: q.exchange), time: q.time))
        }, uniquingKeysWith: { a, _ in a })
        let delivery = PhoneDelivery(coins: quotes.map(\.symbol), time: WatchPhoneQuotes.deliveryTime(quotes))
        if let data = try? JSONEncoder().encode(delivery) { writeExcluded(data, phoneFileName) }
        return quoteFile.mergeTimed(fresh, keep: choice.kept)
    }

    /// Kurse kommen nicht mehr vom iPhone (iPhone-App entfernt): `phoneCoins` und `phoneTime` vergessen, die Uhr holt
    /// dann selbst (`WatchPhoneQuotes.selfFetch`); die Kurse selbst bleiben mit ihrer Zeit. Wie Android.
    static func forgetPhoneQuotes() {
        try? FileManager.default.removeItem(at: directory.appendingPathComponent(phoneFileName))
    }

    /// Portfolio-Wert vom iPhone; nil = keiner (Schalter auf dem iPhone aus oder nie gesendet).
    static func portfolio() -> WatchPortfolio? {
        guard let data = try? Data(contentsOf: directory.appendingPathComponent(portfolioFileName)) else { return nil }
        return try? JSONDecoder().decode(WatchPortfolio.self, from: data)
    }

    /// Portfolio-Wert speichern bzw. bei `.clear` entfernen; nicht gesichert.
    static func takePortfolio(_ message: WatchPortfolio.Message) {
        let url = directory.appendingPathComponent(portfolioFileName)
        guard case .value(let p) = message else {
            try? FileManager.default.removeItem(at: url)
            return
        }
        if let data = try? JSONEncoder().encode(p) { writeExcluded(data, portfolioFileName) }
    }

    static func now() -> Int64 { Int64(Date().timeIntervalSince1970 * 1000) }
}
