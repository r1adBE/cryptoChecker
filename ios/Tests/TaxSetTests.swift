import XCTest
@testable import CryptoChecker

// Kleine Testklassen dieses Themas in einer Datei (R, Build 147); jede Klasse unverändert.

// ---- aus TaxCsvRoundingTests

/// Summenzeilen der CSV-Exporte aus den GERUNDETEN Zeilenwerten: drei Zeilen zu 0.335 stehen als 0.34 in der
/// Datei, das Total ist 1.02 (Nachsummieren geht auf), nicht die exakte Summe 1.005 → 1.01. Wie `TaxCsvRoundingTest.kt`.
final class TaxCsvRoundingTests: XCTestCase {
    private let third = Decimal(string: "0.335", locale: Locale(identifier: "en_US_POSIX"))!

    func testCutoffTotalSumsRoundedRows() {
        let texts = CutoffCsvTexts(
            coin: "Coin", amount: "Menge", priceUsdt: "Kurs USDT", valueUsdt: "Wert USDT", rate: "Kurs",
            valueTarget: "Wert CHF", note: "Hinweis", total: "Total", noPrice: "kein Kurs", noFx: "kein Devisenkurs",
            unchecked: "ungeprüft", incomplete: "unvollständig", commentFormat: "# %@ %@ %@"
        )
        let holdings = ["AAA", "BBB", "CCC"].map { CutoffHolding(coin: $0, amount: 1) }
        let rows = CutoffExport.rows(holdings, prices: ["AAA": third, "BBB": third, "CCC": third], fxRate: 1)
        let lines = CutoffExport.csv(day: CutoffDay(year: 2025, month: 12, day: 31), currency: "CHF",
                                     fxDate: "2025-12-31", rows: rows, texts: texts)
            .replacingOccurrences(of: "\u{FEFF}", with: "").components(separatedBy: "\r\n")
        XCTAssertEqual(lines[2], "AAA;1;0.335;0.34;1;0.34;")
        XCTAssertEqual(lines[5], "Total;;;1.02;1;1.02;")
    }

    func testIncomeTotalSumsRoundedRows() {
        let texts = TaxCsvTexts(
            coin: "Coin", acquired: "Acq", sold: "Sold", holdingDays: "Days", longTerm: "> 1y", amount: "Amount",
            costUsdt: "Cost", proceedsUsdt: "Proceeds", gainUsdt: "Gain", costTarget: "CostT", proceedsTarget: "ProcT",
            gainTarget: "GainT", date: "Date", valueUsdt: "V", valueTarget: "VT", note: "Note", total: "Total",
            yes: "yes", no: "no", costMissing: "cm", proceedsMissing: "pm", feeLeg: "fl", feeValueMissing: "fv",
            avgCostRates: "avg", valueMissing: "vm", noFx: "nofx", incomplete: "inc", wallet: "%s")
        let rows = (1...3).map {
            TaxIncomeRow(date: LocalDay(2025, 3, $0), coin: "SOL", amount: 1, valueUsdt: third, valueTarget: third,
                         fxMissing: false)
        }
        let lines = String(TaxExport.incomeCsv(rows, texts: texts, header: []).dropFirst()).components(separatedBy: "\r\n")
        XCTAssertEqual(lines[1], "2025-03-01;SOL;1;0.34;0.34;")
        XCTAssertEqual(lines[4], "Total;;;1.02;1.02;")
    }

    /// Gewinne-CSV: Gewinn je Zeile = gerundeter Erlös − gerundeter Einstand — Erlös 2.004 (2.00), Einstand 1.005
    /// (1.01), Gewinn exakt 0.999 steht als 0.99, nicht 1.00; die Summe des Gewinns geht so mit den Spalten auf den Cent auf.
    func testGainsRowAndTotalAddUp() {
        let texts = TaxCsvTexts(
            coin: "Coin", acquired: "Acq", sold: "Sold", holdingDays: "Days", longTerm: "> 1y", amount: "Amount",
            costUsdt: "Cost", proceedsUsdt: "Proceeds", gainUsdt: "Gain", costTarget: "CostT", proceedsTarget: "ProcT",
            gainTarget: "GainT", date: "Date", valueUsdt: "V", valueTarget: "VT", note: "Note", total: "Total",
            yes: "yes", no: "no", costMissing: "cm", proceedsMissing: "pm", feeLeg: "fl", feeValueMissing: "fv",
            avgCostRates: "avg", valueMissing: "vm", noFx: "nofx", incomplete: "inc")
        let cost = Decimal(string: "1.005", locale: Locale(identifier: "en_US_POSIX"))!
        let proceeds = Decimal(string: "2.004", locale: Locale(identifier: "en_US_POSIX"))!
        let rows = (1...3).map {
            TaxGainRow(coin: "SOL", acquiredDate: LocalDay(2025, 1, $0), soldDate: LocalDay(2025, 3, $0), holdingDays: 59,
                       longTerm: false, amount: 1, costUsdt: cost, proceedsUsdt: proceeds, gainUsdt: proceeds - cost,
                       costTarget: cost, proceedsTarget: proceeds, gainTarget: proceeds - cost, fxMissing: false,
                       feeLeg: false, feeValueMissing: false, method: .FIFO)
        }
        let lines = String(TaxExport.gainsCsv(rows, texts: texts, header: []).dropFirst()).components(separatedBy: "\r\n")
        XCTAssertEqual(lines[1], "SOL;2025-01-01;2025-03-01;59;no;1;1.01;2.00;0.99;1.01;2.00;0.99;")
        // 6.00 − 3.03 = 2.97 (exakt summiert und gerundet wären es 3.00)
        XCTAssertEqual(lines[4], "Total;;;;;;3.03;6.00;2.97;3.03;6.00;2.97;")
    }

