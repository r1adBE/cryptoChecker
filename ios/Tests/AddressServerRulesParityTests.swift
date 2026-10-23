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
            let output = AddressServerRules.clamp(try XCTUnwrap(c["input"] as? String))
            XCTAssertEqual(output, c["output"] as? String)
            // Gekürzte Eingabe gilt nie still als gültig: 201/250 Zeichen → INVALID, genau 200 → gültig
            XCTAssertEqual(AddressServer.check(output).problem?.rawValue, c["problem"] as? String, "Länge \(output.count)")
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
            // «chains» statt «btcCount»: Zahl aus der Liste (null = nicht lesbar → fragen)
            let count = try c["btcCount"] != nil ? XCTUnwrap(c["btcCount"] as? Int)
                : AddressServerRules.btcCount(Self.addresses(c["chains"]))
            let ask = AddressServerRules.confirmPublicSwitch(current: c["current"] as? String, next: c["next"] as? String,
                                                             btcCount: count)
            XCTAssertEqual(ask, c["ask"] as? Bool, "\(c)")
        }
    }

    func testBitcoinAnzahlListeNichtLesbarFragt() throws {
        for c in try cases("btcCount") {
            XCTAssertEqual(AddressServerRules.btcCount(Self.addresses(c["chains"])), c["count"] as? Int, "\(c)")
        }
    }

    /// Fixture-Liste der Ketten → Adressen; null (JSON) → nil = Liste nicht lesbar.
    private static func addresses(_ chains: Any?) -> [WatchedAddress]? {
        guard let list = chains as? [String] else { return nil }
        return list.enumerated().map { i, chain in
            WatchedAddress(id: Int64(i + 1), chain: AddressChain(rawValue: chain) ?? .BTC, address: "a\(i)")
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
            // Bitcoin-Hosts und eigener Server immer ausdrücklich: kein Standardwert, der still öffentliche Knoten wählt
            XCTAssertFalse(text.contains("btcEntries: [String] ="), name)
            XCTAssertFalse(text.contains("btcServer: String? = nil"), name)
        }
    }
}
