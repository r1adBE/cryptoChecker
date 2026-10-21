import XCTest
@testable import CryptoChecker

/// Grenzen beim Wiederherstellen der Merkliste (`Parity/backup_limits.json`, seit Build 99): unzulässige Kennungen
/// lassen das Paar wegfallen, Gruppe und Notiz werden wie die Eingabe gesäubert, höchstens so viele Paare und Alarme
/// je Paar wie festgelegt. Gegenstück: `BackupRestoreLimitsParityTest.kt`.
final class BackupRestoreLimitsTests: XCTestCase {
    private typealias S = TestSupport

    func testGrenzenWieGemeinsameTestfaelle() throws {
        let limits = try XCTUnwrap(try S.fixture("backup_limits")["limits"] as? [String: Any])
        XCTAssertEqual(S.number(limits["maxMarketKey"]).map { Int($0) }, BackupRestoreLimits.maxMarketKey)
        XCTAssertEqual(S.number(limits["maxMarketName"]).map { Int($0) }, BackupRestoreLimits.maxMarketName)
        XCTAssertEqual(S.number(limits["maxAsset"]).map { Int($0) }, BackupRestoreLimits.maxAsset)
        XCTAssertEqual(S.number(limits["maxPairId"]).map { Int($0) }, BackupRestoreLimits.maxPairId)
        XCTAssertEqual(S.number(limits["maxPairs"]).map { Int($0) }, BackupRestoreLimits.maxPairs)
        XCTAssertEqual(S.number(limits["maxAlarmsPerPair"]).map { Int($0) }, BackupRestoreLimits.maxAlarmsPerPair)
        XCTAssertEqual(S.number(limits["groupNameMax"]).map { Int($0) }, Watch.groupNameMax)
        XCTAssertEqual(S.number(limits["noteMax"]).map { Int($0) }, Watch.noteMax)
    }

    func testPaareWieGemeinsameTestfaelle() throws {
        let cases = S.list(try S.fixture("backup_limits")["watches"])
        XCTAssertGreaterThanOrEqual(cases.count, 20)
        for c in cases {
            let name = c["description"] as? String ?? "?"
            let o = try XCTUnwrap(c["watch"] as? [String: Any], name)
            let watch = try BackupManager.jsonToWatch(o)
            XCTAssertEqual(BackupRestoreLimits.acceptsWatch(watch), c["kept"] as? Bool, name)
            XCTAssertEqual(watch.groupName, c["group"] as? String, name)
            XCTAssertEqual(watch.note, c["note"] as? String, name)
        }
    }

    /// Erste Paare der Datei bleiben; Alarme weggelassener Paare fallen weg, je Paar nur die ersten.
    func testHoechstensSovielePaareUndAlarmeJePaar() {
        let max = BackupRestoreLimits.maxPairs
        let watches = (1...(max + 5)).map {
            Watch(id: Int64($0), marketKey: "Binance", marketName: "Binance", baseAsset: "B\($0)", quoteAsset: "USDT")
        }
        var alarms = (1...(BackupRestoreLimits.maxAlarmsPerPair + 3)).map {
            Alarm(id: Int64($0), watchId: 1, condition: .PRICE_ABOVE, threshold: 1)
        }
        alarms.append(Alarm(id: 1_000, watchId: 2, condition: .PRICE_ABOVE, threshold: 1))
        alarms.append(Alarm(id: 1_001, watchId: Int64(max + 1), condition: .PRICE_ABOVE, threshold: 1))
        let (keptWatches, keptAlarms) = BackupRestoreLimits.limit(watches: watches, alarms: alarms)
        XCTAssertEqual(keptWatches.count, max)
        XCTAssertEqual(keptWatches.last?.id, Int64(max))
        XCTAssertEqual(keptAlarms.filter { $0.watchId == 1 }.map(\.id), (1...BackupRestoreLimits.maxAlarmsPerPair).map { Int64($0) })
        XCTAssertEqual(keptAlarms.filter { $0.watchId == 2 }.map(\.id), [1_000])
        XCTAssertFalse(keptAlarms.contains { $0.watchId > Int64(max) })
    }
}
