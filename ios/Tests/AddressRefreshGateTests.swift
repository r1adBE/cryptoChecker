import XCTest
@testable import CryptoChecker

private let gateTestXpub =
    "xpub6ceT3jjUWNFQ4zxeZWGDSw8iM3MjHpxvZcKVZFLU4zbXnUGAnVSRraqr8LaZwCaUFT6TV7ZF6834RXLF5xzxa2ew6Jw63uPr91LryWJ8fiz"

/// Single-Flight der Adress-Aktualisierung (`AddressRefreshGate`): zwei gleichzeitige Vordergrund-Aufrufe laden jede
/// Adresse genau einmal (auch xpub); der Hintergrund kehrt sofort zurück, wenn schon eine Runde läuft. Während einer
/// Runde vom Nutzer fällig gemacht («Tokens mitzählen» an): Ergebnis verworfen, der Wartende lädt neu. Abbruch des
/// Startenden gibt das Gate frei. Gegenstück: `WatchedAddressSingleFlightTest.kt`.
@MainActor
final class AddressRefreshGateTests: XCTestCase {
    /// Fake-Loader mit Zähler je Adresse über der echten Datei (`WatchedAddressStore.apply`); eine Runde wartet auf
    /// `released`, bei Abbruch speichert sie nichts (wie `loadWatchedAddresses`).
    @MainActor
    private final class FakeStore {
        let now: Int64
        var file: WatchedAddressFile
        var loads: [Int64: Int] = [:]
        /// «Tokens mitzählen» der Adresse 3 je Abruf.
        var ethCountTokens: [Bool] = []
        var rounds = 0
        var started = false
        var released = false

        var stored: [WatchedAddress] { file.addresses }

        init() {
            let now = TimeUtils.nowMillis
            self.now = now
            file = WatchedAddressFile(addresses: [
                WatchedAddress(id: 1, chain: .BTC, address: "bc1qdjgwenuvhz447xe3udlc0yh6ng8lnxd9v666m8"),
                WatchedAddress(id: 2, chain: .BTC, address: gateTestXpub),
                // Schon früher versucht (vor einer Stunde), Token aus
                WatchedAddress(id: 3, chain: .ETH, address: "0x0000000000000000000000000000000000000001",
                               lastAttempt: now - 3_600_000, countTokens: false),
            ], nextId: 4)
        }

        func round() async {
            rounds += 1
            let due = AddressBalance.dueOrder(stored, now: now, force: false, background: false)
            for a in due { loads[a.id, default: 0] += 1 }
            ethCountTokens += due.filter { $0.id == 3 }.map(\.countTokens)
            started = true
            while !released && !Task.isCancelled { await Task.yield() }
            if Task.isCancelled { return }
            for a in due {
                let fetched = WatchedAddressSource.Fetched(balance: "1", provider: "test")
                let update = WatchedAddressSource.Update(id: a.id, time: now, fetched: fetched, error: nil,
                                                         snapshotAttempt: a.lastAttempt)
                WatchedAddressStore.apply(update, to: &file)
            }
        }
    }

    private func waitFor(_ condition: () -> Bool) async {
        for _ in 0..<10_000 where !condition() { await Task.yield() }
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
        await waitFor { gate.waiting == 1 }
        // Der zweite wartet, statt parallel zu laden
        XCTAssertEqual(gate.waiting, 1)
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

    func testNutzerAenderungWaehrendRundeBleibtErhalten() async {
        let gate = AddressRefreshGate()
        let store = FakeStore()
        let first = start(gate, store)
        await waitFor { store.started }
        // Während Runde 1 läuft: «Tokens mitzählen» an (wie AppData über WatchedAddressStore.change)
        WatchedAddressStore.change(id: 3, in: &store.file) { WatchedAddressRules.withCountTokens($0, true) }
        XCTAssertEqual(store.stored[2].lastAttempt, 0)
        let second = start(gate, store)
        await waitFor { gate.waiting == 1 }
        store.released = true
        _ = await first.value
        _ = await second.value
        // Ergebnis von Runde 1 (alte Einstellung) verworfen: Adresse 3 bleibt fällig, der Wartende lädt sie mit Token
        XCTAssertEqual(store.loads, [1: 1, 2: 1, 3: 2])
        XCTAssertEqual(store.ethCountTokens, [false, true])
        XCTAssertEqual(store.stored[2].lastAttempt, store.now)
        XCTAssertFalse(gate.isRunning)
    }

    func testAbbruchDesStartendenGibtGateFrei() async {
        let gate = AddressRefreshGate()
        let store = FakeStore()
        let first = start(gate, store)
        await waitFor { store.started }
        let second = start(gate, store)
        await waitFor { gate.waiting == 1 }
        first.cancel()
        _ = await first.value
        // Der Wartende startet eine eigene Runde (Runde 1 speicherte nichts)
        await waitFor { store.rounds == 2 }
        XCTAssertEqual(store.rounds, 2)
        XCTAssertEqual(gate.waiting, 0)
        store.released = true
        let secondRan = await second.value
        XCTAssertTrue(secondRan)
        XCTAssertEqual(store.loads, [1: 2, 2: 2, 3: 2])
        XCTAssertFalse(gate.isRunning)
    }
}
