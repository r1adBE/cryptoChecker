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

    // Kein Speichern vor dem Lesen (Zugriffe, mutate, init): Regel `ios-wiring` in `.github/scripts/source_rules.py`
}
