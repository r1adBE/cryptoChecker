import Foundation
import os
import WatchConnectivity

/// «Akku & Aktivität» › Uhr: die Uhr legt ihre eigenen Zähler der letzten 24 h unter `usage` in ihren
/// Anwendungskontext (`WatchLink`, `UsageLog.encodeReport`, nur Zahlen und Zeit). Das iPhone merkt sich den neuesten
/// Stand in `watch_usage.txt` im App-Group-Container, aus dem Backup ausgenommen (`PhoneWatchLink`). Wie Android
/// `PhoneWatchUsage`.
enum WatchUsageStore {
    static let fileName = "watch_usage.txt"
    /// Schlüssel im Anwendungskontext der Uhr (gleich in `WatchLink`).
    static let contextKey = "usage"
    /// Nachricht an die Uhr: «Zähler jetzt schicken» (gleich in `WatchLink`).
    static let requestKey = "usageRequest"
    private static let lock = NSLock()

    static var url: URL { SharedStorage.directory.appendingPathComponent(fileName) }

    /// Zuletzt gemerkter Stand der Uhr; nil = noch keiner (dann je nach `presence` der Satz der Karte).
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

    /// Welche Apple Watch am iPhone hängt (Text der Karte, solange die Uhr noch keine Zahlen geschickt hat). Wie
    /// Android `PhoneWatchUsage.Presence`.
    enum Presence: Sendable { case noWatch, noApp, app }

    /// Gekoppelte Uhr und Uhr-App laut WatchConnectivity; vor der Aktivierung oder ohne Unterstützung `.noWatch`.
    static var presence: Presence {
        guard WCSession.isSupported() else { return .noWatch }
        let session = WCSession.default
        guard session.activationState == .activated, session.isPaired else { return .noWatch }
        return session.isWatchAppInstalled ? .app : .noApp
    }

    /// Die Uhr bitten, ihre Zähler gleich zu schicken — geht nur, solange die Uhr-App erreichbar ist (offen oder kurz
    /// danach). true = Antwort erhalten und übernommen (`take`); sonst bleibt der gemerkte Stand. Wie Android
    /// `PhoneWatchUsage.request`.
    static func request() async -> Bool {
        guard WCSession.isSupported() else { return false }
        let session = WCSession.default
        guard session.activationState == .activated, session.isReachable else { return false }
        // Antwort und Fehler schliessen sich aus; die Sperre stellt sicher, dass nur einmal fortgesetzt wird
        let done = OSAllocatedUnfairLock(initialState: false)
        return await withCheckedContinuation { (continuation: CheckedContinuation<Bool, Never>) in
            session.sendMessage([requestKey: true], replyHandler: { reply in
                let text = reply[WatchUsageStore.contextKey] as? String
                WatchUsageStore.take(text)
                guard done.withLock({ was in defer { was = true }; return !was }) else { return }
                continuation.resume(returning: text != nil)
            }, errorHandler: { _ in
                guard done.withLock({ was in defer { was = true }; return !was }) else { return }
                continuation.resume(returning: false)
            })
        }
    }
}
