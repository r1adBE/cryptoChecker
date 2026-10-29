import XCTest
import SwiftUI
@testable import CryptoChecker

/// Wie `DenseRowTextTest.kt`.
final class DenseRowTextTests: XCTestCase {

    func testDefaultSizesSingleLineSideBySide() {
        for size in [DynamicTypeSize.xSmall, .small, .medium, .large, .xLarge] {
            let scale: Double = DenseRowText.scale(size)
            XCTAssertEqual(DenseRowText.maxLines(scale), 1, "\(size)")
            XCTAssertFalse(DenseRowText.stacksTrailing(scale), "\(size)")
        }
    }

    func testLargeSizesWrap() {
        for size in [DynamicTypeSize.xxLarge, .xxxLarge] {
            let scale: Double = DenseRowText.scale(size)
            XCTAssertEqual(DenseRowText.maxLines(scale), 2, "\(size)")
            XCTAssertFalse(DenseRowText.stacksTrailing(scale), "\(size)")
        }
        // Android 1.3 wie iOS xxLarge
        XCTAssertEqual(DenseRowText.maxLines(1.3), 2)
        XCTAssertFalse(DenseRowText.stacksTrailing(1.3))
    }

    func testAccessibilitySizesStackTheTrailingColumn() {
        for size in [DynamicTypeSize.accessibility1, .accessibility2, .accessibility3, .accessibility5] {
            let scale: Double = DenseRowText.scale(size)
            XCTAssertEqual(DenseRowText.maxLines(scale), 2, "\(size)")
            XCTAssertTrue(DenseRowText.stacksTrailing(scale), "\(size)")
        }
        // Android 1.5 / 1.8 / 2.0
        for scale in [1.5, 1.8, 2.0] { XCTAssertTrue(DenseRowText.stacksTrailing(scale)) }
    }

    /// Mini-Chart der Merkliste: 48 pt in Zeilen unter 360 pt, sonst 56 — wie Android.
    func testSparklineNarrowerInNarrowRows() {
        XCTAssertEqual(DenseRowText.sparklineWidth(rowWidth: 320), 48)
        XCTAssertEqual(DenseRowText.sparklineWidth(rowWidth: 359.5), 48)
        XCTAssertEqual(DenseRowText.sparklineWidth(rowWidth: 360), 56)
        XCTAssertEqual(DenseRowText.sparklineWidth(rowWidth: 412), 56)
    }

    /// Kurs eine Stufe grösser nur mit Platz (gestapelt immer) — wie Android.
    func testLargePriceOnlyWithRoom() {
        XCTAssertTrue(DenseRowText.largePrice(scale: 1, rowWidth: 360))
        XCTAssertTrue(DenseRowText.largePrice(scale: 1.15, rowWidth: 412))
        XCTAssertFalse(DenseRowText.largePrice(scale: 1, rowWidth: 320))
        XCTAssertFalse(DenseRowText.largePrice(scale: 1.3, rowWidth: 412))
        XCTAssertTrue(DenseRowText.largePrice(scale: 1.5, rowWidth: 320))
        XCTAssertTrue(DenseRowText.largePrice(scale: 2, rowWidth: 412))
    }

    /// Lange ≈ Zeile (Vermerk) höchstens halbe Zeile, Paar, Name und Börse bleiben sichtbar — wie Android.
    func testConvertedAtMostHalfTheRow() {
        XCTAssertEqual(DenseRowText.convertedMaxWidth(rowWidth: 411), 205.5, accuracy: 0.001)
        XCTAssertEqual(DenseRowText.convertedMaxWidth(rowWidth: 320), 160, accuracy: 0.001)
    }

    /// Neben dem Paar zwei Zeilen (Vermerk am Ende bleibt sichtbar), gestapelt frei — wie Android.
    func testConvertedWrapsToTwoLinesBesideThePair() {
        XCTAssertEqual(DenseRowText.convertedLineLimit(stacked: false), 2)
        XCTAssertNil(DenseRowText.convertedLineLimit(stacked: true))
    }

    /// Symbole der Börsenzeile brechen um, statt Mini-Chart oder Börsenname zu opfern — gemeinsame Fälle
    /// `Parity/row_meta_line.json` (Gegenstück `DenseRowTextTest.kt`).
    func testMetaLineWrapParity() throws {
        let data = try TestSupport.fixture("row_meta_line")
        let cases = TestSupport.list(data["cases"])
        XCTAssertFalse(cases.isEmpty)
        for c in cases {
            XCTAssertEqual(DenseRowText.metaWraps(available: TestSupport.number(c["available"]),
                                                  lead: TestSupport.number(c["lead"]) ?? -1,
                                                  icons: TestSupport.number(c["icons"]) ?? -1,
                                                  gap: TestSupport.number(c["gap"]) ?? -1),
                           c["expected"] as? Bool, c["name"] as? String ?? "")
        }
    }
}
