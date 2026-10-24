import XCTest
@testable import CryptoChecker

/// Synthetische Fehlerantworten der Börsen (`exchange_errors_synthetic.json`, Quelle `android/testdata/exchanges`,
/// nach `Tests/Exchanges` kopiert): kaputtes JSON, leere Antworten, HTTP 429 und die Ratenbegrenzungen, die
/// `RateLimitSignal` kennt. Den Parser selbst prüft `ExchangeContractTests` (gewöhnliches `exchange_*.json`); hier
/// die Regeln darum herum: jeder Fall ist synthetisch gekennzeichnet, ergibt keinen Kurs, `httpStatus` + Antwort
/// ergeben genau dann 429, wenn `rateLimit` gilt, und `messageRateLimit` sagt, ob schon der Fehlertext der Börse
/// als «zu viele Anfragen» zählt. Wie `ExchangeErrorContractTest.kt`.
final class ExchangeErrorContractTests: XCTestCase {

    private typealias S = TestSupport

    private func cases() throws -> [[String: Any]] {
        let bundle = Bundle(for: ExchangeErrorContractTests.self)
        let url = try XCTUnwrap(bundle.url(forResource: "exchange_errors_synthetic", withExtension: "json"),
                                "exchange_errors_synthetic.json im Test-Bundle (tools/gen_xcodeproj.py ausführen)")
        let object = try JSONSerialization.jsonObject(with: Data(contentsOf: url))
        return S.list(try XCTUnwrap(object as? [String: Any])["cases"])
    }

    private func pair(_ spec: [String: Any]) -> PairQuery {
        MarketPair(spec["base"] as? String ?? "", spec["quote"] as? String ?? "", spec["id"] as? String,
                   FuturesContractType(backupName: spec["contract"] as? String ?? "NONE"))
    }

    func testEveryCaseIsSyntheticAndYieldsNoPrice() throws {
        let all = try cases()
        XCTAssertGreaterThanOrEqual(all.count, 60, "synthetic error cases")
        for c in all {
            let name = c["name"] as? String ?? ""
            let expect = c["expect"] as? [String: Any] ?? [:]
            let isError = expect["error"] as? Bool == true
            let noPrice: Bool
            switch c["kind"] as? String {
            case "single": noPrice = isError
            case "bulk": noPrice = isError || S.number(expect["count"]) == 0
            default: noPrice = false
            }
            XCTAssertEqual(c["synthetic"] as? Bool, true, "\(name): not marked synthetic")
            XCTAssertTrue(noPrice, "\(name): an error response must not expect a price")
        }
    }

    func testRateLimitIsRecognisedExactlyWhereExpected() throws {
        for c in try cases() {
            let name = c["name"] as? String ?? ""
            let status = Int(S.number(c["httpStatus"]) ?? 0)
            let body = c["body"] as? String ?? ""
            let limited = RateLimitSignal.httpCode(status: status, body: body) == RateLimitSignal.httpCodeForRateLimit
            XCTAssertEqual(limited, c["rateLimit"] as? Bool, "\(name): rate limit")

            guard let wantText = c["messageRateLimit"] as? Bool else { continue }
            let market = try XCTUnwrap(MarketsConfig.market(c["market"] as? String ?? ""), name)
            let message = try? market.readError(call: Int(S.number(c["requestId"]) ?? 0), body: body,
                                                info: pair(c["pair"] as? [String: Any] ?? [:]))
            XCTAssertEqual(RateLimitSignal.inText(message), wantText,
                           "\(name): error text '\(String(describing: message))' as rate limit")
        }
    }
}
