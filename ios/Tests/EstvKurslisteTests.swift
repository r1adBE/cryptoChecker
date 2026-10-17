import XCTest
@testable import CryptoChecker

/// Gemeinsame Testfälle `estv_kursliste.json` (Android: `EstvKurslisteTest.kt`).
final class EstvKurslisteTests: XCTestCase {

    private typealias S = TestSupport

    private let texts = CutoffCsvTexts(
        coin: "Coin", amount: "Menge", priceUsdt: "Kurs USDT", valueUsdt: "Wert USDT",
        rate: "Kurs", valueTarget: "Wert", note: "Hinweis", total: "Total",
        noPrice: "kein Kurs", noFx: "kein Devisenkurs", unchecked: "ungeprüft", incomplete: "unvollständig",
        commentFormat: "# %1$@ %2$@ %3$@",
        estvNoteFormat: "ESTV-Kursliste %1$@: %2$@",
        estvLanguage: "de"
    )

    private func day(_ iso: String) -> CutoffDay {
        let p = iso.split(separator: "-").compactMap { Int($0) }
        return CutoffDay(year: p[0], month: p[1], day: p[2])
    }

    private func lines(_ csv: String) -> [String] {
        var text = csv
        if text.hasPrefix("\u{FEFF}") { text.removeFirst() }
        // «\r\n» ist in Swift ein Zeichen (Graphem) — nach dem String trennen
        return text.components(separatedBy: "\r\n")
    }

    func testParityFixture() throws {
        let data = try S.fixture("estv_kursliste")
        let urls = S.list(data["urls"])
        for c in urls {
            let year = Int(S.number(c["year"]) ?? 0)
            XCTAssertEqual(EstvKursliste.listUrl(year: year, language: c["language"] as? String ?? ""),
                           c["expected"] as? String, "url \(c["language"] ?? "")")
        }
        let applies = S.list(data["applies"])
        for c in applies {
            XCTAssertEqual(EstvKursliste.applies(day: day(c["date"] as? String ?? ""), currency: c["currency"] as? String ?? ""),
                           c["expected"] as? Bool, "applies \(c["date"] ?? "") \(c["currency"] ?? "")")
        }
        XCTAssertTrue(urls.count >= 5 && applies.count >= 4)
    }

    func testNoteLineOnlyWithFormatInChfAtYearEnd() {
        let d = CutoffDay(year: 2025, month: 12, day: 31)
        XCTAssertEqual(EstvKursliste.noteLine(format: "ESTV-Kursliste %1$@: %2$@", day: d, currency: "CHF", language: "de"),
                       "ESTV-Kursliste 2025: https://www.ictax.admin.ch/extern/de.html#/ratelist/2025")
        XCTAssertNil(EstvKursliste.noteLine(format: nil, day: d, currency: "CHF", language: "de"))
        XCTAssertNil(EstvKursliste.noteLine(format: "", day: d, currency: "CHF", language: "de"))
        XCTAssertNil(EstvKursliste.noteLine(format: "x %1$@ %2$@", day: d, currency: "EUR", language: "de"))
        XCTAssertNil(EstvKursliste.noteLine(format: "x %1$@ %2$@", day: CutoffDay(year: 2025, month: 6, day: 30),
                                            currency: "CHF", language: "de"))
    }

    func testCsvHasNoteLineAfterCommentInChf() {
        let rows = CutoffExport.rows([CutoffHolding(coin: "BTC", amount: Decimal(string: "0.5")!)],
                                     prices: ["BTC": 100_000], fxRate: Decimal(string: "0.8"))
        let out = lines(CutoffExport.csv(day: CutoffDay(year: 2025, month: 12, day: 31), currency: "CHF",
                                         fxDate: "2025-12-31", rows: rows, texts: texts))
        XCTAssertEqual(out[1], "ESTV-Kursliste 2025: https://www.ictax.admin.ch/extern/de.html#/ratelist/2025")
        XCTAssertEqual(out[2], "Coin;Menge;Kurs USDT;Wert USDT;Kurs;Wert;Hinweis")
        // Mit «,» als Trennzeichen bleibt die Zeile ein Feld
        var commaTexts = texts
        commaTexts.estvNoteFormat = "ESTV, %1$@: %2$@"
        let comma = lines(CutoffExport.csv(day: CutoffDay(year: 2025, month: 12, day: 31), currency: "CHF",
                                           fxDate: nil, rows: rows, texts: commaTexts, format: .commaPoint))
        XCTAssertEqual(comma[1], "\"ESTV, 2025: https://www.ictax.admin.ch/extern/de.html#/ratelist/2025\"")
    }

    func testCsvWithoutNoteOutsideChfOrYearEnd() {
        let rows = CutoffExport.rows([CutoffHolding(coin: "BTC", amount: 1)], prices: ["BTC": 1], fxRate: 1)
        let eur = lines(CutoffExport.csv(day: CutoffDay(year: 2025, month: 12, day: 31), currency: "EUR",
                                         fxDate: nil, rows: rows, texts: texts))
        XCTAssertEqual(eur[1], "Coin;Menge;Kurs USDT;Wert USDT;Kurs;Wert;Hinweis")
        let mid = lines(CutoffExport.csv(day: CutoffDay(year: 2025, month: 6, day: 30), currency: "CHF",
                                         fxDate: nil, rows: rows, texts: texts))
        XCTAssertEqual(mid[1], "Coin;Menge;Kurs USDT;Wert USDT;Kurs;Wert;Hinweis")
    }
}
