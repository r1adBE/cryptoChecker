import Combine
import XCTest
@testable import CryptoChecker

/// Rückfrage F-08 als Verhalten an den Schreibwegen von `AppData` statt nur am Quelltext, wie Android
/// `WatchlistWriteConsentTest`: Merkliste «nicht gespeichert», die App könnte fragen (eigenes Tor über
/// `WatchlistNotSavedGate.appDataOverride`, damit kein Dialog der Test-App erscheint und kein «Trotzdem» des Laufs
/// nachwirkt). Echte Testpaare auf `AppData.shared`, danach wiederhergestellt (`AppDataTestWatchlist`).
/// - ohne vorherige Zustimmung lehnt der Schreibweg ab: nichts geschrieben, keine Frage, kein Hinweis;
/// - über `withWatchlistConfirm` nach «Trotzdem» geschrieben, nach «Abbrechen» nicht (dann `cancelled`);
/// - ein Durchlass ohne Szene gilt nur für die Aktion (`runConsented`) samt erbender `Task`, nicht für `Task.detached`.
/// Dass JEDER Bildschirm vorher fragt, prüft weiter der Wächter `WatchlistConfirmCallGuardTests` über alle Dateien.
@MainActor
final class WatchlistConsentBehaviorTests: XCTestCase {
    private var watchlist: AppDataTestWatchlist?
    /// Eigenes Tor des Tests (in `setUp` neu, nie bestätigt).
    private var gate: WatchlistNotSavedGate!

    override func setUp() async throws {
        let watchlist = try await AppDataTestWatchlist()
        self.watchlist = watchlist
        try await watchlist.load([AddWatchesBehaviorTests.btc, AddWatchesBehaviorTests.eth])
        gate = WatchlistNotSavedGate()
        WatchlistNotSavedGate.appDataOverride = (gate: gate, notSaved: true, canAsk: true)
    }

    override func tearDown() async throws {
        WatchlistNotSavedGate.appDataOverride = nil
        await watchlist?.restore()
        watchlist = nil
    }

    private var ids: Set<Int64> { Set(AppData.shared.snapshot.watches.map(\.id)) }
    private var start: Set<Int64> { [AddWatchesBehaviorTests.btc.id, AddWatchesBehaviorTests.eth.id] }

    private func add(_ base: String) throws -> BulkAddResult {
        let exchange = try XCTUnwrap(ExchangesConfig.exchange("Binance"))
        return AppData.shared.addWatches(exchange: exchange, pairs: [ExchangePair(base, "USDT", "\(base)USDT")])
    }

    private func contains(_ base: String) -> Bool { AppData.shared.snapshot.watches.contains { $0.baseAsset == base } }

    /// Signal, sobald das Tor die Frage stellt (Dialog wäre sichtbar).
    private func questionAsked() -> (XCTestExpectation, AnyCancellable) {
        let asked = expectation(description: "Frage gestellt")
        let watcher = gate.$pending.filter { $0 }.first().sink { _ in asked.fulfill() }
        return (asked, watcher)
    }

    func testWriteWithoutConsentIsRefused() throws {
        let data = AppData.shared
        let hints = data.watchlistLockedHints
        let result = try add("SOL")
        XCTAssertTrue(result.locked)
        XCTAssertEqual(result.added, 0)
        XCTAssertTrue(data.deleteForUndo(ids: [AddWatchesBehaviorTests.btc.id]).isEmpty)
        XCTAssertEqual(ids, start, "nichts geschrieben")
        // Der Schreibweg fragt nie selbst und zeigt keinen Sperr-Hinweis
        XCTAssertFalse(gate.pending)
        XCTAssertEqual(data.watchlistLockedHints, hints)
    }

