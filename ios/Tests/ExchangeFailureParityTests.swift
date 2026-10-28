import XCTest
@testable import CryptoChecker

/// Art des Fehlers neben dem Text (`Parity/exchange_failure.json`): Die Art entscheidet, wo sie kann; sonst und für
/// gespeicherte Texte (ohne Art) die Stichwörter. Dazu `ExchangeFailure.of` am Ort des Fehlers. Wie
/// `ExchangeFailureParityTest.kt` und `ExchangeFailureMappingTest.kt`.
final class ExchangeFailureParityTests: XCTestCase {

    private var data: [String: Any] = [:]

    override func setUpWithError() throws {
        data = try TestSupport.fixture("exchange_failure")
    }

    private func cases(_ key: String) -> [[String: Any]] {
        data[key] as? [[String: Any]] ?? []
    }

    private func failure(_ raw: Any?) -> ExchangeFailure? {
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
        case "message": return .message(f["detail"] as? String ?? "")
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

    func testAnswerWithoutPriceStillConfirmsNotTraded() {
        let noPrice = NoTickerDataError()
        XCTAssertEqual(ExchangeFailure.of(noPrice), .parse)
        XCTAssertEqual(ConnectionErrors.describe(noPrice), "Parsed ticker has no data")
        XCTAssertTrue(NotTraded.confirmedBySingle(gotPrice: false, error: ConnectionErrors.describe(noPrice),
                                                  failure: ExchangeFailure.of(noPrice)))
        // Interner Fehler ohne Text: kein «nicht mehr gehandelt» (sonst 6 h stumm)
        XCTAssertFalse(NotTraded.confirmedBySingle(gotPrice: false, error: nil, failure: .other))
        XCTAssertFalse(NotTraded.confirmedBySingle(gotPrice: false, error: " ", failure: .parse))
        // Ohne Art (gespeicherte Ergebnisse) entscheidet der Text
        XCTAssertTrue(NotTraded.confirmedBySingle(gotPrice: false, error: nil))
    }

    // MARK: Am Ort des Fehlers

    func testOfMapsErrorTypes() {
        XCTAssertEqual(ExchangeFailure.of(HttpExchangeError(httpCode: 429, body: "", retryAfterSeconds: 30)),
                       .rateLimited(retryAfterSeconds: 30, code: 429))
        XCTAssertEqual(ExchangeFailure.of(HttpExchangeError(httpCode: 418, body: "")), .rateLimited(retryAfterSeconds: nil, code: 418))
        XCTAssertEqual(ExchangeFailure.of(HttpExchangeError(httpCode: 404, body: "{}")), .httpStatus(404))
        XCTAssertEqual(ExchangeFailure.of(URLError(.timedOut)), .timeout)
        XCTAssertEqual(ExchangeFailure.of(URLError(.secureConnectionFailed)), .ssl)
        XCTAssertEqual(ExchangeFailure.of(URLError(.notConnectedToInternet)), .offline)
        XCTAssertEqual(ExchangeFailure.of(URLError(.cannotFindHost)), .offline)
        XCTAssertEqual(ExchangeFailure.of(URLError(.badServerResponse)), .other)
        XCTAssertEqual(ExchangeFailure.of(JSONError(message: "Kein JSON-Objekt")), .parse)
        XCTAssertEqual(ExchangeFailure.of(UserFriendlyExchangeError(message: "Invalid symbol.")), .unknownInstrument)
        XCTAssertEqual(ExchangeFailure.of(UserFriendlyExchangeError(message: "EGeneral:Too many requests")),
                       .message("EGeneral:Too many requests"))
        XCTAssertEqual(ExchangeFailure.of(UserFriendlyExchangeError(message: "Parsed ticker has no data")), .other)
        XCTAssertEqual(ExchangeFailure.of(ResponseTooLargeError()), .other)
    }

    /// Gleiche Einordnung über die Art wie über den Text (Zeitüberschreitung eigens erkannt: TIMEOUT).
    func testTypedMatchesTextClassification() {
        let errors: [Error] = [
            HttpExchangeError(httpCode: 429, body: "slow down", retryAfterSeconds: 30),
            HttpExchangeError(httpCode: 404, body: "{}"),
            HttpExchangeError(httpCode: 503, body: ""),
            URLError(.secureConnectionFailed),
            URLError(.notConnectedToInternet),
            URLError(.networkConnectionLost),
            UserFriendlyExchangeError(message: "Invalid symbol."),
            UserFriendlyExchangeError(message: "EGeneral:Too many requests"),
            UserFriendlyExchangeError(message: "Parsed ticker has no data"),
            // So meldet die Einzelabfrage eine Antwort ohne gültigen Kurs (wie Android ExchangeParseException → Parse)
            NoTickerDataError(),
        ]
        for error in errors {
            let text = ConnectionErrors.describe(error)
            let kind = ExchangeFailure.of(error)
            XCTAssertEqual(RefreshReportLogic.classify(kind, text), RefreshReportLogic.classify(text), text)
            // Ausnahme mit Absicht: .other (interner Fehler) bestätigt nie «nicht mehr gehandelt»
            let byText = NotTraded.confirmedBySingle(gotPrice: false, error: text)
            XCTAssertEqual(NotTraded.confirmedBySingle(gotPrice: false, error: text, failure: kind),
                           byText && kind != .other, text)
        }
        XCTAssertEqual(RefreshReportLogic.classify(ExchangeFailure.of(URLError(.timedOut)),
                                                   ConnectionErrors.describe(URLError(.timedOut))), .TIMEOUT)
    }
}
