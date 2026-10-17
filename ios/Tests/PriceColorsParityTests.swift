import XCTest
@testable import CryptoChecker

/// Gemeinsame Testfälle `price_colors.json`: Kursfarben je Schema × Akzent (Android:
/// `PriceColorsParityTest.kt`) — gleiche Werte auf beiden Plattformen.
final class PriceColorsParityTests: XCTestCase {

    private typealias S = TestSupport

    private func hex(_ color: UInt32) -> String { String(format: "%06X", color) }

    func testCases() throws {
        let cases = S.list(try S.fixture("price_colors")["cases"])
        XCTAssertEqual(cases.count, PriceColorScheme.allCases.count * (AccentColor.allCases.count + 1) * 8)
        for c in cases {
            let scheme = try XCTUnwrap(PriceColorScheme(rawValue: c["scheme"] as? String ?? ""))
            let accent: AccentColor? = (c["accent"] as? String).flatMap { AccentColor(rawValue: $0) }
            let dark = c["dark"] as? Bool ?? false
            let hc = c["highContrast"] as? Bool ?? false
            let inverted = c["inverted"] as? Bool ?? false
            let key = "\(scheme.rawValue)/\(accent?.rawValue ?? "-")/dark=\(dark)/hc=\(hc)/inv=\(inverted)"
            XCTAssertEqual(hex(scheme.upHex(dark: dark, highContrast: hc, inverted: inverted, accent: accent)),
                           c["up"] as? String, "\(key) up")
            XCTAssertEqual(hex(scheme.downHex(dark: dark, highContrast: hc, inverted: inverted, accent: accent)),
                           c["down"] as? String, "\(key) down")
        }
    }

    func testAccents() throws {
        let data = try S.fixture("price_colors")
        XCTAssertEqual(S.number(data["minAccentDeltaE"]), PriceColorScheme.minAccentDeltaE)
        let accents = try XCTUnwrap(data["accents"] as? [String: [String: Any]])
        XCTAssertEqual(Set(accents.keys), Set(AccentColor.allCases.map(\.rawValue)))
        for accent in AccentColor.allCases {
            XCTAssertEqual(accents[accent.rawValue]?["light"] as? String, hex(accent.primaryHex(dark: false)))
            XCTAssertEqual(accents[accent.rawValue]?["dark"] as? String, hex(accent.primaryHex(dark: true)))
        }
    }
}
