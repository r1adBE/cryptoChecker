import XCTest
@testable import CryptoChecker

/// Neutraler User-Agent der Adressabfragen (`Parity/address_privacy.json`): gleicher Wert wie Android,
/// ohne App-Name/Version (sonst sendet CFNetwork den Bundle-Namen); dazu Host-Regel und eigene Sessions.
/// Gegenstück: `AddressPrivacyParityTest.kt`.
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

    /// Host → User-Agent wie Android.
    func testHostRegelWieGemeinsameFaelle() throws {
        let fixture = try S.fixture("address_privacy")
        let cases = try XCTUnwrap(fixture["hostCases"] as? [[String: Any]])
        let app = "cryptoChecker-iOS/1.0"
        for c in cases {
            let host = try XCTUnwrap(c["host"] as? String)
            let neutral = try XCTUnwrap(c["neutral"] as? Bool)
            XCTAssertEqual(HttpUserAgent.forHost(host, app: app), neutral ? HttpUserAgent.neutral : app, host)
        }
    }

    /// Adress-Hosts laufen nie über die Sessions mit App-User-Agent und teilen keine Verbindungen.
    func testAdressHostsBekommenEigeneSession() {
        let single = MarketHTTP.sessionFor(host: "mempool.space", requested: MarketHTTP.session)
        XCTAssertTrue(single === MarketHTTP.addressHostSession)
        XCTAssertTrue(MarketHTTP.sessionFor(host: "ethereum-rpc.publicnode.com", requested: MarketHTTP.bulkSession)
                      === MarketHTTP.addressHostBulkSession)
        XCTAssertTrue(MarketHTTP.sessionFor(host: "api.binance.com", requested: MarketHTTP.session) === MarketHTTP.session)
        XCTAssertTrue(MarketHTTP.sessionFor(host: nil, requested: MarketHTTP.session) === MarketHTTP.session)
        for s in [MarketHTTP.addressHostSession, MarketHTTP.addressHostBulkSession] {
            XCTAssertEqual(s.configuration.httpAdditionalHeaders?[HttpUserAgent.header] as? String, HttpUserAgent.neutral)
        }
        XCTAssertEqual(MarketHTTP.bodyLimit(session: MarketHTTP.addressHostSession, inExtension: false), MarketHTTP.maxBodyBytes)
    }

    /// Die Sitzung der Adressabfragen setzt den neutralen User-Agent ausdrücklich.
    func testAdressSitzungSetztNeutralenUserAgent() {
        let headers = WatchedAddressSource.session.configuration.httpAdditionalHeaders
        XCTAssertEqual(headers?[HttpUserAgent.header] as? String, HttpUserAgent.neutral)
    }
}
