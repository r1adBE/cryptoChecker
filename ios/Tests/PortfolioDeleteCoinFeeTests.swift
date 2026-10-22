import XCTest
@testable import CryptoChecker

/// Seit Build 102: «Coin löschen» entfernt auch die Gebühr in diesem Coin bei Transaktionen anderer Coins (sonst erschiene
/// der Coin als Position «mehr abgegeben als erfasst»); «Rückgängig» legt die Originalfassung wieder an. Wie
/// `PortfolioCoinDeletionTest.kt` (Android).
final class PortfolioDeleteCoinFeeTests: XCTestCase {

    private func file() -> PortfolioFile {
        var transfer = PortfolioTx(id: 2, coin: "ETH", type: .TRANSFER, amount: 1, time: 2)
        transfer.feeCoin = "bnb"
        transfer.feeCoinAmountExact = Decimal(string: "0.01")
        transfer.feeCoinPriceExact = 600
        return PortfolioFile(transactions: [
            PortfolioTx(id: 1, coin: "BNB", type: .BUY, amount: 1, priceUsdt: 500, time: 1),
            transfer,
            PortfolioTx(id: 3, coin: "ETH", type: .BUY, amount: 2, priceUsdt: 10, time: 3),
        ], nextId: 4)
    }

    func testGebuehrInGeloeschtemCoinWirdMitEntfernt() {
        var written: PortfolioFile?
        let removed = PortfolioEdits.deleteCoin("BNB", in: file(), write: { written = $0; return true })
        // Gelöscht: der BNB-Kauf; geändert (Originalfassung für «Rückgängig»): die ETH-Umbuchung
        XCTAssertEqual(removed.map(\.id), [1, 2])
        XCTAssertEqual(removed.last?.feeCoin, "bnb")
        XCTAssertEqual(written?.transactions.map(\.id), [2, 3])
        let kept = written?.transactions.first { $0.id == 2 }
        XCTAssertNil(kept?.feeCoin)
        XCTAssertNil(kept?.feeCoinAmountExact)
        XCTAssertNil(kept?.feeCoinPriceExact)
        // «Rückgängig» ersetzt je id: alles wie vorher
        var restored: PortfolioFile?
        XCTAssertTrue(PortfolioEdits.restore(removed, in: written!, write: { restored = $0; return true }))
        XCTAssertEqual(restored?.transactions.first { $0.id == 2 }?.feeCoin, "bnb")
        XCTAssertEqual(restored?.transactions.count, 3)
    }

    func testNurGebuehrBezugWirdTrotzdemBereinigt() {
        // Coin ohne eigene Transaktion, nur als Gebühr erfasst (Phantom-Position): Löschen entfernt die Gebühr
        var f = file()
        f.transactions.removeAll { $0.id == 1 }
        var written: PortfolioFile?
        let removed = PortfolioEdits.deleteCoin("bnb", in: f, write: { written = $0; return true })
        XCTAssertEqual(removed.map(\.id), [2])
        XCTAssertNil(written?.transactions.first { $0.id == 2 }?.feeCoin)
        XCTAssertFalse(PortfolioEdits.paysFee(f.transactions[0], in: "ETH"))
        XCTAssertTrue(PortfolioEdits.paysFee(f.transactions[0], in: "BNB"))
    }
}
