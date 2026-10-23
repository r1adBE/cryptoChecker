import XCTest
@testable import CryptoChecker

private let gateTestXpub =
    "xpub6ceT3jjUWNFQ4zxeZWGDSw8iM3MjHpxvZcKVZFLU4zbXnUGAnVSRraqr8LaZwCaUFT6TV7ZF6834RXLF5xzxa2ew6Jw63uPr91LryWJ8fiz"

/// Single-Flight der Adress-Aktualisierung (`AddressRefreshGate`): zwei gleichzeitige Vordergrund-Aufrufe laden jede
/// Adresse genau einmal (auch xpub); der Hintergrund kehrt sofort zurück, wenn schon eine Runde läuft.
/// Gegenstück: `WatchedAddressSingleFlightTest.kt`.
@MainActor
final class AddressRefreshGateTests: XCTestCase {
    /// Fake-Speicher und Fake-Loader mit Zähler je Adresse; eine Runde wartet auf `released`.
    @MainActor
    private final class FakeStore {
        var stored = [
            WatchedAddress(id: 1, chain: .BTC, address: "bc1qdjgwenuvhz447xe3udlc0yh6ng8lnxd9v666m8"),
            WatchedAddress(id: 2, chain: .BTC, address: gateTestXpub),
            WatchedAddress(id: 3, chain: .ETH, address: "0x0000000000000000000000000000000000000001"),
        ]
        var loads: [Int64: Int] = [:]
        var rounds = 0
        var started = false
        var released = false
        let now = TimeUtils.nowMillis

        func round() async {
            rounds += 1
            let due = AddressBalance.dueOrder(stored, now: now, force: false, background: false)
            for a in due { loads[a.id, default: 0] += 1 }
            started = true
            while !released { await Task.yield() }
            let ids = Set(due.map(\.id))
            stored = stored.map { a in
                var copy = a
                if ids.contains(a.id) { copy.lastAttempt = now }
                return copy
            }
        }
    }

    private func start(_ gate: AddressRefreshGate, _ store: FakeStore) -> Task<Bool, Never> {
        Task { await gate.run(background: false) { await store.round() } }
    }

    func testZweiVordergrundAufrufeLadenJedeAdresseEinmal() async {
        let gate = AddressRefreshGate()
        let store = FakeStore()
        XCTAssertTrue(store.stored[1].isXpub)
        let first = start(gate, store)
        while !store.started { await Task.yield() }
        let second = start(gate, store)
        for _ in 0..<10 { await Task.yield() }
        // Der zweite wartet, statt parallel zu laden
        XCTAssertEqual(store.rounds, 1)
        store.released = true
        _ = await first.value
        _ = await second.value
        XCTAssertEqual(store.loads, [1: 1, 2: 1, 3: 1])
        // Zweite Runde lief nach der ersten und las frisch: nichts mehr fällig
        XCTAssertEqual(store.rounds, 2)
        XCTAssertFalse(gate.isRunning)
    }

    func testHintergrundKehrtSofortZurueck() async {
        let gate = AddressRefreshGate()
        let store = FakeStore()
        let first = start(gate, store)
        while !store.started { await Task.yield() }
        let ran = await gate.run(background: true) { await store.round() }
        XCTAssertFalse(ran)
        XCTAssertEqual(store.rounds, 1)
        store.released = true
        let firstRan = await first.value
        XCTAssertTrue(firstRan)
        XCTAssertEqual(store.loads, [1: 1, 2: 1, 3: 1])
        XCTAssertFalse(gate.isRunning)
    }
}
