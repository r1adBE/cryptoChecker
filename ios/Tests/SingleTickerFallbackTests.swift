import XCTest
@testable import CryptoChecker

/// Ausweg des Einzelkurses nach einem Serverfehler (`SingleTickerFallback`): die Fälle `singleFallback` aus
/// `exchange_onetrading.json` (Quelle `android/testdata/exchanges`), dazu 4xx/Bremse ohne Ausweg, Börse ohne
/// Ausweichadresse und eine scheiternde Ausweichanfrage. Wie Android `SingleTickerFallbackTest.kt` (dort zusätzlich
/// der ganze Weg über die Netzschicht; `MarketService` nutzt feste Sessions).
final class SingleTickerFallbackTests: XCTestCase {

    private typealias S = TestSupport

    private let bulkURL = "https://api.onetrading.com/fast/v1/market-ticker"
    private let btcEur = MarketPair("BTC", "EUR", "BTC_EUR")

    private struct Offline: Error {}

    private func fixtureCases() throws -> [[String: Any]] {
        let url = try XCTUnwrap(Bundle(for: SingleTickerFallbackTests.self)
            .url(forResource: "exchange_onetrading", withExtension: "json"), "tools/gen_xcodeproj.py ausführen")
        let object = try JSONSerialization.jsonObject(with: Data(contentsOf: url))
        let data = try XCTUnwrap(object as? [String: Any])
        return S.list(data["cases"]).filter { $0["kind"] as? String == "singleFallback" }
    }

    func testFixtureCasesThroughTheFallback() async throws {
        let cases = try fixtureCases()
        XCTAssertGreaterThanOrEqual(cases.count, 5, "singleFallback-Fälle")
        for c in cases {
            try await runCase(c)
        }
    }

    private func runCase(_ c: [String: Any]) async throws {
        let name = c["name"] as? String ?? ""
        let spec = c["pair"] as? [String: Any] ?? [:]
        let info = MarketPair(spec["base"] as? String ?? "", spec["quote"] as? String ?? "", spec["id"] as? String)
        let status = try Int(XCTUnwrap(S.number(c["status"]), name))
        XCTAssertTrue(SingleTickerFallback.applies(httpCode: status), "\(name): status \(status) ist ein Serverfehler")
        let expect = c["expect"] as? [String: Any] ?? [:]
        let body = c["body"] as? String ?? ""
        let server = HttpMarketError(httpCode: status, body: "")
        var requested: String?
        do {
            let ticker = try await SingleTickerFallback.load(market: OneTrading(), call: 0, info: info, base: Ticker(),
                                                             serverError: server) { url in
                requested = url
                return body
            }
            XCTAssertNotEqual(expect["error"] as? Bool, true, "\(name): Fehler erwartet, Kurs \(ticker.last)")
            XCTAssertEqual(ticker.last, try XCTUnwrap(S.number(expect["last"]), name), accuracy: 1e-9, name)
            if let change = S.number(expect["change24hPercent"]) {
                XCTAssertEqual(try XCTUnwrap(ticker.change24hPercent, name), change, accuracy: 1e-9, name)
            }
        } catch let error as HttpMarketError {
            XCTAssertEqual(expect["error"] as? Bool, true, "\(name): unerwarteter Fehler \(error)")
            // Der ursprüngliche Serverfehler, kein eigener Text
            XCTAssertEqual(error.httpCode, status, name)
            XCTAssertEqual(error.body, "", name)
        }
        XCTAssertEqual(requested, bulkURL, name)
    }

    func testClientErrorsAndRateLimitsHaveNoFallback() async {
        for code in [400, 404, 418, 429, 499, 600] {
            XCTAssertFalse(SingleTickerFallback.applies(httpCode: code), "\(code)")
            var loaded = false
            do {
                _ = try await SingleTickerFallback.load(market: OneTrading(), call: 0, info: btcEur, base: Ticker(),
                                                        serverError: HttpMarketError(httpCode: code, body: "")) { _ in
                    loaded = true
                    return "[]"
                }
                XCTFail("\(code): Fehler erwartet")
            } catch {
                XCTAssertEqual((error as? HttpMarketError)?.httpCode, code)
            }
            XCTAssertFalse(loaded, "\(code): keine Ausweichanfrage")
        }
    }

    func testExchangeWithoutFallbackAddressKeepsTheServerError() async {
        var loaded = false
        do {
            _ = try await SingleTickerFallback.load(market: Kraken(), call: 0, info: btcEur, base: Ticker(),
                                                    serverError: HttpMarketError(httpCode: 500, body: "")) { _ in
                loaded = true
                return "{}"
            }
            XCTFail("Fehler erwartet")
        } catch {
            XCTAssertEqual((error as? HttpMarketError)?.httpCode, 500)
        }
        XCTAssertFalse(loaded)
    }

    func testFailingFallbackRequestKeepsTheServerError() async {
        do {
            _ = try await SingleTickerFallback.load(market: OneTrading(), call: 0, info: btcEur, base: Ticker(),
                                                    serverError: HttpMarketError(httpCode: 502, body: "")) { _ in
                throw Offline()
            }
            XCTFail("Fehler erwartet")
        } catch {
            XCTAssertEqual((error as? HttpMarketError)?.httpCode, 502)
        }
    }
}
