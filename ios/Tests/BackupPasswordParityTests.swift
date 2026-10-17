import XCTest
@testable import CryptoChecker

/// Schwache/starke Passwörter und Versionen der Sicherung (`backup_passwords.json`); Gegenstück:
/// `BackupPasswordParityTest.kt`.
final class BackupPasswordParityTests: XCTestCase {

    func testPasswordsMatchFixture() throws {
        let root = try TestSupport.fixture("backup_passwords")
        let weak = root["weak"] as? [String] ?? []
        let strong = root["strong"] as? [String] ?? []
        XCTAssertTrue(weak.count >= 20 && strong.count >= 6)
        for p in weak { XCTAssertTrue(BackupPasswordStrength.isWeak(p), p) }
        for p in strong { XCTAssertFalse(BackupPasswordStrength.isWeak(p), p) }
    }

    func testVersionsMatchFixture() throws {
        let root = try TestSupport.fixture("backup_passwords")
        for c in TestSupport.list(root["versions"]) {
            let name = c["name"] as? String ?? ""
            let raw: Any? = c["version"] is NSNull ? nil : c["version"]
            if let expected = c["result"] as? String {
                XCTAssertThrowsError(try BackupVersion.check(raw), name) { error in
                    switch error as? BackupVersion.Failure {
                    case .some(.newer): XCTAssertEqual(expected, "newer", name)
                    case .some(.invalid): XCTAssertEqual(expected, "invalid", name)
                    case .none: XCTFail("\(name): \(error)")
                    }
                }
            } else {
                let expected = Int(TestSupport.number(c["result"]) ?? -1)
                XCTAssertEqual(try BackupVersion.check(raw), expected, name)
            }
        }
    }
}
