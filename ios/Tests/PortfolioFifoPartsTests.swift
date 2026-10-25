import XCTest
@testable import CryptoChecker

/// Herkunftsteile der FIFO-Posten erst beim Auslesen (PortfolioFifoParts.swift): Die Gebühr USDT einer Umbuchung steht
/// einmal im Gebühren-Strom der Warteschlange; Veräusserungen und offener Einstand lesen ihre Teile daraus. Gleich wie
/// Android (`PortfolioFifoPartsTest`):
///  - jede Veräusserung: Summe der Teile = Einstand (exakt), kein Teil nach dem Abgang;
///  - offene Posten: Summe der Teile = Einstand mit Kaufpreis (exakt);
///  - Umbuchung auf zwei Posten mit Kaufpreis und einen ohne: Anteil nach Menge, der Anteil ohne Kaufpreis vermerkt.
final class PortfolioFifoPartsTests: XCTestCase {
    private let tolerance = Decimal(string: "1E-18")!

    private func sum<C: Collection>(_ parts: C) -> Decimal where C.Element == PortfolioCostPart {
        parts.reduce(Decimal(0)) { $0 + $1.usdt }
    }

    func testPartsAddUpToCostOnLargePortfolio() {
        let trades = PortfolioPerformanceData.trades(PerformanceTiming.transactions.small)
        for method in [CostMethod.FIFO, .FIFO_WALLET] {
            let books = PortfolioCalculator.ledgers(trades, method: method)
            var checked = 0
            for book in books {
                for d in book.disposals {
                    guard let cost = d.costUsdt, !d.costParts.isEmpty else { continue }
                    // Decimal hat 38 Stellen: Summen ab der 38. Stelle gerundet (Toleranz wie die übrigen Summen)
                    XCTAssertLessThanOrEqual(abs(cost - sum(d.costParts)), tolerance, "\(method) \(d.coin) \(d.sellTxId)")
                    XCTAssertTrue(d.costParts.allSatisfy { $0.time <= d.sellTime })
                    checked += 1
                }
                let open = book.position.pricedCostParts
                if !open.isEmpty, let cost = book.position.pricedCostExact {
                    XCTAssertLessThanOrEqual(abs(cost - sum(open)), tolerance, "\(method) \(book.position.coin) open")
                }
            }
            XCTAssertGreaterThan(checked, 100, "\(method): Veräusserungen mit Teilen geprüft")
        }
    }

    func testTransferFeeSharedByAmountAcrossQueue() throws {
        let t0: Int64 = 1_700_000_000_000
        let day: Int64 = 86_400_000
        func tx(_ id: Int64, _ type: PortfolioTxType, _ amount: Decimal, _ price: Decimal?, _ time: Int64,
                fee: Decimal? = nil) -> PortfolioTx {
            PortfolioTx(id: id, coin: "BTC", type: type, amountExact: amount, priceExact: price, time: time,
                        feeExact: fee, feeUnit: fee == nil ? nil : .QUOTE)
        }
        let list = [
            tx(1, .BUY, 1, 100, t0),
            tx(2, .DEPOSIT, 2, nil, t0 + day),
            tx(3, .BUY, 1, 200, t0 + 2 * day),
            // Gebühr 8 USDT auf 4 Coins: je Coin 2 — Posten 1 und 3 je 2, Posten 2 (ohne Kaufpreis) 4 vermerkt
            tx(4, .TRANSFER, 1, nil, t0 + 3 * day, fee: 8),
            tx(5, .SELL, 1, 500, t0 + 4 * day),
        ]
        let books = PortfolioCalculator.ledgers(list, method: .FIFO)
        XCTAssertEqual(books.count, 1)
        let book = try XCTUnwrap(books.first)
        XCTAssertEqual(book.disposals.count, 1)
        let sale = try XCTUnwrap(book.disposals.first)
        XCTAssertEqual(sale.costUsdt, 102)
        XCTAssertEqual(sale.costParts.map(\.time), [t0, t0 + 3 * day])
        XCTAssertEqual(sale.costParts.map(\.usdt), [100, 2])
        XCTAssertEqual(book.unassignedFees.map(\.usdt), [4])
        // Offen: Posten 3 mit 200 + 2 (und Posten 2 ohne Kaufpreis)
        XCTAssertEqual(book.position.pricedCostExact, 202)
        XCTAssertEqual(book.position.pricedCostParts.map(\.time), [t0 + 2 * day, t0 + 3 * day])
    }
}
