import XCTest
@testable import CryptoChecker

/// «Ziel ändern» am Umbuchungs-Hinweis (`PortfolioRetarget.retargeted`, `AddressWithdrawalMatch.retarget(coin:)`): nur die
/// genannte Umbuchung bekommt die Adress-Wallet als Ziel, alles andere bleibt; danach kein Hinweis mehr. Wie Android
/// `PortfolioRetargetTest`.
final class PortfolioRetargetTests: XCTestCase {

    private let cold = WatchedAddress(id: 1, chain: .BTC, address: "bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4",
                                      label: "Cold", lastBalance: "1.0", lastUpdated: 1_727_740_800_000,
                                      countInPortfolio: true)

    private func tx(_ id: Int64, _ type: PortfolioTxType, _ amount: Decimal, exchange: String? = nil,
                    from: String? = nil, to: String? = nil) -> PortfolioTx {
        var t = PortfolioTx(id: id, coin: "BTC", type: type, amountExact: amount,
                            priceExact: type == .BUY ? 40_000 : nil, time: 1_725_000_000_000 + id * 86_400_000,
                            exchange: exchange)
        t.transferFrom = from
        t.transferTo = to
        return t
    }

    private var all: [PortfolioTx] {
        [tx(1, .BUY, 2, exchange: "Binance"),
         tx(2, .TRANSFER, 1, from: "Binance", to: "Ledger"),
         tx(3, .TRANSFER, Decimal(string: "0.3")!, from: "Binance", to: "Trezor")]
    }

    func testOnlyTheNamedTransferGetsTheAddressWallet() throws {
        var expected = all[1]
        expected.transferTo = "Cold"
        XCTAssertEqual(PortfolioRetarget.retargeted(all, id: 2, to: " Cold "), expected)
        // Keine Umbuchung, unbekannte id oder leeres Ziel: nichts
        XCTAssertNil(PortfolioRetarget.retargeted(all, id: 1, to: "Cold"))
        XCTAssertNil(PortfolioRetarget.retargeted(all, id: 99, to: "Cold"))
        XCTAssertNil(PortfolioRetarget.retargeted(all, id: 2, to: "  "))
        // Gekürzt wie im Feld
        XCTAssertEqual(PortfolioRetarget.retargeted(all, id: 2, to: String(repeating: "x", count: 60))?.transferTo?.count,
                       PortfolioTxForm.maxRoute)
    }

    func testHintGoneAfterTheChange() throws {
        let r = try XCTUnwrap(AddressWithdrawalMatch.retarget(coin: "BTC", addresses: [cold], txs: all))
        XCTAssertEqual(r.tx.id, 2)
        XCTAssertEqual(r.from, "Ledger")
        XCTAssertEqual(r.to, "Cold")
        let saved = try XCTUnwrap(PortfolioRetarget.retargeted(all, id: r.tx.id, to: r.to))
        let after = all.map { $0.id == saved.id ? saved : $0 }
        XCTAssertNil(AddressWithdrawalMatch.retarget(coin: "BTC", addresses: [cold], txs: after))
        XCTAssertEqual(after[2].transferTo, "Trezor")
    }
}
