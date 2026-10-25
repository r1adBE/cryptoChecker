import XCTest
@testable import CryptoChecker

/// Art eines Export-Fehlers → Text-Schlüssel (`Parity/export_errors.json`). Gegenstück: `ExportErrorsParityTest.kt`.
final class ExportErrorsParityTests: XCTestCase {
    private typealias S = TestSupport

    private struct Unexpected: Error {}

    /// Typischer iOS-Fehler je plattformneutralem Namen der Testfälle.
    private func error(_ name: String) -> (any Error)? {
        switch name {
        case "write_failed": return ExportWriteError(underlying: CocoaError(.fileWriteUnknown))
        case "file_missing": return CocoaError(.fileNoSuchFile)
        case "no_permission": return CocoaError(.fileWriteNoPermission)
        case "disk_full": return CocoaError(.fileWriteOutOfSpace)
        case "wrapped_write":
            return NSError(domain: "Export", code: 1, userInfo: [NSUnderlyingErrorKey: CocoaError(.fileNoSuchFile)])
        case "offline": return URLError(.notConnectedToInternet)
        case "timeout": return URLError(.timedOut)
        case "host_unreachable": return URLError(.cannotConnectToHost)
        case "exchange_bad_response": return URLError(.badServerResponse)
        case "unexpected": return Unexpected()
        default: return nil
        }
    }

    func testOrdnetZuWieGemeinsameTestfaelle() throws {
        let cases = S.list(try S.fixture("export_errors")["cases"])
        XCTAssertGreaterThanOrEqual(cases.count, 10)
        for c in cases {
            let name = try XCTUnwrap(c["error"] as? String)
            let expected = try XCTUnwrap((c["kind"] as? String).flatMap(ExportFailureKind.init(rawValue:)), name)
            let err = try XCTUnwrap(error(name), "unbekannter Testfall \(name)")
            XCTAssertEqual(ExportFailures.classify(err), expected, name)
        }
    }

    func testSchluesselWieGemeinsameTestfaelle() throws {
        let keys = try XCTUnwrap(try S.fixture("export_errors")["keys"] as? [String: String])
        XCTAssertEqual(Set(keys.keys), Set(ExportFailureKind.allCases.map(\.rawValue)))
        for kind in ExportFailureKind.allCases {
            XCTAssertEqual(keys[kind.rawValue], kind.key)
        }
    }

    /// Abbrechen in der Dateiablage ist kein Fehler; ein Schreibfehler dort zeigt «Datei nicht schreibbar».
    @MainActor
    func testDateiablage() {
        let run = ExportRun()
        XCTAssertFalse(run.finishExport(.failure(CocoaError(.userCancelled))))
        XCTAssertEqual(run.state, .idle)
        XCTAssertFalse(run.finishExport(.failure(CocoaError(.fileWriteNoPermission))))
        XCTAssertEqual(run.state, .failed(.FILE))
        run.dismissFailure()
        XCTAssertEqual(run.state, .idle)
    }
}
