import Foundation
import os

/// Gemeinsamer Speicher von App und Widgets (App Group).
///
/// Die App Group muss in Xcode für beide Ziele eingerichtet sein
/// (Signing & Capabilities → App Groups). Fehlt sie, arbeitet die App mit
/// ihrem eigenen Ordner weiter — die Widgets bleiben dann aber leer (Hinweis in «Letzte Prüfungen»);
/// beim nächsten Start mit App Group übernimmt `migrateFallbackIfNeeded` die Daten einmal.
///
/// Hier Ordner, Ersatzordner, Einstellungen, Zeitpunkte und Favoriten; Merkliste und Alarme in
/// `SharedStorage+Snapshot.swift`, deren Dateien in `SharedStorage+Files.swift`, Gruppe des Merkliste-Widgets in
/// `WidgetGroupSelection.swift`.
enum SharedStorage {
    static let appGroup = "group.com.cryptochecker.app"

    /// WidgetKit-Arten der Merkliste- und Einzel-Widgets — auch für die App, die nach
    /// der Aktualisierung eines einzelnen Paares nur diese beiden neu laden lässt.
    static let watchlistWidgetKind = "WatchlistWidget"
    static let singleWidgetKind = "SingleWidget"

    static var defaults: UserDefaults {
        if let suite = UserDefaults(suiteName: appGroup) { return suite }
        reportMissingAppGroup()
        return .standard
    }

