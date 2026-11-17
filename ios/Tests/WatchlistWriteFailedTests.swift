import CryptoKit
import XCTest
@testable import CryptoChecker

/// Merkliste bei vollem Speicher bzw. nicht beschreibbarer Datei: `writeFiles` merkt sich das Scheitern
/// (`watchlistWriteFailed`), die App zeigt «Konnte nicht gespeichert werden» und lässt den Stand im Speicher massgebend,
/// bis ein Schreiben gelingt. Gegenstück Android: `GuardedWriteTest.kt`.
final class WatchlistWriteFailedTests: XCTestCase {
    private var dir: URL!

    override func setUpWithError() throws {
        dir = FileManager.default.temporaryDirectory.appendingPathComponent("watchlist-write-failed-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
    }

    override func tearDownWithError() throws {
        try? FileManager.default.removeItem(at: dir)
        SharedStorage.watchlistPending = false
        SharedStorage.watchlistWriteFailed = false
        SharedStorage.portfolioAlarmsPending = false
    }

    private func keys() -> StorageKey {
        let fake = ProtectedFileTests.FakeKeychain()
        fake.stored = Data(repeating: 7, count: 32)
        return StorageKey(keychain: fake)
    }

    func testWriteErrorIsRememberedAndCleared() {
        let snapshot = SharedStorage.Snapshot()
        // Ziel in einem Ordner, den es nicht gibt: das Schreiben scheitert (nicht am Schlüssel)
        let missing = dir.appendingPathComponent("fehlt/watchlist.json")
        XCTAssertFalse(SharedStorage.writeFiles(snapshot, watchlist: missing, portfolioAlarms: dir.appendingPathComponent("pa.json"),
                                                includePortfolioInBackup: false, keys: keys()))
        XCTAssertTrue(SharedStorage.watchlistWriteFailed)
        XCTAssertFalse(SharedStorage.watchlistPending)
        // Nächstes Schreiben gelingt: Merker bleibt — `writeFiles` schreibt auch Stände aus der Datei
        // (`updateSnapshot` im Hintergrund-Abruf) ohne die ungespeicherte Änderung im Speicher der App; früher löschte
        // jedes geglückte Schreiben den Merker, und das nächste Lesen ersetzte die Änderung still
        XCTAssertTrue(SharedStorage.writeFiles(snapshot, watchlist: dir.appendingPathComponent("watchlist.json"),
                                               portfolioAlarms: dir.appendingPathComponent("pa.json"),
                                               includePortfolioInBackup: false, keys: keys()))
        XCTAssertTrue(SharedStorage.watchlistWriteFailed)
    }

    /// Gelöscht wird der Merker nur, wenn der Stand der App geschrieben ist (`saveSnapshotInBackground`), nie in
    /// `writeFiles` bzw. `updateSnapshot` (Stand aus der Datei).
    func testMarkerFallsOnlyWithTheAppState() throws {
        let root = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
        let files = try String(contentsOf: root.appendingPathComponent("Shared/Storage/SharedStorage+Files.swift"), encoding: .utf8)
        XCTAssertFalse(files.contains("watchlistWriteFailed = false"))
        let snapshot = try String(contentsOf: root.appendingPathComponent("Shared/Storage/SharedStorage+Snapshot.swift"),
                                  encoding: .utf8)
        XCTAssertEqual(snapshot.components(separatedBy: "watchlistWriteFailed = false").count - 1, 1)
        let save = try XCTUnwrap(snapshot.components(separatedBy: "static func saveSnapshotInBackground(").last)
        XCTAssertTrue(save.components(separatedBy: "static func mergeAndWriteFiles(").first?
            .contains("if written { watchlistWriteFailed = false }") == true)
        let update = try XCTUnwrap(snapshot.components(separatedBy: "static func updateSnapshot(").last?
            .components(separatedBy: "static func saveSnapshotInBackground(").first)
        XCTAssertFalse(update.contains("watchlistWriteFailed"))
    }

    /// Liegen gebliebene Zwischendateien: nur alte `.<Name>.<UUID>.tmp` weg; junge und echte Dateien bleiben.
    func testOldTemporaryFilesGone() throws {
        let fm = FileManager.default
        let now = EpochTime.nowMillis
        func file(_ name: String, ageMillis: Int64) throws -> URL {
            let url = dir.appendingPathComponent(name)
            try Data("x".utf8).write(to: url)
            let date = Date(timeIntervalSince1970: Double(now - ageMillis) / 1000)
            try fm.setAttributes([.modificationDate: date], ofItemAtPath: url.path)
            return url
        }
        let old = try file(".watchlist.json.\(UUID().uuidString).tmp", ageMillis: ProtectedFile.staleTempMillis + 60_000)
        let young = try file(".portfolio.json.\(UUID().uuidString).tmp", ageMillis: 1_000)
        let real = try file("watchlist.json", ageMillis: ProtectedFile.staleTempMillis * 10)
        XCTAssertEqual(ProtectedFile.removeStaleTemps(in: dir, now: now), 1)
        XCTAssertFalse(fm.fileExists(atPath: old.path))
        XCTAssertTrue(fm.fileExists(atPath: young.path))
        XCTAssertTrue(fm.fileExists(atPath: real.path))
        let storage = try String(contentsOf: URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("Shared/Storage/ProtectedStorage.swift"), encoding: .utf8)
        XCTAssertTrue(storage.contains("ProtectedFile.removeStaleTemps(in: dir, now: now)"))
    }
}
