import XCTest
@testable import CryptoChecker

/// Einstand für Adressguthaben (Build 84) — wie `AddressCostLotsTest.kt`: Prüfung, Abdeckung (FIFO), Datei und Sicherung
/// (hin und zurück, Sicherung ohne das Feld).
final class AddressCostLotsTests: XCTestCase {

    private let btc = "bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq"

    private func d(_ s: String) -> Decimal { Decimal(string: s, locale: Locale(identifier: "en_US_POSIX"))! }

    private func lot(_ id: Int64, _ t: Int64, _ amount: String, price: String? = nil, cost: String? = nil) -> AddressCostLot {
        AddressCostLot(id: id, coin: "btc", time: t, amount: d(amount), priceUsdt: price.map(d), costUsdt: cost.map(d))
    }

    func testCleanDropsInvalidAndSorts() {
        let lots = AddressCostLots.clean([
            lot(2, 20, "0.1", price: "100"), lot(1, 10, "0.2", cost: "50"), lot(3, 30, "0", price: "1"),
            lot(4, 40, "1"), lot(5, 0, "1", price: "1"), lot(6, 50, "1", price: "-1"),
        ])
        XCTAssertEqual(lots.map(\.id), [1, 2])
        XCTAssertEqual(lots[0].coin, "BTC")
        XCTAssertEqual(lots[0].price, 250)
        XCTAssertEqual(lots[1].cost, 10)
        XCTAssertEqual(AddressCostLots.nextId(lots), 3)
    }

    func testCoverConsumesOldestFirstAndKeepsPricePerCoin() {
        let lots = AddressCostLots.clean([lot(1, 10, "0.2", cost: "2000"), lot(2, 20, "0.3", price: "300")])
        let full = AddressCostLots.cover("BTC", lots, balance: 1)
        XCTAssertFalse(full.capped)
        XCTAssertEqual(full.lotted, d("0.5"))
        XCTAssertEqual(full.unlotted, d("0.5"))
        XCTAssertEqual(full.lots, lots)
        let capped = AddressCostLots.cover("BTC", lots, balance: d("0.25"))
        XCTAssertTrue(capped.capped)
        XCTAssertEqual(capped.excess, d("0.25"))
        XCTAssertEqual(capped.lots.map(\.id), [2])
        XCTAssertEqual(capped.lots[0].amount, d("0.25"))
        XCTAssertEqual(capped.lots[0].price, 300)
        XCTAssertEqual(capped.unlotted, 0)
        let partial = AddressCostLots.cover("BTC", lots, balance: d("0.4"))
        XCTAssertEqual(partial.lots[0].amount, d("0.1"))
        XCTAssertEqual(partial.lots[0].price, 10000)
        XCTAssertEqual(partial.lots[0].cost, 1000)
    }

    func testAddressPartShrinksByLots() {
        let a = WatchedAddress(id: 7, chain: .BTC, address: btc, lastBalance: "1", costLots: [lot(1, 10, "0.4", price: "100")])
        let trades = AddressBalance.trades([a], realTrades: [], todayUtcDay: 20_000)
        let split = AddressBalance.split(trades)
        XCTAssertEqual(split.address["BTC"], d("0.6"))
        XCTAssertEqual(split.real.map(\.id), [AddressCostLots.tradeId(addressId: 7, index: 0)])
        XCTAssertEqual(split.real.first?.exchange, String(btc.prefix(6)) + "…" + String(btc.suffix(4)))
        XCTAssertEqual(AddressBalance.amount(for: "BTC", addresses: [a]), d("0.6"))
        var full = a
        full.costLots = [lot(1, 10, "1", price: "100")]
        XCTAssertTrue(AddressBalance.split(AddressBalance.trades([full], realTrades: [], todayUtcDay: 20_000)).address.isEmpty)
        XCTAssertTrue(split.real.allSatisfy { AddressBalance.isAddressTrade($0.id) && !AddressBalance.isAddressPart($0.id) })
    }

    func testFileRoundTrip() throws {
        let lots = AddressCostLots.clean([lot(1, 10, "0.00000001", price: "65000.5"), lot(2, 20, "2", cost: "100")])
        let a = WatchedAddress(id: 3, chain: .BTC, address: btc, costLots: lots)
        let data = try JSONEncoder().encode(WatchedAddressFile(addresses: [a], nextId: 4))
        let back = try JSONDecoder().decode(WatchedAddressFile.self, from: data)
        XCTAssertEqual(back.addresses.first?.costLots, lots)
        // Ältere Datei ohne das Feld: keine Posten
        let old = Data("{\"addresses\":[{\"id\":1,\"chain\":\"BTC\",\"address\":\"\(btc)\"}],\"nextId\":2}".utf8)
        XCTAssertEqual(try JSONDecoder().decode(WatchedAddressFile.self, from: old).addresses.first?.costLots, [])
    }

    func testBackupRoundTripAndBackupWithoutField() throws {
        let lots = AddressCostLots.clean([lot(1, 10, "0.5", price: "30000")])
        let a = WatchedAddress(chain: .BTC, address: btc, label: "Cold", costLots: lots)
        let data = try JSONEncoder().encode(WatchedAddressRules.backupEntries([a]))
        let array = try XCTUnwrap(try JSONSerialization.jsonObject(with: data) as? [Any])
        XCTAssertNotNil((array.first as? [String: Any])?["costLots"])
        let back = WatchedAddressRules.merge([], WatchedAddressRules.entries(fromJSON: array))
        XCTAssertEqual(back.first?.costLots, lots)
        let old: [Any] = [["chain": "BTC", "address": btc, "addedAt": 5]]
        XCTAssertEqual(WatchedAddressRules.merge([], WatchedAddressRules.entries(fromJSON: old)).first?.costLots, [])
    }

    func testExportTradesAddLotsAndFrHistoryWithoutLottedPart() {
        let history = [
            PortfolioTx(id: -1_000_001, coin: "BTC", type: .DEPOSIT, amountExact: d("0.3"), priceExact: nil, time: 10),
            PortfolioTx(id: -1_000_002, coin: "BTC", type: .DEPOSIT, amountExact: d("0.5"), priceExact: nil, time: 20),
            PortfolioTx(id: -1_000_003, coin: "BTC", type: .WITHDRAWAL, amountExact: d("0.1"), priceExact: nil, time: 30),
        ]
        let cut = AddressCostLots.withoutLotted(history, lotted: ["BTC": d("0.4")])
        XCTAssertEqual(cut.map(\.id), [-1_000_002, -1_000_003])
        XCTAssertEqual(cut.first?.amountExact, d("0.4"))
        XCTAssertEqual(AddressCostLots.withoutLotted(history, lotted: [:]), history)
        let a = WatchedAddress(id: 2, chain: .BTC, address: btc, lastBalance: "1", costLots: [lot(1, 5, "0.4", price: "100")])
        let real = [PortfolioTx(id: 1, coin: "BTC", type: .BUY, amountExact: 1, priceExact: 200, time: 50)]
        let export = AddressCostLots.exportTrades(real, addresses: [a], frTotalValue: false)
        XCTAssertEqual(export.map(\.id), [1, AddressCostLots.tradeId(addressId: 2, index: 0)])
        XCTAssertEqual(export.last?.type, .BUY)
    }
}
