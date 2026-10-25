import XCTest
@testable import CryptoChecker

/// `PortfolioFifo.Queue.insert` (binäre Suche) ordnet Posten genau wie das schrittweise Suchen von hinten: hinter allen
/// Posten, vor denen der neue nicht liegt — auch bei gleichem Erwerb, gleicher Zeit und gleicher Id (verschobene Teile
/// eines Postens) und nach verbrauchten Posten vorn.
final class PortfolioFifoQueueTests: XCTestCase {
    private typealias Lot = PortfolioFifo.Lot

    /// Referenz: von hinten suchen, solange der neue Posten vor dem davor liegt.
    private static func insertLinear(_ list: inout [Lot], _ lot: Lot) {
        var i = list.count
        while i > 0 && lot.isBefore(list[i - 1]) { i -= 1 }
        list.insert(lot, at: i)
    }

    func testInsertOrderMatchesLinearSearch() {
        var state: UInt64 = 7
        func next(_ bound: Int) -> Int {
            state = state &* 6_364_136_223_846_793_005 &+ 1_442_695_040_888_963_407
            return Int((state >> 33) % UInt64(bound))
        }
        let queue = PortfolioFifo.Queue(key: "")
        var reference: [Lot] = []
        for step in 0..<3_000 {
            if next(4) == 0, !reference.isEmpty {
                queue.removeFirst()
                reference.removeFirst()
                continue
            }
            // Wenige verschiedene Werte: viele Gleichstände bei Erwerb, Zeit und Id
            let acquired = Int64(next(40))
            let lot = Lot(txId: Int64(next(6)), acquired: acquired, time: acquired + Int64(next(3)), kind: .BUY,
                          amount: Decimal(step + 1), cost: nil)
            queue.insert(lot)
            PortfolioFifoQueueTests.insertLinear(&reference, lot)
            let open = Array(queue.lots)
            XCTAssertEqual(open.count, reference.count)
            XCTAssertTrue(zip(open, reference).allSatisfy { pair in pair.0 === pair.1 }, "Schritt \(step)")
        }
        let expected = reference.reduce(Decimal(0)) { $0 + $1.amount }
        XCTAssertEqual(queue.lots.reduce(Decimal(0)) { $0 + $1.amount }, expected)
    }
}
