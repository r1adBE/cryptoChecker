import XCTest
@testable import CryptoChecker

/// Lesen beim Start abseits des Haupt-Threads (AppData+SnapshotLoad.swift): Es wird genau einmal gelesen, auch wenn
/// der Haupt-Thread während des Lesens im Hintergrund zugreift; vor dem Lesen kann nichts gespeichert werden — jeder
/// Zugriff auf Stand, Adressen und Sperre liest zuerst (Wächter über die Quelltexte), das Speichern läuft nur über
/// diesen Zugriff (`mutate`), und `init` liest nichts Geschütztes mehr synchron.
final class LaunchSnapshotLoadTests: XCTestCase {

    /// Ergebnis ohne Dateien: leere Merkliste, als Merkmal `nextWatchId`.
    private static func launch(_ marker: Int64) -> LaunchRead {
        var snapshot = SharedStorage.Snapshot()
        snapshot.nextWatchId = marker
        return LaunchRead(setAside: false, restored: false, encryptionPending: false, read: .ok(snapshot),
                          keyAvailable: true, watched: WatchedAddressFile(), watchedIntact: true)
    }

    /// Zählt die Lesevorgänge; das erste Lesen wartet, bis `release` signalisiert.
    private final class GatedReader: @unchecked Sendable { // @unchecked: Zähler nur unter `lock`
        private let lock = NSLock()
        private var count = 0
        let started = DispatchSemaphore(value: 0)
        let release = DispatchSemaphore(value: 0)

        var reads: Int { lock.withLock { count } }

        func read() -> LaunchRead {
            let n: Int = lock.withLock {
                count += 1
                return count
            }
            started.signal()
            release.wait()
            return LaunchSnapshotLoadTests.launch(Int64(100 + n))
        }
    }

    func testLoaderReadsOnceWhileAnotherCallerWaits() {
        let reader = GatedReader()
        let loader = LaunchStorageLoader { reader.read() }
        let background = expectation(description: "Lesen im Hintergrund")
        let results = LockedBox<[Int64]>([])
        DispatchQueue.global().async {
            let value = loader.value().read.snapshot?.nextWatchId ?? 0
            results.write { $0.append(value) }
            background.fulfill()
        }
        // Erst wenn das Lesen im Hintergrund läuft, greift ein zweiter Aufrufer zu (wie der Haupt-Thread)
        XCTAssertEqual(reader.started.wait(timeout: .now() + 5), .success)
        let second = expectation(description: "Zugriff während des Lesens")
        DispatchQueue.global().async {
            let value = loader.value().read.snapshot?.nextWatchId ?? 0
            results.write { $0.append(value) }
            second.fulfill()
        }
        reader.release.signal()
        wait(for: [background, second], timeout: 5)
        XCTAssertEqual(reader.reads, 1, "nie zweimal verschlüsseln oder lesen")
        XCTAssertEqual(results.read { $0 }, [101, 101])
        XCTAssertEqual(loader.value().read.snapshot?.nextWatchId, 101)
        XCTAssertEqual(reader.reads, 1)
    }

    @MainActor
    func testAppDataUsesTheLaunchReadAndRunsWaiters() async {
        let data = AppData.shared
        await data.waitForSnapshot()
        XCTAssertTrue(data.snapshotLoaded)
        let ran = LockedBox(false)
        data.whenSnapshotLoaded { ran.write { $0 = true } }
        XCTAssertTrue(ran.current, "nach dem Lesen sofort")
    }

    // MARK: Wächter: kein Speichern vor dem Lesen

    private func source(_ path: String) throws -> String {
        let root = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
        return try String(contentsOf: root.appendingPathComponent(path), encoding: .utf8)
    }

