import XCTest
@testable import CryptoChecker

/// «Wie iPhone» mit dem Paar des Favoriten (`Parity/watch_pairs.json`) — wie Android `WatchPairsParityTest.kt` und
/// `WatchPairQuotesParityTest.kt` (:wear): Paar-Schlüssel und Anzeige, Paare in der Wahl, Kurse vom iPhone genau für
/// dieses Paar (die Quelle folgt dem Paar des iPhones), was die Uhr selbst abfragt und bei welcher Börse zuerst.
final class WatchPairsParityTests: XCTestCase {

    /// Gestellte Abfragen (Paare laufen gleichzeitig).
    private actor RequestLog {
        private(set) var keys: [String] = []
        func add(_ key: String) { keys.append(key) }
    }

    private func strings(_ v: Any?) -> [String] { v as? [String] ?? [] }

    private func pair(_ v: Any?) -> WatchPair? {
        guard let o = v as? [String: Any] else { return nil }
        return WatchPair.of(base: o["base"] as? String ?? "", quote: o["quote"] as? String ?? "",
                            exchange: o["exchange"] as? String ?? "", contract: o["contract"] as? String ?? "")
    }

    private func settings(_ v: Any?) -> WatchSettings {
        let o = v as? [String: Any] ?? [:]
        return WatchSettings(mode: WatchSettings.Mode(rawValue: o["mode"] as? String ?? "") ?? .own,
                             own: strings(o["own"]), phone: strings(o["phone"]),
                             revision: Int64(TestSupport.number(o["revision"]) ?? 0),
                             pairs: (o["pairs"] as? [Any] ?? []).compactMap { pair($0) })
    }

    func testPairs() throws {
        let data = try TestSupport.fixture("watch_pairs")
        for c in TestSupport.list(data["pairs"]) {
            let name = c["name"] as? String ?? ""
            let p = pair(c)
            guard let key = c["key"] as? String else {
                XCTAssertNil(p, name)
                continue
            }
            XCTAssertEqual(p?.key, key, name)
            XCTAssertEqual(p?.label, c["label"] as? String, name)
            XCTAssertEqual(p?.selfFetchable, c["selfFetchable"] as? Bool, name)
            XCTAssertEqual(WatchPair.parse(key), p, name)
        }
        // Pool nur bei DexScreener (WatchIdentity.pool), mit den Stellen seiner Kennung; Börsen-Kennungen bleiben Spot
        for c in TestSupport.list(data["contractOf"]) {
            let pool = WatchIdentity.pool(exchangeKey: c["exchangeKey"] as? String ?? "", pairId: c["pairId"] as? String)
            XCTAssertEqual(WatchPair.contract(short: c["short"] as? String, pool: pool), c["expected"] as? String,
                           c["name"] as? String ?? "")
        }
        for c in TestSupport.list(data["parse"]) {
            let key = c["key"] as? String ?? ""
            XCTAssertEqual(WatchPair.parse(key) != nil, c["valid"] as? Bool, key)
        }
        for c in TestSupport.list(data["labelOf"]) {
            XCTAssertEqual(WatchPair.label(of: c["key"] as? String ?? ""), c["label"] as? String)
        }
        for c in TestSupport.list(data["favoritePairs"]) {
            let pairs = (c["pairs"] as? [Any] ?? []).map { pair($0) }
            XCTAssertEqual(WatchCoins.favoritePairs(strings(c["bases"]), pairs: pairs).map(\.key), strings(c["expected"]),
                           c["name"] as? String ?? "")
        }
    }

    func testSettings() throws {
        let data = try TestSupport.fixture("watch_pairs")
        for c in TestSupport.list(data["settings"]) {
            let name = c["name"] as? String ?? ""
            let parsed = try XCTUnwrap(WatchSettings.fromJson(c["text"] as? String), name)
            XCTAssertEqual(parsed.phoneKeys, strings(c["phoneKeys"]), name)
            XCTAssertEqual(parsed.shown, strings(c["shown"]), name)
            XCTAssertEqual(parsed.kept, strings(c["kept"]), name)
            XCTAssertEqual(WatchSettings.fromJson(parsed.toJson()), parsed, name)
        }
        for c in TestSupport.list(data["merge"]) {
            let merged = WatchSettings.merge(settings(c["local"]), settings(c["remote"]),
                                             remoteIsPhone: c["remoteIsPhone"] as? Bool ?? false)
            XCTAssertEqual(merged, settings(c["expected"]), c["name"] as? String ?? "")
        }
    }

    /// Die Quelle folgt dem Paar des iPhones: genau dieses Paar, nicht ein neuerer Kurs einer anderen Börse.
    func testPick() throws {
        let p = try XCTUnwrap(TestSupport.fixture("watch_pairs")["pick"] as? [String: Any])
        let rows = TestSupport.list(p["rows"]).map { o in
            WatchPhoneQuotes.Row(base: o["base"] as? String ?? "", quote: o["quote"] as? String ?? "",
                                 spot: o["spot"] as? Bool ?? false, disabled: o["disabled"] as? Bool ?? false,
                                 price: TestSupport.number(o["price"]), change24h: TestSupport.number(o["change24h"]),
                                 exchange: o["exchange"] as? String ?? "", time: Int64(TestSupport.number(o["time"]) ?? 0),
                                 sortOrder: Int(TestSupport.number(o["sortOrder"]) ?? 0),
                                 contract: o["contract"] as? String ?? "")
        }
        let expected = TestSupport.list(p["expected"]).map { o in
            WatchPhoneQuotes.Quote(symbol: o["symbol"] as? String ?? "", price: TestSupport.number(o["price"]) ?? 0,
                                   change24h: TestSupport.number(o["change24h"]), exchange: o["exchange"] as? String ?? "",
                                   time: Int64(TestSupport.number(o["time"]) ?? 0))
        }
        let picked = WatchPhoneQuotes.pick(rows, coins: strings(p["coins"]))
        // Kurse mit Toleranz: die Umrechnung darf in der letzten Stelle von Android abweichen (0.07 vs 0.07000000000000003)
        assertQuotes(picked, expected, p["name"] as? String ?? "")
        // Hin und zurück über JSON: gleicher Inhalt (JSON schreibt Kurse auf die kürzeste Darstellung)
        assertQuotes(try XCTUnwrap(WatchPhoneQuotes.fromJson(WatchPhoneQuotes.toJson(picked))), picked, "json")
    }

