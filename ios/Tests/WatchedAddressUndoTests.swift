import XCTest
@testable import CryptoChecker

/// «Adresse entfernen» mit «Rückgängig» (`WatchedAddressUndo`). Gegenstück: `WatchedAddressUndoTest.kt`.
final class WatchedAddressUndoTests: XCTestCase {
    private func address(_ id: Int64, _ addr: String? = nil) -> WatchedAddress {
        WatchedAddress(id: id, chain: .BTC, address: addr ?? "bc1qaddress\(id)")
    }

    private let lot = AddressCostLot(id: 1, coin: "BTC", time: 1_000, amount: Decimal(string: "0.1")!)
    private let obs = AddressObservation(time: 1_000, balance: Decimal(string: "0.1")!)

    func testRueckfrageNenntWasMitgeht() {
        XCTAssertTrue(WatchedAddressUndo.removal(address(1)).isEmpty)
        var a = address(1)
        a.costLots = [lot, AddressCostLot(id: 2, coin: "BTC", time: 2_000, amount: 1)]
        XCTAssertEqual(WatchedAddressUndo.removal(a), .init(lots: 2, history: false))
        var b = address(1)
        b.observations = [obs]
        XCTAssertEqual(WatchedAddressUndo.removal(b), .init(lots: 0, history: true))
        // Token-Reihen zählen als Verlauf
        var c = address(1)
        c.costLots = [lot]
        c.tokenObservations = ["USDC": [obs]]
        XCTAssertEqual(WatchedAddressUndo.removal(c), .init(lots: 1, history: true))
        var d = address(1)
        d.tokenObservations = ["USDC": []]
        XCTAssertTrue(WatchedAddressUndo.removal(d).isEmpty)
    }

    func testZurueckNurMitFreierIdUndOhneDoppel() {
        var removed = address(5)
        removed.costLots = [lot]
        removed.observations = [obs]
        XCTAssertTrue(WatchedAddressUndo.canRestore([address(1), address(2)], removed))
        // Id inzwischen belegt bzw. dieselbe Adresse neu hinzugefügt
        XCTAssertFalse(WatchedAddressUndo.canRestore([address(5, "bc1qother")], removed))
        XCTAssertFalse(WatchedAddressUndo.canRestore([address(9, removed.address)], removed))
        // Liste voll
        let full = (100..<(100 + Int64(WatchedAddressRules.maxAddresses))).map { address($0) }
        XCTAssertFalse(WatchedAddressUndo.canRestore(full, removed))
        var zero = removed
        zero.id = 0
        XCTAssertFalse(WatchedAddressUndo.canRestore([], zero))
    }

    func testRestoreBehaeltIdUndAllesDazu() {
        var removed = address(5)
        removed.costLots = [lot]
        removed.observations = [obs]
        var file = WatchedAddressFile(addresses: [address(1)], nextId: 4)
        XCTAssertTrue(WatchedAddressUndo.restore(removed, into: &file))
        XCTAssertEqual(file.addresses.last, removed)
        // Ids werden nie doppelt vergeben
        XCTAssertEqual(file.nextId, 6)
        XCTAssertFalse(WatchedAddressUndo.restore(removed, into: &file))
    }
}
