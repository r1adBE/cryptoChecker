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
}