    private func assertQuotes(_ got: [WatchPhoneQuotes.Quote], _ want: [WatchPhoneQuotes.Quote], _ name: String,
                              file: StaticString = #filePath, line: UInt = #line) {
        XCTAssertEqual(got.count, want.count, name, file: file, line: line)
        for (g, w) in zip(got, want) {
            XCTAssertEqual(g.symbol, w.symbol, name, file: file, line: line)
            XCTAssertEqual(g.price, w.price, accuracy: abs(w.price) * 1e-9, name, file: file, line: line)
            XCTAssertEqual(g.change24h == nil, w.change24h == nil, name, file: file, line: line)
            if let a = g.change24h, let b = w.change24h { XCTAssertEqual(a, b, accuracy: 1e-9, name, file: file, line: line) }
            XCTAssertEqual(g.exchange, w.exchange, name, file: file, line: line)
            XCTAssertEqual(g.time, w.time, name, file: file, line: line)
        }
    }

    func testSelfFetch() throws {
        let s = try XCTUnwrap(TestSupport.fixture("watch_pairs")["selfFetch"] as? [String: Any])
        let times = (s["times"] as? [String: Any] ?? [:]).mapValues { Int64(TestSupport.number($0) ?? 0) }
        let got = WatchPhoneQuotes.selfFetch(strings(s["shown"]), times: times, phoneCoins: Set(strings(s["phoneCoins"])),
                                             usePhone: s["usePhone"] as? Bool ?? false,
                                             intervalMinutes: Int(TestSupport.number(s["interval"]) ?? 0),
                                             now: Int64(TestSupport.number(s["now"]) ?? 0),
                                             force: s["force"] as? Bool ?? false)
        XCTAssertEqual(got, strings(s["expected"]))
    }

    func testVenuesAndUrls() throws {
        let data = try TestSupport.fixture("watch_pairs")
        for c in TestSupport.list(data["venues"]) {
            let pair = try XCTUnwrap(WatchPair.parse(c["key"] as? String ?? ""))
            XCTAssertEqual(WatchPairQuotes.venues(pair).map(\.label), strings(c["expected"]), pair.key)
        }
        for c in TestSupport.list(data["urls"]) {
            let pair = try XCTUnwrap(WatchPair.parse(c["key"] as? String ?? ""))
            let venue = try XCTUnwrap(WatchPairQuotes.Venue(rawValue: c["venue"] as? String ?? ""))
            XCTAssertEqual(WatchPairQuotes.url(venue, pair)?.absoluteString, c["url"] as? String)
        }
    }

    /// Börse des iPhones zuerst, andere nur, wenn sie nicht antwortet oder das Paar fehlt; Börse = die liefernde.
    func testFetch() async throws {
        let data = try TestSupport.fixture("watch_pairs")
        for c in TestSupport.list(data["fetch"]) {
            let name = c["name"] as? String ?? ""
            // null bzw. fehlend = HTTP-Fehler, Sperre oder kein Netz
            let responses = (c["responses"] as? [String: Any] ?? [:]).compactMapValues { $0 as? String }
            let log = RequestLog()
            let result = await WatchQuoteFetch.fetch(strings(c["coins"])) { url in
                let key = pairRequestKey(url)
                await log.add(key)
                return responses[key].map { Data($0.utf8) }
            }
            let requests = await log.keys
            XCTAssertEqual(requests.sorted(), strings(c["requests"]).sorted(), name)
            let expected = c["expected"] as? [[Any]] ?? []
            XCTAssertEqual(Set(result.keys), Set(expected.compactMap { $0.first as? String }), name)
            for e in expected {
                let q = try XCTUnwrap(result[e[0] as? String ?? ""], name)
                XCTAssertEqual(q.source, e[1] as? String, name)
                XCTAssertEqual(q.price, TestSupport.number(e[2]) ?? .nan, accuracy: 1e-9, name)
                XCTAssertEqual(q.change24h ?? .nan, TestSupport.number(e[3]) ?? .nan, accuracy: 1e-9, name)
            }
        }
    }
}

/// Schlüssel einer Abfrage wie im Fixture: binance (alle Kürzel), binance:<SYMBOL>, binanceFutures:<SYMBOL>,
/// bybit:<SYMBOL>, bybitLinear:<SYMBOL>, okx:<INST-ID>.
private func pairRequestKey(_ url: URL) -> String {
    let items = URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems ?? []
    func value(_ name: String) -> String? { items.first { $0.name == name }?.value }
    switch url.host {
    case "api.binance.com": return value("symbol").map { "binance:" + $0 } ?? "binance"
    case "fapi.binance.com": return "binanceFutures:" + (value("symbol") ?? "")
    case "api.bybit.com": return (value("category") == "linear" ? "bybitLinear:" : "bybit:") + (value("symbol") ?? "")
    case "www.okx.com": return "okx:" + (value("instId") ?? "")
    default: return "unexpected:" + url.absoluteString
    }
}
