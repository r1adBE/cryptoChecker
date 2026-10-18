import XCTest
@testable import CryptoChecker

/// Art des Fehlers neben dem Text (`Parity/market_failure.json`): Die Art entscheidet, wo sie kann; sonst und für
/// gespeicherte ältere Texte die Stichwörter. Dazu `MarketFailure.of` am Ort des Fehlers. Wie
/// `MarketFailureParityTest.kt` und `MarketFailureMappingTest.kt`.
final class MarketFailureParityTests: XCTestCase {

    private var data: [String: Any] = [:]

    override func setUpWithError() throws {
        data = try TestSupport.fixture("market_failure")
    }

    private func cases(_ key: String) -> [[String: Any]] {
        data[key] as? [[String: Any]] ?? []
    }

    private func failure(_ raw: Any?) -> MarketFailure? {
        guard let f = raw as? [String: Any], let kind = f["kind"] as? String else { return nil }
        let code = (f["code"] as? NSNumber)?.intValue ?? 0
        switch kind {
        case "timeout": return .timeout
        case "offline": return .offline
        case "ssl": return .ssl
        case "httpStatus": return .httpStatus(code)
        case "rateLimited": return .rateLimited(retryAfterSeconds: (f["retryAfter"] as? NSNumber)?.int64Value, code: code)
        case "parse": return .parse
        case "unknownInstrument": return .unknownInstrument
        case "exchange": return .exchange(f["detail"] as? String ?? "")
        case "other": return .other
        default:
            XCTFail("unknown kind \(kind)")
            return nil
        }
    }

    func testClassify() {
        let list = cases("classify")
        XCTAssertFalse(list.isEmpty)
        for c in list {
            let text = c["text"] as? String
            let expected = RefreshFailure(rawValue: c["expected"] as? String ?? "")
            XCTAssertEqual(RefreshReportLogic.classify(failure(c["failure"]), text), expected,
                           "classify: \(String(describing: c["failure"])) / \(text ?? "nil")")
        }
    }

    func testHttpStatus() {
        for c in cases("httpStatus") {
            let text = c["text"] as? String
            XCTAssertEqual(RefreshReportLogic.httpStatus(failure(c["failure"]), text), (c["expected"] as? NSNumber)?.intValue,
                           "httpStatus: \(text ?? "nil")")
        }
    }

    func testConfirmedBySingle() {
        for c in cases("confirmedBySingle") {
            let text = c["text"] as? String
            XCTAssertEqual(NotTraded.confirmedBySingle(gotPrice: c["gotPrice"] as? Bool ?? false, error: text,
                                                       failure: failure(c["failure"])),
                           c["confirmed"] as? Bool, "confirmed: \(text ?? "nil")")
        }
    }

    // MARK: Am Ort des Fehlers

    func testOfMapsErrorTypes() {
        XCTAssertEqual(MarketFailure.of(HttpMarketError(httpCode: 429, body: "", retryAfterSeconds: 30)),
                       .rateLimited(retryAfterSeconds: 30, code: 429))
        XCTAssertEqual(MarketFailure.of(HttpMarketError(httpCode: 418, body: "")), .rateLimited(retryAfterSeconds: nil, code: 418))
        XCTAssertEqual(MarketFailure.of(HttpMarketError(httpCode: 404, body: "{}")), .httpStatus(404))
        XCTAssertEqual(MarketFailure.of(URLError(.timedOut)), .timeout)
        XCTAssertEqual(MarketFailure.of(URLError(.secureConnectionFailed)), .ssl)
        XCTAssertEqual(MarketFailure.of(URLError(.notConnectedToInternet)), .offline)
        XCTAssertEqual(MarketFailure.of(URLError(.cannotFindHost)), .offline)
        XCTAssertEqual(MarketFailure.of(URLError(.badServerResponse)), .other)
        XCTAssertEqual(MarketFailure.of(JSONError(message: "Kein JSON-Objekt")), .parse)
        XCTAssertEqual(MarketFailure.of(UserFriendlyMarketError(message: "Invalid symbol.")), .unknownInstrument)
        XCTAssertEqual(MarketFailure.of(UserFriendlyMarketError(message: "EGeneral:Too many requests")),
                       .exchange("EGeneral:Too many requests"))
        XCTAssertEqual(MarketFailure.of(UserFriendlyMarketError(message: "Parsed ticker has no data")), .other)
        XCTAssertEqual(MarketFailure.of(ResponseTooLargeError()), .other)
    }

    /// Gleiche Einordnung wie bisher: Zeit blieb schon TIMEOUT (eigens erkannt), alles andere wie aus dem Text.
    func testTypedMatchesLegacyClassification() {
        let errors: [Error] = [
            HttpMarketError(httpCode: 429, body: "slow down", retryAfterSeconds: 30),
            HttpMarketError(httpCode: 404, body: "{}"),
            HttpMarketError(httpCode: 503, body: ""),
            URLError(.secureConnectionFailed),
            URLError(.notConnectedToInternet),
            URLError(.networkConnectionLost),
            UserFriendlyMarketError(message: "Invalid symbol."),
            UserFriendlyMarketError(message: "EGeneral:Too many requests"),
            UserFriendlyMarketError(message: "Parsed ticker has no data"),
        ]
        for error in errors {
            let text = ConnectionErrors.describe(error)
            let kind = MarketFailure.of(error)
            XCTAssertEqual(RefreshReportLogic.classify(kind, text), RefreshReportLogic.classify(text), text)
            XCTAssertEqual(NotTraded.confirmedBySingle(gotPrice: false, error: text, failure: kind),
                           NotTraded.confirmedBySingle(gotPrice: false, error: text), text)
        }
        XCTAssertEqual(RefreshReportLogic.classify(MarketFailure.of(URLError(.timedOut)),
                                                   ConnectionErrors.describe(URLError(.timedOut))), .TIMEOUT)
    }
}
