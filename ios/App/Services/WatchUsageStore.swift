import Foundation

/// «Akku & Aktivität» › Uhr: die Uhr legt ihre eigenen Zähler der letzten 24 h unter `usage` in ihren
/// Anwendungskontext (`WatchLink`, `UsageLog.encodeReport`, nur Zahlen und Zeit). Das iPhone merkt sich den neuesten
/// Stand in `watch_usage.txt` im App-Group-Container, aus dem Backup ausgenommen (`PhoneWatchLink`). Wie Android
/// `PhoneWatchUsage`.
enum WatchUsageStore {
    static let fileName = "watch_usage.txt"
    /// Schlüssel im Anwendungskontext der Uhr (gleich in `WatchLink`).
    static let contextKey = "usage"
    private static let lock = NSLock()

    static var url: URL { SharedStorage.directory.appendingPathComponent(fileName) }

    /// Zuletzt gemerkter Stand der Uhr; nil = noch keiner (dann «Keine Uhr verbunden»).
    static func load(from url: URL = WatchUsageStore.url) -> UsageReport? {
        UsageLog.decodeReport(try? String(contentsOf: url, encoding: .utf8))
    }

    /// Stand der Uhr übernehmen, wenn lesbar und neuer als der gemerkte.
    static func take(_ text: String?, to url: URL = WatchUsageStore.url) {
        guard let report = UsageLog.decodeReport(text) else { return }
        lock.lock()
        defer { lock.unlock() }
        if let known = load(from: url), known.at >= report.at { return }
        guard let data = UsageLog.encodeReport(report).data(using: .utf8),
              (try? data.write(to: url, options: .atomic)) != nil else { return }
        // Atomares Schreiben ersetzt die Datei: Merkmal jedes Mal neu setzen
        BackupExclusion.set(excluded: true, for: url)
    }
}
