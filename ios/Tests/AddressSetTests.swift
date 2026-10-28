import XCTest
@testable import CryptoChecker

// Kleine Testklassen dieses Themas in einer Datei; jede Klasse unverändert.

// ---- aus AddressPrivacyParityTests

/// Neutraler User-Agent der Adressabfragen (`Parity/address_privacy.json`): gleicher Wert wie Android,
/// ohne App-Name/Version (sonst sendet CFNetwork den Bundle-Namen); dazu Host-Regel und eigene Sessions.
/// Gegenstück: `AddressPrivacyParityTest.kt`.
final class AddressPrivacyParityTests: XCTestCase {
    private typealias S = TestSupport

    func testNeutralerUserAgentWieGemeinsamerWert() throws {
        let fixture = try S.fixture("address_privacy")
        XCTAssertEqual(HttpUserAgent.neutral, fixture["userAgent"] as? String)
        let forbidden = try XCTUnwrap(fixture["forbiddenParts"] as? [String])
        for part in forbidden {
            XCTAssertNil(HttpUserAgent.neutral.range(of: part, options: .caseInsensitive), part)
        }
    }

    /// Weder App-Name noch App-Version (Beispiele und Version dieser App), wie Android.
    func testNeutralerUserAgentOhneAppNameUndVersion() throws {
        let fixture = try S.fixture("address_privacy")
        var versions = try XCTUnwrap(fixture["appVersionSamples"] as? [String])
        let ownVersion = HttpUserAgent.value.split(separator: "/").last.map(String.init) ?? ""
        XCTAssertFalse(ownVersion.isEmpty)
        versions.append(ownVersion)
        for version in versions {
            let appAgent = HttpUserAgent.ios(version)
            XCTAssertNil(HttpUserAgent.neutral.range(of: appAgent, options: .caseInsensitive), appAgent)
            XCTAssertFalse(HttpUserAgent.neutral.contains(version), "Version \(version) im User-Agent")
        }
    }

    /// Host → User-Agent wie Android.
    func testHostRegelWieGemeinsameFaelle() throws {
        let fixture = try S.fixture("address_privacy")
        let cases = try XCTUnwrap(fixture["hostCases"] as? [[String: Any]])
        let app = "cryptoChecker-iOS/1.0"
        for c in cases {
            let host = try XCTUnwrap(c["host"] as? String)
            let neutral = try XCTUnwrap(c["neutral"] as? Bool)
            XCTAssertEqual(HttpUserAgent.forHost(host, app: app), neutral ? HttpUserAgent.neutral : app, host)
        }
    }

    /// Adress-Hosts laufen nie über die Sessions mit App-User-Agent und teilen keine Verbindungen.
    func testAdressHostsBekommenEigeneSession() {
        let single = MarketHTTP.sessionFor(host: "mempool.space", requested: MarketHTTP.session)
        XCTAssertTrue(single === MarketHTTP.addressHostSession)
        XCTAssertTrue(MarketHTTP.sessionFor(host: "ethereum-rpc.publicnode.com", requested: MarketHTTP.bulkSession)
                      === MarketHTTP.addressHostBulkSession)
        XCTAssertTrue(MarketHTTP.sessionFor(host: "api.binance.com", requested: MarketHTTP.session) === MarketHTTP.session)
        XCTAssertTrue(MarketHTTP.sessionFor(host: nil, requested: MarketHTTP.session) === MarketHTTP.session)
        for s in [MarketHTTP.addressHostSession, MarketHTTP.addressHostBulkSession] {
            XCTAssertEqual(s.configuration.httpAdditionalHeaders?[HttpUserAgent.header] as? String, HttpUserAgent.neutral)
        }
        XCTAssertEqual(MarketHTTP.bodyLimit(session: MarketHTTP.addressHostSession, inExtension: false), MarketHTTP.maxBodyBytes)
    }

    /// Die Sitzung der Adressabfragen setzt den neutralen User-Agent ausdrücklich.
    func testAdressSitzungSetztNeutralenUserAgent() {
        let headers = WatchedAddressSource.session.configuration.httpAdditionalHeaders
        XCTAssertEqual(headers?[HttpUserAgent.header] as? String, HttpUserAgent.neutral)
    }
}

// ---- aus AddressServerRulesParityTests

/// Regeln zum eigenen Bitcoin-Server (`Parity/address_server_ui.json`): Eingabe kürzen, Pausen-Schlüssel, Sperr-Ausnahme,
/// Testergebnis zur Eingabe, Rückfrage vor dem Wechsel auf öffentliche Knoten. Gegenstück: `AddressServerRulesParityTest.kt`.
final class AddressServerRulesParityTests: XCTestCase {
    private typealias S = TestSupport

    private func cases(_ key: String) throws -> [[String: Any]] {
        let list = try XCTUnwrap(try S.fixture("address_server_ui")[key] as? [[String: Any]], key)
        XCTAssertFalse(list.isEmpty, key)
        return list
    }

    func testEingabeWirdGekuerzt() throws {
        for c in try cases("clamp") {
            let output = AddressServerRules.clamp(try XCTUnwrap(c["input"] as? String))
            XCTAssertEqual(output, c["output"] as? String)
            // Gekürzte Eingabe gilt nie still als gültig: 201/250 Zeichen → INVALID, genau 200 → gültig (auch nach führendem Leerraum)
            XCTAssertEqual(AddressServer.check(output).problem?.rawValue, c["problem"] as? String, "Länge \(output.count)")
        }
    }

    func testHostSchluesselUndEigenerServer() throws {
        for c in try cases("hostKey") {
            XCTAssertEqual(AddressServerRules.hostKey(try XCTUnwrap(c["entry"] as? String)), c["key"] as? String)
        }
        for c in try cases("own") {
            let entries = try XCTUnwrap(c["entries"] as? [String])
            XCTAssertEqual(AddressServerRules.isOwnServer(entries), c["own"] as? Bool, "own: \(entries)")
        }
    }

