import XCTest
@testable import CryptoChecker

/// «Offene Kauf-Posten»: Zeilen der Karte nur, wenn sie gezeigt werden (`PortfolioOpenLotItem.shown`) — mit denselben
/// Ids und Posten wie aus der ganzen Liste, die das Blatt «Alle zeigen» zeigt (`PortfolioOpenLotItem.of`).
final class PortfolioOpenLotItemTests: XCTestCase {

    private func lots(_ count: Int) -> [PortfolioOpenLot] {
        // Gleiche Angaben mehrfach (fortlaufend gezählte Ids), teils ohne Kaufpreis, zwei Wallets. Werte einzeln
        // ausgerechnet: als ein Ausdruck braucht die Typprüfung zu lange
        var out: [PortfolioOpenLot] = []
        for i in 0..<count {
            let txId = Int64(i % 7)
            let time = Int64(i) * 1_000
            let amount = Decimal(i + 1)
            let cost: Decimal? = i % 5 == 0 ? nil : Decimal(i * 10)
            let wallet: String? = i % 2 == 0 ? "Kraken" : nil
            out.append(PortfolioOpenLot(coin: "BTC", txId: txId, acquiredTime: time, kind: .BUY, amount: amount,
                                        costUsdt: cost, wallet: wallet, aggregated: false))
        }
        return out
    }

    func testShownRowsSameAsFromAllLots() {
        // Wie `PortfolioOpenLotsSection.shownLots` (dort Hauptthread-gebunden)
        let limit = 50
        for count in [0, 1, limit - 1, limit, limit + 1, 3 * limit + 7] {
            let all = lots(count)
            let full = PortfolioOpenLotItem.of(all)
            XCTAssertTrue(PortfolioOpenLotItem.shown(all, expanded: false, limit: limit).isEmpty)
            let expanded = PortfolioOpenLotItem.shown(all, expanded: true, limit: limit)
            XCTAssertEqual(expanded.map(\.id), full.prefix(limit).map(\.id), "\(count)")
            XCTAssertEqual(expanded.map(\.lot), full.prefix(limit).map(\.lot), "\(count)")
            // Das Blatt «Alle zeigen» nimmt immer die ganze Liste (`of`), auch beim Schliessen
            XCTAssertEqual(full.map(\.lot), all, "\(count)")
            XCTAssertEqual(Set(full.map(\.id)).count, full.count, "Ids eindeutig")
        }
    }
}
