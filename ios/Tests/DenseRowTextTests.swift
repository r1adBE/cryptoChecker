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
}
