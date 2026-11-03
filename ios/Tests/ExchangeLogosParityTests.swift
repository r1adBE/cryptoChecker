import XCTest
@testable import CryptoChecker

/// Börsen-Logos (`exchange_logos.json`): jede registrierte Börse hat genau ein mitgeliefertes Logo oder ein festes
/// Symbol; das Paket `exchange_logos.bin` der App enthält genau diese Logos, jedes als Bild lesbar.
/// Wie Android `ExchangeLogosParityTest.kt`.
final class ExchangeLogosParityTests: XCTestCase {

    private typealias S = TestSupport

    func testEveryExchangeHasLogoOrSymbol() throws {
        let data = try S.fixture("exchange_logos")
        let keys = try XCTUnwrap(data["keys"] as? [String: [String: Any]])
        // Die Testfälle nennen genau die registrierten Börsen (neue Börse → hier, in Android und im Generator eintragen)
        XCTAssertEqual(Set(ExchangesConfig.all.map(\.key)), Set(keys.keys))
        for (key, spec) in keys {
            XCTAssertEqual(ExchangeLogos.logoName(key), spec["logo"] as? String, key)
            XCTAssertEqual(ExchangeLogos.symbols[key]?.rawValue, spec["symbol"] as? String, key)
            XCTAssertTrue((ExchangeLogos.logoName(key) != nil) != (ExchangeLogos.symbols[key] != nil), key)
        }
        for group in (data["shared"] as? [[String]]) ?? [] {
            XCTAssertEqual(Set(group.map { ExchangeLogos.logoName($0) }).count, 1, "\(group)")
        }
        XCTAssertEqual(Set(ExchangeLogos.logos.values).count, S.number(data["distinct"]).map { Int($0) })
    }

    @MainActor
    func testPackContainsEveryLogo() throws {
        let url = try XCTUnwrap(Bundle.main.url(forResource: ExchangeLogos.packName, withExtension: ExchangeLogos.packExtension))
        let index = try XCTUnwrap(CoinLogos.parseIconPack(try Data(contentsOf: url)))
        XCTAssertEqual(Set(index.keys), Set(ExchangeLogos.logos.values))
        for name in Set(ExchangeLogos.logos.values) { XCTAssertNotNil(ExchangeLogoPack.image(name), name) }
    }
}
