import XCTest
@testable import CryptoChecker

/// Gemeinsame Testfälle `csv_import.json` (Android: `CsvImportParityTest.kt`).
final class CsvImportTests: XCTestCase {

    private typealias S = TestSupport

    private func string(_ value: Any?) -> String? { value as? String }

    private func int(_ value: Any?) -> Int { Int(S.number(value) ?? -1) }

    /// ISO-UTC-Text oder Epoch-ms; null → nil.
    private func millis(_ value: Any?) -> Int64? {
        guard let value, !(value is NSNull) else { return nil }
        return S.millis(value)
    }

    private func text(_ value: Decimal?) -> String? { value.map { CsvImport.text($0) } }

    /// «yyyy-MM-dd» → Tage seit 1970-01-01.
    private func day(_ iso: String) -> Int64 {
        let p = iso.split(separator: "-").map { Int($0) ?? 0 }
        return CsvImportParser.daysFromCivil(p[0], p[1], p[2])
    }

    private func parse(_ name: String) throws -> CsvImportResult {
        let files = S.list(try S.fixture("csv_import")["files"])
        let file = try XCTUnwrap(files.first { string($0["name"]) == name })
        return CsvImportParser.parse(string(file["csv"]) ?? "")
    }

    func testFiles() throws {
        for f in S.list(try S.fixture("csv_import")["files"]) {
            let name = string(f["name"]) ?? "?"
            let result = CsvImportParser.parse(string(f["csv"]) ?? "")
            XCTAssertEqual(result.format.rawValue, string(f["format"]), "\(name) format")
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
                XCTAssertEqual(r.timeMillis, millis(e["time"]), "\(m) time")
                XCTAssertEqual(r.externalId, string(e["externalId"]), "\(m) externalId")
                XCTAssertEqual(r.key, string(e["key"]), "\(m) key")
                // Tausch (fehlt im Fixture = nil)
                XCTAssertEqual(r.toCoin, string(e["toCoin"]), "\(m) toCoin")
                XCTAssertEqual(text(r.toAmount), string(e["toAmount"]), "\(m) toAmount")
                XCTAssertEqual(text(r.swapValue), string(e["swapValue"]), "\(m) swapValue")
                // Kauf/Verkauf gegen Stablecoin als Tausch (fehlt = false)
                XCTAssertEqual(r.stableSwap, (e["stableSwap"] as? Bool) ?? false, "\(m) stableSwap")
                // Fiat-Gebühr im Preis bzw. Kraken ausstehend ≠ gebucht (fehlt = false)
                XCTAssertEqual(r.feeInPrice, (e["feeInPrice"] as? Bool) ?? false, "\(m) feeInPrice")
                XCTAssertEqual(r.pendingDiffers, (e["pendingDiffers"] as? Bool) ?? false, "\(m) pendingDiffers")
            }
            let skipped = S.list(f["skipped"]).map { "\(int($0["line"])):\(string($0["reason"]) ?? "")" }
            XCTAssertEqual(result.skipped.map { "\($0.line):\($0.reason.rawValue)" }, skipped, "\(name) skipped")
            // Hinweise der Vorschau (fehlt = keine Zeile)
            if f["previewFeeInPrice"] != nil || f["previewPendingDiffers"] != nil {
                let preview = CsvImportMapping.preview(result, existingKeys: []) { _, _ in 1 }
                func lines(_ key: String) -> [Int] { ((f[key] as? [Any]) ?? []).map { int($0) } }
                XCTAssertEqual(preview.feeInPrice, lines("previewFeeInPrice"), "\(name) preview feeInPrice")
                XCTAssertEqual(preview.pendingDiffers, lines("previewPendingDiffers"), "\(name) preview pendingDiffers")
            }
        }
    }

    func testNumbers() throws {
        for c in S.list(try S.fixture("csv_import")["numbers"]) {
            let hint = string(c["hint"]).flatMap(\.first)
            XCTAssertEqual(text(CsvImportParser.parseNumber(string(c["text"]) ?? "", hint: hint)), string(c["expected"]), "number: \(c)")
        }
    }

    /// Abgeleiteter Preis: exakt, einmal auf 34 Stellen gerundet — gleich wie Android `CsvImport.derive`.
    func testDivide() throws {
        let posix = Locale(identifier: "en_US_POSIX")
        for c in S.list(try S.fixture("csv_import")["divide"]) {
            let a = try XCTUnwrap(Decimal(string: string(c["a"]) ?? "", locale: posix), "divide: \(c)")
            let b = try XCTUnwrap(Decimal(string: string(c["b"]) ?? "", locale: posix), "divide: \(c)")
            XCTAssertEqual(text(PortfolioDecimal.divide(a, b)), string(c["expected"]), "divide: \(c)")
        }
    }

    func testDates() throws {
        for c in S.list(try S.fixture("csv_import")["dates"]) {
            // Optionale Zeitzone (Sommerzeit-Wechsel Zürich/New York)
            let zone = string(c["zone"]) ?? CsvImportParser.defaultZone
            XCTAssertEqual(CsvImportParser.parseTime(string(c["text"]) ?? "", zone: zone), millis(c["expected"]), "date: \(c)")
        }
    }

    func testClassify() throws {
        for c in S.list(try S.fixture("csv_import")["classify"]) {
            XCTAssertEqual(CsvImportParser.classify(string(c["text"]) ?? "")?.rawValue, string(c["expected"]), "classify: \(c)")
        }
    }

    /// Zeitzone ohne Versatz: gewählt vor Swissquote (Europe/Zurich) vor Gerät; die Vorschau übernimmt sie — wie Android.
    func testZones() throws {
        let d = try S.fixture("csv_import")
        let files = S.list(d["files"])
        for c in S.list(d["zones"]) {
            let file = try XCTUnwrap(files.first { string($0["name"]) == string(c["file"]) })
            let result = CsvImportParser.parse(string(file["csv"]) ?? "", deviceZone: string(c["deviceZone"]) ?? "",
                                               zone: string(c["override"]))
            let m = "zones \(c)"
            XCTAssertEqual(result.zone, string(c["zone"]), "\(m) zone")
            XCTAssertEqual(result.localTimes, c["localTimes"] as? Bool, "\(m) localTimes")
            let row = int(c["row"])
            XCTAssertTrue(row < result.rows.count, m)
            if row < result.rows.count { XCTAssertEqual(result.rows[row].timeMillis, millis(c["time"]), "\(m) time") }
            let preview = CsvImportMapping.preview(result, existingKeys: []) { _, _ in nil }
            XCTAssertEqual(preview.zone, result.zone, "\(m) preview zone")
            XCTAssertEqual(preview.localTimes, result.localTimes, "\(m) preview localTimes")
        }
        // Unstaking wird eine Umbuchung ohne Preis, mit der Art als Notiz
        let csv = "Timestamp,Type,Asset,Amount,Price,Currency\n2024-03-01T00:00:00Z,Unstaking,ETH,1,3000,USD\n"
        let txs = CsvImportMapping.preview(CsvImportParser.parse(csv), existingKeys: []) { _, _ in nil }.transactions
        XCTAssertEqual(txs.count, 1)
        XCTAssertEqual(txs.first?.type, .TRANSFER)
        XCTAssertNil(txs.first?.priceUsdt)
        XCTAssertEqual(txs.first?.note, "Unstaking")
    }

    func testFx() throws {
        for c in S.list(try S.fixture("csv_import")["fx"]) {
            let file = string(c["file"]) ?? ""
            var rates: [String: [Int64: Decimal]] = [:]
            for (cur, byDay) in (c["rates"] as? [String: Any]) ?? [:] {
                var m: [Int64: Decimal] = [:]
                for (d, v) in (byDay as? [String: Any]) ?? [:] {
                    m[day(d)] = Decimal(string: string(v) ?? "", locale: Locale(identifier: "en_US_POSIX"))
                }
                rates[cur] = m
            }
            // USDT/USD-Tagesschlüsse (Datum → Schluss); fehlt im Fixture = nil (USD gilt 1:1 als USDT)
            var usdtUsd: [Int: Decimal]?
            if let raw = c["usdtUsd"] as? [String: Any] {
                var m: [Int: Decimal] = [:]
                for (d, v) in raw { m[Int(day(d))] = Decimal(string: string(v) ?? "", locale: Locale(identifier: "en_US_POSIX")) }
                usdtUsd = m
            }
            let priced = CsvImport.priceInUsdt(try parse(file).rows, usdtUsd: usdtUsd) { cur, d in rates[cur]?[d] }
            let expected = S.list(c["expected"])
            XCTAssertEqual(priced.map(\.row.line), expected.map { int($0["line"]) }, file)
            for (e, p) in zip(expected, priced) {
                let m = "\(file) line \(p.row.line)"
                XCTAssertEqual(text(p.priceUsdt), string(e["priceUsdt"]), "\(m) priceUsdt")
                XCTAssertEqual(text(p.feeUsdt), string(e["feeUsdt"]), "\(m) feeUsdt")
                XCTAssertEqual(p.needsFx, e["needsFx"] as? Bool, "\(m) needsFx")
                XCTAssertEqual(p.missingPrice, e["missingPrice"] as? Bool, "\(m) missingPrice")
                XCTAssertEqual(text(p.swapValueUsdt), string(e["swapValueUsdt"]), "\(m) swapValueUsdt")
                XCTAssertEqual(p.usdtApproximate, e["usdtApproximate"] as? Bool ?? false, "\(m) usdtApproximate")
            }
        }
    }

    /// USDT zum Marktkurs, Rundlauf Import → Steuer-Export: USD/Fiat → USDT mit dem USDT/USD-Schluss DES Tags, der Export
    /// rechnet mit demselben Schluss zurück — ein USD-Kauf über 10 000 ergibt 10 000 USD Einstand, CHF ohne Scheingewinn.
    /// Wie Android `usdtRoundTrip`.
    func testUsdtRoundTrip() throws {
        let posix = Locale(identifier: "en_US_POSIX")
        for c in S.list(try S.fixture("csv_import")["usdtRoundTrip"]) {
            let name = string(c["name"]) ?? ""
            func byDay(_ raw: Any?) -> [Int: Decimal] {
                var m: [Int: Decimal] = [:]
                for (d, v) in (raw as? [String: Any]) ?? [:] { m[Int(day(d))] = Decimal(string: string(v) ?? "", locale: posix) }
                return m
            }
            var rates: [String: [Int: Decimal]] = [:]
            for (cur, v) in (c["rates"] as? [String: Any]) ?? [:] { rates[cur] = byDay(v) }
            let usdtUsd = byDay(c["usdtUsd"])
            let currency = string(c["currency"]) ?? ""
            let result = CsvImportParser.parse(string(c["csv"]) ?? "", deviceZone: "UTC")
            let preview = CsvImportMapping.preview(result, existingKeys: [], usdtUsd: usdtUsd) { cur, d in rates[cur]?[Int(d)] }
            XCTAssertEqual(preview.transactions.map { text($0.priceUsdt) }, (c["priceUsdt"] as? [Any] ?? []).map { string($0) },
                           "\(name) priceUsdt")
            XCTAssertEqual(preview.usdtApproximate, (c["usdtApproximate"] as? [Any] ?? []).map { int($0) }, "\(name) usdtApproximate")
            let trades = preview.transactions.enumerated().map { item -> PortfolioTx in
                let t = item.element
                var tx = PortfolioTx(id: Int64(item.offset + 1), coin: t.coin, type: t.type, amountExact: t.amount, priceExact: t.priceUsdt,
                                     time: t.time, feeExact: t.fee, feeUnit: t.feeUnit)
                tx.feeCoin = t.feeCoin
                tx.feeCoinAmountExact = t.feeCoinAmount
                return tx
            }
            let zone = try XCTUnwrap(TimeZone(identifier: "UTC"))
            let rows = TaxExport.gainRows(trades, method: TaxMethod.of(.FIFO), period: TaxTemplate.NEUTRAL.period(int(c["year"])),
                                          timeZone: zone, currency: currency, rates: rates[currency] ?? [:], usdtUsd: usdtUsd)
            let expected = S.list(c["gains"])
            XCTAssertEqual(rows.count, expected.count, "\(name) rows")
            for (e, r) in zip(expected, rows) {
                XCTAssertEqual(r.coin, string(e["coin"]), "\(name) coin")
                XCTAssertEqual(r.costTarget.map { CutoffExport.money($0) }, string(e["costTarget"]), "\(name) cost")
                XCTAssertEqual(r.proceedsTarget.map { CutoffExport.money($0) }, string(e["proceedsTarget"]), "\(name) proceeds")
                XCTAssertEqual(r.gainTarget.map { CutoffExport.money($0) }, string(e["gainTarget"]), "\(name) gain")
                XCTAssertEqual(r.usdtApproximate, e["usdtApproximate"] as? Bool, "\(name) usdtApproximate")
            }
        }
    }

    /// Tageskurs-Tag des Imports = Ortstag des Geräts wie im Steuer-Export (`CsvImportResult.fxZone`) — wie Android.
    func testFxDay() throws {
        let fixture = try S.fixture("csv_import")
        let c = try XCTUnwrap(fixture["fxDay"] as? [String: Any])
        let files = S.list(fixture["files"])
        let file = try XCTUnwrap(files.first { string($0["name"]) == string(c["file"]) })
        let deviceZone = string(c["deviceZone"]) ?? ""
        let result = CsvImportParser.parse(string(file["csv"]) ?? "", deviceZone: deviceZone)
        XCTAssertEqual(result.zone, string(c["zone"]))
        XCTAssertEqual(result.fxZone, deviceZone)
        var rates: [String: [Int64: Decimal]] = [:]
        for (cur, byDay) in (c["rates"] as? [String: Any]) ?? [:] {
            var m: [Int64: Decimal] = [:]
            for (d, v) in (byDay as? [String: Any]) ?? [:] {
                m[day(d)] = Decimal(string: string(v) ?? "", locale: Locale(identifier: "en_US_POSIX"))
            }
            rates[cur] = m
        }
        let preview = CsvImportMapping.preview(result, existingKeys: []) { cur, d in rates[cur]?[d] }
        let tx = try XCTUnwrap(preview.transactions.first { $0.line == int(c["line"]) })
        XCTAssertEqual(text(tx.priceUsdt), string(c["priceUsdt"]))
        // Derselbe Tag wie im Export
        let zone = try XCTUnwrap(TimeZone(identifier: deviceZone))
        XCTAssertEqual(Int64(TaxExport.localDay(tx.time, timeZone: zone).epochDay), CsvImport.epochDay(tx.time, zone: result.fxZone))
    }

    /// Fiat-Liste des Imports = Währungen mit Devisenkurs (eine Quelle der Wahrheit) — wie Android.
    func testFiatListMatchesFxCurrencies() {
        XCTAssertEqual(CsvImport.fxFiat, Set(FxRateSource.currencies))
        XCTAssertEqual(CsvImport.fxFiat.count, FxRateSource.currencies.count)
    }

    func testDedupe() throws {
        let d = try XCTUnwrap(try S.fixture("csv_import")["dedupe"] as? [String: Any])
        let rows = try parse(string(d["file"]) ?? "").rows
        let existing = Set((d["existing"] as? [String]) ?? [])
        let split = CsvImport.splitDuplicates(rows, existingKeys: existing)
        XCTAssertEqual(split.fresh.map(\.line), ((d["freshLines"] as? [Any]) ?? []).map { int($0) })
        XCTAssertEqual(split.duplicates.map(\.line), ((d["duplicateLines"] as? [Any]) ?? []).map { int($0) })
        // Gleiche Datei zweimal: alles Duplikate
        XCTAssertTrue(CsvImport.splitDuplicates(rows, existingKeys: Set(rows.map(\.key))).fresh.isEmpty)
    }

    /// Schlüssel ohne Versatz unabhängig von der Zeitzone.
    func testZoneKeys() throws {
        let d = try S.fixture("csv_import")
        let files = S.list(d["files"])
        let cases = S.list(d["zoneKeys"])
        XCTAssertFalse(cases.isEmpty)
        for c in cases {
            let file = try XCTUnwrap(files.first { string($0["name"]) == string(c["file"]) })
            let result = CsvImportParser.parse(string(file["csv"]) ?? "", deviceZone: string(c["deviceZone"]) ?? "",
                                               zone: string(c["override"]))
            let row = int(c["row"])
            let m = "zoneKeys \(c)"
            XCTAssertTrue(row < result.rows.count, m)
            guard row < result.rows.count else { continue }
            XCTAssertEqual(result.rows[row].key, string(c["key"]), "\(m) key")
        }
    }

    /// Zweiter Import derselben Datei mit anderer Zeitzone: keine Doppel.
    func testZoneDedupe() throws {
        let d = try S.fixture("csv_import")
        let files = S.list(d["files"])
        let cases = S.list(d["zoneDedupe"])
        XCTAssertFalse(cases.isEmpty)
        for c in cases {
            let file = try XCTUnwrap(files.first { string($0["name"]) == string(c["file"]) })
            let result = CsvImportParser.parse(string(file["csv"]) ?? "", deviceZone: string(c["deviceZone"]) ?? "",
                                               zone: string(c["override"]))
            let split = CsvImport.splitDuplicates(result.rows, existingKeys: Set((c["existing"] as? [String]) ?? []))
            XCTAssertEqual(split.duplicates.map(\.line), ((c["duplicateLines"] as? [Any]) ?? []).map { int($0) },
                           string(c["name"]) ?? "")
        }
    }

    /// Tag/Monat je Datei; nur mehrdeutige Daten → Auswahl der Vorschau.
    func testDateOrder() throws {
        let cases = S.list(try S.fixture("csv_import")["dateOrder"])
        XCTAssertFalse(cases.isEmpty)
        for c in cases {
            let name = string(c["name"]) ?? ""
            let chosen = string(c["dateOrder"]).flatMap { CsvDateOrder(rawValue: $0) }
            let result = CsvImportParser.parse(string(c["csv"]) ?? "", deviceZone: "UTC", zone: nil, dateOrder: chosen)
            XCTAssertEqual(result.dateOrder.rawValue, string(c["order"]), "\(name) order")
            XCTAssertEqual(result.dateOrderAmbiguous, c["ambiguous"] as? Bool, "\(name) ambiguous")
            XCTAssertEqual(result.rows.map(\.timeMillis), ((c["times"] as? [Any]) ?? []).compactMap { millis($0) },
                           "\(name) times")
            let preview = CsvImportMapping.preview(result, existingKeys: []) { _, _ in nil }
            XCTAssertEqual(preview.dateOrder, result.dateOrder, "\(name) preview order")
            XCTAssertEqual(preview.dateOrderAmbiguous, result.dateOrderAmbiguous, "\(name) preview ambiguous")
        }
    }

    /// Dezimalzeichen: «0.125» verrät es; mehrdeutige Zahl ohne Hinweis → Auswahl der Vorschau — wie Android.
    func testDecimalMark() throws {
        let cases = S.list(try S.fixture("csv_import")["decimalMark"])
        XCTAssertFalse(cases.isEmpty)
        for c in cases {
            let name = string(c["name"]) ?? ""
            let chosen = string(c["decimalMark"]).flatMap(\.first)
            let result = CsvImportParser.parse(string(c["csv"]) ?? "", deviceZone: "UTC", zone: nil, dateOrder: nil,
                                               decimalMark: chosen)
            XCTAssertEqual(result.decimalSample, string(c["sample"]), "\(name) sample")
            XCTAssertEqual(result.decimalMark, string(c["mark"]).flatMap(\.first), "\(name) mark")
            XCTAssertEqual(result.decimalAmbiguous, string(c["sample"]) != nil, "\(name) ambiguous")
            XCTAssertEqual(result.rows.map { text($0.amount) }, ((c["amounts"] as? [Any]) ?? []).map { $0 as? String },
                           "\(name) amounts")
            XCTAssertEqual(result.rows.map { text($0.price) }, ((c["prices"] as? [Any]) ?? []).map { $0 as? String },
                           "\(name) prices")
            let preview = CsvImportMapping.preview(result, existingKeys: []) { _, _ in nil }
            XCTAssertEqual(preview.decimalSample, result.decimalSample, "\(name) preview sample")
            XCTAssertEqual(preview.decimalMark, result.decimalMark, "\(name) preview mark")
            XCTAssertEqual(preview.columns, result.columns, "\(name) preview columns")
        }
        // Auswahl der Vorschau: beide Lesarten der mehrdeutigen Zahl
        XCTAssertEqual(text(CsvImportParser.sampleValue("42,000", mark: ".")), "42000")
        XCTAssertEqual(text(CsvImportParser.sampleValue("42,000", mark: ",")), "42")
        XCTAssertEqual(text(CsvImportParser.sampleValue("42.000", mark: ".")), "42")
        XCTAssertEqual(text(CsvImportParser.sampleValue("42.000", mark: ",")), "42000")
    }

    /// Zeichensatz: UTF-8 (BOM weg), sonst Windows-1252; erkannte Spalten — wie Android.
    func testDecode() throws {
        let cases = S.list(try S.fixture("csv_import")["decode"])
        XCTAssertFalse(cases.isEmpty)
        for c in cases {
            let name = string(c["name"]) ?? ""
            let chars = Array(string(c["hex"]) ?? "")
            let bytes: [UInt8] = stride(from: 0, to: chars.count, by: 2).map { (i: Int) -> UInt8 in
                UInt8(String(chars[i...i + 1]), radix: 16) ?? 0
            }
            let text = CsvImportParser.decode(Data(bytes))
            XCTAssertEqual(text, string(c["text"]), "\(name) text")
            guard let columns = c["columns"] as? [String] else { continue }
            let result = CsvImportParser.parse(text, deviceZone: "UTC")
            XCTAssertEqual(result.columns, columns, "\(name) columns")
            let rows = S.list(c["rows"])
            XCTAssertEqual(result.rows.count, rows.count, "\(name) row count")
            for (e, r) in zip(rows, result.rows) {
                XCTAssertEqual(self.text(r.amount), string(e["amount"]), "\(name) amount")
                XCTAssertEqual(self.text(r.price), string(e["price"]), "\(name) price")
                XCTAssertEqual(r.quote, string(e["quote"]), "\(name) quote")
                XCTAssertEqual(self.text(r.fee), string(e["fee"]), "\(name) fee")
                XCTAssertEqual(r.feeUnit, string(e["feeUnit"]), "\(name) feeUnit")
            }
        }
    }

    /// Vorschau: Hinweis bei Kauf/Verkauf gegen Stablecoin (als Tausch gebucht) und die zugeordneten Transaktionen.
    func testStableSwaps() throws {
        let cases = S.list(try S.fixture("csv_import")["stableSwaps"])
        XCTAssertFalse(cases.isEmpty)
        for c in cases {
            let name = string(c["file"]) ?? ""
            let preview = CsvImportMapping.preview(try parse(name), existingKeys: []) { _, _ in nil }
            XCTAssertEqual(preview.stableSwaps, c["stableSwaps"] as? Bool, "\(name) stableSwaps")
            for e in S.list(c["txs"]) {
                let line = int(e["line"])
                let tx = try XCTUnwrap(preview.transactions.first { $0.line == line }, "\(name) line \(line)")
                let m = "\(name) line \(line)"
                XCTAssertEqual(tx.type.rawValue, string(e["type"]), "\(m) type")
                XCTAssertEqual(tx.coin, string(e["coin"]), "\(m) coin")
                XCTAssertEqual(tx.toCoin, string(e["toCoin"]), "\(m) toCoin")
                XCTAssertEqual(text(tx.toAmount), string(e["toAmount"]), "\(m) toAmount")
                XCTAssertEqual(text(tx.swapValueUsdt), string(e["swapValueUsdt"]), "\(m) swapValueUsdt")
                // Gebühr (fehlt im Fixture = nicht geprüft)
                if e["fee"] != nil { XCTAssertEqual(text(tx.fee), string(e["fee"]), "\(m) fee") }
                if e["feeUnit"] != nil { XCTAssertEqual(tx.feeUnit?.rawValue, string(e["feeUnit"]), "\(m) feeUnit") }
                if e["feeCoin"] != nil { XCTAssertEqual(tx.feeCoin, string(e["feeCoin"]), "\(m) feeCoin") }
                if e["feeCoinAmount"] != nil {
                    XCTAssertEqual(text(tx.feeCoinAmount), string(e["feeCoinAmount"]), "\(m) feeCoinAmount")
                }
            }
            // Bestand nach allen Transaktionen der Vorschau (z. B. Kraken-Saldo) — wie Android
            if let balances = c["balances"] as? [String: Any] {
                let trades: [PortfolioTx] = preview.transactions.enumerated().map { i, t in
                    var tx = PortfolioTx(id: Int64(i + 1), coin: t.coin, type: t.type, amountExact: t.amount,
                                         priceExact: t.priceUsdt, time: t.time, note: t.note, feeExact: t.fee,
                                         feeUnit: t.feeUnit)
                    tx.feeCoin = t.feeCoin
                    tx.feeCoinAmountExact = t.feeCoinAmount
                    tx.importKey = t.importKey
                    tx.feeRow = t.feeRow
                    tx.interest = t.interest
                    tx.toCoin = t.toCoin
                    tx.toAmountExact = t.toAmount
                    tx.swapValueExact = t.swapValueUsdt
                    return tx
                }
                let held = PortfolioCalculator.holdingsAt(trades)
                for (coin, v) in balances {
                    XCTAssertEqual(text(held.first { $0.coin == coin }?.amount), string(v), "\(name) balance \(coin)")
                }
            }
        }
        // Schon importiert: kein Hinweis
        let rows = try parse("generic_stable").rows
        XCTAssertFalse(rows.isEmpty)
        let all = Set(rows.map(\.key))
        XCTAssertFalse(CsvImportMapping.preview(try parse("generic_stable"), existingKeys: all) { _, _ in nil }.stableSwaps)
    }

    func testEpochDay() throws {
        for c in S.list(try S.fixture("csv_import")["epochDay"]) {
            XCTAssertEqual(CsvImport.epochDay(millis(c["time"]) ?? 0), day(string(c["expected"]) ?? ""), "epochDay: \(c)")
        }
    }

    func testOccurrenceAndTokenizer() {
        XCTAssertEqual(CsvImport.withOccurrence(["a", "b", "a", "a"]), ["a", "b", "a#2", "a#3"])
        // «""» in Anführungszeichen = «"»
        let records = CsvImportParser.tokenize(Array("x,\"a \"\"b\"\", c\",y\n".unicodeScalars), delimiter: ",")
        XCTAssertEqual(records.count, 1)
        XCTAssertEqual(records.first?.cells, ["x", "a \"b\", c", "y"])
        XCTAssertNil(CsvImportParser.parseTime("2024-02-29T25:00:00Z"))
    }

    /// Fiat-Gebühr in einer anderen Währung als die Preiswährung zum Tageskurs ihrer Währung, ohne Kurs
    /// missingFx (nie still weg); Tausch aus zwei Seiten ohne Fiat-Wert mit EUR-Gebühr ebenso; Belohnung mit Fiat-Betrag
    /// bekommt Preis = Betrag / Menge (Schlüssel mit Preis). Wie Android `fiatFeeReward`.
    func testFiatFeeReward() throws {
        let c = try XCTUnwrap(try S.fixture("csv_import")["fiatFeeReward"] as? [String: Any])
        var rates: [String: [Int64: Decimal]] = [:]
        for (cur, byDay) in (c["rates"] as? [String: Any]) ?? [:] {
            var m: [Int64: Decimal] = [:]
            for (d, v) in (byDay as? [String: Any]) ?? [:] {
                m[day(d)] = Decimal(string: string(v) ?? "", locale: Locale(identifier: "en_US_POSIX"))
            }
            rates[cur] = m
        }
        let result = try parse(string(c["file"]) ?? "")
        let priced = CsvImport.priceInUsdt(result.rows) { cur, d in rates[cur]?[d] }
        let expected = S.list(c["priced"])
        XCTAssertEqual(priced.map(\.row.line), expected.map { int($0["line"]) })
        for (e, p) in zip(expected, priced) {
            let m = "fiatFeeReward line \(p.row.line)"
            XCTAssertEqual(text(p.priceUsdt), string(e["priceUsdt"]), "\(m) priceUsdt")
            XCTAssertEqual(text(p.feeUsdt), string(e["feeUsdt"]), "\(m) feeUsdt")
            XCTAssertEqual(p.needsFx, e["needsFx"] as? Bool, "\(m) needsFx")
        }
        let preview = CsvImportMapping.preview(result, existingKeys: []) { cur, d in rates[cur]?[d] }
        XCTAssertEqual(preview.missingFx, ((c["missingFx"] as? [Any]) ?? []).map { int($0) })
        for e in S.list(c["txs"]) {
            let line = int(e["line"])
            let tx = try XCTUnwrap(preview.transactions.first { $0.line == line })
            XCTAssertEqual(text(tx.fee), string(e["fee"]), "tx \(line) fee")
            XCTAssertEqual(tx.feeUnit?.rawValue, string(e["feeUnit"]), "tx \(line) feeUnit")
            XCTAssertEqual(tx.feeCoin, string(e["feeCoin"]), "tx \(line) feeCoin")
        }
        // Tageskurse: Gebühr in jeder Fiat-Währung (GBP neben der Preiswährung USD)
        XCTAssertEqual(CsvImportMapping.fxNeeds(result.rows).keys.sorted(), ((c["fxNeeds"] as? [Any]) ?? []).compactMap { string($0) })
        // Belohnung mit Preis: Schlüssel mit Preis
        let reward = try XCTUnwrap(result.rows.first { $0.line == int(c["rewardLine"]) })
        XCTAssertEqual(reward.key, string(c["key"]))
    }
}
