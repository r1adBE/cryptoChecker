import Security
import XCTest
@testable import CryptoChecker

/// Der Test-Schlüsselbund greift nur ohne Schlüsselbund-Berechtigung, nie bei gesperrtem oder verweigertem Zugriff.
final class HostStorageKeyTests: XCTestCase {
    func testOnlyMissingEntitlementUsesTheTestKeychain() {
        XCTAssertTrue(HostStorageKey.shouldUseTestKeychain(status: errSecMissingEntitlement))
        XCTAssertTrue(HostStorageKey.shouldUseTestKeychain(status: errSecNotAvailable))
        XCTAssertFalse(HostStorageKey.shouldUseTestKeychain(status: errSecInteractionNotAllowed))
        XCTAssertFalse(HostStorageKey.shouldUseTestKeychain(status: errSecAuthFailed))
        XCTAssertFalse(HostStorageKey.shouldUseTestKeychain(status: nil))
        XCTAssertFalse(HostStorageKey.shouldUseTestKeychain(status: errSecSuccess))
        XCTAssertFalse(HostStorageKey.shouldUseTestKeychain(status: errSecDuplicateItem))
    }
}
