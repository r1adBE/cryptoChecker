import os
import XCTest
@testable import CryptoChecker

/// Devisenkurs nach einem Abruf ohne Antwort beider Quellen: eine Minute kein neuer Versuch — sonst wartete jede weitere
/// Währung erneut bis 2 × 10 s. Wie Android `FxRateSourceTest.failedFetchIsNotRepeatedWithinAMinute` (früher
/// Quelltext-Wächter, Audit A-01).
final class FxRateFailureTests: XCTestCase {

    override func tearDown() {
        FxRateSource.testTransport.withLock { $0 = nil }
        FxRateSource.resetForTest()
        super.tearDown()
    }

    func testFailedFetchIsNotRepeatedWithinAMinute() async {
        FxRateSource.resetForTest()
        let hits = OSAllocatedUnfairLock(initialState: 0)
        FxRateSource.testTransport.withLock { $0 = { _ in hits.withLock { $0 += 1 }; return nil } }
        let chf = await FxRateSource.usdTo("CHF")
        XCTAssertNil(chf)
        let first = hits.withLock { $0 }
        XCTAssertGreaterThan(first, 0)
        _ = await FxRateSource.usdTo("JPY")
        _ = await FxRateSource.usdTo("CHF")
        XCTAssertEqual(hits.withLock { $0 }, first, "innerhalb der Minute keine neue Anfrage")
    }
}
