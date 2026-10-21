import XCTest
@testable import CryptoChecker

/// Obergrenzen der Merkliste — gleiche Fälle wie `WatchlistLimitsTest.kt`, dazu das Einfügen an der Grenze.
@MainActor
final class WatchlistLimitsTests: XCTestCase {

    func testGrenzenWieEntschieden() {
        XCTAssertEqual(WatchlistLimits.maxPairs, 2_000)
        XCTAssertEqual(WatchlistLimits.maxAlarmsPerPair, 10)
        // Eine Quelle: die Sicherung nimmt dieselben Grenzen
        XCTAssertEqual(BackupRestoreLimits.maxPairs, WatchlistLimits.maxPairs)
        XCTAssertEqual(BackupRestoreLimits.maxAlarmsPerPair, WatchlistLimits.maxAlarmsPerPair)
    }

    func testZehnterAlarmJaElfterNein() {
        XCTAssertTrue(WatchlistLimits.canAddAlarm(0))
        XCTAssertTrue(WatchlistLimits.canAddAlarm(9))
        XCTAssertFalse(WatchlistLimits.canAddAlarm(10))
        // Alte Daten über der Grenze: weiterhin kein neuer
        XCTAssertFalse(WatchlistLimits.canAddAlarm(12))
    }

    func testPaarNurUnterDerGrenze() {
        XCTAssertTrue(WatchlistLimits.canAddPair(0))
        XCTAssertTrue(WatchlistLimits.canAddPair(1_999))
        XCTAssertFalse(WatchlistLimits.canAddPair(2_000))
        XCTAssertFalse(WatchlistLimits.canAddPair(2_500))
    }

    func testFreiePlaetzeNieNegativ() {
        XCTAssertEqual(WatchlistLimits.freePairSlots(0), 2_000)
        XCTAssertEqual(WatchlistLimits.freePairSlots(1_999), 1)
        XCTAssertEqual(WatchlistLimits.freePairSlots(2_000), 0)
        XCTAssertEqual(WatchlistLimits.freePairSlots(2_100), 0)
    }

    private func pool(_ i: Int) -> MarketPair {
        MarketPair("T\(i)", "USD (ethereum)", "ethereum/0x\(i)")
    }

    func testVolleMerklisteNimmtKeinNeuesPaarAberMeldetVorhandeneAlsDoppel() {
        var snapshot = SharedStorage.Snapshot()
        let market = DexScreener()
        for i in 0..<(WatchlistLimits.maxPairs - 1) {
            XCTAssertNotNil(AppData.insert(&snapshot, market: market, pair: pool(i)))
        }
        // Das 2000. Paar passt noch
        guard case .added = AppData.insertOutcome(&snapshot, market: market, pair: pool(1_999)) else {
            return XCTFail("2000. Paar nicht angelegt")
        }
        XCTAssertEqual(snapshot.watches.count, WatchlistLimits.maxPairs)
        // Das 2001. nicht; ein schon vorhandenes bleibt «schon da» (kein Fehler)
        XCTAssertEqual(AppData.insertOutcome(&snapshot, market: market, pair: pool(5_000)), .full)
        XCTAssertNil(AppData.insert(&snapshot, market: market, pair: pool(5_001)))
        XCTAssertEqual(AppData.insertOutcome(&snapshot, market: market, pair: pool(3)), .duplicate)
        XCTAssertEqual(snapshot.watches.count, WatchlistLimits.maxPairs)
    }
}
