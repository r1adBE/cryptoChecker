import Foundation

/// Speicher der Uhr (App-Gruppe, damit die Komplikation mitliest; nicht gesichert): Wahl der Liste und die zuletzt
/// geladenen Kurse, je Coin mit der Zeit seines Abrufs (`WatchCache`), als ein einziger Eintrag — die Komplikation zeigt
/// sie auch ohne Netz. Wie Android `WatchStore`.
enum WatchStore {
    private static let group = "group.com.cryptochecker.app"
    private static let keySettings = "watch_settings"
    private static let keyQuotes = "watch_quotes"

    private static var defaults: UserDefaults { UserDefaults(suiteName: group) ?? .standard }

    /// Wahl der Uhr («Wie iPhone» oder «Eigene Liste»), mit dem iPhone abgeglichen (`WatchLink`).
    static var settings: WatchSettings {
        get { WatchSettings.fromJson(defaults.string(forKey: keySettings)) ?? WatchSettings() }
        set { defaults.set(newValue.toJson(), forKey: keySettings) }
    }

    /// Zuletzt geladene Kurse je Coin; Einträge ohne Zeit fallen weg.
    static func quotes() -> [String: WatchCached] {
        guard let data = defaults.data(forKey: keyQuotes),
              let list = try? JSONDecoder().decode([WatchCached].self, from: data) else { return [:] }
        return Dictionary(list.filter { $0.time > 0 }.map { ($0.quote.symbol, $0) }, uniquingKeysWith: { a, _ in a })
    }

    /// Neue Kurse je Coin übernehmen (`WatchCache.merge`, Abruf zur Zeit `time`) und als ein Eintrag speichern
    /// (Kurse und Zeiten zusammen, nie getrennt); Ergebnis = neuer Stand.
    @discardableResult
    static func mergeQuotes(_ fresh: [String: WatchQuote], time: Int64) -> [String: WatchCached] {
        let choice = settings
        let merged = WatchCache.merge(quotes(), fresh: fresh, time: time, keep: choice.own + choice.phone)
        if let data = try? JSONEncoder().encode(Array(merged.values)) {
            defaults.set(data, forKey: keyQuotes)
        }
        return merged
    }

    static func now() -> Int64 { Int64(Date().timeIntervalSince1970 * 1000) }
}
