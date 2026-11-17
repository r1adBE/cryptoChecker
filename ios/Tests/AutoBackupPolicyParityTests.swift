import XCTest
@testable import CryptoChecker

/// Automatische Sicherung als gemeinsame Testfälle (`auto_backup.json`, Quelle `android/testdata/parity`) — wie
/// `AutoBackupPolicyParityTest.kt`: Fälligkeit, Dateiname, eigene Dateien und was gelöscht wird.
final class AutoBackupPolicyParityTests: XCTestCase {
    private typealias S = TestSupport

    private func millis(_ value: Any?) -> Int64 { Int64(S.number(value) ?? -1) }

    func testConstantsAsShared() throws {
        let data = try S.fixture("auto_backup")
        XCTAssertEqual(millis(data["intervalMillis"]), AutoBackupPolicy.intervalMillis)
        XCTAssertEqual(millis(data["retryAfterMillis"]), AutoBackupPolicy.retryAfterMillis)
        XCTAssertEqual(Int(S.number(data["keep"]) ?? 0), AutoBackupPolicy.keep)
    }

    func testDue() throws {
        let cases = S.list(try S.fixture("auto_backup")["due"])
        XCTAssertFalse(cases.isEmpty)
        for c in cases {
            let due = AutoBackupPolicy.isDue(enabled: c["enabled"] as? Bool ?? false, lastSuccessAt: millis(c["lastSuccessAt"]),
                                             lastAttemptAt: millis(c["lastAttemptAt"]), now: millis(c["now"]))
            XCTAssertEqual(due, c["due"] as? Bool, c["name"] as? String ?? "")
        }
    }

    func testFileName() throws {
        for c in S.list(try S.fixture("auto_backup")["fileName"]) {
            let zone = try XCTUnwrap(TimeZone(identifier: c["zone"] as? String ?? ""))
            XCTAssertEqual(AutoBackupPolicy.fileName(millis(c["millis"]), timeZone: zone), c["file"] as? String,
                           c["name"] as? String ?? "")
        }
    }

    func testOwnFiles() throws {
        for c in S.list(try S.fixture("auto_backup")["own"]) {
            let name = c["name"] as? String ?? ""
            XCTAssertEqual(AutoBackupPolicy.isOwnFile(name), c["own"] as? Bool, name)
        }
    }

    func testToDelete() throws {
        for c in S.list(try S.fixture("auto_backup")["toDelete"]) {
            let names = c["names"] as? [String] ?? []
            XCTAssertEqual(AutoBackupPolicy.toDelete(names, keep: Int(S.number(c["keep"]) ?? 0)), c["delete"] as? [String] ?? [],
                           c["name"] as? String ?? "")
        }
    }
}
