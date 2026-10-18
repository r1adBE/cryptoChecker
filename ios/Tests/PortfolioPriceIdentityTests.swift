import XCTest
@testable import CryptoChecker

/// Binance-Ticker im Portfolio nur für denselben Token (K-8). Wie `PortfolioPriceIdentityTest.kt`.
final class PortfolioPriceIdentityTests: XCTestCase {
    private let binance = ["LUNA": "Terra", "TON": "Toncoin", "GMT": "STEPN", "HOT": "Holo", "BTC": "Bitcoin"]

    func testDifferentTokenSkipsBinance() {
        XCTAssertFalse(PortfolioPriceIdentity.binanceTickerAllowed("LUNA", watchName: "Terra Classic", binanceName: "Terra"))
        XCTAssertFalse(PortfolioPriceIdentity.binanceTickerAllowed("TON", watchName: "Tokamak Network", binanceName: "Toncoin"))
        XCTAssertFalse(PortfolioPriceIdentity.binanceTickerAllowed("GMT", watchName: "GoMining", binanceName: "STEPN"))
        XCTAssertFalse(PortfolioPriceIdentity.binanceTickerAllowed("HOT", watchName: "Hydro Protocol", binanceName: "Holo"))
    }

    func testSameOrUnknownNameKeepsBinance() {
        XCTAssertTrue(PortfolioPriceIdentity.binanceTickerAllowed("LUNA", watchName: "Terra 2.0", binanceName: "Terra"))
        XCTAssertTrue(PortfolioPriceIdentity.binanceTickerAllowed("TON", watchName: "The Open Network", binanceName: "Toncoin"))
        // Ohne Namen in der Merkliste bzw. bei Binance: wie bisher
        XCTAssertTrue(PortfolioPriceIdentity.binanceTickerAllowed("GMT", watchName: nil, binanceName: "STEPN"))
        XCTAssertTrue(PortfolioPriceIdentity.binanceTickerAllowed("GMT", watchName: "GoMining", binanceName: nil))
    }

    func testWatchNamesUseActivePairsAndConsensus() {
        let watches = [
            PortfolioPriceIdentity.WatchName(base: "luna", name: "Terra Classic"),
            PortfolioPriceIdentity.WatchName(base: "TON", name: "Tokamak Network"),
            PortfolioPriceIdentity.WatchName(base: "TON", name: "Toncoin", disabled: true),
            // Widersprüchliche Paare: unklar, welcher Token → kein Name
            PortfolioPriceIdentity.WatchName(base: "HOT", name: "Holo"),
            PortfolioPriceIdentity.WatchName(base: "HOT", name: "Hydro Protocol"),
        ]
        let coins = ["LUNA", "TON", "HOT", "BTC"]
        let names = PortfolioPriceIdentity.watchNames(coins, watches: watches)
        XCTAssertEqual(names["LUNA"] ?? nil, "Terra Classic")
        XCTAssertEqual(names["TON"] ?? nil, "Tokamak Network")
        XCTAssertNil(names["HOT"] ?? nil)
        XCTAssertNil(names["BTC"] ?? nil)
        let allowed = PortfolioPriceIdentity.binanceTickerCoins(coins, watchNames: names, binanceName: { self.binance[$0] })
        XCTAssertEqual(allowed, ["HOT", "BTC"])
    }
}
