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

    func testSearchShowsTokenAddressAndRanksAddressMatchesFirst() throws {
        // Zwei «PEPE»: der liquidere ist ein anderer Token; dazu ein Pool mit dem gesuchten Token nur als Gegenwährung
        let body = """
        {"pairs":[
            {"chainId":"ethereum","dexId":"uniswap","pairAddress":"0xp1","baseToken":{"symbol":"PEPE","address":"0x1111111111111111111111111111111111112222"},"quoteToken":{"symbol":"WETH"},"priceUsd":"0.1","liquidity":{"usd":900000}},
            {"chainId":"ethereum","dexId":"uniswap","pairAddress":"0xp2","baseToken":{"symbol":"WETH","address":"0xC02aaA39b223FE8D0A0e5C4F27eAD9083C756Cc2"},"quoteToken":{"symbol":"PEPE","address":"0x6982508145454Ce325dDbE47a25d4ec3d2311933"},"priceUsd":"2500","liquidity":{"usd":500000}},
            {"chainId":"ethereum","dexId":"uniswap","pairAddress":"0xp3","baseToken":{"symbol":"PEPE","address":"0x6982508145454Ce325dDbE47a25d4ec3d2311933"},"quoteToken":{"symbol":"WETH"},"priceUsd":"0.00001","liquidity":{"usd":40000}},
            {"chainId":"solana","dexId":"raydium","pairAddress":"p4","baseToken":{"symbol":"NOADDR"},"quoteToken":{"symbol":"SOL"},"priceUsd":"1"}
        ]}
        """
        let byLiquidity = try DexScreener.parseSearch(body)
        XCTAssertEqual(byLiquidity.map(\.pairAddress), ["0xp1", "0xp2", "0xp3", "p4"])
        XCTAssertEqual(byLiquidity[0].shortBaseAddress, "0x1111…2222")
        XCTAssertEqual(byLiquidity[2].shortBaseAddress, "0x6982…1933")
        XCTAssertEqual(byLiquidity[3].shortBaseAddress, "")
        // Suche per Vertragsadresse (andere Schreibweise): Pools mit dem Token als Basis zuerst, Rest nach Liquidität
        let byAddress = try DexScreener.parseSearch(body, query: " 0x6982508145454ce325ddbe47a25d4ec3d2311933 ")
        XCTAssertEqual(byAddress.map(\.pairAddress), ["0xp3", "0xp1", "0xp2", "p4"])
        // Kurze Adressen bleiben ganz
        XCTAssertEqual(DexScreener.shortAddress(" So11111 "), "So11111")
        XCTAssertEqual(DexScreener.shortAddress("123456789012"), "123456789012")
        XCTAssertEqual(DexScreener.shortAddress("1234567890123"), "123456…0123")
    }
}
