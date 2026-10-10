import XCTest
@testable import CryptoChecker

/// Merkliste lesen und schreiben (`SharedStorage.readFiles`/`writeFiles`, tolerantes Lesen,
/// eigene Datei der Portfolio-Alarme), tolerante Einstellungen, Zahlen aus der Sicherung
/// (`BackupManager.int64Truncating`), Plan der Marktphasen-Prüfung und `OnceGate`.
/// Läuft auf eigenen Dateien in einem temporären Ordner, nie auf der App Group.
final class SharedStorageTests: XCTestCase {

    private var dir: URL!
    private var watchlist: URL { dir.appendingPathComponent("watchlist.json") }
    private var portfolioAlarms: URL { dir.appendingPathComponent("portfolio_alarms.json") }

    override func setUpWithError() throws {
        dir = FileManager.default.temporaryDirectory.appendingPathComponent("shared-storage-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
    }

    override func tearDownWithError() throws {
        try? FileManager.default.removeItem(at: dir)
    }

    private func write(_ json: String, to url: URL) throws {
        try Data(json.utf8).write(to: url, options: [.atomic])
    }

    private func object(_ url: URL) throws -> [String: Any] {
        let any = try JSONSerialization.jsonObject(with: Data(contentsOf: url))
        return try XCTUnwrap(any as? [String: Any])
    }

    private static let watchBtc = #"{"id":1,"marketKey":"binance","baseAsset":"BTC","quoteAsset":"USDT"}"#
    private static let watchEth = #"{"id":2,"marketKey":"binance","baseAsset":"ETH","quoteAsset":"USDT"}"#

    // MARK: Tolerantes Lesen

    /// Unbekannte Werte aus einer neueren Version (Downgrade) überspringen nur den Eintrag.
    func testUnknownEnumValuesSkipOnlyThatEntry() throws {
        let json = """
        {"watches":[\(Self.watchBtc),
                    {"id":7,"marketKey":"binance","baseAsset":"SOL","quoteAsset":"USDT","contractType":99}],
         "alarms":[{"id":1,"watchId":1,"condition":"PRICE_ABOVE","threshold":100},
                   {"id":2,"watchId":1,"condition":"SOMETHING_NEW","threshold":5}],
         "nextWatchId":3,"nextAlarmId":3,
         "portfolioAlarms":[{"id":1,"kind":"VALUE_ABOVE","threshold":1000,"currency":"USD"},
                            {"id":2,"kind":"FUTURE_KIND","threshold":1}],
         "nextPortfolioAlarmId":3}
        """
        let counter = SharedStorage.DropCounter()
        let decoder = JSONDecoder()
        decoder.userInfo[SharedStorage.DropCounter.key] = counter
        let snap = try decoder.decode(SharedStorage.Snapshot.self, from: Data(json.utf8))
        XCTAssertEqual(snap.watches.map(\.id), [1])
        XCTAssertEqual(snap.alarms.map(\.id), [1])
        XCTAssertEqual(snap.portfolioAlarms?.map(\.id), [1])
        XCTAssertEqual(counter.count, 3)
        // Gespeicherte Zähler bleiben, wo sie nicht hinter vergebene Ids zurückfallen
        XCTAssertEqual(snap.nextWatchId, 3)
        XCTAssertEqual(snap.nextAlarmId, 3)
        XCTAssertEqual(snap.nextPortfolioAlarmId, 3)
    }

    func testNextIdsNeverBehindUsedIds() throws {
        let json = #"{"watches":[{"id":9,"marketKey":"m","baseAsset":"A","quoteAsset":"B"}],"nextWatchId":2}"#
        let snap = try JSONDecoder().decode(SharedStorage.Snapshot.self, from: Data(json.utf8))
        XCTAssertEqual(snap.nextWatchId, 10)
        XCTAssertEqual(snap.nextAlarmId, 1)
        XCTAssertNil(snap.portfolioAlarms)
        XCTAssertNil(snap.nextPortfolioAlarmId)
    }

    /// Alarm eines übersprungenen Paars (id 7): die Id 7 wird nicht neu vergeben.
    func testSkippedWatchIdIsNotReused() throws {
        let json = """
        {"watches":[{"id":7,"marketKey":"m","baseAsset":"A","quoteAsset":"B","contractType":99}],
         "alarms":[{"id":1,"watchId":7,"condition":"PRICE_ABOVE","threshold":1}],"nextWatchId":1}
        """
        let snap = try JSONDecoder().decode(SharedStorage.Snapshot.self, from: Data(json.utf8))
        XCTAssertTrue(snap.watches.isEmpty)
        XCTAssertEqual(snap.nextWatchId, 8)
    }

    func testNextIdHelper() {
        XCTAssertEqual(SharedStorage.nextId(stored: 0, used: []), 1)
        XCTAssertEqual(SharedStorage.nextId(stored: 5, used: [1, 2]), 5)
        XCTAssertEqual(SharedStorage.nextId(stored: 1, used: [4, 2]), 5)
        // Kein Überlauf bei der grössten id
        XCTAssertEqual(SharedStorage.nextId(stored: 1, used: [Int64.max]), Int64.max)
    }

    /// Keine Liste, wo eine stehen muss: die Datei gilt als unlesbar (statt leer).
    func testWrongTypeForListIsHardFailure() {
        let json = #"{"watches":{"id":1}}"#
        XCTAssertThrowsError(try JSONDecoder().decode(SharedStorage.Snapshot.self, from: Data(json.utf8)))
    }

    func testOldFileWithoutOptionalFieldsLoads() throws {
        let snap = try JSONDecoder().decode(SharedStorage.Snapshot.self, from: Data("{}".utf8))
        XCTAssertTrue(snap.watches.isEmpty)
        XCTAssertEqual(snap.nextWatchId, 1)
    }

    // MARK: Lesen der Dateien

    func testMissingFiles() {
        guard case .missing = SharedStorage.readFiles(watchlist: watchlist, portfolioAlarms: portfolioAlarms) else {
            return XCTFail("erwartet .missing")
        }
    }

    /// Kaputte Datei: Ergebnis «unlesbar» (Schreiber brechen ab), Kopie gesichert, Original unverändert.
    func testUnreadableFileIsPreservedAndReported() throws {
        try write("{ kaputt", to: watchlist)
        guard case .unreadable = SharedStorage.readFiles(watchlist: watchlist, portfolioAlarms: portfolioAlarms) else {
            return XCTFail("erwartet .unreadable")
        }
        let copy = SharedStorage.unreadableCopyURL(of: watchlist)
        XCTAssertEqual(copy.lastPathComponent, "watchlist.unreadable.json")
        XCTAssertEqual(try Data(contentsOf: copy), Data("{ kaputt".utf8))
        XCTAssertEqual(try Data(contentsOf: watchlist), Data("{ kaputt".utf8))
    }

    /// Übersprungene Einträge: Original wird gesichert, bevor ein Schreiben sie verwirft.
    func testDroppedEntriesKeepCopyOfOriginal() throws {
        let json = #"{"watches":[\#(Self.watchBtc)],"alarms":[{"id":1,"watchId":1,"condition":"NEW","threshold":1}]}"#
        try write(json, to: watchlist)
        let snap = try XCTUnwrap(SharedStorage.readFiles(watchlist: watchlist, portfolioAlarms: portfolioAlarms).snapshot)
        XCTAssertEqual(snap.watches.count, 1)
        XCTAssertTrue(snap.alarms.isEmpty)
        XCTAssertEqual(try Data(contentsOf: SharedStorage.unreadableCopyURL(of: watchlist)), Data(json.utf8))
    }

    func testCleanFileMakesNoCopy() throws {
        try write(#"{"watches":[\#(Self.watchBtc)]}"#, to: watchlist)
        XCTAssertNotNil(SharedStorage.readFiles(watchlist: watchlist, portfolioAlarms: portfolioAlarms).snapshot)
        XCTAssertFalse(FileManager.default.fileExists(atPath: SharedStorage.unreadableCopyURL(of: watchlist).path))
    }

    // MARK: Portfolio-Alarme in eigener Datei

    /// Alter Stand (Portfolio-Alarme in `watchlist.json`) → nach dem Schreiben in eigener Datei,
    /// ohne Verlust; ein zweites Schreiben ändert nichts mehr.
    func testPortfolioAlarmsMigrateToOwnFile() throws {
        try write("""
        {"watches":[\(Self.watchBtc)],"alarms":[],"nextWatchId":2,"nextAlarmId":1,
         "portfolioAlarms":[{"id":4,"kind":"VALUE_BELOW","threshold":500,"currency":"CHF","enabled":true}],
         "nextPortfolioAlarmId":5}
        """, to: watchlist)
        let legacy = try XCTUnwrap(SharedStorage.readFiles(watchlist: watchlist, portfolioAlarms: portfolioAlarms).snapshot)
        XCTAssertEqual(legacy.portfolioAlarms?.map(\.id), [4])

        XCTAssertTrue(SharedStorage.writeFiles(legacy, watchlist: watchlist, portfolioAlarms: portfolioAlarms,
                                               includePortfolioInBackup: false))
        let main = try object(watchlist)
        XCTAssertNil(main["portfolioAlarms"])
        XCTAssertNil(main["nextPortfolioAlarmId"])
        let file = try JSONDecoder().decode(SharedStorage.PortfolioAlarmsFile.self, from: Data(contentsOf: portfolioAlarms))
        XCTAssertEqual(file.alarms.map(\.id), [4])
        XCTAssertEqual(file.nextId, 5)
        XCTAssertEqual(BackupExclusion.isExcluded(portfolioAlarms), true)

        let again = try XCTUnwrap(SharedStorage.readFiles(watchlist: watchlist, portfolioAlarms: portfolioAlarms).snapshot)
        XCTAssertEqual(again.portfolioAlarms, legacy.portfolioAlarms)
        XCTAssertEqual(again.nextPortfolioAlarmId, 5)
        XCTAssertEqual(again.watches.map(\.id), [1])
        let before = try Data(contentsOf: portfolioAlarms)
        XCTAssertTrue(SharedStorage.writeFiles(again, watchlist: watchlist, portfolioAlarms: portfolioAlarms,
                                               includePortfolioInBackup: false))
        XCTAssertEqual(try Data(contentsOf: portfolioAlarms), before)
    }

    /// nil = unbekannt: Die Datei der Portfolio-Alarme bleibt unberührt.
    func testNilPortfolioAlarmsLeaveFileAlone() throws {
        try write(#"{"alarms":[{"id":1,"kind":"CHANGE_UP","threshold":5}],"nextId":2}"#, to: portfolioAlarms)
        var snap = SharedStorage.Snapshot()
        snap.watches = []
        XCTAssertNil(snap.portfolioAlarms)
        SharedStorage.writeFiles(snap, watchlist: watchlist, portfolioAlarms: portfolioAlarms, includePortfolioInBackup: true)
        let file = try JSONDecoder().decode(SharedStorage.PortfolioAlarmsFile.self, from: Data(contentsOf: portfolioAlarms))
        XCTAssertEqual(file.alarms.map(\.id), [1])
        // Nur Portfolio-Datei vorhanden (Merkliste fehlt): wird trotzdem gelesen
        try FileManager.default.removeItem(at: watchlist)
        let read = try XCTUnwrap(SharedStorage.readFiles(watchlist: watchlist, portfolioAlarms: portfolioAlarms).snapshot)
        XCTAssertEqual(read.portfolioAlarms?.map(\.id), [1])
    }

    func testIncludeInBackupClearsFlag() throws {
        var snap = SharedStorage.Snapshot()
        snap.portfolioAlarms = [PortfolioAlarm(id: 1, kind: .CHANGE_DOWN, threshold: 3, currency: nil)]
        SharedStorage.writeFiles(snap, watchlist: watchlist, portfolioAlarms: portfolioAlarms, includePortfolioInBackup: true)
        XCTAssertEqual(BackupExclusion.isExcluded(portfolioAlarms), false)
    }

    // MARK: Kurse aus dem Widget in den Stand der App

    func testMergeNewerPricesOnlyForSamePair() throws {
        let decoder = JSONDecoder()
        var app = try decoder.decode(SharedStorage.Snapshot.self, from: Data(#"{"watches":[\#(Self.watchBtc),\#(Self.watchEth)]}"#.utf8))
        app.watches[0].lastPrice = 100
        app.watches[0].lastUpdate = 1_000
        app.watches[1].lastPrice = 10
        app.watches[1].lastUpdate = 1_000
        var disk = app
        disk.watches[0].lastPrice = 110
        disk.watches[0].lastUpdate = 2_000
        disk.watches[0].notifiedPrice = 105
        disk.watches[0].notifiedAt = 1_500
        // Gleiche id, aber anderes Paar (in der App geändert): Kurs der App gilt
        disk.watches[1].baseAsset = "XRP"
        disk.watches[1].lastPrice = 0.5
        disk.watches[1].lastUpdate = 3_000
        // Neues Paar nur auf der Platte: der Stand der App entscheidet (bleibt draussen)
        disk.watches.append(try decoder.decode(Watch.self, from: Data(#"{"id":3,"marketKey":"m","baseAsset":"A","quoteAsset":"B"}"#.utf8)))

        SharedStorage.mergeNewerPrices(from: disk, into: &app)
        XCTAssertEqual(app.watches.map(\.id), [1, 2])
        XCTAssertEqual(app.watches[0].lastPrice, 110)
        XCTAssertEqual(app.watches[0].lastUpdate, 2_000)
        XCTAssertEqual(app.watches[0].notifiedPrice, 105)
        XCTAssertEqual(app.watches[1].lastPrice, 10)
        XCTAssertEqual(app.watches[1].baseAsset, "ETH")
    }

    // MARK: Einstellungen

    /// Unbekannte Akzentfarbe (neuere Version) setzt nur die Farbe zurück, nicht alle Einstellungen.
    func testUnknownAccentColorKeepsOtherSettings() throws {
        let json = #"{"accentColor":"PURPLE","backgroundIntervalMinutes":45,"darkMode":true}"#
        let s = try JSONDecoder().decode(AppSettings.self, from: Data(json.utf8))
        XCTAssertEqual(s.accentColor, AppSettings().accentColor)
        XCTAssertEqual(s.backgroundIntervalMinutes, 45)
        XCTAssertEqual(s.darkMode, true)
    }

    func testWrongTypeResetsOnlyThatSetting() throws {
        let json = #"{"liveService":"ja","backgroundIntervalMinutes":30}"#
        let s = try JSONDecoder().decode(AppSettings.self, from: Data(json.utf8))
        XCTAssertEqual(s.liveService, AppSettings().liveService)
        XCTAssertEqual(s.backgroundIntervalMinutes, 30)
    }

    // MARK: Zahlen aus der Sicherung

    func testInt64TruncatingRejectsNonFiniteAndHuge() {
        XCTAssertNil(BackupManager.int64Truncating(.nan))
        XCTAssertNil(BackupManager.int64Truncating(.infinity))
        XCTAssertNil(BackupManager.int64Truncating(-.infinity))
        XCTAssertNil(BackupManager.int64Truncating(1e300))
        XCTAssertNil(BackupManager.int64Truncating(-1e300))
        // 2^63 ist nicht mehr darstellbar
        XCTAssertNil(BackupManager.int64Truncating(9_223_372_036_854_775_808.0))
        XCTAssertEqual(BackupManager.int64Truncating(-9_223_372_036_854_775_808.0), Int64.min)
    }

    func testInt64TruncatingCutsTowardZero() {
        XCTAssertEqual(BackupManager.int64Truncating(3.9), 3)
        XCTAssertEqual(BackupManager.int64Truncating(-3.9), -3)
        XCTAssertEqual(BackupManager.int64Truncating(0), 0)
        XCTAssertEqual(BackupManager.int64Truncating(1_700_000_000_000), 1_700_000_000_000)
    }

    // MARK: Marktphasen-Prüfung

    func testZoneDue() {
        let h: Int64 = 3_600_000
        let now: Int64 = 1_000 * h
        XCTAssertTrue(ZoneSchedule.isDue(lastRunMillis: 0, now: now))
        XCTAssertFalse(ZoneSchedule.isDue(lastRunMillis: now - 11 * h, now: now))
        XCTAssertTrue(ZoneSchedule.isDue(lastRunMillis: now - 12 * h, now: now))
        // Uhr zurückgestellt: letzter Lauf in der Zukunft
        XCTAssertTrue(ZoneSchedule.isDue(lastRunMillis: now + h, now: now))
    }

    func testZoneEarliestBeginKeepsTwelveHourPlan() {
        let h: Int64 = 3_600_000
        let now: Int64 = 1_000 * h
        func millis(_ d: Date) -> Int64 { Int64((d.timeIntervalSince1970 * 1000).rounded()) }
        XCTAssertEqual(millis(ZoneSchedule.earliestBegin(lastRunMillis: now - 2 * h, now: now)), now + 10 * h)
        XCTAssertEqual(millis(ZoneSchedule.earliestBegin(lastRunMillis: 0, now: now)), now + 12 * h)
        // Überfällig: nicht sofort, sondern frühestens nach 15 Minuten
        XCTAssertEqual(millis(ZoneSchedule.earliestBegin(lastRunMillis: now - 30 * h, now: now)),
                       now + ZoneSchedule.minDelayMillis)
        XCTAssertEqual(millis(ZoneSchedule.earliestBegin(lastRunMillis: now + h, now: now)), now + 12 * h)
    }

    // MARK: Ende der Hintergrund-Aufgabe genau einmal

    func testOnceGateLetsExactlyOneThrough() {
        let gate = OnceGate()
        let lock = NSLock()
        var passed = 0
        DispatchQueue.concurrentPerform(iterations: 200) { _ in
            if gate.claim() {
                lock.lock()
                passed += 1
                lock.unlock()
            }
        }
        XCTAssertEqual(passed, 1)
        XCTAssertFalse(gate.claim())
    }
}
