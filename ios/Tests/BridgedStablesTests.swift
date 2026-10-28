import XCTest
@testable import CryptoChecker

/// Gebrückte Stablecoins (USDC.e, USDbC, USDT.e) zählen zum Basis-Stablecoin, die Position trägt «≈». Wie Android.
final class BridgedStablesTests: XCTestCase {
    private func evm(countInPortfolio: Bool = true, tokens: [String: String]? = nil) -> WatchedAddress {
        WatchedAddress(chain: .ETH, address: "0x1111111111111111111111111111111111111111",
                       tokenBalances: tokens ?? [
                           "USDC": "100",
                           "ARBITRUM:USDC.e": "50",
                           "BASE:USDbC": "25",
                           "AVALANCHE:USDT.e": "10",
                           "AVALANCHE:USDT": "5",
                           // Gleiches Symbol (Binance-Peg «USDC» auf BSC) und Guthaben 0 zählen nicht als gebrückt
                           "BSC:USDC": "7",
                           "OPTIMISM:USDC.e": "0",
                       ],
                       countInPortfolio: countInPortfolio)
    }

    func testBridgedVariantsAreListedPerBaseStable() {
        let bridged = AddressBalance.bridgedStables([evm()])
        XCTAssertEqual(bridged, ["USDC": ["USDC.e", "USDbC"], "USDT": ["USDT.e"]])
        // Summe bleibt zusammengezählt, nur markiert
        let holdings = Dictionary(uniqueKeysWithValues: AddressBalance.holdings([evm()]).map { ($0.coin, $0.amount) })
        XCTAssertEqual(holdings["USDC"], "182")
        XCTAssertEqual(holdings["USDT"], "15")
    }

    func testNotCountedOrWithoutBridgedNothingMarked() {
        XCTAssertTrue(AddressBalance.bridgedStables([evm(countInPortfolio: false)]).isEmpty)
        XCTAssertTrue(AddressBalance.bridgedStables([evm(tokens: ["USDC": "1"])]).isEmpty)
    }

    func testIsBridgedStable() {
        XCTAssertTrue(ChainAssets.isBridgedStable(label: "USDC.e", coin: "USDC"))
        XCTAssertTrue(ChainAssets.isBridgedStable(label: "USDbC", coin: "USDC"))
        XCTAssertFalse(ChainAssets.isBridgedStable(label: "USDC", coin: "USDC"))
        // Gekapselte Nicht-Stablecoins laufen über «≈ BTC-Kurs» (priceViaBase), nicht hierüber
        XCTAssertFalse(ChainAssets.isBridgedStable(label: "WBTC", coin: "BTC"))
    }
}
