import XCTest
@testable import CryptoChecker

/// Einstellungen ersetzt (unlesbar) → eigener Bitcoin-Server unbekannt: Bitcoin-Adressen werden nicht abgefragt,
/// bis der Nutzer den Server neu einträgt oder öffentliche Knoten bestätigt. Regel: `AddressServerParityTests`;
/// hier Merker und Verdrahtung. Gegenstück Android: Regel `android-wiring` in `.github/scripts/source_rules.py`.
final class AddressServerUnknownTests: XCTestCase {
    private var defaults: UserDefaults!
    private let suite = "AddressServerUnknownTests"

    override func setUp() {
        defaults = UserDefaults(suiteName: suite)
        defaults.removePersistentDomain(forName: suite)
    }

    override func tearDown() {
        defaults.removePersistentDomain(forName: suite)
    }

    func testReplacedSettingsMakeTheServerUnknown() {
        XCTAssertFalse(StorageRecoveryNotice.addressServerUnknown(defaults: defaults))
        StorageRecoveryNotice.recordSettingsReset(defaults: defaults)
        XCTAssertTrue(StorageRecoveryNotice.addressServerUnknown(defaults: defaults))
        StorageRecoveryNotice.confirmAddressServer(defaults: defaults)
        XCTAssertFalse(StorageRecoveryNotice.addressServerUnknown(defaults: defaults))
    }
}
