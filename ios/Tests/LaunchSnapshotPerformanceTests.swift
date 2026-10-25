import XCTest
@testable import CryptoChecker

/// Lesen beim Start (`LaunchRead`: `ProtectedStorage.prepareAtLaunch` + Lesen der Merkliste) mit bis zu 2000 Paaren und
/// 20 000 Alarmen (10 je Paar), versiegelt wie in der App — läuft beim Start abseits des Haupt-Threads, die Merkliste
/// zeigt so lange Platzhalter. Auf eigenen Dateien in einem temporären Ordner mit Schlüssel im Speicher (nie App Group
/// oder Schlüsselbund). Grenzen und Skalierung: `PerformanceTiming`.
final class LaunchSnapshotPerformanceTests: XCTestCase {
    /// Unter Thread Sanitizer (CI setzt `CC_SKIP_PERFORMANCE=1`) übersprungen: die Zeiten wären verfälscht.
    override func setUpWithError() throws {
        try super.setUpWithError()
        try PerformanceTiming.skipIfRequested()
    }

    private typealias T = PerformanceTiming

    /// Alarme je Paar (Obergrenze): 2000 Paare → 20 000 Alarme.
    private static let alarmsPerPair = WatchlistLimits.maxAlarmsPerPair

    /// Grenze für die grosse Menge: Debug mit Abdeckung grosszügig, im Release-Lauf (`ios-performance.yml`) enger.
    #if DEBUG
    private static let limitMillis = 6_000.0
    #else
    private static let limitMillis = 2_000.0
    #endif

    /// Schlüsselbund im Speicher (eigener Fake: Leistungstests nutzen keine Hilfen anderer Testdateien).
    private final class MemoryKeychain: StorageKeychain, @unchecked Sendable { // @unchecked: nur unter `lock`
        private let lock = NSLock()
        private var stored: Data?

        func read() throws -> Data? { lock.withLock { stored } }

        func add(_ key: Data) throws -> Bool {
            lock.withLock {
                guard stored == nil else { return false }
                stored = key
                return true
            }
        }

        func isDeviceOnly() throws -> Bool? { lock.withLock { stored == nil ? nil : true } }
        func makeDeviceOnly() throws {}
        func delete() throws { lock.withLock { stored = nil } }
    }

    /// Versiegelte Merkliste mit `pairs` Paaren und je `alarmsPerPair` Alarmen in `dir`.
    private struct Fixture {
        let dir: URL
        let watchlist: URL
        let portfolioAlarms: URL
        let keys: StorageKey
        let defaults: UserDefaults
        let suite: String
        /// Geschriebene Alarme.
        let alarmCount: Int

        init(pairs: Int) throws {
            dir = FileManager.default.temporaryDirectory.appendingPathComponent("launch-perf-\(UUID().uuidString)")
            try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
            watchlist = dir.appendingPathComponent("watchlist.json")
            portfolioAlarms = dir.appendingPathComponent("portfolio_alarms.json")
            keys = StorageKey(keychain: MemoryKeychain(), readGeneration: { nil }, writeGeneration: { _ in })
            suite = "launch-perf-\(UUID().uuidString)"
            defaults = UserDefaults(suiteName: suite) ?? .standard
            guard keys.createIfAbsent() != nil else { throw XCTSkip("Kein Schlüssel im Speicher") }
            let watches = PerformanceData.watches(pairs)
            var snapshot = SharedStorage.Snapshot()
            snapshot.watches = watches
            snapshot.alarms = PerformanceData.alarms(watches, perPair: LaunchSnapshotPerformanceTests.alarmsPerPair)
            snapshot.nextWatchId = Int64(watches.count + 1)
            snapshot.nextAlarmId = Int64(snapshot.alarms.count + 1)
            alarmCount = snapshot.alarms.count
            let written = SharedStorage.writeFiles(snapshot, watchlist: watchlist, portfolioAlarms: portfolioAlarms,
                                                   includePortfolioInBackup: false, keys: keys)
            guard written else { throw XCTSkip("Merkliste nicht geschrieben") }
        }

        /// Wie `LaunchRead.read` auf den eigenen Dateien: verschlüsseln (schon versiegelt: nur prüfen), dann lesen.
        func launchRead() -> SharedStorage.SnapshotRead {
            _ = ProtectedStorage.prepareAtLaunch(files: [(watchlist, watchlist), (portfolioAlarms, watchlist)],
                                                 keys: keys, defaults: defaults)
            return SharedStorage.readFiles(watchlist: watchlist, portfolioAlarms: portfolioAlarms, keys: keys)
        }

        func remove() {
            try? FileManager.default.removeItem(at: dir)
            defaults.removePersistentDomain(forName: suite)
        }
    }

    func testFixtureReadsBackCompletely() throws {
        let fixture = try Fixture(pairs: T.large)
        defer { fixture.remove() }
        let read = fixture.launchRead()
        let snapshot = try XCTUnwrap(read.snapshot, "Merkliste nicht lesbar")
        XCTAssertEqual(snapshot.watches.count, T.large)
        XCTAssertEqual(snapshot.alarms.count, fixture.alarmCount)
        XCTAssertEqual(fixture.alarmCount, T.large * LaunchSnapshotPerformanceTests.alarmsPerPair)
        XCTAssertTrue(ProtectedStorage.isSealedFile(fixture.watchlist), "Merkliste versiegelt wie in der App")
    }

    func testPrepareAndReadAtLaunch() throws {
        // Erwartet einige 100 ms für 2000 Paare und 20 000 Alarme (Debug); Release deutlich darunter
        var fixtures: [Int: Fixture] = [:]
        defer { fixtures.values.forEach { $0.remove() } }
        for n in [T.small, T.large] { fixtures[n] = try Fixture(pairs: n) }
        let ready = fixtures
        let limit = LaunchSnapshotPerformanceTests.limitMillis
        T.assertScales("Lesen beim Start (Verschlüsselung + Merkliste)", limitMillis: limit, sizes: T.pairs) { n in
            guard let fixture = ready[n] else { return .infinity }
            return T.medianMillis(warmup: T.quickWarmupRuns, runs: T.quickMeasuredRuns) {
                fixture.launchRead().snapshot?.alarms.count ?? 0
            }
        }
    }
}
