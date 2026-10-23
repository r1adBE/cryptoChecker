import XCTest
@testable import CryptoChecker

/// Regeln zum eigenen Bitcoin-Server (`Parity/address_server_ui.json`): Eingabe kürzen, Pausen-Schlüssel, Sperr-Ausnahme,
/// Testergebnis zur Eingabe, Rückfrage vor dem Wechsel auf öffentliche Knoten. Gegenstück: `AddressServerRulesParityTest.kt`.
final class AddressServerRulesParityTests: XCTestCase {
    private typealias S = TestSupport

    private func cases(_ key: String) throws -> [[String: Any]] {
        let list = try XCTUnwrap(try S.fixture("address_server_ui")[key] as? [[String: Any]], key)
        XCTAssertFalse(list.isEmpty, key)
        return list
    }

    func testEingabeWirdGekuerzt() throws {
        for c in try cases("clamp") {
            XCTAssertEqual(AddressServerRules.clamp(try XCTUnwrap(c["input"] as? String)), c["output"] as? String)
        }
    }

    func testHostSchluesselUndEigenerServer() throws {
        for c in try cases("hostKey") {
            XCTAssertEqual(AddressServerRules.hostKey(try XCTUnwrap(c["entry"] as? String)), c["key"] as? String)
        }
        for c in try cases("own") {
            let entries = try XCTUnwrap(c["entries"] as? [String])
            XCTAssertEqual(AddressServerRules.isOwnServer(entries), c["own"] as? Bool, "own: \(entries)")
        }
    }

    func testTestergebnisNurZurEingabe() throws {
        for c in try cases("testShown") {
            XCTAssertEqual(AddressServerRules.testShown(c["tested"] as? String, inputUrl: c["input"] as? String),
                           c["shown"] as? Bool, "\(c)")
        }
    }

    func testRueckfrageVorOeffentlichenKnoten() throws {
        for c in try cases("confirmPublic") {
            let count = try XCTUnwrap(c["btcCount"] as? Int)
            let ask = AddressServerRules.confirmPublicSwitch(current: c["current"] as? String, next: c["next"] as? String,
                                                             btcCount: count)
            XCTAssertEqual(ask, c["ask"] as? Bool, "\(c)")
        }
    }

    /// Mehrere Abrufe gleichzeitig im Vordergrund: die Bitcoin-Einträge reisen als Parameter mit, kein gemeinsames Feld
    /// des Actors (sonst überschriebe ein zweiter Abruf an einem `await` die Hosts des ersten).
    func testBitcoinEintraegeKeinActorFeld() throws {
        let root = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
        for name in ["WatchedAddressSource.swift", "WatchedAddressSource+Chains.swift"] {
            let text = try String(contentsOf: root.appendingPathComponent("Shared/Portfolio/\(name)"), encoding: .utf8)
            XCTAssertFalse(text.contains("var btcEntries"), name)
            XCTAssertFalse(text.contains("= btcEntries\n"), name)
        }
    }
}
