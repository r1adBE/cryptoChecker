import XCTest
@testable import CryptoChecker

/// CIEDE2000 gegen die Testpaare von Sharma, Wu, Dalal (2005) und sRGB-Paare — gemeinsame
/// Testfälle `price_colors.json` (Android: `ColorDifferenceTest.kt`).
final class ColorDifferenceTests: XCTestCase {

    private typealias S = TestSupport

    private func lab(_ value: Any?) throws -> ColorDifference.Lab {
        let v: [Double] = try XCTUnwrap((value as? [Any])?.compactMap { S.number($0) })
        XCTAssertEqual(v.count, 3)
        return ColorDifference.Lab(l: v[0], a: v[1], b: v[2])
    }

    func testSharmaPairs() throws {
        let pairs = S.list(try S.fixture("price_colors")["deltaE2000"])
        XCTAssertEqual(pairs.count, 34)
        for (i, p) in pairs.enumerated() {
            let expected: Double = try XCTUnwrap(S.number(p["expected"]))
            let a = try lab(p["lab1"])
            let b = try lab(p["lab2"])
            XCTAssertEqual(ColorDifference.deltaE2000(a, b), expected, accuracy: 1e-4, "Paar \(i + 1)")
            // Symmetrisch
            XCTAssertEqual(ColorDifference.deltaE2000(b, a), expected, accuracy: 1e-4, "Paar \(i + 1) getauscht")
        }
    }

    func testSrgbPairs() throws {
        for p in S.list(try S.fixture("price_colors")["srgb"]) {
            let a: UInt32 = try XCTUnwrap(UInt32(p["a"] as? String ?? "", radix: 16))
            let b: UInt32 = try XCTUnwrap(UInt32(p["b"] as? String ?? "", radix: 16))
            let expected: Double = try XCTUnwrap(S.number(p["expected"]))
            XCTAssertEqual(ColorDifference.deltaE2000(a, b), expected, accuracy: 1e-3, "\(p["a"] ?? "")/\(p["b"] ?? "")")
        }
    }

    func testLabOfWhiteBlackGrey() {
        let white = ColorDifference.lab(0xFFFFFF)
        XCTAssertEqual(white.l, 100, accuracy: 0.01)
        // Gerundete Matrix: Weiss liegt knapp neben a = b = 0
        XCTAssertEqual(white.a, 0, accuracy: 0.02)
        XCTAssertEqual(white.b, 0, accuracy: 0.02)
        XCTAssertEqual(ColorDifference.lab(0x000000).l, 0, accuracy: 1e-9)
        let grey = ColorDifference.lab(0x777777)
        XCTAssertEqual(grey.a, 0, accuracy: 0.01)
        XCTAssertEqual(grey.b, 0, accuracy: 0.01)
        XCTAssertGreaterThan(ColorDifference.deltaE2000(0x000000, 0xFFFFFF), 99)
    }
}
