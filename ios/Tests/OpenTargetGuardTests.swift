import XCTest
@testable import CryptoChecker

/// Ziele fremder Links nur mit dem Geheimnis der Installation (`OpenTargetGuard`) — wie Android `OpenTargetGuardTest`.
final class OpenTargetGuardTests: XCTestCase {

    private let secret = "0123456789abcdef0123456789abcdef"

    func testShortcutTargetsWorkWithoutToken() {
        for target in ["add", "alarms", "cycle"] {
            XCTAssertTrue(OpenTargetGuard.accepts(target, token: nil, expected: secret), target)
        }
    }

    func testWatchTargetWorksWithoutToken() {
        XCTAssertTrue(OpenTargetGuard.accepts("watch/12", token: nil, expected: secret))
        XCTAssertFalse(OpenTargetGuard.accepts("watch/0", token: nil, expected: secret))
        XCTAssertFalse(OpenTargetGuard.accepts("watch/x", token: nil, expected: secret))
    }

    func testPortfolioNeedsMatchingToken() {
        XCTAssertFalse(OpenTargetGuard.accepts("portfolio", token: nil, expected: secret))
        XCTAssertFalse(OpenTargetGuard.accepts("portfolio", token: "falsch", expected: secret))
        XCTAssertFalse(OpenTargetGuard.accepts("portfolio", token: "", expected: ""))
        XCTAssertTrue(OpenTargetGuard.accepts("portfolio", token: secret, expected: secret))
    }

    func testOtherTargetsNeedToken() {
        for target in ["cycle/macro", "why/5", "alarm/3/7", "unbekannt"] {
            XCTAssertFalse(OpenTargetGuard.accepts(target, token: nil, expected: secret), target)
            XCTAssertTrue(OpenTargetGuard.accepts(target, token: secret, expected: secret), target)
        }
    }

    /// Der Link des Portfolio-Widgets trägt das Geheimnis, das die App erwartet.
    func testWidgetLinkCarriesToken() throws {
        let url = try XCTUnwrap(OpenTargetGuard.url("portfolio"))
        XCTAssertEqual(url.scheme, "cryptochecker")
        XCTAssertEqual(url.host, "portfolio")
        XCTAssertEqual(OpenTargetGuard.linkToken(of: url), OpenTargetGuard.token)
        XCTAssertNil(OpenTargetGuard.linkToken(of: try XCTUnwrap(URL(string: "cryptochecker://portfolio"))))
    }
}
