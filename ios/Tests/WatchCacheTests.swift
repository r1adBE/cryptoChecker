import XCTest
@testable import CryptoChecker

/// Uhr-Apps: Abrufe, die sich überholen, und Teil-Ausfall eines Coins — wie Android `WatchFetcherTest`. `WatchModel`
/// (Uhr-App) prüft jedes Ergebnis mit `WatchFetchGate` und führt es mit `WatchCache` je Coin zusammen.
final class WatchCacheTests: XCTestCase {

    private func q(_ coin: String, _ price: Double) -> WatchQuote {
        WatchQuote(symbol: coin, price: price, change24h: nil, source: WatchQuoteParser.binanceName)
    }

    func testSlowFirstFetchDoesNotOverwriteTheFastSecond() {
        var gate = WatchFetchGate()
        var cache: [String: WatchCached] = [:]
        var shown = ["BTC", "ETH"]
        let first = (run: gate.start(), asked: shown)
        // Neue Liste vom iPhone, während der erste Abruf noch läuft
        shown = ["SOL"]
        let second = (run: gate.start(), asked: shown)
        // Der zweite endet zuerst, der erste danach
        if gate.accepts(second.run, asked: second.asked, shown: shown) {
            cache = WatchCache.merge(cache, fresh: ["SOL": q("SOL", 3)], time: 2_000, keep: ["SOL"])
        }
        if gate.accepts(first.run, asked: first.asked, shown: shown) {
            cache = WatchCache.merge(cache, fresh: ["BTC": q("BTC", 1), "ETH": q("ETH", 2)], time: 3_000,
                                     keep: ["BTC", "ETH", "SOL"])
        }
        XCTAssertEqual(Set(cache.keys), ["SOL"])
        XCTAssertEqual(WatchCache.listTime(cache, coins: shown), 2_000)
    }

    func testPartialTimeoutKeepsTheLastPricePerCoin() async {
        let coins = ["BTC", "SOL"]
        let both = Data((#"[{"symbol":"BTCUSDT","lastPrice":"66000","priceChangePercent":"1"},"#
                         + #"{"symbol":"SOLUSDT","lastPrice":"150","priceChangePercent":"2"}]"#).utf8)
        let btcOnly = Data(#"[{"symbol":"BTCUSDT","lastPrice":"67000","priceChangePercent":"1"}]"#.utf8)
        // Ausweich-Börsen ohne Antwort (Zeitüberschreitung = nil)
        let full = await WatchQuoteFetch.fetch(coins) { url in url.host == "api.binance.com" ? both : nil }
        var cache = WatchCache.merge([:], fresh: full, time: 1_000, keep: coins)
        XCTAssertEqual(cache["SOL"]?.quote.price, 150)

        let partial = await WatchQuoteFetch.fetch(coins) { url in url.host == "api.binance.com" ? btcOnly : nil }
        XCTAssertEqual(Set(partial.keys), ["BTC"])
        cache = WatchCache.merge(cache, fresh: partial, time: 2_000, keep: coins)

        XCTAssertEqual(cache["BTC"]?.quote.price, 67_000)
        XCTAssertEqual(cache["BTC"]?.time, 2_000)
        XCTAssertEqual(cache["SOL"]?.quote.price, 150)
        XCTAssertEqual(cache["SOL"]?.time, 1_000)
        // Stand der Liste = ältester Kurs, kein gemeinsamer «aktualisiert»-Stempel
        XCTAssertEqual(WatchCache.listTime(cache, coins: coins), 1_000)
    }

    /// Kurse und Zeiten als ein Wert (ein Eintrag in UserDefaults, `WatchStore.mergeQuotes`).
    func testCacheEncodesPriceAndTimeTogether() throws {
        let list = [WatchCached(quote: q("BTC", 67_000), time: 1_000), WatchCached(quote: q("SOL", 150), time: 2_000)]
        let decoded = try JSONDecoder().decode([WatchCached].self, from: JSONEncoder().encode(list))
        XCTAssertEqual(decoded, list)
    }
}