    func testRoundedMoneyMatchesText() {
        XCTAssertEqual(CutoffExport.roundedMoney(third), Decimal(string: "0.34", locale: Locale(identifier: "en_US_POSIX")))
        XCTAssertEqual(CutoffExport.roundedMoney(-third), Decimal(string: "-0.34", locale: Locale(identifier: "en_US_POSIX")))
        XCTAssertEqual(CutoffExport.money(third), "0.34")
    }
}

// ---- aus TaxDayCloseParityTests

/// Gemeinsame Testfälle `Parity/tax_day_close.json` — wie `TaxDayCloseParityTest.kt`.
final class TaxDayCloseParityTests: XCTestCase {
    private func data() throws -> [String: Any] { try TestSupport.fixture("tax_day_close") }

    private func dec(_ value: Any?) -> Decimal? {
        (value as? String).flatMap { Decimal(string: $0, locale: Locale(identifier: "en_US_POSIX")) }
    }

    private func int64(_ value: Any?) -> Int64 { (value as? NSNumber)?.int64Value ?? 0 }

    private func closes(_ d: [String: Any]) -> [String: Decimal] {
        var out: [String: Decimal] = [:]
        for c in TestSupport.list(d["closes"]) {
            out[TaxDayClose.key(c["coin"] as? String ?? "", Int(int64(c["day"])))] = dec(c["close"])
        }
        return out
    }

    private func trades(_ d: [String: Any]) -> [PortfolioTx] {
        TestSupport.list(d["trades"]).map { t in
            var tx = PortfolioTx(id: int64(t["id"]), coin: t["coin"] as? String ?? "",
                                 type: PortfolioTxType(rawValue: t["type"] as? String ?? "") ?? .BUY,
                                 amountExact: dec(t["amount"]) ?? 0, priceExact: nil, time: int64(t["time"]),
                                 feeExact: dec(t["fee"]),
                                 feeUnit: (t["feeUnit"] as? String).flatMap { PortfolioFeeUnit(rawValue: $0) })
            tx.feeRow = t["feeRow"] as? Bool ?? false
            return tx
        }
    }

    private func rounded(_ v: Decimal) -> Decimal {
        var value = v
        var out = Decimal()
        NSDecimalRound(&out, &value, 2, .plain)
        return out
    }

    /// Kraken-Gebühr-Zeile und Netzwerkgebühr derselben Stunde: derselbe Tag, derselbe Kurs, derselbe Wert.
    func testFeeRowAndNetworkFeeShareOneClose() throws {
        let d = try data()
        let expectedDay = Int(int64(d["expectedDay"]))
        let expectedClose = try XCTUnwrap(dec(d["expectedClose"]))
        let expectedValue = try XCTUnwrap(dec(d["expectedFeeValue"]))
        let lookup = TaxDayClose.lookup(closes(d))
        let txs = trades(d)

        // Gebühr-Zeile: Bedarf wie feeDisposalPrices (PortfolioLedger.dayCloseNeeds → UTC-Tag)
        let feeRow = try XCTUnwrap(txs.first { $0.feeRow })
        let feeNeeds = PortfolioLedger.dayCloseNeeds([feeRow]).map { "\($0.coin)|\(TaxDayClose.day($0.time))" }
        XCTAssertEqual(feeNeeds, ["BTC|\(expectedDay)"])
        // Netzwerkgebühr als Veräusserung: Bedarf wie networkFeePrices
        let transfer = try XCTUnwrap(txs.first { $0.type == .TRANSFER })
        XCTAssertEqual(TaxDayClose.networkFeeNeeds(txs, end: int64(d["end"])), ["BTC": [expectedDay]])

        let feeRowClose = try XCTUnwrap(lookup("BTC", feeRow.time))
        let networkClose = try XCTUnwrap(lookup("BTC", transfer.time))
        XCTAssertEqual(feeRowClose, expectedClose)
        XCTAssertEqual(networkClose, expectedClose)
        XCTAssertEqual(rounded(feeRow.amountExact * feeRowClose), expectedValue)
        XCTAssertEqual(rounded(transfer.coinFeeExact * networkClose), expectedValue)
    }

