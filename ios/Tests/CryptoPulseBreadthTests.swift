import XCTest
@testable import CryptoChecker

/// Marktbreite («Top 30») in «Was gerade auffällt» — gleiche Fälle wie Android `CryptoPulseBreadthTest`.
final class CryptoPulseBreadthTests: XCTestCase {

    private func changes(up: Int, down: Int, flat: Int = 0) -> [Double] {
        Array(repeating: 1.0, count: up) + Array(repeating: -1.0, count: down) + Array(repeating: 0.0, count: flat)
    }

    func testCountsUpDownAndTotal() {
        XCTAssertEqual(CryptoPulse.breadth(changes(up: 22, down: 7, flat: 1)), PulseBreadth(up: 22, down: 7, total: 30))
    }

    func testUsesOnlyTheLargestThirtyAndDropsInvalid() {
        let list = [Double.nan] + changes(up: 30, down: 0) + changes(up: 0, down: 10)
        XCTAssertEqual(CryptoPulse.breadth(list), PulseBreadth(up: 30, down: 0, total: 30))
    }

    func testTooFewValuesGiveNothing() {
        XCTAssertNil(CryptoPulse.breadth(changes(up: 10, down: 4)))
        XCTAssertEqual(CryptoPulse.breadth(changes(up: 10, down: 5))?.total, 15)
    }

    func testNoteBroadAndNarrow() {
        XCTAssertEqual(CryptoPulse.breadthNote(PulseBreadth(up: 23, down: 7, total: 30), btc24h: 0.2), .broadUp)
        XCTAssertEqual(CryptoPulse.breadthNote(PulseBreadth(up: 5, down: 25, total: 30), btc24h: -0.2), .broadDown)
        XCTAssertEqual(CryptoPulse.breadthNote(PulseBreadth(up: 10, down: 20, total: 30), btc24h: 2.0), .narrowUp)
        XCTAssertEqual(CryptoPulse.breadthNote(PulseBreadth(up: 21, down: 9, total: 30), btc24h: -1.5), .narrowDown)
        XCTAssertNil(CryptoPulse.breadthNote(PulseBreadth(up: 16, down: 14, total: 30), btc24h: 0.5))
        XCTAssertNil(CryptoPulse.breadthNote(PulseBreadth(up: 10, down: 20, total: 30), btc24h: 0.9))
    }

    func testReportCarriesBreadthAndMarket() throws {
        let input = PulseInput(btc: 2.0, eth: 1.0, sol: 0.5, volumeRatio: nil, fearGreed: nil, fundingPercent: nil,
                               ethGasGwei: nil, topChanges: changes(up: 24, down: 6),
                               marketCapUsd: 3.4e12, marketCap24h: 2.1)
        let report = try XCTUnwrap(CryptoPulse.evaluate(input))
        XCTAssertEqual(report.breadth, PulseBreadth(up: 24, down: 6, total: 30))
        XCTAssertEqual(report.breadthNote, .broadUp)
        XCTAssertEqual(report.marketCapUsd, 3.4e12)
        XCTAssertEqual(report.marketCap24h, 2.1)
        var noCap = input
        noCap.marketCapUsd = nil
        XCTAssertNil(try XCTUnwrap(CryptoPulse.evaluate(noCap)).marketCap24h)
    }
}
