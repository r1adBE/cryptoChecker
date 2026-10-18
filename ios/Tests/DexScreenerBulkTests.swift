import XCTest
@testable import CryptoChecker

/// Sammelabfrage von DexScreener: Pools derselben Chain in einer Anfrage (`/latest/dex/pairs/{chain}/{a,b,…}`),
/// höchstens 30 Adressen je Anfrage. Gleiche Fälle in `DexScreenerBulkTest.kt`.
final class DexScreenerBulkTests: XCTestCase {
    private let market = DexScreener()

    func testGroupsByChainSortedWithoutDuplicates() {
        let groups = DexScreener.bulkGroups([
            "solana/8sLbNZoA1cfnvMJLPfp98ZLAnFSYCFApfJKMbiXNLwxj",
            "ethereum/0xB",
            "ethereum/0xA",
            " ethereum/0xA ",
            // ohne Chain oder Adresse, mit Zeichen, die kodiert werden müssten: Einzelabfrage
            "noslash", "ethereum/", "/0xC", "ethereum/0x D", "ethereum/0xE,0xF", "ethereum/0x1/2",
        ])
        XCTAssertEqual(groups.map { $0.chain }, ["ethereum", "solana"])
        XCTAssertEqual(groups.map { $0.addresses }, [["0xA", "0xB"], ["8sLbNZoA1cfnvMJLPfp98ZLAnFSYCFApfJKMbiXNLwxj"]])
    }

    func testAtMostThirtyAddressesPerRequest() {
        let ids = (1...31).map { String(format: "base/0x%02d", $0) }
        let groups = DexScreener.bulkGroups(ids)
        XCTAssertEqual(groups.map { $0.addresses.count }, [30, 1])
        XCTAssertEqual(groups.first?.addresses.count, DexScreener.bulkMaxPools)
        XCTAssertEqual(groups.last?.addresses, ["0x31"])
    }

    func testRequestAddressesFollowTheGroups() {
        let ids = ["ethereum/0xB", "ethereum/0xA", "bsc/0xC"]
        XCTAssertEqual(market.bulkTickersNumOfRequests, 1)
        XCTAssertEqual(market.bulkTickersRequestCount(pairIds: ids), 2)
        XCTAssertEqual(market.bulkTickersURL(call: 0, pairIds: ids), "https://api.dexscreener.com/latest/dex/pairs/bsc/0xC")
        XCTAssertEqual(market.bulkTickersURL(call: 1, pairIds: ids),
                       "https://api.dexscreener.com/latest/dex/pairs/ethereum/0xA,0xB")
        XCTAssertNil(market.bulkTickersURL(call: 2, pairIds: ids))
        // Keine ungefilterte Liste; ein fehlender Pool geht in die Einzelabfrage
        XCTAssertNil(market.bulkTickersURL(call: 0))
        XCTAssertFalse(market.bulkTickersComplete)
        XCTAssertEqual(market.bulkTickersRequestCount(pairIds: ["invalid"]), 0)
    }
}
