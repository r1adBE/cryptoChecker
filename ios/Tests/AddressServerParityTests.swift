import XCTest
@testable import CryptoChecker

/// Eigener Bitcoin-Server für Adressabfragen (`Parity/address_server.json`): Prüfung der Eingabe, Adress-URL, «Testen»,
/// Anbieter-Name, Zustimmung vor dem ersten xpub und Übernahme aus der Sicherung. Gegenstück: `AddressServerParityTest.kt`.
final class AddressServerParityTests: XCTestCase {
    private typealias S = TestSupport

    private func cases(_ key: String) throws -> [[String: Any]] {
        try XCTUnwrap(try S.fixture("address_server")[key] as? [[String: Any]], key)
    }

    func testPruefungWieGemeinsameFaelle() throws {
        let list = try cases("check")
        XCTAssertGreaterThan(list.count, 30)
        for c in list {
            let input = try XCTUnwrap(c["input"] as? String)
            let result = AddressServer.check(input)
            XCTAssertEqual(result.url, c["url"] as? String, "url: \(input)")
            XCTAssertEqual(result.problem?.rawValue, c["problem"] as? String, "problem: \(input)")
        }
    }

    func testUrlsUndTest() throws {
        for c in try cases("urls") {
            let entry = try XCTUnwrap(c["entry"] as? String)
            let address = try XCTUnwrap(c["address"] as? String)
            XCTAssertEqual(AddressServer.btcUrl(entry, address: address), c["url"] as? String)
        }
        let test = try XCTUnwrap(try S.fixture("address_server")["testUrl"] as? [String: Any])
        XCTAssertEqual(AddressServer.testUrl(try XCTUnwrap(test["server"] as? String)), test["url"] as? String)
        for c in try cases("tip") {
            let body = try XCTUnwrap(c["body"] as? String)
            XCTAssertEqual(AddressServer.parseTipHeight(body), (c["height"] as? NSNumber)?.int64Value, "tip: \(body)")
        }
    }

    func testAnbieterName() throws {
        for c in try cases("providers") {
            XCTAssertEqual(AddressBalance.providerName(try XCTUnwrap(c["host"] as? String)), c["name"] as? String)
        }
    }

    func testZustimmungVorDemErstenXpub() throws {
        for c in try cases("consent") {
            let needed = AddressServer.consentNeeded(
                addressCount: try XCTUnwrap(c["addresses"] as? Int), xpubCount: try XCTUnwrap(c["xpubs"] as? Int),
                newIsKey: try XCTUnwrap(c["newIsKey"] as? Bool))
            XCTAssertEqual(needed, c["needed"] as? Bool, "\(c)")
        }
    }

    func testUebernahmeAusDerSicherung() throws {
        let restoreCases = try cases("restore")
        XCTAssertTrue(restoreCases.contains { $0["trusted"] as? Bool == false && $0["current"] is NSNull && $0["value"] is String })
        for c in restoreCases {
            let trusted = try XCTUnwrap(c["trusted"] as? Bool)
            let value = c["value"] as? String
            let current = c["current"] as? String
            XCTAssertEqual(AddressServer.restored(value: value, current: current, trusted: trusted),
                           c["result"] as? String, "\(c)")
            // Hinweis in der Wiederherstellungsmeldung (wie Android)
            let note = BackupRestorePolicy.addressServerNote(value: value, current: current, trusted: trusted)
            XCTAssertEqual(note?.rawValue, c["note"] as? String, "\(c)")
            // Neu gesetzt, wo keiner war: die Meldung nennt den Hostnamen
            XCTAssertEqual(BackupRestorePolicy.addressServerSetHost(value: value, current: current, trusted: trusted),
                           c["host"] as? String, "\(c)")
        }
        XCTAssertGreaterThanOrEqual(restoreCases.filter { $0["note"] as? String == "SET" && $0["host"] is String }.count, 3)
    }

    /// Von der Sicherung abgeschalteter Schutz kommt in die Meldung (`BackupRestorePolicy.loweredProtection`).
    func testAbgeschalteterSchutzWirdGenannt() throws {
        let loweredCases = try cases("lowered")
        XCTAssertGreaterThanOrEqual(loweredCases.count, 7)
        for c in loweredCases {
            let trusted = try XCTUnwrap(c["trusted"] as? Bool)
            let lowered = BackupRestorePolicy.loweredProtection(
                wantedSystemBackup: try XCTUnwrap(c["wantedSystemBackup"] as? Bool),
                currentSystemBackup: try XCTUnwrap(c["currentSystemBackup"] as? Bool),
                wantedHideAmounts: try XCTUnwrap(c["wantedHideAmounts"] as? Bool),
                currentHideAmounts: try XCTUnwrap(c["currentHideAmounts"] as? Bool), trusted: trusted)
            XCTAssertEqual(lowered.systemBackupOn, c["systemBackupOn"] as? Bool, "\(c)")
            XCTAssertEqual(lowered.amountsShown, c["amountsShown"] as? Bool, "\(c)")
            XCTAssertEqual(lowered.any, lowered.systemBackupOn || lowered.amountsShown, "\(c)")
            // Ohne Passwort nie
            if !trusted { XCTAssertFalse(lowered.any, "\(c)") }
        }
    }

    /// Gesetzt: nur der eigene Server, kein Rückfall auf öffentliche Hosts.
    func testEigenerServerOhneRueckfall() {
        XCTAssertEqual(AddressServer.btcEntries("https://node.example.org/api"), ["https://node.example.org/api"])
        XCTAssertEqual(AddressServer.btcEntries(nil), AddressBalance.btcHosts)
    }

    /// Sicherung: ohne Passwort nie gesetzt, ersetzt oder gelöscht, mit Passwort schon — wie Android.
    func testSicherungOhnePasswortLoeschtNicht() throws {
        var current = AppSettings()
        current.addressServerBtc = "https://a.example/api"
        let cleared = TestSupport.settingsBackup(current, ["addressServerBtc": NSNull()])
        let other = TestSupport.settingsBackup(current, ["addressServerBtc": "https://B.example/api/"])
        XCTAssertEqual(try BackupManager.applySettings(cleared, onto: current, trusted: false).addressServerBtc, "https://a.example/api")
        XCTAssertNil(try BackupManager.applySettings(cleared, onto: current, trusted: true).addressServerBtc)
        // Fehlt das Feld, ist die Sicherung unlesbar
        var missing = TestSupport.settingsBackup(current)
        missing.removeValue(forKey: "addressServerBtc")
        XCTAssertThrowsError(try BackupManager.applySettings(missing, onto: current, trusted: true))
        XCTAssertEqual(try BackupManager.applySettings(other, onto: current, trusted: false).addressServerBtc, "https://a.example/api")
        XCTAssertEqual(try BackupManager.applySettings(other, onto: current, trusted: true).addressServerBtc, "https://b.example/api")
        // Ohne Passwort auch keinen Server setzen, wo keiner war
        XCTAssertNil(try BackupManager.applySettings(other, onto: AppSettings(), trusted: false).addressServerBtc)
    }
}
