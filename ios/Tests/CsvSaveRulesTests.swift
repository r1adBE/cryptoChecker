import XCTest
@testable import CryptoChecker

/// Was der CSV-Import speichert (`csv_import.json` › «priceText», «importKeys», «cleanImportKeys», «rateDays»,
/// «rateCover»): Preise mit ihren gültigen Stellen, lange Herkunftsschlüssel als Hash, Schlüssel in UTF-8-Bytes begrenzt
/// und Tageskurse neu geladen, wenn eine andere Zeitzone einen Tag verschiebt. Gegenstück: `CsvSaveRulesParityTest.kt`.
final class CsvSaveRulesTests: XCTestCase {

    private typealias S = TestSupport

    private let posix = Locale(identifier: "en_US_POSIX")

    private func data() throws -> [String: Any] { try S.fixture("csv_import") }

    private func decimal(_ value: Any?) -> Decimal? { (value as? String).flatMap { Decimal(string: $0, locale: posix) } }

    func testPricesKeepTheirDigits() throws {
        let all = S.list(try data()["priceText"])
        XCTAssertGreaterThanOrEqual(all.count, 10)
        for c in all {
            let value = try XCTUnwrap(decimal(c["value"]))
            let stored = PortfolioDecimal.priceText(value)
            XCTAssertEqual(stored, c["expected"] as? String, "priceText \(c)")
            // Gespeichert und wieder gelesen: unverändert (Lesegrenze 40 Nachkommastellen)
            XCTAssertEqual(PortfolioDecimal.parse(stored), PortfolioDecimal.price(value), "roundtrip \(c)")
        }
        // Mengen bleiben bei 18 Nachkommastellen
        XCTAssertEqual(PortfolioDecimal.text(Decimal(string: "0.0000000000000000004", locale: posix) ?? 1), "0")
    }

    func testLongKeysBecomeAHash() throws {
        for c in S.list(try data()["importKeys"]) {
            let keys = (c["keys"] as? [String]) ?? []
            let out = CsvImport.withOccurrence(keys)
            XCTAssertEqual(out, (c["expected"] as? [String]) ?? [], c["name"] as? String ?? "?")
            for k in out { XCTAssertEqual(PortfolioTx.cleanImportKey(k), k, "\(c["name"] ?? "?"): gespeichert") }
        }
        XCTAssertEqual(PortfolioTx.maxImportKey, CsvImport.maxKeyBytes)
    }

    func testKeysLimitedInUtf8Bytes() throws {
        for c in S.list(try data()["cleanImportKeys"]) {
            XCTAssertEqual(PortfolioTx.cleanImportKey(c["key"] as? String), c["expected"] as? String, "cleanImportKey \(c)")
        }
    }

    private func days(_ value: Any?) -> [String: ClosedRange<Int64>] {
        var out: [String: ClosedRange<Int64>] = [:]
        for (currency, range) in (value as? [String: Any]) ?? [:] {
            let r = ((range as? [String]) ?? []).compactMap { LocalDay(iso: $0)?.epochDay }.map(Int64.init)
            if r.count == 2 { out[currency] = r[0]...r[1] }
        }
        return out
    }

    func testOtherZoneReloadsRatesWhenADayFallsOutside() throws {
        let files = S.list(try data()["files"])
        let all = S.list(try data()["rateDays"])
        XCTAssertTrue(all.contains { ($0["cover"] as? Bool) == false } && all.contains { ($0["cover"] as? Bool) == true })
        for c in all {
            let csv = try XCTUnwrap(files.first { $0["name"] as? String == c["file"] as? String }?["csv"] as? String)
            let device = c["deviceZone"] as? String ?? ""
            let first = CsvImportParser.parse(csv, deviceZone: device, zone: c["loaded"] as? String)
            let again = CsvImportParser.parse(csv, deviceZone: device, zone: c["reread"] as? String)
            let loaded = CsvImportRateDays.of(first.rows, zone: first.fxZone)
            let reread = CsvImportRateDays.of(again.rows, zone: again.fxZone)
            let name = c["name"] as? String ?? "?"
            XCTAssertEqual(loaded, days(c["loadedDays"]), "\(name) loaded")
            XCTAssertEqual(reread, days(c["rereadDays"]), "\(name) reread")
            XCTAssertEqual(CsvImportRateDays.cover(loaded, reread), c["cover"] as? Bool, "\(name) cover")
        }
        for c in S.list(try data()["rateCover"]) {
            XCTAssertEqual(CsvImportRateDays.cover(days(c["loaded"]), days(c["needed"])), c["expected"] as? Bool,
                           c["name"] as? String ?? "?")
        }
        XCTAssertFalse(CsvImport.fxFiat.contains(CsvImportRateDays.usdtUsd))
    }
}
