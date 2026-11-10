import XCTest
@testable import CryptoChecker

/// Kurs der Komplikation (`Parity/watch_price.json`): knapp für kleine Familien (`WatchPriceFormat.compactPrice`),
/// Zeile der rechteckigen Komplikation (`WatchPriceFormat.line`) und die Portfolio-Komplikation ohne Wert
/// (`WatchPortfolioOff`). Gegenstück: Android `WatchPriceParityTest.kt` (:wear).
final class WatchPriceFormatParityTests: XCTestCase {
    private typealias S = TestSupport

    private func language(_ id: String) -> String { String(id.split(separator: "_").first ?? "") }

    /// Tausender-Apostroph der Schweiz: Java/CLDR liefert U+2019 (’), Foundation das ASCII-Zeichen ('). Jede Uhr folgt
    /// ihrem Handy (gleiche Plattformdaten); verglichen wird darum ohne diesen einen Zeichenunterschied.
    private func same(_ text: String?) -> String? { text?.replacingOccurrences(of: "\u{2019}", with: "'") }

    private func cases(_ block: String) throws -> [[String: Any]] {
        let section = try XCTUnwrap(try S.fixture("watch_price")[block] as? [String: Any])
        let cases = S.list(section["cases"])
        XCTAssertFalse(cases.isEmpty)
        return cases
    }

    func testKursKnapp() throws {
        for c in try cases("compact") {
            let id = c["locale"] as? String ?? ""
            let value = try XCTUnwrap(S.number(c["value"]))
            let text = WatchPriceFormat.compactPrice(value, locale: Locale(identifier: id))
            XCTAssertEqual(same(text), same(c["expected"] as? String), "\(id) \(value)")
        }
    }

    func testKnappHoechstensSiebenZeichen() {
        for value in [84_450.12, 999_999, 2_234.56, 123.456, 0.02468, 0.0098765, 0.000012345, 999_000_000] {
            let text = WatchPriceFormat.compactPrice(value, locale: Locale(identifier: "en_US"))
            XCTAssertLessThanOrEqual(text.count, 7, "\(value) → \(text)")
        }
    }

    func testZeileDerRechteckigenKomplikation() throws {
        for c in try cases("line") {
            let id = c["locale"] as? String ?? ""
            let actual = WatchPriceFormat.line(coin: c["coin"] as? String ?? "", price: S.number(c["price"]),
                                               quote: c["quote"] as? String ?? "", change: S.number(c["change"]),
                                               detail: c["detail"] as? String, locale: Locale(identifier: id),
                                               language: language(id))
            XCTAssertEqual(same(actual), same(c["expected"] as? String), "\(c)")
        }
    }

    func testPortfolioOhneWert() throws {
        for c in try cases("portfolio_off") {
            let title = c["title"] as? String ?? ""
            let hint = c["hint"] as? String ?? ""
            XCTAssertEqual(WatchPortfolioOff.line(title: title, hint: hint), c["line"] as? String)
            XCTAssertEqual(WatchPortfolioOff.spoken(title: title, hint: hint), c["spoken"] as? String)
        }
    }

    /// Beide Komplikationen: klein der Kurs statt der Änderung, Portfolio ohne Wert sagt es (kein «—»).
    func testKomplikationenNutzenDieRegeln() throws {
        let root = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
        let price = try String(contentsOf: root.appendingPathComponent("WatchWidgets/WatchPriceWidget.swift"), encoding: .utf8)
        XCTAssertTrue(price.contains("compact: q.map { WatchText.compactPrice($0.price) } ?? \"—\""))
        XCTAssertTrue(price.contains("Text(entry.compact).foregroundStyle(valueStyle).widgetLabel(title)"))
        XCTAssertTrue(price.contains("quote: WatchPair.parse(coin)?.quote ?? WatchPair.usdt"))
        XCTAssertFalse(price.contains("Text(entry.change).foregroundStyle(valueStyle)"))
        let portfolio = try String(contentsOf: root.appendingPathComponent("WatchWidgets/WatchPortfolioWidget.swift"),
                                   encoding: .utf8)
        XCTAssertTrue(portfolio.contains("WatchText.t(\"watch_portfolio_turn_on\")"))
        XCTAssertTrue(portfolio.contains("WatchText.t(\"watch_portfolio_off\")"))
        XCTAssertTrue(portfolio.contains("WatchPortfolioOff.line(title: title, hint: hint)"))
    }
}
