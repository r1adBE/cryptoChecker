import Foundation

/// Gemeinsamer Speicher von App und Widgets (App Group).
///
/// Die App Group muss in Xcode für beide Ziele eingerichtet sein
/// (Signing & Capabilities → App Groups). Fehlt sie, arbeitet die App mit
/// ihrem eigenen Ordner weiter — die Widgets bleiben dann aber leer.
enum SharedStorage {
    static let appGroup = "group.com.cryptochecker.app"

    /// WidgetKit-Arten der Merkliste- und Einzel-Widgets — auch für die App, die nach
    /// der Aktualisierung eines einzelnen Paares nur diese beiden neu laden lässt.
    static let watchlistWidgetKind = "WatchlistWidget"
    static let singleWidgetKind = "SingleWidget"

    static var defaults: UserDefaults {
        UserDefaults(suiteName: appGroup) ?? .standard
    }

    static var isAppGroupAvailable: Bool {
        FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: appGroup) != nil
    }

    static var directory: URL {
        if let url = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: appGroup) {
            return url
        }
        let url = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        try? FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        return url
    }

    static var watchlistURL: URL { directory.appendingPathComponent("watchlist.json") }

    // MARK: Merkliste & Alarme

    struct Snapshot: Codable {
        var watches: [Watch] = []
        var alarms: [Alarm] = []
        var nextWatchId: Int64 = 1
        var nextAlarmId: Int64 = 1
    }

    static func loadSnapshot() -> Snapshot {
        guard let data = try? Data(contentsOf: watchlistURL),
              let snap = try? JSONDecoder().decode(Snapshot.self, from: data)
        else { return Snapshot() }
        return snap
    }

    static func saveSnapshot(_ snapshot: Snapshot) {
        guard let data = try? JSONEncoder().encode(snapshot) else { return }
        try? data.write(to: watchlistURL, options: [.atomic])
    }

    // MARK: Einstellungen

    private static let settingsKey = "settings"

    static func loadSettings() -> AppSettings {
        guard let data = defaults.data(forKey: settingsKey),
              let s = try? JSONDecoder().decode(AppSettings.self, from: data)
        else { return AppSettings() }
        return s
    }

    static func saveSettings(_ settings: AppSettings) {
        if let data = try? JSONEncoder().encode(settings) {
            defaults.set(data, forKey: settingsKey)
        }
    }

    // MARK: Letzte Aktualisierung (Widget-Kopf)

    static var lastRefreshAt: Int64 {
        get { Int64(defaults.double(forKey: "last_refresh_at")) }
        set { defaults.set(Double(newValue), forKey: "last_refresh_at") }
    }

    static var lastRefreshDuration: Int64 {
        get { Int64(defaults.double(forKey: "last_refresh_duration")) }
        set { defaults.set(Double(newValue), forKey: "last_refresh_duration") }
    }

    static var lastRefreshReport: String {
        get { defaults.string(forKey: "last_refresh_report") ?? "" }
        set { defaults.set(newValue, forKey: "last_refresh_report") }
    }

    // MARK: Favoriten (Hinzufügen-Tab)

    static func favorites(_ kind: FavoriteKind) -> Set<String> {
        Set(defaults.stringArray(forKey: "fav_" + kind.rawValue.lowercased()) ?? [])
    }

    static func setFavorites(_ kind: FavoriteKind, _ items: Set<String>) {
        defaults.set(Array(items).sorted(), forKey: "fav_" + kind.rawValue.lowercased())
    }
}