    func testTestergebnisNurZurEingabe() throws {
        for c in try cases("testShown") {
            XCTAssertEqual(AddressServerRules.testShown(c["tested"] as? String, inputUrl: c["input"] as? String),
                           c["shown"] as? Bool, "\(c)")
        }
    }

    func testRueckfrageVorOeffentlichenKnoten() throws {
        for c in try cases("confirmPublic") {
            // «chains» statt «btcCount»: Zahl aus der Liste (null = nicht lesbar → fragen)
            let count = try c["btcCount"] != nil ? XCTUnwrap(c["btcCount"] as? Int)
                : AddressServerRules.btcCount(Self.addresses(c["chains"]))
            let ask = AddressServerRules.confirmPublicSwitch(current: c["current"] as? String, next: c["next"] as? String,
                                                             btcCount: count)
            XCTAssertEqual(ask, c["ask"] as? Bool, "\(c)")
        }
    }

    func testBitcoinAnzahlListeNichtLesbarFragt() throws {
        for c in try cases("btcCount") {
            XCTAssertEqual(AddressServerRules.btcCount(Self.addresses(c["chains"])), c["count"] as? Int, "\(c)")
        }
    }

    /// Fixture-Liste der Ketten → Adressen; null (JSON) → nil = Liste nicht lesbar.
    private static func addresses(_ chains: Any?) -> [WatchedAddress]? {
        guard let list = chains as? [String] else { return nil }
        return list.enumerated().map { i, chain in
            WatchedAddress(id: Int64(i + 1), chain: AddressChain(rawValue: chain) ?? .BTC, address: "a\(i)")
        }
    }

    /// Mehrere Abrufe gleichzeitig im Vordergrund: die Bitcoin-Einträge reisen als Parameter mit, kein gemeinsames Feld
    /// des Actors (sonst überschriebe ein zweiter Abruf an einem `await` die Hosts des ersten).
    func testBitcoinEintraegeKeinActorFeld() throws {
        let root = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
        for name in ["WatchedAddressSource.swift", "WatchedAddressSource+Chains.swift"] {
            let text = try String(contentsOf: root.appendingPathComponent("Shared/Portfolio/\(name)"), encoding: .utf8)
            XCTAssertFalse(text.contains("var btcEntries"), name)
            XCTAssertFalse(text.contains("= btcEntries\n"), name)
            // Bitcoin-Hosts und eigener Server immer ausdrücklich: kein Standardwert, der still öffentliche Knoten wählt
            XCTAssertFalse(text.contains("btcEntries: [String] ="), name)
            XCTAssertFalse(text.contains("btcServer: String? = nil"), name)
        }
    }
}

// ---- aus AddressServerTestRunTests

/// «Testen» des eigenen Servers (`AddressServerTestRun`): Test A abbrechen (Eingabe geändert), erneut A testen — das
/// späte Ergebnis des ersten Abrufs (der den Abbruch nicht bemerkt) überschreibt `.running(A)` des zweiten nicht.
/// Gegenstück: `AddressServerTestRunTest.kt`.
@MainActor
final class AddressServerTestRunTests: XCTestCase {
    private let url = "https://node.example.org"

    /// Abruf-Attrappe, die den Abbruch nicht bemerkt: liefert erst nach `release(n)` (n-ter Aufruf: n × 111).
    @MainActor
    private final class FakeServer {
        var calls = 0
        var released: Set<Int> = []

        func load() async -> Int64? {
            calls += 1
            let n = calls
            while !released.contains(n) { await Task.yield() }
            return Int64(n * 111)
        }
    }

    private func waitFor(_ condition: () -> Bool) async {
        for _ in 0..<1_000 where !condition() { await Task.yield() }
    }

    func testSpaetesErgebnisUeberschreibtNeuerenTestNicht() async {
        let server = FakeServer()
        let run = AddressServerTestRun(load: { _ in await server.load() })
        let first = run.start(url)
        await waitFor { server.calls == 1 }
        run.reset()
        XCTAssertEqual(run.state, .idle)
        let second = run.start(url)
        await waitFor { server.calls == 2 }
        XCTAssertEqual(run.state, .running(url))

        server.released.insert(1)
        await first?.value
        XCTAssertEqual(run.state, .running(url))

        server.released.insert(2)
        await second?.value
        XCTAssertEqual(run.state, .ok(url, 222))
    }

    func testSpaetesOkDerAltenUrlErscheintNachNeuerUrlNicht() async {
        let other = "https://other.example"
        let server = FakeServer()
        let run = AddressServerTestRun(load: { _ in await server.load() })
        let first = run.start(url)
        await waitFor { server.calls == 1 }
        run.reset()
        let second = run.start(other)
        await waitFor { server.calls == 2 }
        XCTAssertEqual(run.state, .running(other))

        // Spätes Ok der alten URL: weder .ok(url) noch eine Änderung an .running(other)
        server.released.insert(1)
        await first?.value
        XCTAssertEqual(run.state, .running(other))

        server.released.insert(2)
        await second?.value
        XCTAssertEqual(run.state, .ok(other, 222))
    }

    func testZweiterStartWaehrendEinesTestsZaehltNicht() async {
        let server = FakeServer()
        let run = AddressServerTestRun(load: { _ in await server.load() })
        let first = run.start(url)
        XCTAssertNil(run.start("https://other.example"))
        server.released.insert(1)
        await first?.value
        XCTAssertEqual(server.calls, 1)
        XCTAssertEqual(run.state, .ok(url, 111))
    }
}
