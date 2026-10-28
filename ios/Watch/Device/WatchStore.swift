import Foundation

/// Speicher der Uhr (App-Gruppe, damit die Komplikation mitliest; nicht gesichert): Wahl der Liste in `UserDefaults` und
/// die zuletzt geladenen Kurse, je Coin mit dem Start seines Abrufs (`WatchCached.time`), in einer Datei, die Uhr-App und
/// Komplikation nur über `NSFileCoordinator` lesen und zusammenführen (`WatchQuoteFile`) — die Komplikation zeigt sie
/// auch ohne Netz. Die Wahl schreibt nur die Uhr-App, auf dem MainActor (`WatchModel`). Wie Android `WatchStore`.
enum WatchStore {
    private static let group = "group.com.cryptochecker.app"
    private static let keySettings = "watch_settings"
    private static let quotesFileName = "watch_quotes.json"

    private static var defaults: UserDefaults { UserDefaults(suiteName: group) ?? .standard }

    /// Datei der Kurse im Container der App-Gruppe (ohne Gruppe: eigener temporärer Ordner, dann ohne Komplikation).
    private static var quoteFile: WatchQuoteFile {
        let dir = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: group)
            ?? FileManager.default.temporaryDirectory
        return WatchQuoteFile(url: dir.appendingPathComponent(quotesFileName))
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

    static func now() -> Int64 { Int64(Date().timeIntervalSince1970 * 1000) }
}
