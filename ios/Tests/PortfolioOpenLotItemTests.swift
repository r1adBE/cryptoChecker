import XCTest
@testable import CryptoChecker

/// «Offene Kauf-Posten»: Zeilen nur, wenn sie gezeigt werden (`PortfolioOpenLotItem.shown`) — mit denselben Ids und
/// Posten wie aus der ganzen Liste.
final class PortfolioOpenLotItemTests: XCTestCase {

    private func lots(_ count: Int) -> [PortfolioOpenLot] {
        (0..<count).map { i in
            // Gleiche Angaben mehrfach (fortlaufend gezählte Ids), teils ohne Kaufpreis, zwei Wallets
            PortfolioOpenLot(coin: "BTC", txId: Int64(i % 7), acquiredTime: Int64(i) * 1_000, kind: .BUY,
                             amount: Decimal(i + 1), costUsdt: i % 5 == 0 ? nil : Decimal(i * 10),
                             wallet: i % 2 == 0 ? "Kraken" : nil, aggregated: false)
        }
    }

    func testShownRowsSameAsFromAllLots() {
        // Wie `PortfolioOpenLotsSection.shownLots` (dort Hauptthread-gebunden)
        let limit = 50
        for count in [0, 1, limit - 1, limit, limit + 1, 3 * limit + 7] {
            let all = lots(count)
            let full = PortfolioOpenLotItem.of(all)
            XCTAssertTrue(PortfolioOpenLotItem.shown(all, expanded: false, showAll: false, limit: limit).isEmpty)
            let expanded = PortfolioOpenLotItem.shown(all, expanded: true, showAll: false, limit: limit)
            XCTAssertEqual(expanded.map(\.id), full.prefix(limit).map(\.id), "\(count)")
            XCTAssertEqual(expanded.map(\.lot), full.prefix(limit).map(\.lot), "\(count)")
            for open in [false, true] {
                let sheet = PortfolioOpenLotItem.shown(all, expanded: open, showAll: true, limit: limit)
                XCTAssertEqual(sheet.map(\.id), full.map(\.id), "\(count)")
                XCTAssertEqual(sheet.map(\.lot), all, "\(count)")
            }
            XCTAssertEqual(Set(full.map(\.id)).count, full.count, "Ids eindeutig")
        }
    }
}
