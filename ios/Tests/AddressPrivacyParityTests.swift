import XCTest
@testable import CryptoChecker

/// Neutraler User-Agent der Adressabfragen (`Parity/address_privacy.json`, seit Build 103): gleicher Wert wie Android,
/// ohne App-Name/Version (sonst sendet CFNetwork den Bundle-Namen). Gegenstück: `AddressPrivacyParityTest.kt`.
final class AddressPrivacyParityTests: XCTestCase {
    private typealias S = TestSupport

    func testNeutralerUserAgentWieGemeinsamerWert() throws {
        let fixture = try S.fixture("address_privacy")
        XCTAssertEqual(HttpUserAgent.neutral, fixture["userAgent"] as? String)
        let forbidden = try XCTUnwrap(fixture["forbiddenParts"] as? [String])
        for part in forbidden {
            XCTAssertNil(HttpUserAgent.neutral.range(of: part, options: .caseInsensitive), part)
        }
    }

    /// Die Sitzung der Adressabfragen setzt den neutralen User-Agent ausdrücklich.
    func testAdressSitzungSetztNeutralenUserAgent() {
        let headers = WatchedAddressSource.session.configuration.httpAdditionalHeaders
        XCTAssertEqual(headers?[HttpUserAgent.header] as? String, HttpUserAgent.neutral)
    }
}
