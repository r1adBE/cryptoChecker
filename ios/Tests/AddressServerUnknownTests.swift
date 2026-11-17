import XCTest
@testable import CryptoChecker

/// Einstellungen ersetzt (unlesbar) → eigener Bitcoin-Server unbekannt (S3-02): Bitcoin-Adressen werden nicht abgefragt,
/// bis der Nutzer den Server neu einträgt oder öffentliche Knoten bestätigt. Regel: `AddressServerParityTests`;
/// hier Merker und Verdrahtung. Gegenstück Android: `AddressServerUnknownTest.kt`.
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

    func testErsetzteEinstellungenMachenDenServerUnbekannt() {
        XCTAssertFalse(StorageRecoveryNotice.addressServerUnknown(defaults: defaults))
        StorageRecoveryNotice.recordSettingsReset(defaults: defaults)
        XCTAssertTrue(StorageRecoveryNotice.addressServerUnknown(defaults: defaults))
        StorageRecoveryNotice.confirmAddressServer(defaults: defaults)
        XCTAssertFalse(StorageRecoveryNotice.addressServerUnknown(defaults: defaults))
    }
}
