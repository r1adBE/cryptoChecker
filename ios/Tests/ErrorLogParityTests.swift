import XCTest
@testable import CryptoChecker

/// Fehlerprotokoll als gemeinsame Testfälle (`error_log.json`, Quelle `android/testdata/parity`) — wie
/// `ErrorLogParityTest.kt`: gefilterte Felder ohne freien Text, Zeilenformat, Wiederholungen, Obergrenze, Bericht.
final class ErrorLogParityTests: XCTestCase {
    private typealias S = TestSupport

    private func strings(_ value: Any?) -> [String] { value as? [String] ?? [] }

    func testLimitAsShared() throws {
        XCTAssertEqual(Int(S.number(try S.fixture("error_log")["maxEntries"]) ?? 0), ErrorLog.maxEntries)
    }

    func testFieldKeepsNoFreeText() throws {
        let cases = S.list(try S.fixture("error_log")["field"])
        XCTAssertFalse(cases.isEmpty)
        for c in cases {
            XCTAssertEqual(ErrorLog.field(c["text"] as? String), c["field"] as? String, c["name"] as? String ?? "")
        }
    }

    func testStoredLine() throws {
        let cases = S.list(try S.fixture("error_log")["line"])
        XCTAssertFalse(cases.isEmpty)
        for c in cases {
            let level = (c["level"] as? String ?? "E").first ?? "E"
            let line = ErrorLog.line(Int64(S.number(c["millis"]) ?? 0), level, c["area"] as? String ?? "",
                                     c["type"] as? String)
            XCTAssertEqual(line, c["line"] as? String, c["name"] as? String ?? "")
        }
    }

    func testParse() throws {
        let cases = S.list(try S.fixture("error_log")["parse"])
        XCTAssertFalse(cases.isEmpty)
        for c in cases {
            let want = (c["entry"] as? [Any]).map {
                ErrorLog.Entry(millis: Int64(S.number($0[0]) ?? -1), level: $0[1] as? String ?? "",
                               area: $0[2] as? String ?? "", type: $0[3] as? String ?? "")
            }
            XCTAssertEqual(ErrorLog.parse(c["line"] as? String ?? ""), want, c["name"] as? String ?? "")
        }
    }

    func testAppended() throws {
        let cases = S.list(try S.fixture("error_log")["appended"])
        XCTAssertFalse(cases.isEmpty)
        for c in cases {
            XCTAssertEqual(ErrorLog.appended(strings(c["lines"]), c["new"] as? String ?? ""), strings(c["result"]),
                           c["name"] as? String ?? "")
        }
    }

    func testShown() throws {
        let cases = S.list(try S.fixture("error_log")["shown"])
        XCTAssertFalse(cases.isEmpty)
        for c in cases {
            let zone = try XCTUnwrap(TimeZone(identifier: c["zone"] as? String ?? ""))
            XCTAssertEqual(ErrorLog.shown(strings(c["lines"]), timeZone: zone), strings(c["shown"]),
                           c["name"] as? String ?? "")
        }
    }
}
