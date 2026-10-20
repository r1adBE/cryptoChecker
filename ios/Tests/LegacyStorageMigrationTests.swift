import XCTest
@testable import CryptoChecker

/// Gemeinsame Testfälle `legacy_storage_migration.json` (Android: `LegacyStorageMigrationParityTest.kt`), dazu das Lesen
/// alter Dateien (`PortfolioTx`) und Sicherungen (`BackupManager`).
final class LegacyStorageMigrationTests: XCTestCase {

    private typealias S = TestSupport

    private func data() throws -> [String: Any] { try S.fixture("legacy_storage_migration") }

    private func string(_ value: Any?) -> String? { value as? String }

    private func fields(_ o: [String: Any]) -> LegacyStorageMigration.Fields {
        LegacyStorageMigration.Fields(transferTo: string(o["transferTo"]), giftToSpouse: o["giftToSpouse"] as? Bool ?? false,
                                      importKey: string(o["importKey"]), linkedImportKey: string(o["linkedImportKey"]),
                                      importRestoreState: CsvRestoreState.from(json: o["importRestoreState"]))
    }

    /// Gleiche JSON-Struktur (Reihenfolge der Schlüssel egal); beide fehlend zählt als gleich.
    private func sameJSON(_ a: [String: Any]?, _ b: Any?) -> Bool {
        guard let a, let b = b as? [String: Any] else { return a == nil && (b == nil || b is NSNull) }
        return NSDictionary(dictionary: a).isEqual(to: b)
    }

    func testLegacyFormsBecomeFields() throws {
        for c in S.list(try data()["cases"]) {
            let name = string(c["name"]) ?? "?"
            let type = PortfolioTxType(rawValue: string(c["type"]) ?? "")
            let before = try XCTUnwrap(c["before"] as? [String: Any])
            let after = try XCTUnwrap(c["after"] as? [String: Any])
            let converted = LegacyStorageMigration.convert(type, fields(before))
            XCTAssertEqual(converted.transferTo, string(after["transferTo"]), "\(name) transferTo")
            XCTAssertEqual(converted.giftToSpouse, after["giftToSpouse"] as? Bool ?? false, "\(name) giftToSpouse")
            XCTAssertEqual(converted.importKey, string(after["importKey"]), "\(name) importKey")
            XCTAssertEqual(converted.linkedImportKey, string(after["linkedImportKey"]), "\(name) linkedImportKey")
            XCTAssertTrue(sameJSON(converted.importRestoreState?.json(), after["importRestoreState"]), "\(name) importRestoreState")
            // Beliebig oft anwendbar: ein zweiter Lauf ändert nichts mehr
            XCTAssertEqual(LegacyStorageMigration.convert(type, converted), converted, "\(name) idempotent")
        }
    }

    func testRestoreStateRoundTripsAndRejectsInvalid() throws {
        let d = try data()
        for c in S.list(d["cases"]) {
            guard let e = (c["after"] as? [String: Any])?["importRestoreState"] as? [String: Any] else { continue }
            let state = try XCTUnwrap(CsvRestoreState.from(json: e), string(c["name"]) ?? "?")
            XCTAssertTrue(sameJSON(state.json(), e), string(c["name"]) ?? "?")
            XCTAssertEqual(CsvRestoreState.from(json: state.json()), state)
        }
        for c in S.list(d["restoreStateInvalid"]) {
            XCTAssertNil(CsvRestoreState.from(json: c["json"]), string(c["name"]) ?? "?")
        }
        // Unsinnig langer Inhalt (Sicherung von aussen) wird nicht gelesen
        var long = try XCTUnwrap(S.list(d["cases"]).compactMap { ($0["after"] as? [String: Any])?["importRestoreState"] as? [String: Any] }.first)
        long["padding"] = String(repeating: "x", count: CsvRestoreState.maxText)
        XCTAssertNil(CsvRestoreState.from(json: long))
    }

    /// Neue Eingaben: Zins nur bei ganzen Wörtern («Verzinsung», «Zinseszins» nicht). Altbestand (alte Dateien, Sicherungen
    /// ohne Feld): eingefrorene Teilstring-Regel, damit sich alte Daten nicht nachträglich ändern. Wie Android.
    func testInterestRewardMatchesWholeWords() throws {
        for c in S.list(try data()["interestReward"]) {
            let text = string(c["text"])
            let expected = try XCTUnwrap(c["interest"] as? Bool)
            let legacy = try XCTUnwrap(c["legacyInterest"] as? Bool)
            XCTAssertEqual(TaxEngines.interestReward(text), expected, "interestReward: \(text ?? "nil")")
            XCTAssertEqual(LegacyStorageMigration.interestRewardLegacy(text), legacy, "interestRewardLegacy: \(text ?? "nil")")
            XCTAssertEqual(LegacyStorageMigration.legacyInterest(.REWARD, note: text), legacy, "legacyInterest: \(text ?? "nil")")
            XCTAssertFalse(LegacyStorageMigration.legacyInterest(.BUY, note: text), "legacyInterest BUY: \(text ?? "nil")")
        }
    }

