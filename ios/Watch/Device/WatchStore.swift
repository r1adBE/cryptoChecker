import Foundation

/// Speicher der Uhr (App-Gruppe, damit die Komplikation mitliest; nicht gesichert): Wahl der Liste und die zuletzt
/// geladenen Kurse samt Zeit — die Komplikation zeigt sie auch ohne Netz. Wie Android `WatchStore`.
enum WatchStore {
    private static let group = "group.com.cryptochecker.app"
    private static let keySettings = "watch_settings"
    private static let keyQuotes = "watch_quotes"
    private static let keyQuotesTime = "watch_quotes_time"

    private static var defaults: UserDefaults { UserDefaults(suiteName: group) ?? .standard }

    /// Wahl der Uhr («Wie iPhone» oder «Eigene Liste»), mit dem iPhone abgeglichen (`WatchLink`).
    static var settings: WatchSettings {
        get { WatchSettings.fromJson(defaults.string(forKey: keySettings)) ?? WatchSettings() }
        set { defaults.set(newValue.toJson(), forKey: keySettings) }
    }

    /// Zuletzt geladene Kurse und Zeit (Epoch-ms; 0 = nie).
    static func quotes() -> (quotes: [String: WatchQuote], time: Int64) {
        guard let data = defaults.data(forKey: keyQuotes),
              let list = try? JSONDecoder().decode([WatchQuote].self, from: data) else { return ([:], 0) }
        return (Dictionary(list.map { ($0.symbol, $0) }, uniquingKeysWith: { a, _ in a }),
                Int64(defaults.double(forKey: keyQuotesTime)))
    }

    static func saveQuotes(_ quotes: [String: WatchQuote], time: Int64) {
        guard let data = try? JSONEncoder().encode(Array(quotes.values)) else { return }
        defaults.set(data, forKey: keyQuotes)
        defaults.set(Double(time), forKey: keyQuotesTime)
    }

    static func now() -> Int64 { Int64(Date().timeIntervalSince1970 * 1000) }
}
