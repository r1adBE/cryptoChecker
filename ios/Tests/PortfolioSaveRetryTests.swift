import CryptoKit
import Security
import XCTest
@testable import CryptoChecker

/// Wartende Portfolio-Alarme erneut schreiben (`AppData.retryPendingPortfolioSaves`): über die
/// Schreib-Warteschlange mit Übernahme neuerer Kurse und Auslösungen (`SharedStorage.mergeAndWriteFiles`) —
/// sonst schriebe ein synchrones `saveSnapshot` den älteren Stand im Speicher über eine Auslösung des Widgets
/// (doppelter Alarm).
/// Läuft in einem temporären Ordner mit gefälschtem Schlüsselbund, nie auf der App Group.
final class PortfolioSaveRetryTests: XCTestCase {
    private var dir: URL!
    private var watchlist: URL { dir.appendingPathComponent("watchlist.json") }
    private var portfolioAlarms: URL { dir.appendingPathComponent("portfolio_alarms.json") }
    private let fixedKey = SymmetricKey(data: Data(repeating: 7, count: 32))

    override func setUpWithError() throws {
        dir = FileManager.default.temporaryDirectory.appendingPathComponent("portfolio-retry-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
    }

    override func tearDownWithError() throws {
        try? FileManager.default.removeItem(at: dir)
        SharedStorage.watchlistPending = false
        SharedStorage.portfolioAlarmsPending = false
    }

    private func keys() -> StorageKey {
        let fake = ProtectedFileTests.FakeKeychain()
        fake.stored = fixedKey.withUnsafeBytes { Data($0) }
        return StorageKey(keychain: fake)
    }

    private func snapshot(triggeredAt: Int64, portfolioThreshold: Double) -> SharedStorage.Snapshot {
        var s = SharedStorage.Snapshot()
        s.watches = [Watch(id: 1, exchangeKey: "binance", exchangeName: "Binance", baseAsset: "BTC", quoteAsset: "USDT")]
        s.alarms = [Alarm(id: 5, watchId: 1, condition: .PRICE_ABOVE, threshold: 70_000, lastTriggeredAt: triggeredAt)]
        s.nextWatchId = 2
        s.nextAlarmId = 6
        s.portfolioAlarms = [PortfolioAlarm(id: 3, kind: .VALUE_BELOW, threshold: portfolioThreshold, currency: "CHF")]
        s.nextPortfolioAlarmId = 4
        return s
    }

    // MARK: Schreiben mit Übernahme

    /// Das Widget hat den Alarm inzwischen gemeldet (Datei neuer als der Speicher): Das erneute Schreiben der
    /// Portfolio-Alarme behält die Auslösung — sonst meldete er sich ein zweites Mal.
    func testRetryKeepsNewerAlarmTriggerOfWidget() throws {
        let disk = snapshot(triggeredAt: 2_000, portfolioThreshold: 500)
        XCTAssertTrue(SharedStorage.writeFiles(disk, watchlist: watchlist, portfolioAlarms: portfolioAlarms,
                                               includePortfolioInBackup: true, keys: keys()))
        // Speicher der App: älter, aber mit geänderter Portfolio-Schwelle (die wartete)
        let memory = snapshot(triggeredAt: 1_000, portfolioThreshold: 450)
        XCTAssertTrue(SharedStorage.mergeAndWriteFiles(memory, watchlist: watchlist, portfolioAlarms: portfolioAlarms,
                                                       includePortfolioInBackup: true, keys: keys()))
        let stored = try XCTUnwrap(SharedStorage.readFiles(watchlist: watchlist, portfolioAlarms: portfolioAlarms,
                                                           keys: keys()).snapshot)
        XCTAssertEqual(stored.alarms.first?.lastTriggeredAt, 2_000, "Auslösung des Widgets bleibt")
        XCTAssertEqual(stored.portfolioAlarms?.first?.threshold, 450, "Portfolio-Alarm aus dem Speicher geschrieben")
        XCTAssertFalse(SharedStorage.portfolioAlarmsPending)
    }

    /// Gerade nicht zugreifbare Merkliste: nichts schreiben (der Speicher könnte leer sein).
    func testRetryWritesNothingWhileWatchlistInaccessible() throws {
        XCTAssertTrue(SharedStorage.writeFiles(snapshot(triggeredAt: 0, portfolioThreshold: 500), watchlist: watchlist,
                                               portfolioAlarms: portfolioAlarms, includePortfolioInBackup: true,
                                               keys: keys()))
        let before = try Data(contentsOf: watchlist)
        let locked = ProtectedFileTests.FakeKeychain()
        locked.readStatus = errSecInteractionNotAllowed
        XCTAssertFalse(SharedStorage.mergeAndWriteFiles(SharedStorage.Snapshot(), watchlist: watchlist,
                                                        portfolioAlarms: portfolioAlarms, includePortfolioInBackup: true,
                                                        keys: StorageKey(keychain: locked)))
        XCTAssertEqual(try Data(contentsOf: watchlist), before)
    }

    // MARK: Entscheide

    func testQueuesAlarmsOnlyWithWatchlistReadOnce() {
        XCTAssertTrue(PortfolioSaveRetry.queuesAlarms(alarmsPending: true, watchlistReadOnce: true))
        XCTAssertFalse(PortfolioSaveRetry.queuesAlarms(alarmsPending: true, watchlistReadOnce: false))
        XCTAssertFalse(PortfolioSaveRetry.queuesAlarms(alarmsPending: false, watchlistReadOnce: true))
    }

    /// Portfolio-Alarme halten das Lesen nicht auf (eingereiht; `readSnapshot` wartet sie ab) — Portfolio und Adressen schon.
    func testDiskReadWaitsOnlyForPortfolioFileAndAddresses() {
        XCTAssertTrue(PortfolioSaveRetry.allowsDiskRead(portfolioDone: true, addressesPending: false))
        XCTAssertFalse(PortfolioSaveRetry.allowsDiskRead(portfolioDone: false, addressesPending: false))
        XCTAssertFalse(PortfolioSaveRetry.allowsDiskRead(portfolioDone: true, addressesPending: true))
        XCTAssertTrue(PortfolioSaveRetry.stillPending(portfolioDone: true, addressesPending: false, alarmsPending: true))
        XCTAssertFalse(PortfolioSaveRetry.stillPending(portfolioDone: true, addressesPending: false, alarmsPending: false))
    }

    /// Warten Portfolio-Alarme nach dem Schreiben noch auf den Schlüssel, bleiben die im Speicher; sonst gilt die Datei.
    func testDiskReadKeepsPendingPortfolioAlarmsFromMemory() {
        var disk = snapshot(triggeredAt: 2_000, portfolioThreshold: 500)
        disk.nextPortfolioAlarmId = 4
        var memory = snapshot(triggeredAt: 1_000, portfolioThreshold: 450)
        memory.nextPortfolioAlarmId = 9

        let kept = PortfolioSaveRetry.merged(disk: disk, memory: memory, alarmsPending: true)
        XCTAssertEqual(kept.portfolioAlarms?.first?.threshold, 450)
        XCTAssertEqual(kept.nextPortfolioAlarmId, 9)
        XCTAssertEqual(kept.alarms.first?.lastTriggeredAt, 2_000, "Merkliste und Alarme aus der Datei")

        let fromDisk = PortfolioSaveRetry.merged(disk: disk, memory: memory, alarmsPending: false)
        XCTAssertEqual(fromDisk.portfolioAlarms?.first?.threshold, 500)
        XCTAssertEqual(fromDisk.nextPortfolioAlarmId, 4)
    }
}