    /// Datei bis Build 57 (`portfolio.json`): alte Formen werden beim Lesen Felder, das Speichern schreibt nur noch diese.
    func testOldFileFormsBecomeFieldsOnLoad() throws {
        let old = """
        [{"id": 1, "coin": "BTC", "type": "GIFT", "amountText": "0.5", "time": 10, "transferTo": "@spouse"},
         {"id": 2, "coin": "BTC", "type": "TRANSFER", "amountText": "0.005", "time": 1704987000000, "transferFrom": "Binance",
          "transferTo": "Coinbase", "importBatch": "COINBASE:1729100000000:1b2c3d4e",
          "importKey": "BINANCE|W ⇄ COINBASE|D ⟲ 2|BINANCE:1729000000000:0a1b2c3d|WITHDRAWAL|0.005||1704987000000||||||||| ⟲ DEPOSIT|0.0049||1704989100000||||"}]
        """
        let txs = try JSONDecoder().decode([PortfolioTx].self, from: Data(old.utf8))
        XCTAssertTrue(txs[0].giftToSpouse)
        XCTAssertTrue(txs[0].isGiftToSpouse)
        XCTAssertNil(txs[0].transferTo)
        XCTAssertEqual(txs[1].importKey, "BINANCE|W")
        XCTAssertEqual(txs[1].linkedImportKey, "COINBASE|D")
        XCTAssertEqual(txs[1].importRestoreState?.importBatch, "BINANCE:1729000000000:0a1b2c3d")
        XCTAssertEqual(txs[1].importRestoreState?.absorbed?.importKey, "COINBASE|D")
        // Neue Form: kein Merker, keine gepackten Schlüssel; liest sich unverändert zurück
        let saved = String(decoding: try JSONEncoder().encode(txs), as: UTF8.self)
        XCTAssertFalse(saved.contains("@spouse") || saved.contains("⇄") || saved.contains("⟲"))
        XCTAssertEqual(try JSONDecoder().decode([PortfolioTx].self, from: Data(saved.utf8)), txs)
    }

    /// Sicherung bis Build 57: alte Formen werden beim Wiederherstellen Felder; die neue Form übersteht die Sicherung. Wie Android.
    func testOldBackupFormsBecomeFields() throws {
        var gift = BackupManager.txToJson(PortfolioTx(id: 9, coin: "BTC", type: .GIFT, amountExact: 1, time: 12))
        gift["transferTo"] = "@spouse"
        gift.removeValue(forKey: "giftToSpouse")
        let restoredGift = try XCTUnwrap(BackupManager.jsonToTx(gift))
        XCTAssertTrue(restoredGift.giftToSpouse)
        XCTAssertNil(restoredGift.transferTo)
        var link = BackupManager.txToJson(PortfolioTx(id: 5, coin: "BTC", type: .TRANSFER, amountExact: 1, time: 12))
        link["importKey"] = "BINANCE|W5 ⇄ COINBASE|D5 ⟲ 1|BINANCE:1729000000000:0a1b2c3d|WITHDRAWAL|0.005||1704987000000|||||||||"
        link["importBatch"] = "COINBASE:1729100000000:1b2c3d4e"
        link.removeValue(forKey: "linkedImportKey")
        link.removeValue(forKey: "importRestoreState")
        let linked = try XCTUnwrap(BackupManager.jsonToTx(link))
        XCTAssertEqual(linked.importKey, "BINANCE|W5")
        XCTAssertEqual(linked.linkedImportKey, "COINBASE|D5")
        XCTAssertNil(linked.importRestoreState?.absorbed)
        XCTAssertEqual(linked.importRestoreState?.importKey, "BINANCE|W5")
        XCTAssertEqual(BackupManager.jsonToTx(BackupManager.txToJson(linked)), linked)
        // Zweiter Schlüssel ohne ersten bzw. Zustand ohne Verknüpfung: weggelassen
        var orphan = BackupManager.txToJson(linked)
        orphan["importKey"] = NSNull()
        let restoredOrphan = try XCTUnwrap(BackupManager.jsonToTx(orphan))
        XCTAssertNil(restoredOrphan.linkedImportKey)
        XCTAssertNil(restoredOrphan.importRestoreState)
    }
}
