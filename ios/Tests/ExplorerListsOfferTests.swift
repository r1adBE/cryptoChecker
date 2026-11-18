import XCTest
@testable import CryptoChecker

/// Börsen ohne Antwort: wenige gleich mit Namen, viele zuerst nur als Zahl (Namen auf Tippen). Wie Android
/// `ExplorerListsFailedTest`.
final class ExplorerListsOfferTests: XCTestCase {
    func testFewFailedExchangesAreNamedAtOnce() {
        XCTAssertFalse(ExplorerListsOffer.summarized(failed: 3, expanded: false))
        let shown = ExplorerListsOffer.shown(["OKX", "kraken", "Bybit"], expanded: false)
        XCTAssertEqual(shown.names, ["Bybit", "kraken", "OKX"])
        XCTAssertEqual(shown.hidden, 0)
    }

    func testManyFailedExchangesStayCalmUntilTapped() {
        XCTAssertTrue(ExplorerListsOffer.summarized(failed: 4, expanded: false))
        XCTAssertFalse(ExplorerListsOffer.summarized(failed: 75, expanded: true))
        XCTAssertEqual(ExplorerListsOffer.shown((1...10).map { "E\($0)" }, expanded: true).names.count, 10)
    }
}