    /// Kurz nach Mitternacht Ortszeit gilt der Schluss des UTC-Tags, nicht des lokalen Kalendertags.
    func testUtcDayDecides() throws {
        let d = try data()
        let late = try XCTUnwrap(d["lateLocal"] as? [String: Any])
        let time = int64(late["time"])
        XCTAssertEqual(TaxDayClose.day(time), Int(int64(late["expectedDay"])))
        XCTAssertEqual(TaxDayClose.lookup(closes(d))("BTC", time), dec(late["expectedClose"]))
    }
}

// ---- aus TaxDeIncomeEstimatedTests

/// DE 256-€-Grenze (§ 22 Nr. 3) mit zum Tagesschluss geschätzter Belohnung: Vermerk «geschätzt» an der Grenzzeile (Karte
/// und CSV), mit erfasstem Wert keiner. Wie `TaxDeIncomeEstimatedTest.kt` (Android).
final class TaxDeIncomeEstimatedTests: XCTestCase {

    private let day = 19_783 // 1.3.2024
    private let zone = TimeZone(identifier: "UTC")!

    private var texts: TaxSummaryTexts {
        var t = TaxSummaryTexts(title: "T", disclaimer: "D", item: "I", usdt: "USDT", target: "EUR", note: "N",
                                orientation: "O", labels: [.DE_INCOME_LIMIT: "LIMIT"], below: "below", reached: "reached",
                                unknown: "unknown", incomplete: "inc", noFx: "nofx")
        t.estimated = "est"
        return t
    }

    private func limit(_ price: Decimal?) throws -> (TaxSummaryLine, String) {
        let reward = PortfolioTx(id: 1, coin: "ETH", type: .REWARD, amountExact: Decimal(string: "0.01")!, priceExact: price,
                                 time: Int64(day) * 86_400_000 + 43_200_000)
        let s = TaxExport.yearSummary([reward], template: .DE, method: .FIFO, startYear: 2024, timeZone: zone,
                                      currency: "EUR", rates: [day: Decimal(string: "0.9")!], priceAt: { _, _ in 3000 })
        let line = try XCTUnwrap(s.lines.first { $0.line == .DE_INCOME_LIMIT })
        let csv = try XCTUnwrap(TaxExport.summaryCsv(s, texts: texts, header: []).components(separatedBy: "\r\n")
            .first { $0.contains("LIMIT;") })
        return (line, csv)
    }

    func testEstimatedDayCloseIsMarkedAtTheLimit() throws {
        let (line, csv) = try limit(nil)
        XCTAssertEqual(line.status, .below)
        XCTAssertTrue(line.estimated)
        XCTAssertTrue(csv.hasSuffix("below, est"), csv)
    }

    func testRecordedValueIsNotMarked() throws {
        let (line, csv) = try limit(2900)
        XCTAssertEqual(line.status, .below)
        XCTAssertFalse(line.estimated)
        XCTAssertTrue(csv.hasSuffix("below"), csv)
    }
}

// ---- aus TaxRulesStaleParityTests

/// Regelstand der Länder-Vorlagen als gemeinsame Testfälle (`tax_rules_stale.json`, Quelle `android/testdata/parity`) —
/// wie `TaxRulesStaleParityTest.kt`: Lesen von «JJJJ-MM» und Hinweis «Regeln womöglich veraltet» für Steuerjahre nach
/// dem Jahr des Regelstands; dazu die Anzeige als Monat und Jahr (`TaxRulesDate.label`).
final class TaxRulesStaleParityTests: XCTestCase {
    private typealias S = TestSupport

    func testMonth() throws {
        let cases = S.list(try S.fixture("tax_rules_stale")["month"])
        XCTAssertFalse(cases.isEmpty)
        for c in cases {
            let rulesDate = c["rulesDate"] as? String ?? ""
            let want = (c["month"] as? [Any]).map { [Int(S.number($0[0]) ?? 0), Int(S.number($0[1]) ?? 0)] }
            let got = TaxRulesDate.month(rulesDate).map { [$0.year, $0.month] }
            XCTAssertEqual(got, want, "month «\(rulesDate)»")
        }
    }

