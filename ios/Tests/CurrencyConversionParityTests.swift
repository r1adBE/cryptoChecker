import XCTest
@testable import CryptoChecker

/// Reine Regeln der «≈ Umrechnung» als gemeinsame Testfälle (`currency_conversion.json`, Quelle
/// `android/testdata/parity`) — wie `CurrencyConversionParityTest.kt`: Einordnung der Quote, Quote → USD über
/// USDT-Kurse, Kette Quote → Ziel samt Betrag (sehr klein/gross, Überlauf), fehlende und veraltete Kurse an der Grenze.
final class CurrencyConversionParityTests: XCTestCase {
    private typealias S = TestSupport

    /// Gleiche Namen wie Android `QuoteKind`.
    private func kind(_ name: String) -> CurrencyConversion.QuoteKind {
        switch name {
        case "USD": return .usd
        case "FIAT": return .fiat
        case "FIAT_UNSUPPORTED": return .fiatUnsupported
        case "CRYPTO": return .crypto
        default: return .invalid
        }
    }

    private func name(_ kind: CurrencyConversion.QuoteKind) -> String {
        switch kind {
        case .usd: return "USD"
        case .fiat: return "FIAT"
        case .fiatUnsupported: return "FIAT_UNSUPPORTED"
        case .crypto: return "CRYPTO"
        case .invalid: return "INVALID"
        }
    }

    private func assertClose(_ name: String, _ expected: Double?, _ actual: Double?) {
        guard let expected else { return XCTAssertNil(actual, name) }
        guard let actual else { return XCTFail("\(name): expected \(expected), got nil") }
        XCTAssertLessThanOrEqual(abs(expected - actual), abs(expected) * 1e-12,
                                 "\(name): expected \(expected), got \(actual)")
    }

    private func fiat(_ data: [String: Any]) -> [String] { data["fiat"] as? [String] ?? [] }

    func testClassify() throws {
        let data = try S.fixture("currency_conversion")
        for c in S.list(data["classify"]) {
            let quote = c["quote"] as? String ?? ""
            XCTAssertEqual(name(CurrencyConversion.classify(quote, fiat: fiat(data))), c["kind"] as? String,
                           "classify '\(quote)'")
        }
    }

    func testQuoteToUsd() throws {
        for c in S.list(try S.fixture("currency_conversion")["quoteToUsd"]) {
            let actual = CurrencyConversion.quoteToUsd(kind(c["kind"] as? String ?? ""),
                                                       usdToQuote: S.number(c["usdToQuote"]),
                                                       cryptoUsdt: S.number(c["cryptoUsdt"]),
                                                       code: c["code"] as? String, usdtUsd: S.number(c["usdtUsd"]))
            assertClose(c["name"] as? String ?? "", S.number(c["expected"]), actual)
        }
    }

    func testChainQuoteToTargetAndAmount() throws {
        let data = try S.fixture("currency_conversion")
        for c in S.list(data["chain"]) {
            let name = c["name"] as? String ?? ""
            let quote = c["quote"] as? String ?? ""
            let quoteUsd = CurrencyConversion.quoteToUsd(CurrencyConversion.classify(quote, fiat: fiat(data)),
                                                         usdToQuote: S.number(c["usdToQuote"]),
                                                         cryptoUsdt: S.number(c["cryptoUsdt"]), code: quote,
                                                         usdtUsd: S.number(c["usdtUsd"]))
            let rate = CurrencyConversion.rate(quoteUsd: quoteUsd, usdToTarget: S.number(c["usdToTarget"]))
            assertClose("\(name) (rate)", S.number(c["rate"]), rate)
            assertClose("\(name) (amount)", S.number(c["converted"]),
                        CurrencyConversion.convert(S.number(c["amount"]) ?? 0, rate: rate))
        }
    }

    func testFreshQuotePriceAndStaleFx() throws {
        let data = try S.fixture("currency_conversion")
        for c in S.list(data["freshQuotePrice"]) {
            let time = S.number(c["time"]).map { Int64($0) }
            let actual = CurrencyConversion.freshQuotePrice(S.number(c["price"]), time: time,
                                                            now: Int64(S.number(c["now"]) ?? 0))
            XCTAssertEqual(actual, S.number(c["expected"]), c["name"] as? String ?? "")
        }
        for c in S.list(data["staleFxTime"]) {
            let actual = CurrencyConversion.staleFxTime(quoteFxAt: S.number(c["quoteFxAt"]).map { Int64($0) },
                                                        targetFxAt: S.number(c["targetFxAt"]).map { Int64($0) },
                                                        now: Int64(S.number(c["now"]) ?? 0))
            XCTAssertEqual(actual, S.number(c["expected"]).map { Int64($0) }, c["name"] as? String ?? "")
        }
    }

    func testConversionQuoteAndSameCurrency() throws {
        let data = try S.fixture("currency_conversion")
        for c in S.list(data["conversionQuote"]) {
            let market = c["market"] as? String
            let quote = c["quote"] as? String
            let actual = CurrencyConversion.conversionQuote(marketKey: market, quote: quote)
            XCTAssertEqual(actual, c["expected"] as? String, "conversionQuote \(market ?? "nil") \(quote ?? "nil")")
        }
        for c in S.list(data["sameCurrency"]) {
            let a = c["a"] as? String
            let b = c["b"] as? String
            XCTAssertEqual(CurrencyConversion.sameCurrency(a, b), c["expected"] as? Bool,
                           "sameCurrency \(a ?? "nil") \(b ?? "nil")")
        }
    }
}
