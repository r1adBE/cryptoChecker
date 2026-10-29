import XCTest
@testable import CryptoChecker

/// Prozent auf der Uhr wie in der iPhone-App (`Parity/percent_text.json`): dieselben Fälle wie `PercentText` und die
/// Änderung der Uhr samt Richtungs-Insel in RTL-Sprachen (`watch_change`). Gegenstück: Android
/// `WatchPercentParityTest.kt` (:wear).
final class WatchPercentParityTests: XCTestCase {
    private typealias S = TestSupport

    private func language(_ id: String) -> String { String(id.split(separator: "_").first ?? "") }

    func testProzentWieDieIphoneApp() throws {
        let cases = S.list(try S.fixture("percent_text")["cases"])
        XCTAssertFalse(cases.isEmpty)
        for c in cases {
            let id = c["locale"] as? String ?? ""
            let value = try XCTUnwrap(S.number(c["value"]))
            let decimals = Int(try XCTUnwrap(S.number(c["decimals"])))
            let locale = Locale(identifier: id)
            let actual = (c["kind"] as? String) == "signed"
                ? WatchPercent.signed(value, decimals: decimals, locale: locale)
                : WatchPercent.unsigned(value, decimals: decimals, locale: locale)
            XCTAssertEqual(actual, c["expected"] as? String, "\(id) \(value)")
        }
    }

    func testAenderungDerUhr() throws {
        let block = try XCTUnwrap(try S.fixture("percent_text")["watch_change"] as? [String: Any])
        let cases = S.list(block["cases"])
        XCTAssertFalse(cases.isEmpty)
        for c in cases {
            let id = c["locale"] as? String ?? ""
            let value = S.number(c["value"])
            XCTAssertEqual(WatchPercent.change(value, locale: Locale(identifier: id), language: language(id)),
                           c["expected"] as? String, "\(id) \(String(describing: value))")
        }
    }
}
