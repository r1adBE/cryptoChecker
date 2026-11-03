import Foundation

/// Speicher der Uhr (App-Gruppe, damit die Komplikation mitliest; nicht gesichert): Wahl der Liste in `UserDefaults` und
/// die zuletzt geladenen Kurse, je Coin mit dem Start seines Abrufs (`WatchCached.time`), in einer Datei, die Uhr-App und
/// Komplikation nur über `NSFileCoordinator` lesen und zusammenführen (`WatchQuoteFile`) — die Komplikation zeigt sie
/// auch ohne Netz. Dazu die Coins der letzten Kurse vom iPhone (`phoneCoins`) und der Portfolio-Wert vom iPhone
/// (`portfolio`, eigene Datei, nicht gesichert). Die Wahl schreibt nur die Uhr-App, auf dem MainActor (`WatchModel`).
/// Wie Android `WatchStore`.
enum WatchStore {
    private static let group = "group.com.cryptochecker.app"
    private static let keySettings = "watch_settings"
    private static let keyPhoneCoins = "watch_phone_coins"
    private static let quotesFileName = "watch_quotes.json"
    private static let portfolioFileName = "watch_portfolio.json"

    private static var directory: URL {
        FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: group)
            ?? FileManager.default.temporaryDirectory
    }

    private static var defaults: UserDefaults { UserDefaults(suiteName: group) ?? .standard }

    /// Datei der Kurse im Container der App-Gruppe (ohne Gruppe: eigener temporärer Ordner, dann ohne Komplikation).
    private static var quoteFile: WatchQuoteFile {
        WatchQuoteFile(url: directory.appendingPathComponent(quotesFileName))
    }

    /// Wahl der Uhr («Wie iPhone» oder «Eigene Liste»), mit dem iPhone abgeglichen (`WatchLink`, `WatchModel`).
    static var settings: WatchSettings {
        get { WatchSettings.fromJson(defaults.string(forKey: keySettings)) ?? WatchSettings() }
        set { defaults.set(newValue.toJson(), forKey: keySettings) }
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
        return quoteFile.merge(fresh, startedAt: startedAt, keep: choice.own + choice.phone)
    }

    /// Coins der letzten Kurse vom iPhone (`WatchPhoneQuotes.selfFetch`).
    static var phoneCoins: Set<String> {
        Set(defaults.stringArray(forKey: keyPhoneCoins) ?? [])
    }

    /// Kurse vom iPhone übernehmen (`WatchCache.mergeTimed`, je Coin mit der Zeit des iPhones) und die gelieferten
    /// Coins merken; Ergebnis = neuer Stand, nil = nicht gespeichert.
    @discardableResult
    static func takePhoneQuotes(_ quotes: [WatchPhoneQuotes.Quote]) -> [String: WatchCached]? {
        let choice = settings
        let fresh = Dictionary(quotes.map { q in
            (q.symbol, WatchCached(quote: WatchQuote(symbol: q.symbol, price: q.price, change24h: q.change24h,
                                                     source: q.exchange), time: q.time))
        }, uniquingKeysWith: { a, _ in a })
        defaults.set(quotes.map(\.symbol), forKey: keyPhoneCoins)
        return quoteFile.mergeTimed(fresh, keep: choice.own + choice.phone)
    }

    /// Portfolio-Wert vom iPhone; nil = keiner (Schalter auf dem iPhone aus oder nie gesendet).
    static func portfolio() -> WatchPortfolio? {
        guard let data = try? Data(contentsOf: directory.appendingPathComponent(portfolioFileName)) else { return nil }
        return try? JSONDecoder().decode(WatchPortfolio.self, from: data)
    }

    /// Portfolio-Wert speichern bzw. bei `.clear` entfernen; nicht gesichert.
    static func takePortfolio(_ message: WatchPortfolio.Message) {
        var url = directory.appendingPathComponent(portfolioFileName)
        guard case .value(let p) = message else {
            try? FileManager.default.removeItem(at: url)
            return
        }
        guard let data = try? JSONEncoder().encode(p), (try? data.write(to: url, options: .atomic)) != nil else { return }
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        try? url.setResourceValues(values)
    }

    static func now() -> Int64 { Int64(Date().timeIntervalSince1970 * 1000) }
}
