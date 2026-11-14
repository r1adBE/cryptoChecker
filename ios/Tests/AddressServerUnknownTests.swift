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

    func testVerdrahtung() throws {
        let root = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
        func source(_ path: String) throws -> String {
            try String(contentsOf: root.appendingPathComponent(path), encoding: .utf8)
        }
        let load = try source("App/Features/Portfolio/PortfolioLoadActions.swift")
        XCTAssertTrue(load.contains("data.watchedAddresses.filter { AddressServer.queryAllowed($0.chain, serverUnknown: unknown) }"))
        let manager = try source("App/Services/BackupManager.swift")
        XCTAssertTrue(manager.contains("if journal && input.newSettings.addressServerBtc != nil { StorageRecoveryNotice.confirmAddressServer() }"))
        let card = try source("App/Features/Settings/SettingsAddressServerCard.swift")
        XCTAssertTrue(card.contains("if url != nil { StorageRecoveryNotice.confirmAddressServer() }"))
        XCTAssertTrue(card.contains("if serverUnknown && data.settings.addressServerBtc == nil { confirmPublic() } else { save(nil) }"))
        let notice = try source("App/Services/StorageRecoveryNotice.swift")
        XCTAssertTrue(notice.contains("L(\"settings_address_server_unknown\")"))
    }
}
