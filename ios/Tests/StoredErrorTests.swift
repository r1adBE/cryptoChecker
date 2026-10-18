import XCTest
@testable import CryptoChecker

/// Gespeicherte Fehler ohne Hosts/URLs/Adressen; Fälle `stored_error.json` (Android: `StoredErrorTest.kt`).
final class StoredErrorTests: XCTestCase {

    private typealias S = TestSupport

    func testFixtureCases() throws {
        let data = try S.fixture("stored_error")
        for c in S.list(data["cases"]) {
            XCTAssertEqual(StoredError.sanitize(c["input"] as? String), c["expected"] as? String,
                           "\(c["name"] ?? "")")
        }
    }

    func testClassificationUnchanged() throws {
        let data = try S.fixture("stored_error")
        for c in S.list(data["cases"]) {
            guard let input = c["input"] as? String else { continue }
            XCTAssertEqual(RefreshReportLogic.classify(input), RefreshReportLogic.classify(StoredError.sanitize(input)),
                           "\(c["name"] ?? "")")
        }
    }

    func testNoUrlsOrHostsSurvive() throws {
        let data = try S.fixture("stored_error")
        for c in S.list(data["cases"]) {
            guard let out = StoredError.sanitize(c["input"] as? String) else { continue }
            XCTAssertFalse(out.contains("http://") || out.contains("https://") || out.contains(".com"),
                           "\(c["name"] ?? "")")
        }
    }

    /// Kurzer Börsentext für den Bericht; Fälle `exchange_detail.json` (Android: `StoredErrorTest.kt`).
    func testExchangeDetailCases() throws {
        let data = try S.fixture("exchange_detail")
        for c in S.list(data["cases"]) {
            let out = StoredError.exchangeDetail(c["input"] as? String)
            XCTAssertEqual(out, c["expected"] as? String, "\(c["name"] ?? "")")
            if let out {
                XCTAssertLessThanOrEqual(out.count, StoredError.maxDetail)
                XCTAssertFalse(out.contains("://") || out.contains(".com") || out.contains(".io"), "\(c["name"] ?? "")")
            }
        }
        for r in S.list(data["report"]) {
            let errors = (r["errors"] as? [Any] ?? []).map { $0 as? String }
            let reason = (r["reason"] as? String).flatMap(RefreshFailure.init(rawValue:))
            XCTAssertEqual(StoredError.reportDetail(errors, reason: reason), r["expected"] as? String, "\(r)")
        }
        // Übersetzte eigene Texte sagen nichts Neues
        XCTAssertNil(StoredError.exchangeDetail(L("something_went_wrong")))
    }

    /// Eigene, übersetzte Texte (nur iOS gespeichert) bleiben.
    func testLocalizedTextsKept() {
        XCTAssertEqual(StoredError.sanitize(L("something_went_wrong")), L("something_went_wrong"))
        XCTAssertEqual(StoredError.sanitize(L("market_data_empty_error")), L("market_data_empty_error"))
    }
}
