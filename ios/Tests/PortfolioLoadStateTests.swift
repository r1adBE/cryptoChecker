import XCTest
@testable import CryptoChecker

/// Portfolio beim Start im Hintergrund gelesen (`PortfolioLoadState`, AppData+PortfolioLoad.swift): Nur das erste
/// Lesen wird übernommen — ein späteres (das im Hintergrund nach einem sofortigen Zugriff) ersetzt nie einen schon
/// übernommenen oder inzwischen geänderten Stand; Sortierung wie bisher (`PortfolioStore.newestFirst`).
@MainActor
final class PortfolioLoadStateTests: XCTestCase {

    private func tx(_ id: Int64, _ time: Int64) -> PortfolioTx {
        PortfolioTx(id: id, coin: "BTC", type: .BUY, amountExact: 1, priceExact: 100, time: time)
    }

    private func read(_ txs: [PortfolioTx], intact: Bool = true) -> PortfolioRead {
        var file = PortfolioFile()
        for t in txs { PortfolioStore.insert(t, into: &file) }
        return PortfolioRead(file: file, intact: intact, newestFirst: PortfolioStore.newestFirst(file.transactions),
                             restoreData: nil)
    }

    func testOnlyFirstReadIsAdopted() {
        var state = PortfolioLoadState()
        XCTAssertFalse(state.loaded)
        let first = read([tx(1, 1_000), tx(2, 3_000), tx(3, 2_000)])
        XCTAssertTrue(state.adopt(first))
        XCTAssertTrue(state.loaded)
        XCTAssertEqual(state.list.map(\.id), [2, 3, 1])
        XCTAssertEqual(state.file.transactions.count, 3)
        XCTAssertTrue(state.intact)
        // Seither geändert (z. B. Transaktion hinzugefügt), dann kommt das Lesen im Hintergrund: bleibt der neue Stand
        state.list = [tx(4, 4_000)] + state.list
        XCTAssertFalse(state.adopt(read([], intact: false)))
        XCTAssertEqual(state.list.map(\.id), [4, 2, 3, 1])
        XCTAssertTrue(state.intact)
    }

    func testUnreadableReadKeepsIntactFalse() {
        var state = PortfolioLoadState()
        XCTAssertTrue(state.adopt(read([], intact: false)))
        XCTAssertFalse(state.intact)
        XCTAssertTrue(state.list.isEmpty)
    }

    /// Zugriff über `AppData` liefert immer einen gelesenen Stand (nie «noch nicht gelesen»), sortiert wie die Datei.
    func testAppDataAccessReadsBeforeUse() {
        let data = AppData.shared
        let list = data.portfolio
        XCTAssertTrue(data.portfolioState.loaded)
        XCTAssertEqual(list, PortfolioStore.newestFirst(data.portfolioFile.transactions))
    }
}
