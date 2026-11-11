import XCTest
@testable import CryptoChecker

/// Sicherung mit Portfolio-Daten: Transaktionen, beobachtete Adressen und Portfolio-Alarme zählen gleich — dann Passwort
/// Pflicht und Entsperren vor dem Sichern. Gemeinsame Fälle mit Android (`BackupPortfolioDataParityTest.kt`,
/// `backup_portfolio_data.json`).
final class BackupPortfolioDataParityTests: XCTestCase {
    func testPortfolioDataMatchesFixture() throws {
        let cases = TestSupport.list(try TestSupport.fixture("backup_portfolio_data")["cases"])
        XCTAssertEqual(cases.count, 6)
        for c in cases {
            let label = "\(c["name"] ?? "")"
            let has = PortfolioLockPolicy.backupHasPortfolioData(
                transactions: c["transactions"] as? Int ?? -1,
                watchedAddresses: c["watchedAddresses"] as? Int ?? -1,
                portfolioAlarms: c["portfolioAlarms"] as? Int ?? -1)
            XCTAssertEqual(has, c["hasPortfolioData"] as? Bool, label)
            XCTAssertEqual(PortfolioLockPolicy.backupExportRequiresPassword(hasPortfolioData: has), has, label)
            XCTAssertEqual(PortfolioLockPolicy.backupExportAllowed(hasPassword: false, hasPortfolioData: has),
                           c["allowedWithoutPassword"] as? Bool, label)
            XCTAssertTrue(PortfolioLockPolicy.backupExportAllowed(hasPassword: true, hasPortfolioData: has), label)
            XCTAssertEqual(PortfolioLockPolicy.backupExportNeedsUnlock(locked: true, hasPortfolioData: has),
                           c["needsUnlockWhenLocked"] as? Bool, label)
            XCTAssertFalse(PortfolioLockPolicy.backupExportNeedsUnlock(locked: false, hasPortfolioData: has), label)
        }
    }
}
