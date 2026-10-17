import XCTest
@testable import CryptoChecker

/// Börsen-Exporte (Binance, Coinbase, Bitpanda) aus den synthetischen Dateien `Tests/Csv/` (Kopie von
/// `android/testdata/csv/`) gegen `csv_exchanges.json` (Android: `CsvExchangeImportParityTest.kt`).
final class CsvExchangeImportTests: XCTestCase {

    private typealias S = TestSupport

    /// Testdatei neben dieser Quelle (`Tests/Csv/<name>`): die Tests laufen im Simulator auf dem Mac und lesen den
    /// Projektordner direkt, die Dateien müssen so nicht ins Test-Bundle.
    static func csv(_ name: String, file: StaticString = #filePath) throws -> String {
        let url = URL(fileURLWithPath: "\(file)").deletingLastPathComponent().appendingPathComponent("Csv/\(name)")
        return try String(contentsOf: url, encoding: .utf8)
    }

    private func string(_ value: Any?) -> String? { value as? String }
    private func int(_ value: Any?) -> Int { Int(S.number(value) ?? -1) }
    private func text(_ value: Decimal?) -> String? { value.map { CsvImport.text($0) } }
    private func data() throws -> [String: Any] { try S.fixture("csv_exchanges") }
    private func parse(_ name: String) throws -> CsvImportResult { CsvImportParser.parse(try Self.csv(name)) }

    func testFiles() throws {
        for f in S.list(try data()["files"]) {
            let name = string(f["file"]) ?? "?"
            let result = try parse(name)
            XCTAssertEqual(result.format.rawValue, string(f["format"]), "\(name) format")
            XCTAssertEqual(result.zone, string(f["zone"]), "\(name) zone")
            XCTAssertEqual(result.localTimes, f["localTimes"] as? Bool, "\(name) localTimes")
            let expected = S.list(f["rows"])
            XCTAssertEqual(result.rows.map(\.line), expected.map { int($0["line"]) }, "\(name) rows")
            for (e, r) in zip(expected, result.rows) {
                let m = "\(name) line \(r.line)"
                XCTAssertEqual(r.kind.rawValue, string(e["kind"]), "\(m) kind")
                XCTAssertEqual(r.rawKind, string(e["rawKind"]), "\(m) rawKind")
                XCTAssertEqual(r.coin, string(e["coin"]), "\(m) coin")
                XCTAssertEqual(text(r.amount), string(e["amount"]), "\(m) amount")
                XCTAssertEqual(text(r.price), string(e["price"]), "\(m) price")
                XCTAssertEqual(r.quote, string(e["quote"]), "\(m) quote")
                XCTAssertEqual(text(r.fee), string(e["fee"]), "\(m) fee")
                XCTAssertEqual(r.feeUnit, string(e["feeUnit"]), "\(m) feeUnit")
                XCTAssertEqual(r.timeMillis, S.millis(e["time"]), "\(m) time")
                XCTAssertEqual(r.externalId, string(e["externalId"]), "\(m) externalId")
                XCTAssertEqual(r.key, string(e["key"]), "\(m) key")
                XCTAssertEqual(r.toCoin, string(e["toCoin"]), "\(m) toCoin")
                XCTAssertEqual(text(r.toAmount), string(e["toAmount"]), "\(m) toAmount")
                XCTAssertEqual(text(r.swapValue), string(e["swapValue"]), "\(m) swapValue")
                XCTAssertEqual(r.stableSwap, (e["stableSwap"] as? Bool) ?? false, "\(m) stableSwap")
            }
            let skipped = S.list(f["skipped"]).map { "\(int($0["line"])):\(string($0["reason"]) ?? "")" }
            XCTAssertEqual(result.skipped.map { "\($0.line):\($0.reason.rawValue)" }, skipped, "\(name) skipped")
        }
    }

    /// Zuordnung zu Transaktionen (Gebühr im Coin, im dritten Coin, in Fiat; Art) mit festem Tageskurs.
    func testMapping() throws {
        for c in S.list(try data()["mapping"]) {
            let name = string(c["file"]) ?? "?"
            var rates: [String: Decimal] = [:]
            for (k, v) in (c["fx"] as? [String: Any]) ?? [:] {
                rates[k] = string(v).flatMap { Decimal(string: $0, locale: Locale(identifier: "en_US_POSIX")) }
            }
            let preview = CsvImportMapping.preview(try parse(name), existingKeys: []) { currency, _ in rates[currency] }
            for e in S.list(c["transactions"]) {
                let line = int(e["line"])
                let tx = try XCTUnwrap(preview.transactions.first { $0.line == line }, "\(name) line \(line)")
                let m = "\(name) line \(line)"
                XCTAssertEqual(tx.type.rawValue, string(e["type"]), "\(m) type")
                XCTAssertEqual(text(tx.priceUsdt), string(e["priceUsdt"]), "\(m) priceUsdt")
                XCTAssertEqual(text(tx.fee), string(e["fee"]), "\(m) fee")
                XCTAssertEqual(tx.feeUnit?.rawValue, string(e["feeUnit"]), "\(m) feeUnit")
                XCTAssertEqual(tx.feeCoin, string(e["feeCoin"]), "\(m) feeCoin")
                XCTAssertEqual(text(tx.feeCoinAmount), string(e["feeCoinAmount"]), "\(m) feeCoinAmount")
            }
        }
    }

    /// Beide Binance-Exporte nacheinander (beide Reihenfolgen): genau gleiche sind Doppelte, fast gleiche in der Prüfliste.
    func testBinanceBothExportsNeverCountTwice() throws {
        for c in S.list(try data()["bothExports"]) {
            let first = CsvImportMapping.preview(try parse(string(c["first"]) ?? ""), existingKeys: []) { _, _ in nil }
            let second = CsvImportMapping.preview(try parse(string(c["second"]) ?? ""),
                                                  existingKeys: Set(first.transactions.map(\.importKey))) { _, _ in nil }
            let m = "\(string(c["first"]) ?? "?") → \(string(c["second"]) ?? "?")"
            XCTAssertEqual(second.duplicates, int(c["duplicates"]), "\(m) duplicates")
            XCTAssertEqual(second.skipped.filter { $0.reason == .POSSIBLE_DUPLICATE }.map(\.line),
                           ((c["possibleDuplicates"] as? [Any]) ?? []).map { int($0) }, "\(m) possible duplicates")
            XCTAssertEqual(second.transactions.map(\.line), ((c["transactions"] as? [Any]) ?? []).map { int($0) }, "\(m) transactions")
        }
    }

    /// Dasselbe Geschäft aus Transaktions- und Handelsverlauf hat denselben Schlüssel (zweiter Import = doppelt).
    func testBinanceLedgerAndTradeHistoryShareKeys() throws {
        let c = try XCTUnwrap(try data()["sharedKeys"] as? [String: Any])
        let ledger = Set(try parse(string(c["ledger"]) ?? "").rows.map(\.key))
        let trades = try parse(string(c["trades"]) ?? "").rows.map(\.key)
        XCTAssertEqual(trades.filter { ledger.contains($0) }, (c["shared"] as? [String]) ?? [])
    }
}
