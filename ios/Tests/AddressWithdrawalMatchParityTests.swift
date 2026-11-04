import XCTest
@testable import CryptoChecker

/// Hinweis auf eine nicht in die Adress-Wallet erfasste Umbuchung (`AddressWithdrawalMatch`,
/// `address_withdrawal_match.json`). Wie `AddressWithdrawalMatchParityTest.kt`.
final class AddressWithdrawalMatchParityTests: XCTestCase {

    private typealias S = TestSupport

    private func dec(_ raw: Any?) -> Decimal? { PortfolioDecimal.parse(raw as? String) }

    private func int64(_ value: Any?) -> Int64 { Int64(S.number(value) ?? 0) }

    private func address(_ o: [String: Any]) -> WatchedAddress {
        WatchedAddress(id: int64(o["id"]), chain: AddressChain(rawValue: o["chain"] as? String ?? "") ?? .BTC,
                       address: o["address"] as? String ?? "", label: o["label"] as? String,
                       lastBalance: o["lastBalance"] as? String, lastUpdated: int64(o["lastUpdated"]),
                       countInPortfolio: o["countInPortfolio"] as? Bool ?? true)
    }

    private func trade(_ o: [String: Any]) -> PortfolioTx {
        var t = PortfolioTx(id: int64(o["id"]), coin: o["coin"] as? String ?? "",
                            type: PortfolioTxType(rawValue: o["type"] as? String ?? "") ?? .BUY,
                            amountExact: dec(o["amount"]) ?? 0, priceExact: dec(o["price"]), time: int64(o["time"]),
                            exchange: o["exchange"] as? String)
        t.transferFrom = o["transferFrom"] as? String
        t.transferTo = o["transferTo"] as? String
        return t
    }

    func testHintOnlyForTransfersIntoAnotherWallet() throws {
        let data = try S.fixture("address_withdrawal_match")
        for c in S.list(data["cases"]) {
            let name = c["name"] as? String ?? ""
            let a = address(c["address"] as? [String: Any] ?? [:])
            let txs = S.list(c["trades"]).map(trade)
            let expected = (c["matches"] as? [Any] ?? []).map { int64($0) }
            let matches = AddressWithdrawalMatch.matches(a, txs: txs)
            XCTAssertEqual(matches.map(\.id), expected, name)
            XCTAssertEqual(AddressWithdrawalMatch.targetWallet(matches), c["target"] as? String, name + " (Ziel)")
            XCTAssertEqual(AddressWithdrawalMatch.matches(coin: "btc", addresses: [a], txs: txs).map(\.id), expected,
                           name + " (Coin)")
        }
    }
}
