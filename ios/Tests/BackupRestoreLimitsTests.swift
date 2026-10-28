import XCTest
@testable import CryptoChecker

/// Grenzen beim Wiederherstellen der Merkliste (`Parity/backup_limits.json`): unzulässige Kennungen
/// lassen das Paar wegfallen, Gruppe und Notiz werden wie die Eingabe gesäubert, höchstens so viele Paare und Alarme
/// je Paar wie festgelegt; Weggelassenes wird gezählt (Rückfrage und Erfolgsmeldung). Gegenstück: `BackupRestoreLimitsParityTest.kt`.
final class BackupRestoreLimitsTests: XCTestCase {
    private typealias S = TestSupport

    func testGrenzenWieGemeinsameTestfaelle() throws {
        let limits = try XCTUnwrap(try S.fixture("backup_limits")["limits"] as? [String: Any])
        XCTAssertEqual(S.number(limits["maxExchangeKey"]).map { Int($0) }, BackupRestoreLimits.maxExchangeKey)
        XCTAssertEqual(S.number(limits["maxExchangeName"]).map { Int($0) }, BackupRestoreLimits.maxExchangeName)
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
            Watch(id: Int64($0), exchangeKey: "Binance", exchangeName: "Binance", baseAsset: "B\($0)", quoteAsset: "USDT")
        }
        var alarms = (1...(BackupRestoreLimits.maxAlarmsPerPair + 3)).map {
            Alarm(id: Int64($0), watchId: 1, condition: .PRICE_ABOVE, threshold: 1)
        }
        alarms.append(Alarm(id: 1_000, watchId: 2, condition: .PRICE_ABOVE, threshold: 1))
        alarms.append(Alarm(id: 1_001, watchId: Int64(max + 1), condition: .PRICE_ABOVE, threshold: 1))
        let limited = BackupRestoreLimits.limit(watches: watches, alarms: alarms)
        let (keptWatches, keptAlarms) = (limited.watches, limited.alarms)
        XCTAssertEqual(keptWatches.count, max)
        XCTAssertEqual(keptWatches.last?.id, Int64(max))
        XCTAssertEqual(keptAlarms.filter { $0.watchId == 1 }.map(\.id), (1...BackupRestoreLimits.maxAlarmsPerPair).map { Int64($0) })
        XCTAssertEqual(keptAlarms.filter { $0.watchId == 2 }.map(\.id), [1_000])
        XCTAssertFalse(keptAlarms.contains { $0.watchId > Int64(max) })
        // Gezählt für Rückfrage und Erfolgsmeldung: 5 Paare zu viel, 3 Alarme über der Grenze und 1 eines weggelassenen Paars
        XCTAssertEqual(limited.droppedPairs, 5)
        XCTAssertEqual(limited.droppedAlarms, 4)
    }

    /// Paare mit unzulässiger Kennung fallen samt ihren Alarmen weg und werden gezählt; Alarme ohne Paar nicht.
    func testUnzulaessigePaareSamtAlarmenGezaehlt() {
        let watches = [
            Watch(id: 1, exchangeKey: "Binance", exchangeName: "Binance", baseAsset: "BTC", quoteAsset: "USDT", pairId: "BTCUSDT"),
            Watch(id: 2, exchangeKey: "Binance", exchangeName: "Binance", baseAsset: "ETH", quoteAsset: "USDT", pairId: "ETH USDT"),
            Watch(id: 3, exchangeKey: "Binance", exchangeName: "Binance", baseAsset: "SOL", quoteAsset: "USDT", pairId: "SOLUSDT"),
        ]
        let alarms = [(1, 1), (2, 2), (3, 2), (4, 9)].map {
            Alarm(id: Int64($0.0), watchId: Int64($0.1), condition: .PRICE_ABOVE, threshold: 1)
        }
        let accepted = BackupRestoreLimits.accept(watches: watches, alarms: alarms)
        XCTAssertEqual(accepted.watches.map(\.id), [1, 3])
        XCTAssertEqual(accepted.alarms.map(\.id), [1, 4])
        XCTAssertEqual(accepted.droppedPairs, 1)
        XCTAssertEqual(accepted.droppedAlarms, 2)
    }

    /// Ganze Merkliste wie beim Wiederherstellen (lesen, bereinigen, begrenzen): behaltene und wegen der Grenzen
    /// weggelassene Paare und Alarme wie in den gemeinsamen Testfällen «limitCases».
    func testWeggelasseneWieGemeinsameTestfaelle() throws {
        let cases = S.list(try S.fixture("backup_limits")["limitCases"])
        XCTAssertGreaterThanOrEqual(cases.count, 5)
        for c in cases {
            let name = c["description"] as? String ?? "?"
            let watchlist = try BackupManager.restoredWatchlist(limitCaseRoot(c))
            XCTAssertEqual(watchlist.watches.count, S.number(c["keptPairs"]).map { Int($0) }, name)
            XCTAssertEqual(watchlist.alarms.count, S.number(c["keptAlarms"]).map { Int($0) }, name)
            XCTAssertEqual(watchlist.droppedPairs, S.number(c["droppedPairs"]).map { Int($0) }, name)
            XCTAssertEqual(watchlist.droppedAlarms, S.number(c["droppedAlarms"]).map { Int($0) }, name)
            XCTAssertEqual(watchlist.invalidAlarms, 0, name)
        }
    }

    /// Sicherung zu einem Fall aus «limitCases» (Aufbau siehe «description» der Testfälle).
    private func limitCaseRoot(_ c: [String: Any]) -> [String: Any] {
        func ints(_ value: Any?) -> [Int64] { (value as? [Any] ?? []).compactMap { S.number($0).map { Int64($0) } } }
        let invalid = Set(ints(c["invalidPairIds"]))
        var sameAs: [Int64: Int64] = [:]
        for d in c["duplicates"] as? [Any] ?? [] {
            let pair = ints(d)
            sameAs[pair[0]] = pair[1]
        }
        let pairs = Int64(S.number(c["pairs"]) ?? 0)
        let watches: [[String: Any]] = (1...pairs).map { id in
            let base = "B\(sameAs[id] ?? id)"
            return ["id": NSNumber(value: id), "exchangeKey": "Binance", "exchangeName": "Binance", "baseAsset": base,
                    "quoteAsset": "USDT", "contractType": "NONE",
                    "pairId": invalid.contains(id) ? "\(base) USDT" : "\(base)USDT"]
        }
        var alarms: [[String: Any]] = []
        for spec in c["alarms"] as? [Any] ?? [] {
            let v = ints(spec)
            for _ in 0..<v[1] {
                alarms.append(["id": NSNumber(value: Int64(alarms.count + 1)), "watchId": NSNumber(value: v[0]),
                               "condition": "PRICE_ABOVE", "threshold": 1.0])
            }
        }
        return ["watches": watches, "alarms": alarms]
    }
}
