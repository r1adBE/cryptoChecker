import XCTest
@testable import CryptoChecker

/// Gemeinsame Testfälle `bulk_delisted.json` — wie `BulkDelistedTest.kt`.
final class BulkDelistedTests: XCTestCase {
    private typealias S = TestSupport

    private func strings(_ v: Any?) -> [String] { (v as? [Any])?.compactMap { $0 as? String } ?? [] }

    private func state(_ v: Any?) -> BulkDelisted.State? {
        guard let o = v as? [String: Any] else { return nil }
        return BulkDelisted.State(delisted: Set(strings(o["delisted"])), recheckedAt: Int64(S.number(o["recheckedAt"]) ?? 0))
    }

    func testRecheckInterval() throws {
        let data = try S.fixture("bulk_delisted")
        XCTAssertEqual(BulkDelisted.recheckMillis, Int64(S.number(data["recheckMillis"]) ?? 0))
    }

    func testPlan() throws {
        let data = try S.fixture("bulk_delisted")
        let cases = S.list(data["plan"])
        XCTAssertFalse(cases.isEmpty)
        for c in cases {
            let name = c["name"] as? String ?? ""
            let plan = BulkDelisted.plan(state: state(c["state"]), pairIds: strings(c["pairIds"]),
                                         notTraded: Set(strings(c["notTraded"])), now: Int64(S.number(c["now"]) ?? 0))
            XCTAssertEqual(plan.request, strings(c["request"]), name)
            XCTAssertEqual(plan.recheck, c["recheck"] as? Bool, name)
            XCTAssertEqual(plan.all, strings(c["pairIds"]), name)
        }
    }

    func testLearn() throws {
        let data = try S.fixture("bulk_delisted")
        let cases = S.list(data["learn"])
        XCTAssertFalse(cases.isEmpty)
        for c in cases {
            let name = c["name"] as? String ?? ""
            let p = c["plan"] as? [String: Any] ?? [:]
            let plan = BulkDelisted.Plan(all: strings(p["all"]), request: strings(p["request"]), recheck: p["recheck"] as? Bool ?? false)
            let next = BulkDelisted.learn(state: state(c["state"]), plan: plan, tickers: Set(strings(c["tickers"])),
                                          failed: Set(strings(c["failed"])), complete: c["complete"] as? Bool ?? false,
                                          now: Int64(S.number(c["now"]) ?? 0),
                                          // Teilabfrage gescheitert (z. B. Alpha): keine Aussage über diese Paare
                                          incomplete: Set(strings(c["incomplete"])))
            XCTAssertEqual(next.delisted, Set(strings(c["delisted"])), name)
            XCTAssertEqual(next.recheckedAt, Int64(S.number(c["recheckedAt"]) ?? 0), name)
        }
    }

    private func states(_ v: Any?) -> [String: BulkDelisted.State] {
        var out: [String: BulkDelisted.State] = [:]
        for (k, o) in (v as? [String: Any]) ?? [:] { out[k] = state(o) }
        return out
    }

    /// Speicherform: gleicher Text wie Android, Rundweg ohne Verlust.
    func testCodec() throws {
        let data = try S.fixture("bulk_delisted")
        let cases = S.list(data["codec"])
        XCTAssertFalse(cases.isEmpty)
        for c in cases {
            let name = c["name"] as? String ?? ""
            let encoded = BulkDelisted.encode(states(c["states"]))
            XCTAssertEqual(encoded, c["encoded"] as? String, name)
            let back = BulkDelisted.decode(encoded)
            XCTAssertEqual(BulkDelisted.encode(back), encoded, name)
            XCTAssertEqual(Set(back.keys), Set(states(c["states"]).keys), name)
        }
    }

    func testDecodeInvalid() throws {
        let data = try S.fixture("bulk_delisted")
        let cases = S.list(data["decodeInvalid"])
        XCTAssertFalse(cases.isEmpty)
        for c in cases {
            XCTAssertEqual(BulkDelisted.decode(c["text"] as? String), states(c["states"]), c["name"] as? String ?? "")
        }
    }

    /// Widget: nie eine Neuprüfung, weder ohne Stand noch mit altem Stand (leere Anfrage für markierte Paare) — ein Fehler
    /// der gefilterten Anfrage überschriebe sonst die Markierung «nicht mehr gehandelt». Die Neuprüfung macht die App.
    func testWidgetNeverRechecks() {
        let widget = BulkDelisted.plan(state: nil, pairIds: ["OLDUSDT"], notTraded: ["OLDUSDT"], now: 1_000_000,
                                       lowMemory: true)
        XCTAssertEqual(widget.request, [])
        XCTAssertFalse(widget.recheck)
        let mixed = BulkDelisted.plan(state: nil, pairIds: ["BTCUSDT", "OLDUSDT"], notTraded: ["OLDUSDT"],
                                      now: 1_000_000, lowMemory: true)
        XCTAssertEqual(mixed.request, ["BTCUSDT"])
        let stale = BulkDelisted.State(delisted: ["GONEUSDT"], recheckedAt: 0)
        let old = BulkDelisted.plan(state: stale, pairIds: ["OLDUSDT", "GONEUSDT"], notTraded: ["OLDUSDT"],
                                    now: BulkDelisted.recheckMillis * 2, lowMemory: true)
        XCTAssertEqual(old.request, [])
        XCTAssertFalse(old.recheck)
        // Uhr zurückgestellt: im Widget ebenfalls keine Neuprüfung
        XCTAssertFalse(BulkDelisted.plan(state: BulkDelisted.State(delisted: [], recheckedAt: 5_000), pairIds: ["OLDUSDT"],
                                         notTraded: ["OLDUSDT"], now: 1_000, lowMemory: true).recheck)
        // App: alter Stand → Neuprüfung
        XCTAssertTrue(BulkDelisted.plan(state: stale, pairIds: ["OLDUSDT"], notTraded: ["OLDUSDT"],
                                        now: BulkDelisted.recheckMillis).recheck)
    }

    /// Der Stand überlebt einen Prozess-Start: eine neue Instanz liest ihn aus den Einstellungen und prüft nicht neu.
    func testMemoryPersistsAcrossInstances() throws {
        let suite = "BulkDelistedTests.persist"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defaults.removePersistentDomain(forName: suite)
        defer { defaults.removePersistentDomain(forName: suite) }

        // Je Aufruf eine Instanz derselben Suite (der Rückruf ist `@Sendable`, `UserDefaults` nicht)
        let first = BulkDelistedMemory(defaults: { UserDefaults(suiteName: suite) ?? .standard })
        let plan = first.plan("Binance", pairIds: ["OLDUSDT"], notTraded: ["OLDUSDT"], now: 1_000_000)
        XCTAssertTrue(plan.recheck)
        first.learn("Binance", plan: plan, result: BulkTickers(tickers: [:], complete: true), now: 1_000_000)

        let restarted = BulkDelistedMemory(defaults: { UserDefaults(suiteName: suite) ?? .standard })
        let next = restarted.plan("Binance", pairIds: ["OLDUSDT"], notTraded: ["OLDUSDT"], now: 1_060_000)
        XCTAssertFalse(next.recheck)
        XCTAssertEqual(next.request, [])
        XCTAssertNotNil(defaults.string(forKey: BulkDelistedMemory.defaultsKey))
    }
}
