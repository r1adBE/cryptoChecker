import XCTest
@testable import CryptoChecker

/// Merkliste-Widget: Logos und Zeitpunkte nur für die Zeilen, die es zeigen kann (`WidgetRows`).
final class WidgetRowsTests: XCTestCase {

    private func watches(_ count: Int, marketKey: String = "kraken") -> [Watch] {
        (0..<count).map { index in
            Watch(id: Int64(index + 1), marketKey: marketKey, marketName: "Kraken", baseAsset: "C\(index)", quoteAsset: "EUR")
        }
    }

    func testZweitausendPaareHoechstensZehnLogoSchluessel() {
        let keys = WidgetRows.logoKeys(for: watches(2000))
        XCTAssertLessThanOrEqual(keys.count, WidgetRows.maxListRows)
        XCTAssertEqual(keys.count, 10)
    }

    func testGezeigtSindDieErstenPaareInGleicherReihenfolge() {
        let list = watches(25)
        XCTAssertEqual(WidgetRows.shown(list).map(\.id), Array(list.prefix(10)).map(\.id))
        XCTAssertEqual(WidgetRows.shown(list, rows: WidgetRows.mediumListRows).count, 4)
        XCTAssertEqual(WidgetRows.shown(watches(3)).count, 3)
    }

    func testDexPoolsOhneLogo() {
        XCTAssertTrue(WidgetRows.logoKeys(for: watches(5, marketKey: CoinLogos.dexMarketKey)).isEmpty)
    }
}
