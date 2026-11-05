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
            // «Ziel ändern»: genannte Umbuchung → Adress-Wallet; danach kein Hinweis mehr
            let want = c["retarget"] as? [String: Any]
            let r = AddressWithdrawalMatch.retarget(a, txs: txs)
            XCTAssertEqual(r?.tx.id, want.map { int64($0["id"]) }, name + " (Ändern)")
            XCTAssertEqual(r?.from, want?["from"] as? String, name + " (von)")
            XCTAssertEqual(r?.to, want?["to"] as? String, name + " (nach)")
            XCTAssertEqual(AddressWithdrawalMatch.retarget(coin: "BTC", addresses: [a], txs: txs), r, name + " (Karte)")
            if let r {
                let after = txs.map { t -> PortfolioTx in
                    var u = t
                    if t.id == r.tx.id { u.transferTo = r.to }
                    return u
                }
                XCTAssertEqual(AddressWithdrawalMatch.matches(a, txs: after).map(\.id), [], name + " (danach)")
                XCTAssertNil(AddressWithdrawalMatch.retarget(coin: "BTC", addresses: [a], txs: after), name + " (danach Karte)")
            }
        }
    }

    /// Coin-Karte mit zwei Adressen: Ziel ist die Wallet der Adresse, zu der die genannte Umbuchung passt. Wie Android.
    func testCardTargetsTheMatchingAddress() throws {
        let data = try S.fixture("address_withdrawal_match")
        let c = try XCTUnwrap(S.list(data["cases"]).first { !(($0["matches"] as? [Any]) ?? []).isEmpty })
        let txs = S.list(c["trades"]).map(trade)
        let hit = address(c["address"] as? [String: Any] ?? [:])
        let other = WatchedAddress(id: 9, chain: .BTC, address: "bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq",
                                   label: "Spare", lastBalance: "7", lastUpdated: hit.lastUpdated, countInPortfolio: true)
        let r = AddressWithdrawalMatch.retarget(coin: "BTC", addresses: [other, hit], txs: txs)
        XCTAssertEqual(r?.to, "Cold")
        XCTAssertEqual(r?.tx.id, 2)
    }
}
