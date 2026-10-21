import XCTest
@testable import CryptoChecker

/// Summenzeilen der CSV-Exporte aus den GERUNDETEN Zeilenwerten (Build 85): drei Zeilen zu 0.335 stehen als 0.34 in der
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

    /// Gewinne-CSV (Build 93): Gewinn je Zeile = gerundeter Erlös − gerundeter Einstand — Erlös 2.004 (2.00), Einstand 1.005
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
