import XCTest
@testable import CryptoChecker

/// Uhr und iPhone (`Parity/watch_link.json`) — wie `WatchLinkParityTest.kt` und `WatchLinkWearTest.kt`: Intervall der
/// Uhr, Kursquelle und Intervall in der Wahl (immer vom iPhone), Kurse vom iPhone (Auswahl, JSON, was die Uhr selbst
/// abfragt, Zusammenführen nach Zeit) und Portfolio-Wert (verborgen ohne Zahlen, Löschen).
final class WatchLinkParityTests: XCTestCase {

    private func strings(_ v: Any?) -> [String] { v as? [String] ?? [] }

    private func int(_ v: Any?) -> Int { Int(TestSupport.number(v) ?? -1) }

    private func settings(_ v: Any?) -> WatchSettings {
        let o = v as? [String: Any] ?? [:]
        return WatchSettings(mode: WatchSettings.Mode(rawValue: o["mode"] as? String ?? "") ?? .own,
                             own: strings(o["own"]), phone: strings(o["phone"]),
                             revision: Int64(TestSupport.number(o["revision"]) ?? 0),
                             source: WatchSettings.Source(rawValue: o["source"] as? String ?? "") ?? .phone,
                             interval: int(o["interval"]))
    }

    private func quote(_ o: [String: Any]) -> WatchPhoneQuotes.Quote {
        WatchPhoneQuotes.Quote(symbol: o["symbol"] as? String ?? "", price: TestSupport.number(o["price"]) ?? 0,
                               change24h: TestSupport.number(o["change24h"]), exchange: o["exchange"] as? String ?? "",
                               time: TestSupport.millis(o["time"]))
    }

    private func portfolio(_ v: Any?) -> WatchPortfolio.Message? {
        if v as? String == "clear" { return .clear }
        guard let o = v as? [String: Any] else { return nil }
        return .value(WatchPortfolio(total: TestSupport.number(o["total"]), changePercent: TestSupport.number(o["changePercent"]),
                                     currency: o["currency"] as? String ?? "", time: TestSupport.millis(o["time"]),
                                     hidden: o["hidden"] as? Bool ?? false))
    }

    private func cached(_ o: [String: Any]) -> WatchCached {
        WatchCached(quote: WatchQuote(symbol: o["symbol"] as? String ?? "", price: TestSupport.number(o["price"]) ?? 0,
                                      change24h: TestSupport.number(o["change24h"]), source: o["source"] as? String ?? ""),
                    time: TestSupport.millis(o["time"]))
    }

    func testInterval() throws {
        let data = try TestSupport.fixture("watch_link")
        for c in TestSupport.list(data["interval"]) {
            XCTAssertEqual(WatchInterval.clamp(int(c["minutes"])), int(c["clamped"]))
            XCTAssertEqual(WatchInterval.refetchAfterMillis(int(c["minutes"])), TestSupport.millis(c["refetchAfterMillis"]))
        }
    }

    func testSettingsJsonAndMerge() throws {
        let data = try TestSupport.fixture("watch_link")
        for c in TestSupport.list(data["settingsJson"]) {
            let name = c["name"] as? String ?? ""
            let parsed = try XCTUnwrap(WatchSettings.fromJson(c["text"] as? String), name)
            XCTAssertEqual(parsed.source.rawValue, c["source"] as? String, name)
            XCTAssertEqual(parsed.interval, int(c["interval"]), name)
            XCTAssertEqual(WatchSettings.fromJson(parsed.toJson()), parsed, name)
        }
        for c in TestSupport.list(data["settingsMerge"]) {
            let merged = WatchSettings.merge(settings(c["local"]), settings(c["remote"]),
                                             remoteIsPhone: c["remoteIsPhone"] as? Bool ?? false)
            XCTAssertEqual(merged, settings(c["expected"]), c["name"] as? String ?? "")
        }
        // Uhr meldet einen anderen iPhone-Teil: das iPhone sendet den ganzen Stand noch einmal
        for c in TestSupport.list(data["phoneSideDiffers"]) {
            XCTAssertEqual(WatchSettings.phoneSideDiffers(settings(c["phone"]), watch: settings(c["watch"])),
                           c["expected"] as? Bool, c["name"] as? String ?? "")
        }
        // iPhone ohne Stand (Apple Watch: iPhone-App entfernt; Wear: Eintrag gelöscht)
        for c in TestSupport.list(data["forgetPhone"]) {
            XCTAssertEqual(WatchSettings.forgetPhone(settings(c["local"])), settings(c["expected"]), c["name"] as? String ?? "")
        }
    }

