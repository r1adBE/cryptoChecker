import XCTest
@testable import CryptoChecker

/// Portfolio-Kurse aus der Kerzen-Kette: Netz, Zeitgrenze oder 5xx heissen «nicht
/// abrufbar» (`failed`), lauter «gibt es nicht» bleibt «kein Kurs». Antworten aus dem Test-Transport statt Netz. Wie
/// Android `CandleDataSourceChainTest.attemptTellsFailureFromNoData`.
final class CandleChainAttemptTests: XCTestCase {
    override func tearDown() {
        ExchangeHTTP.testTransport.withLock { $0 = nil }
        super.tearDown()
    }

    /// Eigenes Kürzel je Test: keine gemerkte Quelle (`preferredSource`) aus einem anderen Lauf.
    private func coin() -> String { "ZQ\(Int.random(in: 10_000...99_999))" }

    func testNetworkFailureIsUnavailable() async {
        ExchangeHTTP.testTransport.withLock { $0 = { _ in throw URLError(.timedOut) } }
        let result = await PortfolioPriceSource.candlePrice(coin(), identityName: nil)
        XCTAssertNil(result.price)
        XCTAssertTrue(result.failed, "Netz: nicht abrufbar, nicht «kein Kurs»")
    }

    func testServerErrorIsUnavailable() async {
        ExchangeHTTP.testTransport.withLock { $0 = { _ in throw HttpExchangeError(httpCode: 503, body: "test") } }
        let result = await PortfolioPriceSource.candlePrice(coin(), identityName: nil)
        XCTAssertTrue(result.failed)
    }

    func testNoDataIsNoPrice() async {
        ExchangeHTTP.testTransport.withLock { $0 = { _ in throw HttpExchangeError(httpCode: 400, body: "Invalid symbol.") } }
        let result = await PortfolioPriceSource.candlePrice(coin(), identityName: nil)
        XCTAssertNil(result.price)
        XCTAssertFalse(result.failed, "gibt es nicht: «kein Kurs»")
    }
}