    /// App Group eingerichtet (Signing & Capabilities)? Ohne sie bleiben die Widgets leer; «Letzte Prüfungen»
    /// zeigt dann einen Hinweis (`ios_storage_app_group_missing`).
    static var isAppGroupAvailable: Bool {
        FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: appGroup) != nil
    }

    static var directory: URL {
        if let url = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: appGroup) {
            return url
        }
        reportMissingAppGroup()
        let url = fallbackDirectory
        try? FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        return url
    }

    // MARK: Ohne App Group (Ersatzordner)

    /// Ersatzordner ohne App Group: eigener Ordner der App bzw. der Erweiterung (nicht geteilt).
    static var fallbackDirectory: URL {
        FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
    }

    /// Merker in den eigenen Einstellungen des Prozesses: Es wurde schon einmal ohne App Group geschrieben —
    /// beim nächsten Start MIT App Group einmal übernehmen (`migrateFallbackIfNeeded`).
    static let fallbackUsedKey = "storage_app_group_fallback_used"

    /// Einmal je Prozess melden: im Debug-Build laut (eine fehlende App Group ist ein Fehler im Projekt), sonst
    /// im Systemprotokoll; dazu der Merker für die spätere Übernahme. Nicht in Unit-Tests (dort fehlt die
    /// Berechtigung je nach Umgebung, die Tests sollen trotzdem laufen).
    private static let missingAppGroupReport: Void = {
        UserDefaults.standard.set(true, forKey: fallbackUsedKey)
        AppLog.storage.error("App Group nicht verfügbar – Ersatzordner, Widgets bleiben leer")
        // Kein assertionFailure: unsignierte Builds (CI, UI-Tests im Simulator) haben keine App Group
    }()

    private static func reportMissingAppGroup() {
        _ = missingAppGroupReport
    }

    /// Ergebnis von `migrateFallback`: übernommene Dateien und Einstellungen.
    struct FallbackMigration: Equatable {
        var files = 0
        var keys = 0
    }

    /// Nicht übernehmen: Dateien, die im Ersatzordner anderen gehören bzw. nur für den laufenden Prozess gelten
    /// (Paarlisten `PairCache` liegen immer dort, die Alarm-Lease ist nach dem Neustart wertlos).
    static let fallbackSkippedFiles: Set<String> = ["pairs", "alarm.lease"]

    /// Nach einem Start ohne App Group (Merker `fallbackUsedKey`) beim nächsten Start MIT App Group einmal die
    /// Daten aus dem Ersatzordner und den Ersatz-Einstellungen in den gemeinsamen Speicher übernehmen — nur, was
    /// dort noch fehlt (nichts überschreiben). Nur aus der App aufrufen, vor dem ersten Lesen der Daten.
    @discardableResult
    static func migrateFallbackIfNeeded() -> FallbackMigration? {
        guard UserDefaults.standard.bool(forKey: fallbackUsedKey),
              let group = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: appGroup),
              let groupDefaults = UserDefaults(suiteName: appGroup) else { return nil }
        // Ersatz-Einstellungen: ohne Berechtigung legt iOS die Suite in den eigenen Library/Preferences ab; mit
        // App Group zeigt derselbe Name auf den gemeinsamen Speicher — die lokale Kopie also direkt lesen.
        // (`.standard` nicht: dort liegen auch reine Geräte-Einstellungen der App, und der Rückfall dorthin
        // greift nur, wenn iOS gar keine Suite liefert.)
        var sources: [[String: Any]] = []
        let localSuite = URL(fileURLWithPath: NSHomeDirectory())
            .appendingPathComponent("Library/Preferences/\(appGroup).plist")
        if let data = try? Data(contentsOf: localSuite),
           let plist = try? PropertyListSerialization.propertyList(from: data, format: nil) as? [String: Any] {
            sources.append(plist)
        }
        let result = migrateFallback(from: fallbackDirectory, to: group, defaultsSources: sources,
                                     into: groupDefaults)
        UserDefaults.standard.removeObject(forKey: fallbackUsedKey)
        AppLog.storage.notice("Ersatzspeicher übernommen: \(result.files, privacy: .public) Dateien, \(result.keys, privacy: .public) Einstellungen")
        return result
    }

    /// Kern von `migrateFallbackIfNeeded` (testbar): oberste Einträge von `from` nach `to` kopieren, wenn es sie
    /// dort noch nicht gibt; Einstellungen aus `defaultsSources` übernehmen, die `target` noch nicht hat.
    static func migrateFallback(from: URL, to: URL, defaultsSources: [[String: Any]],
                                into target: UserDefaults) -> FallbackMigration {
        var result = FallbackMigration()
        let fm = FileManager.default
        if from.standardizedFileURL != to.standardizedFileURL {
            let items = (try? fm.contentsOfDirectory(at: from, includingPropertiesForKeys: nil)) ?? []
            for item in items {
                let name = item.lastPathComponent
                guard !fallbackSkippedFiles.contains(name), !name.hasPrefix(".") else { continue }
                let dest = to.appendingPathComponent(name)
                guard !fm.fileExists(atPath: dest.path) else { continue }
                do {
                    try fm.copyItem(at: item, to: dest)
                    result.files += 1
                } catch {
                    AppLog.storage.error("Übernahme einer Datei fehlgeschlagen")
                }
            }
        }
        for source in defaultsSources {
            for (key, value) in source where key != fallbackUsedKey && target.object(forKey: key) == nil {
                target.set(value, forKey: key)
                result.keys += 1
            }
        }
        return result
    }

    static var watchlistURL: URL { directory.appendingPathComponent("watchlist.json") }

    /// Portfolio-Alarme (Beträge) in eigener Datei statt in `watchlist.json` — so gilt für sie wie für
    /// `portfolio.json` die Einstellung «Portfolio in Systemsicherung» (wie Android, wo die
    /// Portfolio-Alarme mit dem Portfolio aus der Systemsicherung fallen).
    static var portfolioAlarmsURL: URL { directory.appendingPathComponent("portfolio_alarms.json") }


    // MARK: Einstellungen

    private static let settingsKey = "settings"
    /// Merker, der mit jeder gespeicherten Einstellung (und beim ersten lesbaren Start der App) geschrieben wird.
    /// Fehlen Einstellungen UND Merker, ist die App-Group-Ablage vermutlich noch gesperrt (Neustart vor dem
    /// ersten Entsperren) — so erkennen das auch die Widgets, die `isProtectedDataAvailable` nicht kennen.
    private static let settingsMarkerKey = "settings_written"

    /// Ergebnis beim Lesen der Einstellungen.
    enum SettingsReadState: Equatable {
        /// Gelesen.
        case ok
        /// Nicht vorhanden (bzw. vorhanden, aber unlesbar): Standardwerte, Speichern erlaubt.
        case missing
        /// Ablage (noch) nicht zugänglich, z. B. App nach einem Neustart vor dem ersten Entsperren vorgewärmt:
        /// Standardwerte nur zur Anzeige — nie zurückschreiben, Portfolio-Sperre gilt als an.
        case inaccessible
    }

    /// Gelesene Einstellungen mit Zustand.
    struct LoadedSettings {
        var settings: AppSettings
        var state: SettingsReadState
        /// Gespeichert, aber nicht lesbar (beschädigt oder unbekanntes Format): Standardwerte statt der Einstellungen
        /// (`settingsCorrupt`). Die App meldet das einmal (`StorageRecoveryNotice.settingsReset`).
        var corrupt = false

        /// Portfolio-Sperre: unbekannt (Ablage nicht zugänglich oder unlesbar) → an.
        var appLockAssumed: Bool { state == .inaccessible || corrupt || settings.appLock }
    }

    /// Entscheidung rein (testbar). `protectedDataAvailable`: in der App `UIApplication.isProtectedDataAvailable`,
    /// in den Erweiterungen nil (unbekannt) — dann entscheidet der Merker.
    static func settingsReadState(hasData: Bool, decodes: Bool, markerPresent: Bool,
                                  protectedDataAvailable: Bool?) -> SettingsReadState {
        if hasData && decodes { return .ok }
        // Da, aber nicht lesbar: die Ablage war zugänglich — Standardwerte
        if hasData { return .missing }
        switch protectedDataAvailable {
        case .some(false): return .inaccessible
        case .some(true): return .missing
        case .none: return markerPresent ? .missing : .inaccessible
        }
    }

    /// Rein (testbar): Einstellungen ersetzt, weil der gespeicherte Stand unlesbar war — nicht bei fehlendem Stand (neue
    /// Installation oder gesperrte Ablage). Wie Android, wo DataStore nur unlesbaren Inhalt ersetzt.
    static func settingsCorrupt(hasData: Bool, decodes: Bool) -> Bool { hasData && !decodes }

    /// Rein (testbar): gelesene Einstellungen bzw. Standardwerte. Nach unlesbarem Stand mit Portfolio-Sperre
    /// an — unbekannt heisst gesperrt, bis der Nutzer sie selbst ausschaltet (wie Android `SettingsLockFallback`).
    static func settingsAfterRead(_ decoded: AppSettings?, corrupt: Bool) -> AppSettings {
        var settings = decoded ?? AppSettings()
        if corrupt { settings.appLock = true }
        return settings
    }

    static func loadSettingsChecked(protectedDataAvailable: Bool? = nil) -> LoadedSettings {
        let store = defaults
        let data = store.data(forKey: settingsKey)
        let decoded = data.flatMap { try? JSONDecoder().decode(AppSettings.self, from: $0) }
        let state = settingsReadState(hasData: data != nil, decodes: decoded != nil,
                                      markerPresent: store.object(forKey: settingsMarkerKey) != nil,
                                      protectedDataAvailable: protectedDataAvailable)
        let corrupt = settingsCorrupt(hasData: data != nil, decodes: decoded != nil)
        return LoadedSettings(settings: settingsAfterRead(decoded, corrupt: corrupt), state: state, corrupt: corrupt)
    }

    /// Einstellungen oder Standardwerte (fehlend wie nicht zugänglich). Für die Portfolio-Sperre
    /// `loadSettingsChecked().appLockAssumed` nehmen.
    static func loadSettings() -> AppSettings {
        loadSettingsChecked().settings
    }

    /// Nur aus der App aufrufen und nur, wenn die Einstellungen lesbar waren (`AppData.settingsAccessible`).
    static func saveSettings(_ settings: AppSettings) {
        if let data = try? JSONEncoder().encode(settings) {
            defaults.set(data, forKey: settingsKey)
            defaults.set(true, forKey: settingsMarkerKey)
        }
    }

    /// Ablage ist zugänglich, aber (noch) ohne Einstellungen: Merker setzen, damit die Widgets das nicht
    /// für eine gesperrte Ablage halten.
    static func markSettingsReadable() {
        defaults.set(true, forKey: settingsMarkerKey)
    }

    // MARK: Letzte Aktualisierung (Widget-Kopf)

    static var lastRefreshAt: Int64 {
        get { Int64(defaults.double(forKey: "last_refresh_at")) }
        set { defaults.set(Double(newValue), forKey: "last_refresh_at") }
    }

    /// Ende der letzten VOLLSTÄNDIGEN Aktualisierung mit mindestens einem Kurs (App, Hintergrund-Aufgabe, Widget), ms;
    /// 0 = noch keine. Grundlage der 15-s-Sperre für «Aktualisieren» (`RefreshDebounce`) — Live-Takte setzen nur
    /// `lastRefreshAt` (Uhrzeit im Widget) und sperren das Aktualisieren von Hand so nicht. Nicht in der Sicherung.
    static var lastFullRefreshAt: Int64 {
        get { Int64(defaults.double(forKey: "last_full_refresh_at")) }
        set { defaults.set(Double(newValue), forKey: "last_full_refresh_at") }
    }

    /// Ende einer vollständigen Aktualisierung: Uhrzeit der Anzeige (`lastRefreshAt`) und der Sperre
    /// (`lastFullRefreshAt`) zugleich.
    static func markFullRefresh(at now: Int64 = EpochTime.nowMillis) {
        lastRefreshAt = now
        lastFullRefreshAt = now
    }

    /// Letzte Prüfung MIT allen Alarmen (App, Live-Strom, Hintergrund-Aufgabe), ms; 0 = noch nie. Das Widget
    /// prüft nur Kursalarme (setzt `lastRefreshAt`) — die Hintergrund-Aufgabe darf sich darum nur hiernach
    /// überspringen.
    static var lastAlarmCheckAt: Int64 {
        get { Int64(defaults.double(forKey: "last_alarm_check_at")) }
        set { defaults.set(Double(newValue), forKey: "last_alarm_check_at") }
    }

    /// Wann `watchlist.json` zuletzt geschrieben wurde (ms); 0 = unbekannt (keine Datei).
    /// Das Widget vergleicht das mit `lastRefreshAt` (`WidgetDataFreshness`).
    static var watchlistWrittenAt: Int64 {
        let attributes = try? FileManager.default.attributesOfItem(atPath: watchlistURL.path)
        guard let date = attributes?[.modificationDate] as? Date else { return 0 }
        return Int64(date.timeIntervalSince1970 * 1000)
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

    /// Bericht «Letzte Aktualisierung» (JSON); nil = noch keiner.
    static var lastRefreshReport: RefreshReport? {
        get {
            guard let data = defaults.data(forKey: "last_refresh_report") else { return nil }
            return try? JSONDecoder().decode(RefreshReport.self, from: data)
        }
        set {
            if let newValue, let data = try? JSONEncoder().encode(newValue) {
                defaults.set(data, forKey: "last_refresh_report")
            } else {
                defaults.removeObject(forKey: "last_refresh_report")
            }
        }
    }

    // MARK: Favoriten (Seite «Paar hinzufügen»)

    static func favorites(_ kind: FavoriteKind) -> Set<String> {
        Set(defaults.stringArray(forKey: "fav_" + kind.rawValue.lowercased()) ?? [])
    }

    static func setFavorites(_ kind: FavoriteKind, _ items: Set<String>) {
        defaults.set(Array(items).sorted(), forKey: "fav_" + kind.rawValue.lowercased())
    }
}
