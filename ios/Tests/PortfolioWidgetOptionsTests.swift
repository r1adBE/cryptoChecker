import XCTest
@testable import CryptoChecker

/// Gemeinsame Testfälle `widget_portfolio_options.json` (Android: `WidgetPortfolioOptionsParityTest.kt`).
final class PortfolioWidgetOptionsTests: XCTestCase {

    private typealias S = TestSupport

    private func view(_ raw: Any?) throws -> PortfolioWidgetMode {
        try XCTUnwrap(PortfolioWidgetMode(rawValue: raw as? String ?? ""), "Ansicht \(raw ?? "nil")")
    }

    private func display(_ raw: Any?) throws -> PortfolioWidgetDisplay {
        try XCTUnwrap(PortfolioWidgetDisplay(rawValue: raw as? String ?? ""), "Anzeige \(raw ?? "nil")")
    }

    private func range(_ raw: Any?) throws -> PortfolioWidgetRange {
        try XCTUnwrap(PortfolioWidgetRange(rawValue: raw as? String ?? ""), "Zeitraum \(raw ?? "nil")")
    }

    func testOffered() throws {
        let offered = try XCTUnwrap(try S.fixture("widget_portfolio_options")["offered"] as? [String: Any])
        XCTAssertEqual(offered.count, PortfolioWidgetMode.allCases.count)
        for (name, raw) in offered {
            let v = try view(name)
            let o = try XCTUnwrap(raw as? [String: Any])
            let displays = try (o["displays"] as? [Any] ?? []).map { try display($0) }
            let ranges = try (o["ranges"] as? [Any] ?? []).map { try range($0) }
            XCTAssertEqual(PortfolioWidgetOptions.displays(v), displays, "displays \(name)")
            XCTAssertEqual(PortfolioWidgetOptions.ranges(v), ranges, "ranges \(name)")
        }
    }

    func testCases() throws {
        let cases = S.list(try S.fixture("widget_portfolio_options")["cases"])
        // Alle Kombinationen Ansicht × Anzeige × Zeitraum
        XCTAssertEqual(cases.count, PortfolioWidgetMode.allCases.count * PortfolioWidgetDisplay.allCases.count
                       * PortfolioWidgetRange.allCases.count)
        for c in cases {
            let v = try view(c["view"])
            let label = "\(c["view"] ?? "") \(c["display"] ?? "") \(c["range"] ?? "")"
            XCTAssertEqual(PortfolioWidgetOptions.effectiveDisplay(v, try display(c["display"])),
                           try display(c["effectiveDisplay"]), "display \(label)")
            XCTAssertEqual(PortfolioWidgetOptions.effectiveRange(v, try range(c["range"])),
                           try range(c["effectiveRange"]), "range \(label)")
        }
    }

    /// Was wirkt, ist auch angeboten (Zeitraum nur, wo die Ansicht ihn nutzt).
    func testEffectiveIsAlwaysOffered() {
        for v in PortfolioWidgetMode.allCases {
            for d in PortfolioWidgetDisplay.allCases {
                XCTAssertTrue(PortfolioWidgetOptions.displays(v).contains(PortfolioWidgetOptions.effectiveDisplay(v, d)))
            }
            guard v.usesRange else { continue }
            for r in PortfolioWidgetRange.allCases {
                XCTAssertTrue(PortfolioWidgetOptions.ranges(v).contains(PortfolioWidgetOptions.effectiveRange(v, r)))
            }
        }
    }
}
