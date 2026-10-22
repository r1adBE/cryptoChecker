import Foundation

/// Seit Build 104: gezählte Fehler im Hintergrund — nur Zahlen je Bereich und Zeitpunkt des letzten, nie Texte.
/// Wie `BackgroundErrorSummary` (Android).
struct BackgroundErrorSummary: Sendable, Equatable {
    var counts: [String: Int] = [:]
    /// Letzter gezählter Fehler (ms); 0 = keiner bekannt.
    var lastAt: Int64 = 0
}

/// Seit Build 104 (F-25): Fehler in Hintergrundarbeit sind im Release sonst unsichtbar (nur Systemprotokoll). Statt eines
/// Protokolls nur ein Zähler je Bereich; im Diagnosebericht eine Zeile. Gleiches Format wie `BackgroundErrors.kt`
/// (Testfälle `background_errors.json`).
enum BackgroundErrors {
    static let refresh = "refresh"
    static let storage = "storage"
    static let widgets = "widgets"
    static let logos = "logos"
    static let coins = "coins"
    static let live = "live"
    static let alarms = "alarms"
    static let schedule = "schedule"
    static let startup = "startup"
    static let other = "other"

    /// Feste Bereiche — nur diese Codes gelangen in den Bericht (kein Platz für freien Text).
    static let areas: Set<String> = [refresh, storage, widgets, logos, coins, live, alarms, schedule, startup, other]

    /// Unbekannter Code → `other`.
    static func area(_ code: String) -> String { areas.contains(code) ? code : other }

    /// Zeile im Bericht: «Background errors: refresh 2, logos 1 (last 2026-10-22 08:14 UTC)» bzw. «… none». Nach Anzahl
    /// absteigend, bei Gleichstand nach Code; Zeit immer in UTC. Wie Android.
    static func line(_ summary: BackgroundErrorSummary) -> String {
        var merged: [String: Int] = [:]
        for (code, n) in summary.counts where n > 0 { merged[area(code), default: 0] += n }
        guard !merged.isEmpty else { return "Background errors: none" }
        let parts = merged.sorted { $0.value != $1.value ? $0.value > $1.value : $0.key < $1.key }
            .map { "\($0.key) \($0.value)" }.joined(separator: ", ")
        guard summary.lastAt > 0 else { return "Background errors: \(parts)" }
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.calendar = Calendar(identifier: .gregorian)
        formatter.timeZone = TimeZone(identifier: "UTC")
        formatter.dateFormat = "yyyy-MM-dd HH:mm"
        let last = formatter.string(from: Date(timeIntervalSince1970: TimeInterval(summary.lastAt) / 1000))
        return "Background errors: \(parts) (last \(last) UTC)"
    }
}

/// Seit Build 104 (F-25): zählt Fehler im Hintergrund dauerhaft je Bereich in der App Group (App und Widgets zählen
/// zusammen) — nur Zahlen. Wie `BackgroundErrorCounter` (Android, SharedPreferences).
enum BackgroundErrorCounter {
    private static let countPrefix = "bg_errors_n_"
    private static let lastAtKey = "bg_errors_last_at"
    private static let lock = NSLock()

    /// Einen Fehler im Bereich `area` zählen (unbekannt → «other»). Nicht bei gesperrter Ablage (Neustart vor dem ersten
    /// Entsperren): Schreiben in die noch unlesbaren Einstellungen könnte sie sonst überschreiben.
    static func record(_ area: String, now: Int64 = TimeUtils.nowMillis, defaults: UserDefaults? = nil) {
        let store: UserDefaults
        if let defaults {
            store = defaults
        } else {
            guard SharedStorage.loadSettingsChecked().state != .inaccessible else { return }
            store = SharedStorage.defaults
        }
        let key = countPrefix + BackgroundErrors.area(area)
        lock.lock()
        defer { lock.unlock() }
        let n = store.integer(forKey: key)
        store.set(n == Int.max ? n : n + 1, forKey: key)
        store.set(Double(now), forKey: lastAtKey)
    }

    /// Zählerstand für den Diagnosebericht.
    static func summary(defaults: UserDefaults = SharedStorage.defaults) -> BackgroundErrorSummary {
        var counts: [String: Int] = [:]
        for area in BackgroundErrors.areas {
            let n = defaults.integer(forKey: countPrefix + area)
            if n > 0 { counts[area] = n }
        }
        return BackgroundErrorSummary(counts: counts, lastAt: Int64(defaults.double(forKey: lastAtKey)))
    }
}
