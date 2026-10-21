import XCTest
@testable import CryptoChecker

/// Portfolio-Widget-Stand ausserhalb des Hauptthreads (`OrderedSerialRunner`, `PortfolioWidgetStore.updateInBackground`,
/// seit Build 99): die Rechnung läuft nie auf dem Hauptthread, Aufnahmen laufen einzeln nacheinander, eine ältere
/// überschreibt nie eine neuere, und das Ergebnis ist dasselbe wie synchron gerechnet.
final class PortfolioWidgetBackgroundTests: XCTestCase {
    @MainActor
    func testWorkRunsOffMainThread() async {
        XCTAssertTrue(Thread.isMainThread)
        let runner = OrderedSerialRunner()
        let onMain = await runner.runDetached { Thread.isMainThread }
        XCTAssertEqual(onMain, false)
    }

    func testOlderTicketIsSkippedAfterNewerRan() {
        let runner = OrderedSerialRunner()
        let older = runner.ticket()
        let newer = runner.ticket()
        XCTAssertEqual(runner.run(newer) { "neu" }, "neu")
        // Kommt erst danach an die Reihe: Eingaben älter → entfällt
        XCTAssertNil(runner.run(older) { "alt" })
        XCTAssertEqual(runner.run(runner.ticket()) { "neuer" }, "neuer")
    }

    func testWorkNeverRunsConcurrently() async {
        let runner = OrderedSerialRunner()
        let state = LockedBox((active: 0, maxActive: 0))
        await withTaskGroup(of: Void.self) { group in
            for _ in 0..<8 {
                group.addTask {
                    _ = await runner.runDetached {
                        state.write { $0.active += 1; $0.maxActive = max($0.maxActive, $0.active) }
                        Thread.sleep(forTimeInterval: 0.01)
                        state.write { $0.active -= 1 }
                    }
                }
            }
        }
        XCTAssertEqual(state.current.maxActive, 1)
    }

    /// Gleiche Eingaben → gleicher Stand, ob auf dem Hauptthread oder im Hintergrund gerechnet. Ohne Kursstand
    /// (`updatedAt` 0) schreibt `compute` nichts — der Test verändert die App Group nicht.
    @MainActor
    func testBackgroundResultEqualsSynchronousResult() async {
        let txs = [PortfolioTx(id: 1, coin: "BTC", type: .BUY, amount: 0.5, priceUsdt: 30_000, time: 1_700_000_000_000),
                   PortfolioTx(id: 2, coin: "ETH", type: .BUY, amount: 2, priceUsdt: 2_000, time: 1_700_000_100_000)]
        let prices = PortfolioPrices(prices: ["BTC": 60_000, "ETH": 3_000], updatedAt: 0)
        let now: Int64 = 1_760_000_000_000
        @Sendable func run() -> PortfolioWidgetSnapshot {
            PortfolioWidgetStore.compute(transactions: txs, prices: prices, currency: "CHF", rate: 0.8, now: now,
                                         dailyPrices: [:], routed: [])
        }
        let sync = run()
        let background = await OrderedSerialRunner().runDetached { run() }
        XCTAssertEqual(background, sync)
        XCTAssertEqual(sync.totalUsdt ?? 0, 36_000, accuracy: 1e-6)
    }
}
