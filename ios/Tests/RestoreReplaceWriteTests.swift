import CryptoKit
import Security
import XCTest
@testable import CryptoChecker

/// Wiederherstellen und Zurückrollen ersetzen die Merkliste OHNE Abgleich mit der Datei (`SharedStorage.replaceFiles`):
/// Ein eingespielter Alarm mit gleicher Id und gleichen Werten wie ein inzwischen gemeldeter ist scharf wie in der
/// Sicherung (Regel «aus einer Sicherung geladen → scharf», wie Android). Gewöhnliches Schreiben übernimmt die
/// Auslösungen des Widgets weiter (`mergeAndWriteFiles`).
/// Läuft in einem temporären Ordner mit gefälschtem Schlüsselbund, nie auf der App Group.
final class RestoreReplaceWriteTests: XCTestCase {
    private var dir: URL!
    private var watchlist: URL { dir.appendingPathComponent("watchlist.json") }
    private var portfolioAlarms: URL { dir.appendingPathComponent("portfolio_alarms.json") }
    private let fixedKey = SymmetricKey(data: Data(repeating: 9, count: 32))

    override func setUpWithError() throws {
        dir = FileManager.default.temporaryDirectory.appendingPathComponent("restore-replace-\(UUID().uuidString)")
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

    /// Alarm 3 «über 100 000», einmalig. `fired`: hat seit der Sicherung gemeldet (aus, mit Auslösung) — sonst scharf.
    private func snapshot(fired: Bool, repeating: Bool = false) -> SharedStorage.Snapshot {
        var s = SharedStorage.Snapshot()
        s.watches = [Watch(id: 1, exchangeKey: "binance", exchangeName: "Binance", baseAsset: "BTC", quoteAsset: "USDT")]
        var alarm = Alarm(id: 3, watchId: 1, condition: .PRICE_ABOVE, threshold: 100_000, repeating: repeating)
        if fired {
            alarm.enabled = repeating
            alarm.referenceAt = 5_000
            alarm.lastTriggeredAt = 5_000
            alarm.lastTriggeredPrice = 100_500
        }
        s.alarms = [alarm]
        s.nextWatchId = 2
        s.nextAlarmId = 4
        s.portfolioAlarms = []
        s.nextPortfolioAlarmId = 1
        return s
    }

    private func stored() throws -> SharedStorage.Snapshot {
        try XCTUnwrap(SharedStorage.readFiles(watchlist: watchlist, portfolioAlarms: portfolioAlarms, keys: keys()).snapshot)
    }

    private func writeDisk(_ s: SharedStorage.Snapshot) {
        XCTAssertTrue(SharedStorage.writeFiles(s, watchlist: watchlist, portfolioAlarms: portfolioAlarms,
                                               includePortfolioInBackup: true, keys: keys()))
    }

    // MARK: Ersetzen

    /// Sicherung vom Montag (scharf), am Dienstag gemeldet (Datei: aus, mit Auslösung), am Mittwoch eingespielt.
    func testRestoredAlarmWithSameIdIsArmed() throws {
        writeDisk(snapshot(fired: true))
        XCTAssertTrue(SharedStorage.replaceFiles(snapshot(fired: false), watchlist: watchlist,
                                                 portfolioAlarms: portfolioAlarms, includePortfolioInBackup: true,
                                                 keys: keys()))
        let alarm = try XCTUnwrap(try stored().alarms.first)
        XCTAssertTrue(alarm.enabled, "eingespielter Alarm ist an")
        XCTAssertEqual(alarm.lastTriggeredAt, 0)
        XCTAssertEqual(alarm.referenceAt, 0, "scharf, nicht «gemeldet»")
        XCTAssertNil(alarm.lastTriggeredPrice)
    }

    /// Wiederholender Alarm: nach dem Einspielen scharf, er wartet nicht auf die Rückkehr hinter die Marke.
    func testRestoredRepeatingAlarmIsArmed() throws {
        writeDisk(snapshot(fired: true, repeating: true))
        XCTAssertTrue(SharedStorage.replaceFiles(snapshot(fired: false, repeating: true), watchlist: watchlist,
                                                 portfolioAlarms: portfolioAlarms, includePortfolioInBackup: true,
                                                 keys: keys()))
        let alarm = try XCTUnwrap(try stored().alarms.first)
        XCTAssertTrue(alarm.enabled)
        XCTAssertEqual(alarm.referenceAt, 0)
        XCTAssertEqual(alarm.lastTriggeredAt, 0)
    }

    /// Auch die Kurse der Datei wandern nicht in den eingespielten Stand (kein Abgleich, in keiner Richtung).
    func testReplaceTakesNoPricesFromDisk() throws {
        var disk = snapshot(fired: true)
        disk.watches[0].lastPrice = 100_500
        disk.watches[0].lastUpdate = 5_000
        disk.watches[0].notifiedPrice = 100_500
        disk.watches[0].notifiedAt = 5_000
        writeDisk(disk)
        XCTAssertTrue(SharedStorage.replaceFiles(snapshot(fired: false), watchlist: watchlist,
                                                 portfolioAlarms: portfolioAlarms, includePortfolioInBackup: true,
                                                 keys: keys()))
        let watch = try XCTUnwrap(try stored().watches.first)
        XCTAssertEqual(watch.lastUpdate, 0)
        XCTAssertEqual(watch.notifiedAt, 0)
    }

    /// Gerade nicht zugreifbare Merkliste: auch das Ersetzen schreibt nichts (wie `mergeAndWriteFiles`).
    func testReplaceWritesNothingWhileWatchlistInaccessible() throws {
        writeDisk(snapshot(fired: true))
        let before = try Data(contentsOf: watchlist)
        let locked = ProtectedFileTests.FakeKeychain()
        locked.readStatus = errSecInteractionNotAllowed
        XCTAssertFalse(SharedStorage.replaceFiles(snapshot(fired: false), watchlist: watchlist,
                                                  portfolioAlarms: portfolioAlarms, includePortfolioInBackup: true,
                                                  keys: StorageKey(keychain: locked)))
        XCTAssertEqual(try Data(contentsOf: watchlist), before)
    }

    // MARK: Gewöhnliches Schreiben

    /// Derselbe Fall über den gewöhnlichen Weg: Die Auslösung des Widgets bleibt (sonst meldete der Alarm doppelt).
    func testNormalWriteStillMergesNewerTrigger() throws {
        writeDisk(snapshot(fired: true))
        XCTAssertTrue(SharedStorage.mergeAndWriteFiles(snapshot(fired: false), watchlist: watchlist,
                                                       portfolioAlarms: portfolioAlarms, includePortfolioInBackup: true,
                                                       keys: keys()))
        let alarm = try XCTUnwrap(try stored().alarms.first)
        XCTAssertFalse(alarm.enabled)
        XCTAssertEqual(alarm.lastTriggeredAt, 5_000)
        XCTAssertEqual(alarm.referenceAt, 5_000)
    }

    // MARK: Entscheid und Aufrufer

    /// Ein noch nicht geschriebenes Ersetzen gilt auch für das nächste Schreiben (sonst käme der alte Zustand doch noch).
    func testReplaceStaysPendingUntilWritten() {
        XCTAssertTrue(SharedStorage.replacesFile(requested: true, pending: false))
        XCTAssertTrue(SharedStorage.replacesFile(requested: false, pending: true))
        XCTAssertFalse(SharedStorage.replacesFile(requested: false, pending: false))
    }

    /// Nur Wiederherstellen und Zurückrollen (`replaceAll`) ersetzen; die Warteschlange merkt sich ein offenes Ersetzen.
    func testOnlyReplaceAllUsesTheReplacePath() throws {
        let root = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
        let reset = try String(contentsOf: root.appendingPathComponent("App/Services/AppData+Reset.swift"), encoding: .utf8)
        XCTAssertTrue(reset.contains("mutate({ s in s = newSnapshot }, replacing: true)"))
        XCTAssertEqual(reset.components(separatedBy: "replacing: true").count - 1, 1)
        let queue = try String(contentsOf: root.appendingPathComponent("Shared/Storage/SharedStorage+Snapshot.swift"),
                               encoding: .utf8)
        XCTAssertTrue(queue.contains("let replace = replacesFile(requested: replacing, pending: replacePendingBox.current)"))
        XCTAssertTrue(queue.contains("if replace { replacePendingBox.current = !written }"))
        let services = root.appendingPathComponent("App/Services")
        for file in try FileManager.default.contentsOfDirectory(at: services, includingPropertiesForKeys: nil)
        where file.pathExtension == "swift" && file.lastPathComponent != "AppData+Reset.swift" {
            let text = try String(contentsOf: file, encoding: .utf8)
            XCTAssertFalse(text.contains("replacing: true"), file.lastPathComponent)
        }
    }
}
