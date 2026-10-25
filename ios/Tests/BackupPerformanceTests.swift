import XCTest
@testable import CryptoChecker

/// Merkliste und Paar-Alarme mit 2000 Paaren in der Sicherung (`BackupManager`, gleiches Format wie Android) und im
/// gespeicherten Stand (`SharedStorage.Snapshot`, `watchlist.json`, auch von den Widgets gelesen): Grösse, Zeit zum
/// Schreiben und Lesen. Grenzen und Skalierung: `PerformanceTiming`. Wie `BackupPerformanceTest.kt`.
final class BackupPerformanceTests: XCTestCase {
    /// Unter Thread Sanitizer (CI setzt `CC_SKIP_PERFORMANCE=1`) übersprungen: die Zeiten wären verfälscht.
    override func setUpWithError() throws {
        try super.setUpWithError()
        try PerformanceTiming.skipIfRequested()
    }

    private typealias T = PerformanceTiming
    private static let twoMB = 2 * 1024 * 1024
    /// Der Stand trägt zusätzlich Kurse, Zeiten und Veränderung je Paar: gerechnet rund 1,7 MB.
    private static let threeMB = 3 * 1024 * 1024

    /// Merkliste-Teil der Sicherung, aufgebaut wie in `BackupManager.plainExportData`.
    private func root(_ watches: [Watch], _ alarms: [Alarm]) -> [String: Any] {
        ["watches": watches.map(BackupManager.watchToJson), "alarms": alarms.map(BackupManager.alarmToJson)]
    }

    private func backup(_ n: Int, perPair: Int? = nil) throws -> Data {
        let watches = PerformanceData.watches(n)
        return try BackupManager.encode(root(watches, PerformanceData.alarms(watches, perPair: perPair)))
    }

    private func snapshot(_ n: Int) -> SharedStorage.Snapshot {
        var snapshot = SharedStorage.Snapshot()
        snapshot.watches = PerformanceData.watches(n)
        snapshot.alarms = PerformanceData.alarms(snapshot.watches)
        return snapshot
    }

    /// Wie `SharedStorage.loadMain`: mit Zähler übersprungener Einträge.
    private static func decodeSnapshot(_ data: Data) throws -> SharedStorage.Snapshot {
        let decoder = JSONDecoder()
        decoder.userInfo[SharedStorage.DropCounter.key] = SharedStorage.DropCounter()
        return try decoder.decode(SharedStorage.Snapshot.self, from: data)
    }

    func testSizesStaySmall() throws {
        let n = T.large
        // Sicherung, 2000 Paare mit 1–3 Alarmen: gerechnet rund 1,4 MB
        let typical = try backup(n).count
        T.reportSize("Sicherung Merkliste (1–3 Alarme je Paar)", pairs: n, bytes: typical, limitBytes: Self.twoMB)
        XCTAssertLessThan(typical, Self.twoMB)
        // Volle Grenzen (je zehn Alarme): bleibt weit unter dem, was sich einlesen lässt
        let full = try backup(n, perPair: WatchlistLimits.maxAlarmsPerPair).count
        T.reportSize("Sicherung Merkliste (10 Alarme je Paar)", pairs: n, bytes: full,
                     limitBytes: BackupLimits.maxBytes / 8)
        XCTAssertLessThan(full, BackupLimits.maxBytes / 8)
        // Gespeicherter Stand (Codable)
        let stored = try JSONEncoder().encode(snapshot(n)).count
        T.reportSize("Gespeicherter Stand watchlist.json", pairs: n, bytes: stored, limitBytes: Self.threeMB)
        XCTAssertLessThan(stored, Self.threeMB)
    }

    func testWriteAndReadBackup() throws {
        // JSON aufbauen und kompakt schreiben bzw. lesen, Kennungen prüfen, bereinigen, begrenzen: erwartet einige
        // 100 ms (Debug)
        try T.assertScales("Sicherung schreiben (Merkliste)", limitMillis: 3_000) { n in
            let watches = PerformanceData.watches(n)
            let alarms = PerformanceData.alarms(watches)
            // Mehr Läufe: im Debug-Lauf mit Abdeckung streut das Schreiben (Build 130: einmal ×6.1 knapp über der Grenze)
            return try T.medianMillis(warmup: 3, runs: 7) { try BackupManager.encode(root(watches, alarms)) }
        }
        try T.assertScales("Sicherung lesen (Merkliste)", limitMillis: 4_000) { n in
            let data = try backup(n)
            return try T.medianMillis {
                let object = try JSONSerialization.jsonObject(with: data)
                return try BackupManager.restoredWatchlist(try XCTUnwrap(object as? [String: Any]))
            }
        }
        let watches = PerformanceData.watches(T.large)
        let alarms = PerformanceData.alarms(watches)
        let object = try JSONSerialization.jsonObject(with: try BackupManager.encode(root(watches, alarms)))
        let restored = try BackupManager.restoredWatchlist(try XCTUnwrap(object as? [String: Any]))
        XCTAssertEqual(restored.watches.count, watches.count)
        XCTAssertEqual(restored.alarms.count, alarms.count)
        XCTAssertEqual(restored.invalidAlarms + restored.droppedPairs + restored.droppedAlarms, 0)
    }

    func testEncodeAndDecodeSnapshot() throws {
        // Speichern bzw. Laden des Stands (App, Hintergrund, Widgets): erwartet einige 100 ms (Debug)
        try T.assertScales("Stand kodieren (watchlist.json)", limitMillis: 3_000) { n in
            let stored = snapshot(n)
            return try T.medianMillis { try JSONEncoder().encode(stored) }
        }
        try T.assertScales("Stand dekodieren (watchlist.json)", limitMillis: 4_000) { n in
            let data = try JSONEncoder().encode(snapshot(n))
            return try T.medianMillis { try Self.decodeSnapshot(data) }
        }
        let stored = snapshot(T.large)
        let decoded = try Self.decodeSnapshot(try JSONEncoder().encode(stored))
        XCTAssertEqual(decoded.watches.map(\.id), stored.watches.map(\.id))
        XCTAssertEqual(decoded.alarms.map(\.id), stored.alarms.map(\.id))
        XCTAssertEqual(decoded.watches.last?.lastPrice, stored.watches.last?.lastPrice)
    }
}
