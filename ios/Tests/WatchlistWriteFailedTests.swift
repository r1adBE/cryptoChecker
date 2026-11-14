import CryptoKit
import XCTest
@testable import CryptoChecker

/// Merkliste bei vollem Speicher bzw. nicht beschreibbarer Datei (N3-15): `writeFiles` merkt sich das Scheitern
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

    func testSchreibfehlerWirdGemerktUndGeloescht() {
        let snapshot = SharedStorage.Snapshot()
        // Ziel in einem Ordner, den es nicht gibt: das Schreiben scheitert (nicht am Schlüssel)
        let missing = dir.appendingPathComponent("fehlt/watchlist.json")
        XCTAssertFalse(SharedStorage.writeFiles(snapshot, watchlist: missing, portfolioAlarms: dir.appendingPathComponent("pa.json"),
                                                includePortfolioInBackup: false, keys: keys()))
        XCTAssertTrue(SharedStorage.watchlistWriteFailed)
        XCTAssertFalse(SharedStorage.watchlistPending)
        // Nächstes Schreiben gelingt: Merker weg
        XCTAssertTrue(SharedStorage.writeFiles(snapshot, watchlist: dir.appendingPathComponent("watchlist.json"),
                                               portfolioAlarms: dir.appendingPathComponent("pa.json"),
                                               includePortfolioInBackup: false, keys: keys()))
        XCTAssertFalse(SharedStorage.watchlistWriteFailed)
    }

    /// Liegen gebliebene Zwischendateien (N3-11): nur alte `.<Name>.<UUID>.tmp` weg; junge und echte Dateien bleiben.
    func testAlteZwischendateienWeg() throws {
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

    /// Hinweis, erneutes Schreiben und Speicher massgebend sind verdrahtet.
    func testVerdrahtung() throws {
        let root = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
        func source(_ path: String) throws -> String {
            try String(contentsOf: root.appendingPathComponent(path), encoding: .utf8)
        }
        let appData = try source("App/Services/AppData.swift")
        XCTAssertTrue(appData.contains("if watchlistSavePending || SharedStorage.watchlistWriteFailed {"))
        XCTAssertTrue(appData.contains("if !waiting, !SharedStorage.watchlistWriteFailed, let fresh = read.snapshot {"))
        let snapshot = try source("Shared/Storage/SharedStorage+Snapshot.swift")
        XCTAssertTrue(snapshot.contains("NotificationCenter.default.post(name: watchlistWriteFailedNotice, object: nil)"))
        let hint = try source("App/Components/WatchlistLockedHint.swift")
        XCTAssertTrue(hint.contains(".onReceive(NotificationCenter.default.publisher(for: SharedStorage.watchlistWriteFailedNotice))"))
        XCTAssertTrue(hint.contains("show(L(\"portfolio_write_failed\"))"))
    }
}
