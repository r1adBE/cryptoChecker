import XCTest
@testable import CryptoChecker

/// Alarm-Sperre für dünne DEX-Pools (unter 10’000 USD Liquidität oder unbekannt). Wie `DexLiquidityTest.kt`.
final class DexLiquidityTests: XCTestCase {
    func testThresholdIsTenThousandUsd() {
        XCTAssertEqual(DexScreener.minAlarmLiquidityUsd, 10_000)
        XCTAssertTrue(DexScreener.alarmCapable(10_000))
        XCTAssertTrue(DexScreener.alarmCapable(45_678_901.23))
        XCTAssertFalse(DexScreener.alarmCapable(9_999.99))
        XCTAssertFalse(DexScreener.alarmCapable(0))
        // Unbekannt zählt wie zu wenig
        XCTAssertFalse(DexScreener.alarmCapable(nil))
        XCTAssertFalse(DexScreener.alarmCapable(.nan))
        XCTAssertFalse(DexScreener.alarmCapable(.infinity))
    }

    func testOnlyDexPoolsAreBlocked() {
        XCTAssertTrue(DexLiquidity.blocksAlarms(marketKey: "DexScreener", liquidityUsd: 5_000))
        XCTAssertTrue(DexLiquidity.blocksAlarms(marketKey: "DexScreener", liquidityUsd: nil))
        XCTAssertFalse(DexLiquidity.blocksAlarms(marketKey: "DexScreener", liquidityUsd: 25_000))
        // Börsen-Paare: nie gesperrt, auch ohne Liquidität
        XCTAssertFalse(DexLiquidity.blocksAlarms(marketKey: "Binance", liquidityUsd: nil))
        XCTAssertFalse(DexLiquidity.blocksAlarms(marketKey: "Kraken", liquidityUsd: 1))
    }

    func testKeyIgnoresCaseAndSpaces() {
        XCTAssertEqual(DexLiquidity.key(" ethereum/0xABC "), "ethereum/0xabc")
        XCTAssertNil(DexLiquidity.key("  "))
        XCTAssertNil(DexLiquidity.key(nil))
    }

    func testSearchKeepsSortingAndMarksThinPools() throws {
        let body = """
        {"pairs":[
            {"chainId":"bsc","dexId":"pancake","pairAddress":"0x1","baseToken":{"symbol":"THIN"},"quoteToken":{"symbol":"WBNB"},"priceUsd":"0.1","liquidity":{"usd":2500.5}},
            {"chainId":"ethereum","dexId":"uniswap","pairAddress":"0x2","baseToken":{"symbol":"BIG"},"quoteToken":{"symbol":"WETH"},"priceUsd":"1.5","liquidity":{"usd":250000}},
            {"chainId":"solana","dexId":"raydium","pairAddress":"0x3","baseToken":{"symbol":"NOLIQ"},"quoteToken":{"symbol":"SOL"},"priceUsd":"2"}
        ]}
        """
        let pools = try DexScreener.parseSearch(body)
        XCTAssertEqual(pools.map(\.baseSymbol), ["BIG", "THIN", "NOLIQ"])
        XCTAssertEqual(pools.map(\.alarmCapable), [true, false, false])
        XCTAssertEqual(pools[1].liquidityUsd, 2500.5)
        XCTAssertNil(pools[2].liquidityUsd)
    }
}
