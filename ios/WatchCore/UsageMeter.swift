import Foundation
import os

/// Zähler von «Akku & Aktivität» (`UsageLog`) für diesen Prozess: zählt im Speicher und schreibt höchstens alle
/// `flushGapMillis` (abseits des Aufrufers) in `usage_log.txt` im App-Group-Container — iPhone: App, Hintergrund-Aufgabe
/// und Widgets; Uhr: Uhr-App und Komplikation —, aus dem Backup ausgenommen; sofort mit `flush()` (Ende eines
/// Hintergrund-Laufs bzw. einer Timeline). Lesen, Zusammenführen und Schreiben laufen über `NSFileCoordinator` (mehrere
/// Prozesse, wie `WatchQuoteFile`). Misslingt das Schreiben, bleiben die Zahlen im Speicher; endet der Prozess vorher,
/// fehlt höchstens die letzte Minute. Nur Zahlen, keine Inhalte und keine Hosts. Wie Android `UsageMeter`.
final class UsageMeter: Sendable {
    static let fileName = "usage_log.txt"
    static let flushGapMillis: Int64 = 60_000

    /// Zähler des Prozesses.
    static let shared = UsageMeter(url: defaultURL)

    /// Datei im App-Group-Container (ohne Gruppe: temporärer Ordner, dann nur für diesen Prozess).
    static var defaultURL: URL {
        let directory = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: "group.com.cryptochecker.app")
            ?? FileManager.default.temporaryDirectory
        return directory.appendingPathComponent(fileName)
    }

    private struct State: Sendable {
        var pending: [UsageBucket] = []
        var lastFlush: Int64 = 0
    }

    let url: URL
    private let now: @Sendable () -> Int64
    private let state = OSAllocatedUnfairLock(initialState: State())
    /// Schreiben und Lesen nacheinander: Was `flush` gerade schreibt, fehlt sonst kurz in `totals`.
    private let file = NSLock()

    init(url: URL, now: @escaping @Sendable () -> Int64 = { Int64(Date().timeIntervalSince1970 * 1000) }) {
        self.url = url
        self.now = now
    }

    /// Ein Hintergrund-Lauf (BGTask, Widget-Timeline mit Abruf, Komplikation).
    func countRun() { record(runs: 1) }

    /// Eine Netzanfrage mit `bytes` empfangenen Bytes (Körper der Antwort).
    func countRequest(bytes: Int64) { record(requests: 1, bytes: bytes) }

    /// Weitere empfangene Bytes ohne neue Anfrage (Live-Nachrichten einer WebSocket-Verbindung).
    func countBytes(_ bytes: Int64) { record(bytes: bytes) }

    private func record(runs: Int = 0, requests: Int = 0, bytes: Int64 = 0) {
        let at = now()
        let due = state.withLock { s -> Bool in
            s.pending = UsageLog.add(s.pending, now: at, runs: runs, requests: requests, bytes: max(bytes, 0))
            guard at - s.lastFlush >= Self.flushGapMillis else { return false }
            // Gleich als geschrieben vermerken: weitere Zahlen bis dahin lösen keinen zweiten Auftrag aus
            s.lastFlush = at
            return true
        }
        // Abseits des Aufrufers (Netz-Delegate, Hauptfaden): die Datei-Abstimmung kann auf einen anderen Prozess warten
        if due { Task.detached(priority: .utility) { self.flush() } }
    }

    /// Gezähltes jetzt in die Datei (zusammengeführt mit ihrem Stand). Blockiert kurz (Datei-Abstimmung).
    func flush() {
        file.lock()
        defer { file.unlock() }
        let at = now()
        let taken = state.withLock { s -> [UsageBucket] in
            s.lastFlush = at
            let pending = s.pending
            s.pending = []
            return pending
        }
        guard !taken.isEmpty else { return }
        if !write(taken, at: at) {
            state.withLock { s in s.pending = UsageLog.merge(taken, s.pending, now: at) }
        }
    }

    /// Summe der letzten 24 h: Datei und noch nicht geschriebene Zahlen dieses Prozesses.
    func totals() -> UsageTotals {
        file.lock()
        defer { file.unlock() }
        let at = now()
        let pending = state.withLock { $0.pending }
        var stored: [UsageBucket] = []
        var error: NSError?
        NSFileCoordinator().coordinate(readingItemAt: url, options: [], error: &error) { url in
            stored = Self.read(url)
        }
        return UsageLog.totals(UsageLog.merge(stored, pending, now: at), now: at)
    }

    private func write(_ taken: [UsageBucket], at: Int64) -> Bool {
        var written = false
        var error: NSError?
        NSFileCoordinator().coordinate(writingItemAt: url, options: .forMerging, error: &error) { url in
            let merged = UsageLog.merge(Self.read(url), taken, now: at)
            guard let data = UsageLog.encode(merged).data(using: .utf8),
                  (try? data.write(to: url, options: .atomic)) != nil else { return }
            // Nur Zahlen für diese Seite: nicht sichern (atomares Schreiben legt die Datei jedes Mal neu an)
            var stored = url
            var values = URLResourceValues()
            values.isExcludedFromBackup = true
            try? stored.setResourceValues(values)
            written = true
        }
        return written
    }

    private static func read(_ url: URL) -> [UsageBucket] {
        UsageLog.decode(try? String(contentsOf: url, encoding: .utf8))
    }
}

/// «Akku & Aktivität»: zählt je URLSession-Aufgabe eine Anfrage (gelungen, gescheitert oder abgebrochen) und die
/// empfangenen Bytes der Antwortkörper wie übertragen (`countOfResponseBodyBytesReceived` aller Übertragungen, auch
/// Weiterleitungen) in `UsageMeter.shared` — nur Zahlen, weder Adresse noch Inhalt. Als Delegate je Aufgabe
/// (`bytes(for:delegate:)`); wie Android `UsageEventListener`.
final class UsageTaskMetrics: NSObject, URLSessionTaskDelegate, Sendable {
    static let shared = UsageTaskMetrics()

    func urlSession(_ session: URLSession, task: URLSessionTask, didFinishCollecting metrics: URLSessionTaskMetrics) {
        let bytes = metrics.transactionMetrics.reduce(Int64(0)) { $0 + max($1.countOfResponseBodyBytesReceived, 0) }
        UsageMeter.shared.countRequest(bytes: bytes)
    }
}
