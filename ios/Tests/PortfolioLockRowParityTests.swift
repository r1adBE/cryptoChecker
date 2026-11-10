import XCTest
@testable import CryptoChecker

/// Zeile «Portfolio-Sperre» und Kurzwert «Portfolio»: gemeinsame Fälle mit Android
/// (`PortfolioLockRowParityTest.kt`, `portfolio_lock_row.json`).
final class PortfolioLockRowParityTests: XCTestCase {
    private func name(_ p: SettingsSummary.Portfolio) -> String {
        switch p {
        case .off: return "off"
        case .offLocked: return "offLocked"
        case .on: return "on"
        case .locked: return "locked"
        }
    }

    func testRowAndSummaryMatchFixture() throws {
        let cases = TestSupport.list(try TestSupport.fixture("portfolio_lock_row")["cases"])
        XCTAssertEqual(cases.count, 4)
        for c in cases {
            let label = "\(c["name"] ?? "")"
            let enabled = c["portfolioEnabled"] as? Bool ?? false
            let lock = c["lock"] as? Bool ?? false
            XCTAssertEqual(PortfolioLockPolicy.showSetting(portfolioEnabled: enabled, lockOn: lock),
                           c["showSetting"] as? Bool, label)
            XCTAssertEqual(name(SettingsSummary.portfolio(enabled: enabled, lock: lock)), c["summary"] as? String, label)
        }
    }
}
