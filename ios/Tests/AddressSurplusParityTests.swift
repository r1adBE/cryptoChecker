import XCTest
@testable import CryptoChecker

/// «Nur Mehrbestand» (`AddressSurplus`, `address_surplus.json`): Karte, Reihe (Zeiträume/Verlauf) und Stichtag zählen je
/// Coin nur, was die zählenden Adressen über die eigenen Transaktionen hinaus halten. Wie `AddressSurplusParityTest.kt`.
final class AddressSurplusParityTests: XCTestCase {

    private typealias S = TestSupport

    private func dec(_ raw: Any?) -> Decimal? { PortfolioDecimal.parse(raw as? String) }

    private func int64(_ value: Any?) -> Int64 { Int64(S.number(value) ?? 0) }

    private func address(_ o: [String: Any]) -> WatchedAddress {
        WatchedAddress(id: int64(o["id"]), chain: AddressChain(rawValue: o["chain"] as? String ?? "") ?? .BTC,
                       address: o["address"] as? String ?? "", lastBalance: o["lastBalance"] as? String,
                       lastUpdated: int64(o["lastUpdated"]))
    }

    private func trade(_ o: [String: Any]) -> PortfolioTx {
        PortfolioTx(id: int64(o["id"]), coin: o["coin"] as? String ?? "",
                    type: PortfolioTxType(rawValue: o["type"] as? String ?? "") ?? .BUY,
                    amountExact: dec(o["amount"]) ?? 0, priceExact: dec(o["price"]), time: int64(o["time"]),
                    exchange: o["exchange"] as? String)
    }

    private func same(_ label: String, _ expected: Any?, _ actual: [String: Decimal]) {
        let e = (expected as? [String: Any] ?? [:]).compactMapValues { dec($0) }
        XCTAssertEqual(Set(e.keys), Set(actual.keys), "\(label) coins")
        for (coin, v) in e { XCTAssertEqual(actual[coin], v, "\(label) \(coin)") }
    }

    func testRuleAlone() throws {
        let data = try S.fixture("address_surplus")
        for raw in data["counted"] as? [[String]] ?? [] {
            let v = raw.compactMap { PortfolioDecimal.parse($0) }
            XCTAssertEqual(v.count, 3)
            XCTAssertEqual(AddressSurplus.counted(v[0], ledger: v[1]), v[2], "\(raw[0]) − \(raw[1])")
        }
    }

    func testCardHistoryAndCutoffCountOnlyTheSurplus() throws {
        let data = try S.fixture("address_surplus")
        let today = int64(data["today"])
        let cutoff = int64(data["cutoff"])
        for c in S.list(data["cases"]) {
            let name = c["name"] as? String ?? ""
            let addresses = S.list(c["addresses"]).map(address)
            let real = S.list(c["trades"]).map(trade)
            let all = AddressBalance.withAddresses(real, addresses: addresses, todayUtcDay: today)
            same("\(name) card", c["addressPart"], AddressBalance.split(all).address)
            for (coin, v) in (c["holdings"] as? [String: Any] ?? [:]).compactMapValues({ dec($0) }) {
                let p = PortfolioCalculator.position(coin, trades: all, currentPrice: nil)
                XCTAssertEqual(p.holdingsExact, v, "\(name) holdings \(coin)")
            }
            for (coin, v) in (c["addressPart"] as? [String: Any] ?? [:]).compactMapValues({ dec($0) }) {
                XCTAssertEqual(AddressBalance.amount(for: coin, addresses: addresses, realTrades: real), v, "\(name) detail \(coin)")
            }
            same("\(name) booked", c["booked"],
                 AddressBalanceHistory.latestByCoin(AddressBooked.histories(addresses, real: real)))
            same("\(name) cutoff", c["cutoffAddress"],
                 AddressCutoff.at(addresses, real: real, cutoffMillis: cutoff).part.balances)
        }
    }
}
