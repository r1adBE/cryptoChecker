import XCTest
@testable import CryptoChecker

/// Adress-Guthaben im Stichtag-Export (`AddressBalanceHistory.balancesAt`, `CutoffExport.addressRows`): heute das
/// aktuelle Guthaben, früher das an dem Tag beobachtete, sonst nicht enthalten mit Hinweis — wie `CutoffAddressTest.kt`.
final class CutoffAddressTests: XCTestCase {

    private let day: Int64 = 20_000
    private func t(_ d: Int64) -> Int64 { d * 86_400_000 + 43_200_000 }
    private var cutoff: Int64 { (day + 1) * 86_400_000 - 1 }

    private var histories: [AddressBalanceHistory] {
        [
            // BTC: 0.5 seit 10 Tagen vor dem Stichtag, danach 0.7
            AddressBalanceHistory(chain: .BTC, address: "bc1qexampleaddress",
                                  observations: [AddressObservation(time: t(day - 10), balance: Decimal(string: "0.5")!),
                                                 AddressObservation(time: t(day + 2), balance: Decimal(string: "0.7")!)]),
            // ETH: erst nach dem Stichtag beobachtet
            AddressBalanceHistory(chain: .ETH, address: "0x00000000000000000000000000000000000000aa",
                                  observations: [AddressObservation(time: t(day + 1), balance: 2)]),
        ]
    }

    private let texts = CutoffCsvTexts(
        coin: "Coin", amount: "Menge", priceUsdt: "Kurs USDT", valueUsdt: "Wert USDT",
        rate: "Kurs USD→CHF", valueTarget: "Wert CHF", note: "Hinweis", total: "Total",
        noPrice: "kein Kurs", noFx: "kein Devisenkurs", unchecked: "ungeprüft", incomplete: "unvollständig",
        commentFormat: "# Stichtag %1$@, Währung %2$@, Devisenkurs vom %3$@",
        fromAddress: "aus Adresse – ohne Kaufpreis",
        addressUnobserved: "Adresse an diesem Tag nicht beobachtet – nicht enthalten"
    )

    func testPastDateUsesObservedBalanceOtherwiseExcluded() {
        let at = AddressBalanceHistory.balancesAt(histories, time: cutoff)
        XCTAssertEqual(Set(at.balances.keys), ["BTC"])
        XCTAssertEqual(at.balances["BTC"], Decimal(string: "0.5"))
        XCTAssertEqual(at.unobserved, ["ETH"])

        let rows = CutoffExport.addressRows(at, prices: ["BTC": 100], fxRate: Decimal(string: "0.9"))
        let csv = CutoffExport.csv(day: CutoffDay(year: 2024, month: 10, day: 4), currency: "CHF", fxDate: "2024-10-04",
                                   rows: rows, texts: texts)
        let lines = csv.replacingOccurrences(of: "\u{FEFF}", with: "")
            .components(separatedBy: "\r\n").filter { !$0.isEmpty }.dropFirst(2)
        XCTAssertEqual(Array(lines), [
            "BTC;0.5;100;50.00;0.9;45.00;aus Adresse – ohne Kaufpreis",
            "ETH;;;;0.9;;aus Adresse – ohne Kaufpreis, Adresse an diesem Tag nicht beobachtet – nicht enthalten",
            "Total;;;50.00;0.9;45.00;unvollständig",
        ])
    }

    /// Adressen nicht lesbar: Zeile nach dem Kommentar und Total «unvollständig», auch wenn alle übrigen Zeilen
    /// vollständig sind — nie still eine zu tiefe Summe (wie Android).
    func testUnreadableAddressesMarkExportIncomplete() {
        var withText = texts
        withText.addressesUnreadable = "Adressen nicht lesbar"
        let rows = CutoffExport.rows([CutoffHolding(coin: "BTC", amount: 1)], prices: ["BTC": 100],
                                     fxRate: Decimal(string: "0.9"))
        let day = CutoffDay(year: 2024, month: 10, day: 4)
        let marked = CutoffExport.csv(day: day, currency: "CHF", fxDate: "2024-10-04", rows: rows, texts: withText,
                                      addressesUnreadable: true)
            .replacingOccurrences(of: "\u{FEFF}", with: "").components(separatedBy: "\r\n").filter { !$0.isEmpty }
        XCTAssertEqual(marked[1], "Adressen nicht lesbar")
        XCTAssertEqual(marked.last, "Total;;;100.00;0.9;90.00;unvollständig")
        let plain = CutoffExport.csv(day: day, currency: "CHF", fxDate: "2024-10-04", rows: rows, texts: withText)
            .replacingOccurrences(of: "\u{FEFF}", with: "").components(separatedBy: "\r\n").filter { !$0.isEmpty }
        XCTAssertFalse(plain.contains("Adressen nicht lesbar"))
        XCTAssertEqual(plain.last, "Total;;;100.00;0.9;90.00;")
    }

