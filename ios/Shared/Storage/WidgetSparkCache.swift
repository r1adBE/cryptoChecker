import Foundation
import os

/// Aufräumen der Chart-Zwischenspeicher der Widgets (`widget_spark_*` in den App-Group-Defaults). Je Paar,
/// Zeitraum und Quelle entsteht ein Eintrag; entfernte Paare oder gewechselte Zeiträume blieben sonst für immer
/// liegen und machten die Defaults-Datei (die jeder Prozess beim Start ganz liest) immer grösser.
enum WidgetSparkCache {
    static let keyPrefix = "widget_spark_"
    /// Merker «zuletzt aufgeräumt» (ms) — höchstens einmal je `pruneIntervalMillis`.
    static let lastPruneKey = "widget_spark_prune_at"
    static let pruneIntervalMillis: Int64 = 24 * 3_600_000
    /// Älter als das → weg. Das Einzel-Widget zeigt einen Chart höchstens 1 h nach dem Laden
    /// (`WidgetSparkline.staleFallbackMillis`), das Portfolio-Widget liest die Tageskerzen aber bis zu 26 h
    /// (`PortfolioWidgetSeries.keepMillis`) — massgebend ist darum der längere der beiden Werte.
    static var maxAgeMillis: Int64 { max(60 * 60_000, PortfolioWidgetSeries.keepMillis) }

    /// Schlüssel, die weg können: Präfix passt, Eintrag ohne lesbare Zeit oder älter als `maxAge`. Ein Eintrag
    /// «aus der Zukunft» (Uhr zurückgestellt) zählt als alt, sonst bliebe er bis zu diesem Zeitpunkt liegen.
    static func staleKeys(in entries: [String: Any], now: Int64, maxAge: Int64 = maxAgeMillis) -> [String] {
        entries.compactMap { (key: String, value: Any) -> String? in
            guard key.hasPrefix(keyPrefix), key != lastPruneKey else { return nil }
            guard let entry = value as? [String: Any], let t = entry["t"] as? Double, t > 0 else { return key }
            let age = now - Int64(t)
            return (age < 0 || age > maxAge) ? key : nil
        }.sorted()
    }

    /// Einmal am Tag aufräumen (sonst sofort zurück).
    /// - Returns: Anzahl entfernter Einträge (0 = nicht fällig oder nichts zu tun).
    @discardableResult
    static func pruneIfDue(defaults: UserDefaults = SharedStorage.defaults, now: Int64 = EpochTime.nowMillis) -> Int {
        let last = Int64(defaults.double(forKey: lastPruneKey))
        if last > 0, now - last >= 0, now - last < pruneIntervalMillis { return 0 }
        defaults.set(Double(now), forKey: lastPruneKey)
        let stale = staleKeys(in: defaults.dictionaryRepresentation(), now: now)
        for key in stale { defaults.removeObject(forKey: key) }
        if !stale.isEmpty {
            AppLog.widget.info("Chart-Zwischenspeicher: \(stale.count, privacy: .public) alte Einträge entfernt")
        }
        return stale.count
    }
}