    /// Alle Swift-Dateien unter `App/` (Dateiname, Inhalt).
    private func appSources() throws -> [(name: String, text: String)] {
        let root = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
        let app = root.appendingPathComponent("App")
        guard let walker = FileManager.default.enumerator(at: app, includingPropertiesForKeys: nil) else { return [] }
        var out: [(name: String, text: String)] = []
        for case let url as URL in walker where url.pathExtension == "swift" {
            out.append((url.lastPathComponent, try String(contentsOf: url, encoding: .utf8)))
        }
        return out
    }

    private func occurrences(_ needle: String, in text: String) -> Int {
        text.components(separatedBy: needle).count - 1
    }

    func testStoredStateIsOnlyReachedThroughLoadingAccessors() throws {
        let appData = try source("App/Services/AppData.swift")
        // Stand: nur der Setter von `snapshot` (liest vorher) und das Übernehmen beim Start schreiben ihn
        let writes = appData.components(separatedBy: "\n").filter {
            $0.trimmingCharacters(in: .whitespaces).hasPrefix("storedSnapshot = ")
        }
        XCTAssertEqual(writes.count, 2)
        XCTAssertTrue(appData.contains("ensureSnapshotLoaded()\n            return storedSnapshot"))
        XCTAssertTrue(appData.contains("ensureSnapshotLoaded()\n            storedSnapshot = newValue"))
        // Adressen und Sperre: ausser der Deklaration nur in den Zugriffen mit Lesen
        let allowed: Set<String> = ["AppData.swift", "AppData+SnapshotLoad.swift"]
        let sources = try appSources()
        XCTAssertGreaterThan(sources.count, 100, "Quelltexte der App gefunden")
        for file in sources where !allowed.contains(file.name) {
            XCTAssertFalse(file.text.contains("watchedState"), "\(file.name): an `watchedAddresses` vorbei")
            XCTAssertFalse(file.text.contains("watchlistLockedValue"), "\(file.name): an `watchlistLocked` vorbei")
        }
        let load = try source("App/Services/AppData+SnapshotLoad.swift")
        for accessor in ["watchedState.addresses", "watchedState.file", "watchedState.intact", "watchlistLockedValue"] {
            let reads = occurrences("return \(accessor)", in: load)
            XCTAssertEqual(reads, 1, accessor)
            XCTAssertEqual(occurrences("ensureSnapshotLoaded()\n            return \(accessor)", in: load), 1, accessor)
        }
    }

    func testSavingGoesThroughTheLoadingAccessor() throws {
        let appData = try source("App/Services/AppData.swift")
        // `mutate` beginnt mit dem Stand aus `snapshot` (liest vorher) — ein Speichern vor dem Lesen schriebe sonst
        // eine leere Merkliste
        let mutate = try XCTUnwrap(appData.range(of: "    func mutate("))
        let body = appData[mutate.upperBound...].prefix(400)
        XCTAssertTrue(body.contains("var s = snapshot\n"), "mutate liest den Stand über `snapshot`")
        // Portfolio liest erst nach dem Lesen beim Start (Verschlüsselung vor dem ersten Lesen)
        let portfolio = try source("App/Services/AppData+PortfolioLoad.swift")
        let ensured = "guard !portfolioState.loaded else { return }\n        ensureSnapshotLoaded()"
        XCTAssertTrue(portfolio.contains(ensured))
    }

    func testInitReadsNothingProtectedSynchronously() throws {
        let appData = try source("App/Services/AppData.swift")
        let start = try XCTUnwrap(appData.range(of: "    private init() {"))
        let end = try XCTUnwrap(appData.range(of: "\n    }\n", range: start.upperBound..<appData.endIndex))
        let initBody = appData[start.upperBound..<end.lowerBound]
        for call in ["prepareAtLaunch", "readSnapshot", "loadChecked", "snapshot.", "startPortfolioPrefetch",
                     "watchedAddress"] {
            XCTAssertFalse(initBody.contains(call), "init: \(call) erst nach dem Lesen beim Start")
        }
        XCTAssertTrue(initBody.contains("startSnapshotLoad()"))
    }
}
