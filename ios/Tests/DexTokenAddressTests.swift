import XCTest
@testable import CryptoChecker

/// Token-Adresse der DEX-Pools fürs Paar-Blatt (`DexTokenAddress`). Wie `DexTokenAddressTest.kt`.
final class DexTokenAddressTests: XCTestCase {

    private let evm = "0x6982508145454Ce325dDbE47a25d4ec3d2311933"

    func testCleanedKeepsRealAddressesAndDropsJunk() {
        XCTAssertEqual(DexTokenAddress.cleaned("  \(evm) "), evm)
        XCTAssertEqual(DexTokenAddress.cleaned("So11111111111111111111111111111111111111112"),
                       "So11111111111111111111111111111111111111112")
        XCTAssertNil(DexTokenAddress.cleaned(nil))
        XCTAssertNil(DexTokenAddress.cleaned("   "))
        XCTAssertNil(DexTokenAddress.cleaned("0x12 34"))
        XCTAssertNil(DexTokenAddress.cleaned(String(repeating: "a", count: DexTokenAddress.maxLength + 1)))
        XCTAssertEqual(DexTokenAddress.cleaned(String(repeating: "a", count: DexTokenAddress.maxLength))?.count,
                       DexTokenAddress.maxLength)
    }

    func testShownIsShortened() {
        XCTAssertEqual(DexTokenAddress.shown(evm), "0x6982…1933")
        XCTAssertEqual(DexTokenAddress.shown("short"), "short")
    }

    func testSearchResultCarriesTheAddressUsedForTheSheet() throws {
        let body = """
        {"pairs":[{"chainId":"ethereum","dexId":"uniswap","pairAddress":"0xA43fe16908251ee70EF74718545e4FE6C5cCEc9f",\
        "baseToken":{"address":"\(evm)","name":"Pepe","symbol":"PEPE"},"quoteToken":{"symbol":"WETH"},\
        "priceUsd":"0.00001","liquidity":{"usd":1000000}}]}
        """
        let pools = try DexScreener.parseSearch(body, query: "pepe")
        XCTAssertEqual(pools.count, 1)
        XCTAssertEqual(DexTokenAddress.cleaned(pools.first?.baseAddress), evm)
        // Schlüssel wie die Liquidität: Kennung ohne Gross-/Kleinschreibung
        XCTAssertEqual(DexLiquidity.key(pools.first?.pairId), "ethereum/0xa43fe16908251ee70ef74718545e4fe6c5ccec9f")
    }
}
