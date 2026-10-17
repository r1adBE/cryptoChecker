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

    /// Eigene, übersetzte Texte (nur iOS gespeichert) bleiben.
    func testLocalizedTextsKept() {
        XCTAssertEqual(StoredError.sanitize(L("something_went_wrong")), L("something_went_wrong"))
        XCTAssertEqual(StoredError.sanitize(L("market_data_empty_error")), L("market_data_empty_error"))
    }
}
