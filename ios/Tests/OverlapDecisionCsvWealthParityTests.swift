import XCTest
@testable import CryptoChecker

/// Sicherung mit Überschneidungs-Entscheid (`address_balance.json` «backupOverlap»), Formelschutz nach führenden
/// Leerzeichen, Hinweis «unvollständig» im CH-Vermögenswert und Speichern ohne Schlüssel — gleiche Fälle wie Android
/// `OverlapDecisionCsvWealthParityTest.kt`.
final class OverlapDecisionCsvWealthParityTests: XCTestCase {

    private typealias S = TestSupport

    /// Entscheid «trotzdem zählen» übersteht Sichern, Einspielen und erneutes Sichern.
    func testBackupKeepsOverlapDecision() throws {
        let c = try XCTUnwrap(try S.fixture("address_balance")["backupOverlap"] as? [String: Any])
        let array = try JSONSerialization.jsonObject(with: Data((c["text"] as? String ?? "").utf8)) as? [Any]
        let parsed = WatchedAddressRules.entries(fromJSON: array)
        let expected = S.list(c["expected"])
        XCTAssertEqual(parsed.count, expected.count)
        for (x, e) in zip(expected, parsed) {
            let name = x["address"] as? String ?? ""
            XCTAssertEqual(e.chain.rawValue, x["chain"] as? String, name)
            XCTAssertEqual(e.address, name)
            XCTAssertEqual(e.overlapChecked, x["overlapChecked"] as? Bool, "\(name) checked")
        }
        // Geschrieben nur die gesetzten Felder; zurückgelesen ohne Verlust (auch über Codable)
        let json = WatchedAddressRules.json(parsed)
        for (x, o) in zip(expected, json) {
            let written = ["overlapChecked"].filter { o[$0] != nil }
            XCTAssertEqual(written, x["written"] as? [String], "\(x["address"] ?? "") written")
        }
        let reread = try JSONSerialization.jsonObject(with: JSONSerialization.data(withJSONObject: json)) as? [Any]
        XCTAssertEqual(WatchedAddressRules.entries(fromJSON: reread), parsed)
        let encoded = try JSONEncoder().encode(parsed)
        XCTAssertEqual(try JSONDecoder().decode([WatchedAddressBackupEntry].self, from: encoded), parsed)
        // Einspielen übernimmt den Entscheid, und die nächste Sicherung schreibt ihn wieder
        let merged = WatchedAddressRules.merge([], parsed)
        for (x, a) in zip(expected, merged) {
            XCTAssertEqual(a.overlapChecked, x["overlapChecked"] as? Bool, "\(a.address) merge checked")
            XCTAssertEqual(a.countInPortfolio, x["countInPortfolio"] as? Bool, "\(a.address) merge count")
        }
        XCTAssertEqual(WatchedAddressRules.backupEntries(merged), parsed)
    }

    /// Eröffnungsposten gehen in die Sicherung (wie Android «costLots») und kommen gleich zurück.
    func testBackupJsonKeepsCostLots() throws {
        let lot = AddressCostLot(id: 1, coin: "BTC", time: 1_600_000_000_000, amount: Decimal(string: "0.3")!,
                                 priceUsdt: Decimal(10000))
        let entry = WatchedAddressBackupEntry(chain: .BTC, address: "bc1qxy2kgdygjrsqtzq2n0yrf2493p83kkfjhx0wlh",
                                              costLots: [lot])
        let json = WatchedAddressRules.json([entry])
        let lots = try XCTUnwrap(json.first?["costLots"] as? [[String: Any]])
        XCTAssertEqual(lots.first?["amount"] as? String, "0.3")
        XCTAssertEqual(lots.first?["price"] as? String, "10000")
        let reread = try JSONSerialization.jsonObject(with: JSONSerialization.data(withJSONObject: json)) as? [Any]
        XCTAssertEqual(WatchedAddressRules.entries(fromJSON: reread), [entry])
    }

    /// « =1+1» ist für Tabellen trotz Leerzeichen eine Formel; Zahlen und gewöhnlicher Text bleiben unverändert.
    func testFormulaAfterLeadingSpaces() {
        XCTAssertEqual(CutoffExport.escape(" =1+1"), "' =1+1")
        XCTAssertEqual(CutoffExport.escape("   @SUM(A1)"), "'   @SUM(A1)")
        XCTAssertEqual(CutoffExport.escape("\u{00A0}+41"), "'\u{00A0}+41")
        XCTAssertEqual(CutoffExport.escape(" \t-x"), "' \t-x")
        XCTAssertEqual(CutoffExport.escape(" =a;b"), "\"' =a;b\"")
        XCTAssertEqual(CutoffExport.escape("  BTC"), "  BTC")
        XCTAssertEqual(CutoffExport.escape("   "), "   ")
        XCTAssertEqual(CutoffExport.escapeNumber(" -12.50"), " -12.50")
    }

    /// Nur ein Teil fehlt: Wert steht, Hinweis «unvollständig – Grund» statt «nicht gerechnet».
    func testWealthNoteForPartialCoin() {
        var labels: [TaxLine: String] = [:]
        for l in TaxLine.allCases { labels[l] = l.rawValue }
        let texts = TaxSummaryTexts(title: "T", disclaimer: "D", item: "Item", usdt: "USDT", target: "CHF", note: "Note",
                                    orientation: "O", labels: labels, below: "below", reached: "reached", unknown: "unknown",
                                    incomplete: "incomplete", noFx: "nofx", wealthMissing: "nc",
                                    wealthAddressUnobserved: "unobserved")
        let partial = TaxWealthRow(coin: "BTC", amount: Decimal(string: "0.1")!, priceUsdt: 60000, valueUsdt: 6000,
                                   valueTarget: 4800, addressUnobserved: true, incomplete: true)
        XCTAssertEqual(texts.wealthCoinNote(partial), "incomplete – unobserved")
        let missing = TaxWealthRow(coin: "BTC", amount: Decimal(string: "0.1")!, priceUsdt: 60000, valueUsdt: nil,
                                   valueTarget: nil, addressUnobserved: true)
        XCTAssertEqual(texts.wealthCoinNote(missing), "nc – unobserved")
    }

    /// Ohne Schlüssel nur dann «gespeichert» melden, wenn noch kein Stand in der Datei liegt (wie Android
    /// `PortfolioStorageLock`: mit gespeichertem Stand gesperrt, ohne nur im Arbeitsspeicher mit Hinweis).
    func testUnsealedKeptOnlyWithoutStoredFile() {
        XCTAssertTrue(PortfolioStore.keepsUnsealed(storedFileExists: false))
        XCTAssertFalse(PortfolioStore.keepsUnsealed(storedFileExists: true))
    }
}
