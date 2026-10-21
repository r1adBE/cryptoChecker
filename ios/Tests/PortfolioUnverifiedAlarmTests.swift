import XCTest
@testable import CryptoChecker

/// «Kurs ungeprüft» (Binance-Kurs mit unbestätigtem Token, seit Build 101) zählt für die Portfolio-Alarme wie ein alter
/// Kurs: wesentlich → keine Prüfung und Hinweis mit dem Coin, unwesentlich (unter 1 %) → Alarme prüfen. Wie
/// `PortfolioUnverifiedAlarmTest.kt`.
final class PortfolioUnverifiedAlarmTests: XCTestCase {
    private func snapshot(unverified: [String]? = nil, unverifiedValue: Double? = nil,
                          stale: [String]? = nil, staleValue: Double? = nil) -> PortfolioWidgetSnapshot {
        var s = PortfolioWidgetSnapshot(total: 10_000, changeAmount: nil, changePercent: 1, currency: "USDT",
                                        updatedAt: 1, empty: false, totalUsdt: 10_000)
        s.stalePrices = stale?.count
        s.staleCoins = stale
        s.staleValueUsdt = staleValue
        s.unverifiedCoins = unverified
        s.unverifiedValueUsdt = unverifiedValue
        return s
    }

    func testMaterialUnverifiedPausesAlarms() {
        let s = snapshot(unverified: ["BEAM"], unverifiedValue: 500)
        let reading = s.alarmReading()
        XCTAssertEqual(reading.stalePrices, 0)
        XCTAssertEqual(reading.unverifiedCoins, ["BEAM"])
        XCTAssertEqual(reading.unverifiedValueUsdt, 500, accuracy: 1e-9)
        XCTAssertTrue(PortfolioAlarmLogic.staleMaterial(reading))
        XCTAssertTrue(PortfolioAlarmLogic.incomplete(.VALUE_ABOVE, reading))
        // Eigener Hinweis «Kurs ungeprüft für BEAM»
        XCTAssertEqual(s.alarmPause([.VALUE_ABOVE]), .priceUnverified(["BEAM"]))
    }

    func testImmaterialUnverifiedKeepsAlarms() {
        let s = snapshot(unverified: ["BEAM"], unverifiedValue: 50)
        XCTAssertFalse(PortfolioAlarmLogic.staleMaterial(s.alarmReading()))
        XCTAssertNil(s.alarmPause([.CHANGE_UP]))
    }

    func testUnverifiedAddsToStaleValueAndKeepsStaleWording() {
        // Je 60 USDT: einzeln unwesentlich, zusammen 1.2 % → wesentlich; Satz «kein aktueller Kurs» mit dem alten Coin
        let s = snapshot(unverified: ["BEAM"], unverifiedValue: 60, stale: ["PEPE"], staleValue: 60)
        XCTAssertTrue(PortfolioAlarmLogic.staleMaterial(s.alarmReading()))
        XCTAssertEqual(s.alarmPause([.VALUE_BELOW]), .noPrice(["PEPE"]))
    }
}
