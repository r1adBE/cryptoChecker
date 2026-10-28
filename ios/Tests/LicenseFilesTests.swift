import XCTest
@testable import CryptoChecker

/// Lizenzdatei der App wie `LICENSE` (beginnt mit «MIT License», ohne Anhang), der Herkunftshinweis in eigener Datei
/// `NOTICE-thirdparty.txt` — wie `LicenseFilesTest.kt` (Android, dort auch der Byte-Vergleich mit `LICENSE`).
final class LicenseFilesTests: XCTestCase {

    func testAppLicenseAndOriginNotice() {
        let license = LicenseNotices.text("MIT-cryptochecker")
        XCTAssertTrue(license.hasPrefix("MIT License"))
        XCTAssertTrue(license.hasSuffix("SOFTWARE."))
        XCTAssertFalse(license.contains("Third-party names"))
        let notice = LicenseNotices.text("NOTICE-thirdparty")
        XCTAssertTrue(notice.hasPrefix("Third-party names, logos and data"))
    }
}