    func testProceedWritesThroughWithWatchlistConfirm() async throws {
        let probe = ConsentWriteProbe()
        let (asked, watcher) = questionAsked()
        let done = expectation(description: "Aktion gelaufen")
        AppData.shared.withWatchlistConfirm({
            probe.own = try? self.add("SOL")
            done.fulfill()
        }, cancelled: { probe.cancelled = true })
        await fulfillment(of: [asked], timeout: 5)
        XCTAssertNil(probe.own, "Aktion erst nach der Antwort")
        gate.answer(proceed: true)
        await fulfillment(of: [done], timeout: 5)
        watcher.cancel()
        XCTAssertEqual(probe.own?.added, 1)
        XCTAssertFalse(probe.cancelled)
        XCTAssertTrue(contains("SOL"))
        // «Trotzdem» gilt für den Rest des Laufs: ein weiterer Schreibweg ohne neue Frage
        XCTAssertEqual(try add("XRP").added, 1)
        XCTAssertFalse(gate.pending)
    }

    func testCancelWritesNothing() async throws {
        let probe = ConsentWriteProbe()
        let (asked, watcher) = questionAsked()
        let cancelled = expectation(description: "Abbrechen gemeldet")
        AppData.shared.withWatchlistConfirm({ probe.own = try? self.add("SOL") }, cancelled: {
            probe.cancelled = true
            cancelled.fulfill()
        })
        await fulfillment(of: [asked], timeout: 5)
        gate.answer(proceed: false)
        await fulfillment(of: [cancelled], timeout: 5)
        watcher.cancel()
        XCTAssertNil(probe.own, "Aktion nach «Abbrechen» nicht gelaufen")
        XCTAssertEqual(ids, start)
        // Die Sicherung bleibt: ein Schreibweg ohne Zustimmung wird weiter abgelehnt
        XCTAssertTrue(try add("SOL").locked)
    }

    /// Review F-27, ARC7-02: Durchlass ohne Szene (`run` mit `confirm` ohne Szene, wie `withWatchlistConfirm`) nur für
    /// die Aktion: Sie selbst und ein erbender `Task` schreiben, ein `Task.detached` aus ihr wird abgelehnt; nach der
    /// Aktion gilt die Sicherung wieder.
    func testUnaskedPassCoversOnlyTheActionTask() async throws {
        let exchange = try XCTUnwrap(ExchangesConfig.exchange("Binance"))
        let finished = expectation(description: "Aufgaben der Aktion")
        finished.expectedFulfillmentCount = 2
        let probe = ConsentWriteProbe(exchange: exchange, finished: finished)
        let gate: WatchlistNotSavedGate = gate
        gate.run(blocked: false, notSaved: true,
                 confirm: { await gate.confirm(notSaved: true, canAsk: false, awaitAsker: { false }) },
                 { probe.start() }, cancelled: nil)
        await fulfillment(of: [finished], timeout: 5)
        XCTAssertEqual(probe.own?.added, 1, "Aktion selbst")
        XCTAssertEqual(probe.child?.added, 1, "erbender Task gehört zur Aktion")
        XCTAssertEqual(probe.detached?.locked, true, "Task.detached gehört nicht zur Aktion")
        XCTAssertTrue(contains("SOL") && contains("XRP"))
        XCTAssertFalse(contains("ADA"))
        XCTAssertFalse(gate.pending)
        XCTAssertTrue(try add("DOGE").locked, "ausserhalb der Aktion")
    }
}

/// Messpunkte einer Aktion in `WatchlistConsentBehaviorTests` (Ergebnisse der Schreibwege).
@MainActor
private final class ConsentWriteProbe {
    var own: BulkAddResult?
    var child: BulkAddResult?
    var detached: BulkAddResult?
    var cancelled = false
    private let exchange: Exchange?
    private let finished: XCTestExpectation?

    init(exchange: Exchange? = nil, finished: XCTestExpectation? = nil) {
        self.exchange = exchange
        self.finished = finished
    }

    private func add(_ base: String) -> BulkAddResult? {
        guard let exchange else { return nil }
        return AppData.shared.addWatches(exchange: exchange, pairs: [ExchangePair(base, "USDT", "\(base)USDT")])
    }

    /// Aktion (synchron unter `runConsented`): selbst schreiben, dann eine erbende und eine losgelöste Aufgabe.
    func start() {
        own = add("SOL")
        Task { @MainActor in
            self.child = self.add("XRP")
            self.finished?.fulfill()
        }
        Task.detached { @MainActor in
            self.detached = self.add("ADA")
            self.finished?.fulfill()
        }
    }
}
