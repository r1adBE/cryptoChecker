import XCTest
@testable import CryptoChecker

/// Ersatz-Kreis ohne Logo (`CoinLogoPlaceholder`), gemeinsame Testfälle `coin_placeholder.json` (Android:
/// `CoinLogoPlaceholderParityTest.kt`): dieselbe Palette, dieselbe Farbe je Kürzel und Schrift mit WCAG AA auf jeder
/// Füllung, hell und dunkel.
final class CoinLogoPlaceholderTests: XCTestCase {

    private typealias S = TestSupport

    private func hex(_ value: Any?) -> UInt32? {
        (value as? String).flatMap { UInt32($0, radix: 16) }
    }

    private func luminance(_ rgb: UInt32) -> Double {
        func channel(_ c: UInt32) -> Double {
            let s = Double(c) / 255
            return s <= 0.04045 ? s / 12.92 : pow((s + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * channel((rgb >> 16) & 0xFF) + 0.7152 * channel((rgb >> 8) & 0xFF) + 0.0722 * channel(rgb & 0xFF)
    }

    private func ratio(_ a: UInt32, _ b: UInt32) -> Double {
        let la = luminance(a), lb = luminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    func testPaletteAsFixture() throws {
        let data = try S.fixture("coin_placeholder")
        XCTAssertEqual(((data["fillsLight"] as? [Any]) ?? []).compactMap(hex), CoinLogoPlaceholder.fillsLight)
        XCTAssertEqual(((data["fillsDark"] as? [Any]) ?? []).compactMap(hex), CoinLogoPlaceholder.fillsDark)
        XCTAssertEqual(hex(data["textLight"]), CoinLogoPlaceholder.textLight)
        XCTAssertEqual(hex(data["textDark"]), CoinLogoPlaceholder.textDark)
        XCTAssertEqual(S.number(data["fadeMillis"]).map(Int.init), CoinLogoPlaceholder.fadeMillis)
    }

    func testInitialsReachAAOnEveryFill() throws {
        let min = try XCTUnwrap(S.number(try S.fixture("coin_placeholder")["minContrast"]))
        for dark in [false, true] {
            for fill in dark ? CoinLogoPlaceholder.fillsDark : CoinLogoPlaceholder.fillsLight {
                let r = ratio(CoinLogoPlaceholder.textHex(dark: dark), fill)
                XCTAssertGreaterThanOrEqual(r, min, String(format: "dark=%d %06X", dark ? 1 : 0, fill))
            }
        }
    }

    func testColourFollowsTheSymbol() throws {
        for c in S.list(try S.fixture("coin_placeholder")["cases"]) {
            let symbol = c["symbol"] as? String ?? ""
            XCTAssertEqual(CoinLogoPlaceholder.key(symbol), c["key"] as? String, "key \(symbol)")
            let index = try XCTUnwrap(S.number(c["index"]).map(Int.init))
            XCTAssertEqual(CoinLogoPlaceholder.index(symbol), index, "index \(symbol)")
            XCTAssertEqual(CoinLogoPlaceholder.fillHex(symbol, dark: true), CoinLogoPlaceholder.fillsDark[index])
            XCTAssertEqual(CoinLogoPlaceholder.fillHex(symbol, dark: false), CoinLogoPlaceholder.fillsLight[index])
        }
    }

    func testDifferentSymbolsSpreadOverThePalette() {
        let used = Set(["BTC", "ETH", "SOL", "XRP", "DOGE", "ADA", "BNB", "TRX", "LINK", "AVAX", "DOT", "SUI"]
            .map(CoinLogoPlaceholder.index))
        XCTAssertGreaterThanOrEqual(used.count, 5)
    }
}
