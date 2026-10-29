import XCTest
@testable import CryptoChecker

/// DEX-Namen in der Coin-Suche (`Parity/dex_names.json`): «uniswap pepe» → DexScreener mit Hinweis «Pools von
/// Uniswap …» und «pepe» vorbelegt. Gegenstück: `DexNamesParityTest.kt`.
final class DexNamesParityTests: XCTestCase {
    private typealias S = TestSupport

    func testNamenUndMindestlaengeWieGemeinsameListe() throws {
        let data = try S.fixture("dex_names")
        XCTAssertEqual(data["named"] as? [String], DexNames.named)
        XCTAssertEqual(data["generic"] as? [String], DexNames.generic)
        XCTAssertEqual(S.number(data["minPrefix"]).map { Int($0) }, DexNames.minPrefix)
    }

    func testTrefferWieGemeinsameFaelle() throws {
        let cases = S.list(try S.fixture("dex_names")["cases"])
        XCTAssertGreaterThanOrEqual(cases.count, 20)
        for c in cases {
            let query = try XCTUnwrap(c["query"] as? String)
            let actual = DexNames.match(query)
            if try XCTUnwrap(c["match"] as? Bool) {
                let expected = DexNames.Match(dexName: c["dex"] as? String, token: try XCTUnwrap(c["token"] as? String))
                XCTAssertEqual(actual, expected, query)
            } else {
                XCTAssertNil(actual, query)
            }
        }
    }

    /// Jeder Name findet sich selbst, auch klein geschrieben und schon mit den ersten drei Zeichen.
    func testJederNameFindetSich() {
        for name in DexNames.named {
            XCTAssertEqual(DexNames.match(name)?.dexName, name)
            XCTAssertEqual(DexNames.match(name.lowercased())?.dexName, name)
            XCTAssertEqual(DexNames.match(name)?.token, "", name)
            XCTAssertEqual(DexNames.match(String(name.prefix(DexNames.minPrefix)))?.dexName, name)
        }
        for name in DexNames.generic {
            XCTAssertEqual(DexNames.match(name), DexNames.Match(dexName: nil, token: ""), name)
        }
    }
}
