import XCTest
@testable import CryptoChecker

/// Uhr-Apps (`Parity/watch_quotes.json`) — wie Android `WatchQuotesParityTest.kt` (:wear) und
/// `WatchFreshnessParityTest.kt`: Antworten je Börse (Abfrage je Coin), Ausweich-Reihenfolge Binance → Bybit → OKX mit
/// nur den fehlenden Coins, höchstens 8 Coins, Abfrage-Adressen, Alter und «veraltet».
final class WatchQuotesParityTests: XCTestCase {

    /// Gestellte Abfragen (Abfragen je Börse laufen gleichzeitig).
    private actor RequestLog {
        private(set) var keys: [String] = []
        func add(_ key: String) { keys.append(key) }
    }

    private func strings(_ v: Any?) -> [String] { v as? [String] ?? [] }

    private func query(_ url: URL) -> [String: String] {
        let items = URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems ?? []
        return Dictionary(items.map { ($0.name, $0.value ?? "") }, uniquingKeysWith: { a, _ in a })
    }

    func testMaxCoins() throws {
        let data = try TestSupport.fixture("watch_quotes")
        XCTAssertEqual(WatchCoins.max, Int(TestSupport.number(data["maxCoins"]) ?? 0))
    }

    func testUrls() throws {
        let u = try XCTUnwrap(TestSupport.fixture("watch_quotes")["urls"] as? [String: Any])
        let binance = try XCTUnwrap(WatchQuoteParser.binanceURL(strings(u["coins"])))
        XCTAssertEqual(binance.absoluteString.components(separatedBy: "?").first, u["binanceBase"] as? String)
        XCTAssertEqual(query(binance)["symbols"], u["binanceSymbols"] as? String)
        for (coin, url) in u["bybit"] as? [String: String] ?? [:] {
            XCTAssertEqual(WatchQuoteParser.bybitURL(coin)?.absoluteString, url)
        }
        for (coin, url) in u["okx"] as? [String: String] ?? [:] {
            XCTAssertEqual(WatchQuoteParser.okxURL(coin)?.absoluteString, url)
        }
    }

    func testParse() throws {
        let data = try TestSupport.fixture("watch_quotes")
        for c in TestSupport.list(data["parse"]) {
            let name = c["name"] as? String ?? ""
            let wanted = strings(c["wanted"])
            let body = Data((c["body"] as? String ?? "").utf8)
            let quotes: [WatchQuote] = switch c["source"] as? String {
            case "binance": WatchQuoteParser.binance(body, wanted: wanted)
            case "bybit": WatchQuoteParser.bybit(body, wanted: wanted)
            case "okx": WatchQuoteParser.okx(body, wanted: wanted)
            default: []
            }
            let expected = TestSupport.list(c["expected"])
            XCTAssertEqual(quotes.count, expected.count, name)
            for (e, q) in zip(expected, quotes) {
                XCTAssertEqual(q.symbol, e["symbol"] as? String, name)
                XCTAssertEqual(q.price, TestSupport.number(e["price"]) ?? .nan, accuracy: 1e-9, name)
                XCTAssertEqual(q.source, e["source"] as? String, name)
                if let change = TestSupport.number(e["change24h"]) {
                    XCTAssertEqual(q.change24h ?? .nan, change, accuracy: 1e-9, name)
                } else {
                    XCTAssertNil(q.change24h, name)
                }
            }
        }
    }

    func testFallback() async throws {
        let data = try TestSupport.fixture("watch_quotes")
        for c in TestSupport.list(data["fallback"]) {
            let name = c["name"] as? String ?? ""
            // null bzw. fehlend = HTTP-Fehler, Sperre oder kein Netz
            let responses = (c["responses"] as? [String: Any] ?? [:]).compactMapValues { $0 as? String }
            let log = RequestLog()
            let result = await WatchQuoteFetch.fetch(strings(c["coins"])) { url in
                let key = watchRequestKey(url)
                await log.add(key)
                return responses[key].map { Data($0.utf8) }
            }
            let requests = await log.keys
            XCTAssertEqual(requests.sorted(), strings(c["requests"]).sorted(), name)
            let expected = (c["expected"] as? [[String]] ?? []).map { "\($0[0]):\($0[1])" }
            XCTAssertEqual(result.map { "\($0.key):\($0.value.source)" }.sorted(), expected.sorted(), name)
        }
    }

    func testFreshness() throws {
        let fresh = try XCTUnwrap(TestSupport.fixture("watch_quotes")["freshness"] as? [String: Any])
        XCTAssertEqual(WatchFreshness.updateMinutes, Int(TestSupport.number(fresh["updateMinutes"]) ?? 0))
        XCTAssertEqual(WatchFreshness.outdatedAfterMillis, Int64(TestSupport.number(fresh["outdatedAfterMillis"]) ?? 0))
        // Dieselbe Regel wie Merkliste und Widgets
        XCTAssertEqual(WatchFreshness.outdatedAfterMillis,
                       OutdatedRule.afterMillis(backgroundIntervalMinutes: WatchFreshness.updateMinutes))
        let now = Int64(TestSupport.number(fresh["now"]) ?? 0)
        for c in TestSupport.list(fresh["cases"]) {
            let name = c["name"] as? String ?? ""
            let time = Int64(TestSupport.number(c["time"]) ?? 0)
            XCTAssertEqual(WatchFreshness.isOutdated(time, now: now), c["outdated"] as? Bool, name)
            XCTAssertEqual(WatchFreshness.isOutdated(time, now: now),
                           OutdatedRule.isOutdated(time, now: now, afterMillis: WatchFreshness.outdatedAfterMillis), name)
            guard let raw = c["unit"] as? String else { continue }
            let unit = try XCTUnwrap(WatchFreshness.AgeUnit(rawValue: raw), name)
            let expected = WatchFreshness.Age(unit: unit, count: Int(TestSupport.number(c["count"]) ?? -1))
            XCTAssertEqual(WatchFreshness.age(time, now: now), expected, name)
        }
    }
}

/// Schlüssel einer Abfrage wie im Fixture: `binance`, `bybit:<SYMBOL>`, `okx:<INST-ID>`.
private func watchRequestKey(_ url: URL) -> String {
    let items = URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems ?? []
    func value(_ name: String) -> String { items.first { $0.name == name }?.value ?? "" }
    switch url.host {
    case "api.binance.com": return "binance"
    case "api.bybit.com": return "bybit:" + value("symbol")
    case "www.okx.com": return "okx:" + value("instId")
    default: return "unexpected:" + url.absoluteString
    }
}
