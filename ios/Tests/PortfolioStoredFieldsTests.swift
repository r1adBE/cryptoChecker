import XCTest
@testable import CryptoChecker

/// Gemeinsame Testfälle `portfolio_stored_fields.json` (Android: `PortfolioStoredFieldsParityTest.kt`), dazu die Felder
/// verknüpfter Umbuchungen in Datei und Sicherung.
final class PortfolioStoredFieldsTests: XCTestCase {

    private typealias S = TestSupport

    private func data() throws -> [String: Any] { try S.fixture("portfolio_stored_fields") }

    private func string(_ value: Any?) -> String? { value as? String }

    /// Gleiche JSON-Struktur (Reihenfolge der Schlüssel egal).
    private func sameJSON(_ a: [String: Any], _ b: [String: Any]) -> Bool {
        NSDictionary(dictionary: a).isEqual(to: b)
    }

    func testRestoreStateRoundTripsAndRejectsInvalid() throws {
        let d = try data()
        let states = S.list(d["restoreStates"])
        for c in states {
            let name = string(c["name"]) ?? "?"
            let e = try XCTUnwrap(c["json"] as? [String: Any], name)
            let state = try XCTUnwrap(CsvRestoreState.from(json: e), name)
            XCTAssertTrue(sameJSON(state.json(), e), name)
            XCTAssertEqual(CsvRestoreState.from(json: state.json()), state)
        }
        for c in S.list(d["restoreStateInvalid"]) {
            XCTAssertNil(CsvRestoreState.from(json: c["json"]), string(c["name"]) ?? "?")
        }
        // Unsinnig langer Inhalt (Sicherung von aussen) wird nicht gelesen
        var long = try XCTUnwrap(states.compactMap { $0["json"] as? [String: Any] }.first)
        long["padding"] = String(repeating: "x", count: CsvRestoreState.maxText)
        XCTAssertNil(CsvRestoreState.from(json: long))
    }

    /// Verknüpfte Umbuchung: zweiter Herkunftsschlüssel und Zustand davor übersteht Datei und Sicherung; zweiter Schlüssel
    /// ohne ersten bzw. Zustand ohne Verknüpfung fällt weg. Wie Android `PortfolioJsonTypesTest.linkedTransferFieldsRoundTrip`.
    func testLinkedTransferFieldsRoundTrip() throws {
        let stateJSON: [String: Any] = [
            "v": 1,
            "stored": ["type": "WITHDRAWAL", "amount": "0.005", "time": 1_704_987_000_000, "importKey": "BINANCE|W",
                       "importBatch": "BINANCE:1729000000000:0a1b2c3d"],
            "absorbed": ["type": "DEPOSIT", "amount": "0.0049", "time": 1_704_989_100_000, "importKey": "COINBASE|D"],
        ]
        var linked = PortfolioTx(id: 5, coin: "BTC", type: .TRANSFER, amountExact: 1, time: 12)
        linked.importKey = "BINANCE|W"
        linked.linkedImportKey = "COINBASE|D"
        linked.importRestoreState = try XCTUnwrap(CsvRestoreState.from(json: stateJSON))
        linked.importBatch = "COINBASE:1729100000000:1b2c3d4e"
        XCTAssertEqual(BackupManager.jsonToTx(BackupManager.txToJson(linked)), linked)
        XCTAssertEqual(try JSONDecoder().decode(PortfolioTx.self, from: JSONEncoder().encode(linked)), linked)
        var orphan = BackupManager.txToJson(linked)
        orphan["importKey"] = NSNull()
        let restoredOrphan = try XCTUnwrap(BackupManager.jsonToTx(orphan))
        XCTAssertNil(restoredOrphan.linkedImportKey)
        XCTAssertNil(restoredOrphan.importRestoreState)
    }

    /// Zins nur bei ganzen Wörtern («Verzinsung», «Zinseszins» nicht). Wie Android.
    func testInterestRewardMatchesWholeWords() throws {
        for c in S.list(try data()["interestReward"]) {
            let text = string(c["text"])
            let expected = try XCTUnwrap(c["interest"] as? Bool)
            XCTAssertEqual(TaxEngines.interestReward(text), expected, "interestReward: \(text ?? "nil")")
        }
    }
}