    func testRulesStale() throws {
        let cases = S.list(try S.fixture("tax_rules_stale")["stale"])
        XCTAssertFalse(cases.isEmpty)
        for c in cases {
            let name = c["name"] as? String ?? ""
            let taxYear = Int(S.number(c["taxYear"]) ?? 0)
            let rulesDate = c["rulesDate"] as? String ?? ""
            XCTAssertEqual(TaxRulesDate.rulesStale(taxYear: taxYear, rulesDate: rulesDate), c["stale"] as? Bool, name)
        }
    }

    /// Der hinterlegte Regelstand ist lesbar — sonst bliebe der Hinweis still aus.
    func testStoredRulesDateIsReadable() throws {
        let month = try XCTUnwrap(TaxRulesDate.month(TaxTemplate.rulesDate), "rulesDate nicht lesbar")
        XCTAssertTrue(TaxRulesDate.rulesStale(taxYear: month.year + 1))
        XCTAssertFalse(TaxRulesDate.rulesStale(taxYear: month.year))
    }

    /// Monat und Jahr statt «2026-10»; Arabisch mit landeseigenen Ziffern; unlesbar: Rohwert.
    func testLabel() {
        let german = TaxRulesDate.label("2026-10", locale: Locale(identifier: "de_DE"))
        XCTAssertTrue(german.contains("2026"), german)
        XCTAssertNotEqual(german, "2026-10")
        let arabic = TaxRulesDate.label("2026-10", locale: Locale(identifier: "ar"))
        XCTAssertTrue(arabic.contains("٢٠٢٦"), arabic)
        let thai = TaxRulesDate.label("2026-10", locale: Locale(identifier: "th_TH"))
        XCTAssertTrue(thai.contains("2026"), "gregorianisch wie Android: \(thai)")
        XCTAssertEqual(TaxRulesDate.label("2026-13", locale: Locale(identifier: "en_US")), "2026-13")
    }
}

// ---- aus TaxSummaryUsdtTests

/// Jahresübersicht mit USDT = 1 an einem Tag ohne USDT/USD-Schluss (z. B. Kauf vor dem Fenster der Quellen): nie still —
/// `TaxYearSummary.usdtApproximate` und eine eigene Zeile in der CSV. Wie `TaxSummaryUsdtTest.kt`.
final class TaxSummaryUsdtTests: XCTestCase {
    private let buyDay = 20213 // 5.5.2025
    private let sellDay = 20270 // 1.7.2025
    private let trades = [
        PortfolioTx(id: 1, coin: "BTC", type: .BUY, amountExact: 1, priceExact: 10000, time: 1_746_446_400_000),
        PortfolioTx(id: 2, coin: "BTC", type: .SELL, amountExact: 1, priceExact: 12000, time: 1_751_371_200_000),
    ]
    private let texts = TaxSummaryTexts(
        title: "T", disclaimer: "D", item: "I", usdt: "USDT", target: "CHF", note: "N", orientation: "O", labels: [:],
        below: "below", reached: "reached", unknown: "unknown", incomplete: "inc", noFx: "nofx",
        usdtAssumed: "USDT dollar rate missing")

    private func dec(_ s: String) -> Decimal { Decimal(string: s, locale: Locale(identifier: "en_US_POSIX"))! }

    private func summary(_ usdtUsd: [Int: Decimal]?) throws -> TaxYearSummary {
        let utc = try XCTUnwrap(TimeZone(identifier: "UTC"))
        return TaxExport.yearSummary(trades, template: .NEUTRAL, method: .FIFO, startYear: 2025, timeZone: utc,
                                     currency: "CHF", rates: [buyDay: dec("0.83"), sellDay: dec("0.80")], usdtUsd: usdtUsd)
    }

    private func lines(_ s: TaxYearSummary) -> [String] {
        String(TaxExport.summaryCsv(s, texts: texts, header: []).dropFirst()).components(separatedBy: "\r\n")
    }

    func testAllDaysCoveredStaysQuiet() throws {
        let s = try summary([buyDay: dec("0.97"), sellDay: dec("0.98")])
        XCTAssertFalse(s.usdtApproximate)
        XCTAssertFalse(lines(s).contains("USDT dollar rate missing"))
    }

    func testMissingDayIsMarked() throws {
        // Kauftag ohne Schluss (vor dem Fenster): Einstand in CHF mit USDT = 1
        let s = try summary([sellDay: dec("0.98")])
        XCTAssertTrue(s.usdtApproximate)
        // Nach Titel und Hinweis, vor der Spaltenzeile
        XCTAssertEqual(Array(lines(s).prefix(4)), ["T", "D", "USDT dollar rate missing", "I;USDT;CHF;N"])
    }

    func testUsdtCurrencyNeedsNoNote() throws {
        // Ohne USDT-Umrechnung (usdtUsd nil) gibt es nichts anzunehmen
        XCTAssertFalse(try summary(nil).usdtApproximate)
    }
}