    /// Zählende Adresse mit Einstand: Hinweiszeile «Umbuchungen evtl. doppelt/fehlend» nach dem Kommentar — nur dann
    /// (ohne Posten, «Nur anzeigen» oder ohne Text keine Zeile); Zeilen und Total unverändert (wie Android).
    func testAddressLotsNoteOnlyWhenNeeded() {
        let lot = AddressCostLot(id: 1, coin: "BTC", time: t(day - 20), amount: Decimal(string: "0.4")!, priceUsdt: 100)
        let plainAddress = WatchedAddress(id: 1, chain: .BTC, address: "bc1qexampleaddress", lastBalance: "0.5")
        // Eigene Adresse (gleiche Adresse zählt nur einmal, AddressCostLots.counted)
        var withLots = WatchedAddress(id: 2, chain: .BTC, address: "bc1qsecondexampleaddress", lastBalance: "0.5")
        withLots.costLots = [lot]
        XCTAssertTrue(AddressCostLots.hasCountedLots([plainAddress, withLots]))
        XCTAssertFalse(AddressCostLots.hasCountedLots([plainAddress]))
        var shownOnly = withLots
        shownOnly.countInPortfolio = false
        XCTAssertFalse(AddressCostLots.hasCountedLots([shownOnly]))
        XCTAssertFalse(AddressCostLots.hasCountedLots([]))

        let note = "Hinweis: Adressen mit Einstand"
        var withText = texts
        withText.addressLots = note
        let rows = CutoffExport.rows([CutoffHolding(coin: "BTC", amount: 1)], prices: ["BTC": 100],
                                     fxRate: Decimal(string: "0.9"))
        let cutoffDay = CutoffDay(year: 2024, month: 10, day: 4)
        func lines(_ t: CutoffCsvTexts, _ lots: Bool) -> [String] {
            CutoffExport.csv(day: cutoffDay, currency: "CHF", fxDate: "2024-10-04", rows: rows, texts: t, addressLots: lots)
                .replacingOccurrences(of: "\u{FEFF}", with: "").components(separatedBy: "\r\n").filter { !$0.isEmpty }
        }
        let marked = lines(withText, true)
        XCTAssertEqual(marked[1], note)
        XCTAssertEqual(marked.filter { $0 == note }.count, 1)
        let plain = lines(withText, false)
        XCTAssertFalse(plain.contains(note))
        XCTAssertEqual(plain, marked.filter { $0 != note })
        XCTAssertEqual(lines(texts, false), lines(texts, true))
    }

    func testTodayUsesCurrentBalance() {
        // Stichtag heute: alle Beobachtungen bis jetzt — das aktuelle Guthaben, nichts fehlt
        let at = AddressBalanceHistory.balancesAt(histories, time: (day + 3) * 86_400_000 - 1)
        XCTAssertEqual(at.balances["BTC"], Decimal(string: "0.7"))
        XCTAssertEqual(at.balances["ETH"], 2)
        XCTAssertTrue(at.unobserved.isEmpty)
        let rows = CutoffExport.addressRows(at, prices: ["BTC": 100, "ETH": 3000], fxRate: nil)
        XCTAssertTrue(rows.allSatisfy { $0.fromAddress && !$0.addressUnobserved })
        XCTAssertEqual(rows.map(\.coin), ["BTC", "ETH"])
    }

    /// USDT zum Marktkurs ohne USDT/USD am Stichtag: Zeile «Dollarkurs von USDT fehlt …» nach dem Kommentar (wie Android).
    func testUsdtApproximateLine() {
        var withText = texts
        withText.usdtAssumed = "Dollarkurs von USDT fehlt – gerechnet als 1 US-Dollar"
        let rows = CutoffExport.rows([], prices: [:], fxRate: Decimal(string: "0.9"))
        let day = CutoffDay(year: 2024, month: 10, day: 4)
        let marked = CutoffExport.csv(day: day, currency: "CHF", fxDate: "2024-10-04", rows: rows, texts: withText,
                                      usdtApproximate: true)
            .replacingOccurrences(of: "\u{FEFF}", with: "").components(separatedBy: "\r\n")
        XCTAssertEqual(marked[1], "Dollarkurs von USDT fehlt – gerechnet als 1 US-Dollar")
        let plain = CutoffExport.csv(day: day, currency: "CHF", fxDate: "2024-10-04", rows: rows, texts: withText)
        XCTAssertFalse(plain.contains("Dollarkurs von USDT fehlt – gerechnet als 1 US-Dollar"))
        // Umgerechnet wird jede Währung ausser USDT selbst (auch USD)
        XCTAssertTrue(CutoffExport.convertsUsdt("CHF") && CutoffExport.convertsUsdt(" usd "))
        XCTAssertFalse(CutoffExport.convertsUsdt("usdt"))
    }
}