    func testPickAndQuotesJson() throws {
        let data = try TestSupport.fixture("watch_link")
        let p = try XCTUnwrap(data["pick"] as? [String: Any])
        let rows = TestSupport.list(p["rows"]).map { o in
            WatchPhoneQuotes.Row(base: o["base"] as? String ?? "", quote: o["quote"] as? String ?? "",
                                 spot: o["spot"] as? Bool ?? false, disabled: o["disabled"] as? Bool ?? false,
                                 price: TestSupport.number(o["price"]), change24h: TestSupport.number(o["change24h"]),
                                 exchange: o["exchange"] as? String ?? "", time: TestSupport.millis(o["time"]),
                                 sortOrder: int(o["sortOrder"]))
        }
        let picked = WatchPhoneQuotes.pick(rows, coins: strings(p["coins"]))
        XCTAssertEqual(picked, TestSupport.list(p["expected"]).map(quote))
        XCTAssertEqual(WatchPhoneQuotes.fromJson(WatchPhoneQuotes.toJson(picked)), picked)
        for c in TestSupport.list(data["quotesJson"]) {
            let parsed = WatchPhoneQuotes.fromJson(c["text"] as? String)
            if c["expected"] is NSNull || c["expected"] == nil {
                XCTAssertNil(parsed, c["name"] as? String ?? "")
            } else {
                XCTAssertEqual(parsed, TestSupport.list(c["expected"]).map(quote), c["name"] as? String ?? "")
            }
        }
    }

    func testSelfFetch() throws {
        let data = try TestSupport.fixture("watch_link")
        let s = try XCTUnwrap(data["selfFetch"] as? [String: Any])
        let times = (s["times"] as? [String: Any] ?? [:]).mapValues { TestSupport.millis($0) }
        for c in TestSupport.list(s["cases"]) {
            let got = WatchPhoneQuotes.selfFetch(strings(s["shown"]), times: times, phoneCoins: Set(strings(s["phoneCoins"])),
                                                 usePhone: c["usePhone"] as? Bool ?? false, intervalMinutes: int(c["interval"]),
                                                 now: TestSupport.millis(s["now"]), force: c["force"] as? Bool ?? false)
            XCTAssertEqual(got, strings(c["expected"]), c["name"] as? String ?? "")
        }
    }

    /// iPhone weg: gleiche Grenze auf beiden Uhren (Intervall + 5 Min. nach der letzten Lieferung).
    func testUsePhone() throws {
        let u = try XCTUnwrap(TestSupport.fixture("watch_link")["usePhone"] as? [String: Any])
        let now = TestSupport.millis(u["now"])
        for s in TestSupport.list(u["silentAfter"]) {
            XCTAssertEqual(WatchPhoneQuotes.phoneSilentAfterMillis(int(s["interval"])), TestSupport.millis(s["millis"]))
        }
        for d in TestSupport.list(u["deliveryTime"]) {
            let quotes = (d["times"] as? [Any] ?? []).map {
                WatchPhoneQuotes.Quote(symbol: "BTC", price: 1, change24h: nil, exchange: "Binance", time: TestSupport.millis($0))
            }
            XCTAssertEqual(WatchPhoneQuotes.deliveryTime(quotes), TestSupport.millis(d["expected"]))
        }
        for c in TestSupport.list(u["cases"]) {
            let source = WatchSettings.Source(rawValue: c["source"] as? String ?? "") ?? .phone
            let got = WatchPhoneQuotes.usePhone(source: source, connected: c["connected"] as? Bool ?? false,
                                                lastDelivery: TestSupport.millis(c["lastDelivery"]), now: now,
                                                intervalMinutes: int(c["interval"]))
            XCTAssertEqual(got, c["expected"] as? Bool, c["name"] as? String ?? "")
        }
    }

    func testPortfolio() throws {
        let data = try TestSupport.fixture("watch_link")
        for c in TestSupport.list(data["portfolioMessage"]) {
            let name = c["name"] as? String ?? ""
            let message = WatchPortfolio.message(
                onWatch: c["onWatch"] as? Bool ?? false, portfolioEnabled: c["portfolioEnabled"] as? Bool ?? false,
                hidden: c["hidden"] as? Bool ?? false, locked: c["locked"] as? Bool ?? false,
                total: TestSupport.number(c["total"]), changePercent: TestSupport.number(c["changePercent"]),
                currency: c["currency"] as? String, time: TestSupport.millis(c["time"]),
                fallbackCurrency: c["fallbackCurrency"] as? String, now: TestSupport.millis(c["now"]))
            XCTAssertEqual(message, portfolio(c["expected"]), name)
            if let message {
                XCTAssertEqual(WatchPortfolio.fromJson(WatchPortfolio.toJson(message)), message, name)
            }
        }
        for c in TestSupport.list(data["portfolioJson"]) {
            XCTAssertEqual(WatchPortfolio.fromJson(c["text"] as? String), portfolio(c["expected"]), c["name"] as? String ?? "")
        }
    }

    func testCacheTimed() throws {
        let data = try TestSupport.fixture("watch_link")
        for c in TestSupport.list(data["cacheTimed"]) {
            func map(_ key: String) -> [String: WatchCached] {
                Dictionary(TestSupport.list(c[key]).map { ($0["symbol"] as? String ?? "", cached($0)) }, uniquingKeysWith: { a, _ in a })
            }
            let merged = WatchCache.mergeTimed(map("old"), fresh: map("fresh"), keep: strings(c["keep"]))
            XCTAssertEqual(merged, map("expected"), c["name"] as? String ?? "")
        }
    }
}
