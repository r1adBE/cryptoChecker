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

    /// Ohne bekannten Namen: Binance nur bei eindeutigem Kürzel, sonst «ungeprüft»; mehrdeutig laut anderen Paarlisten:
    /// Binance-Kurs, aber «Kurs ungeprüft» — Fixture `portfolio_price_identity.json`.
    func testParityBinanceCheckAndSplit() throws {
        let data = try TestSupport.fixture("portfolio_price_identity")
        let names = (data["binanceName"] as? [String: String]) ?? [:]
        let rawOthers = (data["otherNames"] as? [String: [String]]) ?? [:]
        let others: [String: [String?]] = rawOthers.mapValues { $0.map { Optional($0) } }
        for c in TestSupport.list(data["checks"]) {
            let coin = c["coin"] as? String ?? ""
            let known = c["knownName"] as? String
            let got = PortfolioPriceIdentity.binanceCheck(coin, knownName: known, binanceName: names[coin],
                                                          otherNames: others[coin] ?? [])
            let want: PortfolioPriceIdentity.BinanceCheck
            switch c["check"] as? String {
            case "USE": want = .use
            case "SKIP": want = .skip
            case "UNVERIFIED": want = .unverified
            default: want = .unchecked
            }
            XCTAssertEqual(got, want, "\(coin) / \(known ?? "nil")")
        }
        let split = try XCTUnwrap(data["split"] as? [String: Any])
        let rawKnown = split["knownNames"] as? [String: Any] ?? [:]
        let known: [String: String?] = rawKnown.mapValues { $0 as? String }
        let got = PortfolioPriceIdentity.binanceSplit(split["coins"] as? [String] ?? [], knownNames: known,
                                                      binanceName: { names[$0] }, otherNames: { others[$0] ?? [] })
        XCTAssertEqual(got.use, split["use"] as? [String])
        XCTAssertEqual(got.unchecked, Set(split["unchecked"] as? [String] ?? []))
        XCTAssertEqual(got.unverified, Set(split["unverified"] as? [String] ?? []))
    }

    /// Ohne andere Paarlisten bleibt alles wie ohne diese Prüfung: kein Coin «Kurs ungeprüft».
    func testWithoutOtherListsNothingUnverified() {
        XCTAssertEqual(PortfolioPriceIdentity.binanceCheck("BEAM", knownName: nil, binanceName: "Beam Gaming"), .use)
        let split = PortfolioPriceIdentity.binanceSplit(["BTC", "BEAM"], knownNames: [:],
                                                        binanceName: { $0 == "BTC" ? "Bitcoin" : "Beam Gaming" })
        XCTAssertEqual(split.use, ["BTC", "BEAM"])
        XCTAssertTrue(split.unverified.isEmpty)
    }

    /// Widersprechen sich nur die anderen Listen (nicht Binance), ist das Kürzel trotzdem mehrdeutig.
    func testConflictAmongOtherListsIsAmbiguous() {
        XCTAssertTrue(PortfolioPriceIdentity.ambiguous("ABC", binanceName: "Alpha Beta", otherNames: ["Alpha Beta", "Zebra Quux"]))
        XCTAssertFalse(PortfolioPriceIdentity.ambiguous("ABC", binanceName: "Alpha Beta",
                                                        otherNames: ["Alpha Beta", "Alpha Beta Chain"]))
        // Name = Kürzel zählt nicht als Name
        XCTAssertFalse(PortfolioPriceIdentity.ambiguous("BEAM", binanceName: "Beam", otherNames: ["Beam", "Zebra Quux"]))
    }
}
