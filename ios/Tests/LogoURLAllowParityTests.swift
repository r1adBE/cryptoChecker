import XCTest
@testable import CryptoChecker

/// Freigabe von Logo-Adressen (`Parity/logo_url_allow.json`, seit Build 105, SEC3-02): die Prüfung entscheidet wie der
/// URL-Parser, der die Anfrage stellt. Gegenstück: `LogoUrlAllowParityTest.kt`.
final class LogoURLAllowParityTests: XCTestCase {
    private typealias S = TestSupport

    private func cases() throws -> [[String: Any]] {
        S.list(try S.fixture("logo_url_allow")["cases"])
    }

    func testFreigabeWieGemeinsameFaelle() throws {
        for c in try cases() {
            XCTAssertEqual(CoinLogos.isAllowedURL(c["url"] as? String), try XCTUnwrap(c["expected"] as? Bool),
                           "allowed: \(c)")
        }
    }

    /// Jede freigegebene Adresse geht beim Abruf (Foundation) wirklich an einen freigegebenen Host.
    func testFreigegebeneAdresseLaedtVomFreigegebenenHost() throws {
        for c in try cases() where c["expected"] as? Bool == true {
            let host = URL(string: try XCTUnwrap(c["url"] as? String))?.host?.lowercased() ?? ""
            let known = host == "coingecko.com" || host.hasSuffix(".coingecko.com") || host.hasSuffix(".bnbstatic.com")
            XCTAssertTrue(known, "host: \(c)")
        }
    }
}
