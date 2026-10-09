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
        /// Alarme «Portfolio-Wert» (Runde 28). Optional: ältere Dateien haben sie nicht, und
        /// ältere Versionen ignorieren das Feld beim Lesen.
        var portfolioAlarms: [PortfolioAlarm]?
        var nextPortfolioAlarmId: Int64?
    }

    static func loadSnapshot() -> Snapshot {
        // Noch nicht geschriebene Stände der App zuerst auf die Platte (`saveSnapshotInBackground`)
        flushSnapshotWrites()
        guard let data = try? Data(contentsOf: watchlistURL),
              let snap = try? JSONDecoder().decode(Snapshot.self, from: data)
        else { return Snapshot() }
        return snap
    }

    static func saveSnapshot(_ snapshot: Snapshot) {
        guard let data = try? JSONEncoder().encode(snapshot) else { return }
        try? data.write(to: watchlistURL, options: [.atomic])
    }

    /// Schreibt der Reihe nach, nicht auf dem Haupt-Thread (JSON der ganzen Merkliste, bei Live-Kursen
    /// alle 10 Sekunden) — wie Android den IO-Dispatcher nutzt.
    private static let snapshotWriter = DispatchQueue(label: "com.cryptochecker.snapshot-writer", qos: .utility)

    /// Speichert im Hintergrund (Reihenfolge bleibt); [done] danach auf dem Haupt-Thread (z. B. Widgets neu laden).
    static func saveSnapshotInBackground(_ snapshot: Snapshot, done: (() -> Void)? = nil) {
        snapshotWriter.async {
            saveSnapshot(snapshot)
            if let done { DispatchQueue.main.async(execute: done) }
        }
    }

    /// Wartet, bis alle Hintergrund-Schreibvorgänge auf der Platte sind (vor dem Lesen, beim Wechsel
    /// in den Hintergrund). Nie vom Schreib-Thread selbst aufrufen.
    static func flushSnapshotWrites() {
        snapshotWriter.sync {}
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

    /// Mit welcher %-Basis (und welchem Tagesbeginn) die gespeicherten Veränderungen zuletzt
    /// gerechnet wurden; nil = noch nie (gilt als rollend) — siehe `ChangeBasisMath.isCurrent`.
    static var changeStamp: ChangeStamp? {
        get { ChangeStamp.decode(defaults.string(forKey: "change_basis_stamp")) }
        set {
            if let newValue { defaults.set(newValue.encoded, forKey: "change_basis_stamp") }
            else { defaults.removeObject(forKey: "change_basis_stamp") }
        }
    }

    static var lastRefreshDuration: Int64 {
        get { Int64(defaults.double(forKey: "last_refresh_duration")) }
        set { defaults.set(Double(newValue), forKey: "last_refresh_duration") }
    }

    /// Pausen je Börse (`ExchangeBackoff`), Schlüssel = Börsen-Kennung. Geteilt mit Hintergrund
    /// und Widget, damit eine pausierte Börse nirgends angefragt wird.
    static var exchangeBackoff: [String: ExchangeBackoff.State] {
        get { ExchangeBackoff.decode(defaults.string(forKey: "exchange_backoff")) }
        set {
            if newValue.isEmpty { defaults.removeObject(forKey: "exchange_backoff") }
            else { defaults.set(ExchangeBackoff.encode(newValue), forKey: "exchange_backoff") }
        }
    }

    /// App-Start bis zum ersten Bild der Merkliste (zuletzt gemessen, ms); nil = noch nie. Nur lokal.
    static var appStartMillis: Int64? {
        get {
            let value = Int64(defaults.double(forKey: "app_start_millis"))
            return value > 0 ? value : nil
        }
        set {
            if let newValue { defaults.set(Double(newValue), forKey: "app_start_millis") }
            else { defaults.removeObject(forKey: "app_start_millis") }
        }
    }

    /// Bericht «Letzte Aktualisierung» (JSON); nil = noch keiner. Bis Runde 21 als Klartext
    /// unter `last_refresh_report` — der wird beim ersten neuen Bericht entfernt.
    static var lastRefreshReport: RefreshReport? {
        get {
            guard let data = defaults.data(forKey: "last_refresh_report_v2") else { return nil }
            return try? JSONDecoder().decode(RefreshReport.self, from: data)
        }
        set {
            defaults.removeObject(forKey: "last_refresh_report")
            if let newValue, let data = try? JSONEncoder().encode(newValue) {
                defaults.set(data, forKey: "last_refresh_report_v2")
            } else {
                defaults.removeObject(forKey: "last_refresh_report_v2")
            }
        }
    }

    // MARK: Favoriten (Hinzufügen-Tab)

    static func favorites(_ kind: FavoriteKind) -> Set<String> {
        Set(defaults.stringArray(forKey: "fav_" + kind.rawValue.lowercased()) ?? [])
    }

    static func setFavorites(_ kind: FavoriteKind, _ items: Set<String>) {
        defaults.set(Array(items).sorted(), forKey: "fav_" + kind.rawValue.lowercased())
    }
}
