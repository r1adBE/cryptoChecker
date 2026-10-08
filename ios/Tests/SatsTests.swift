import XCTest
@testable import CryptoChecker

/// Wie `SatsTest.kt`: «1 CHF = … Sats» im Aktionsblatt eines Bitcoin-Paars.
final class SatsTests: XCTestCase {

    func testOnlyBaseBtcCounts() {
        XCTAssertTrue(Sats.isBitcoin("BTC"))
        XCTAssertTrue(Sats.isBitcoin(" btc "))
        XCTAssertFalse(Sats.isBitcoin("WBTC"))
        XCTAssertFalse(Sats.isBitcoin("ETH"))
        XCTAssertFalse(Sats.isBitcoin(nil))
    }

    func testSatsPerUnitUsesConvertedPrice() throws {
        // BTC/USDT 100’000, 1 USDT = 0.8 CHF → 80’000 CHF → 1’250 Sats je CHF
        XCTAssertEqual(try XCTUnwrap(Sats.perUnit(price: 100_000, rate: 0.8)), 1_250, accuracy: 1e-9)
        XCTAssertEqual(try XCTUnwrap(Sats.perUnit(price: 100_000, rate: 1)), 1_000, accuracy: 1e-9)
    }

    func testInvalidInputsGiveNothing() {
        XCTAssertNil(Sats.perUnit(price: nil, rate: 1))
        XCTAssertNil(Sats.perUnit(price: 100_000, rate: nil))
        XCTAssertNil(Sats.perUnit(price: 0, rate: 1))
        XCTAssertNil(Sats.perUnit(price: -5, rate: 1))
        XCTAssertNil(Sats.perUnit(price: 100_000, rate: 0))
        XCTAssertNil(Sats.perUnit(price: .nan, rate: 1))
        XCTAssertNil(Sats.perUnit(price: 100_000, rate: .infinity))
    }

    func testDecimalsFollowSize() {
        XCTAssertEqual(Sats.decimals(1_250), 0)
        XCTAssertEqual(Sats.decimals(100), 0)
        XCTAssertEqual(Sats.decimals(6.7), 1)
        XCTAssertEqual(Sats.decimals(0.0625), 3)
    }
}
