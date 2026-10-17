import XCTest
@testable import CryptoChecker

/// Phemex-Faktoren aus `/public/products` (`PhemexScales`): Preis (Ep) je Produkt, Werte (Ev) je Währung, Standard 8
/// nur ohne Angabe — wie `PhemexScalesTest.kt`.
final class PhemexScalesTests: XCTestCase {

    private let json = """
        {"currencies":[{"currency":"BTC","valueScale":8},{"currency":"SHIB","valueScale":2},{"currency":"VPAD","valueScale":4},
        {"currency":"USDT","valueScale":8},{"currency":"NEW","valueScale":6}],
        "products":[{"symbol":"sBTCUSDT","type":"Spot","baseCurrency":"BTC","quoteCurrency":"USDT"},
        {"symbol":"sNEWUSDT","type":"Spot","baseCurrency":"NEW","quoteCurrency":"USDT","priceScale":6},
        {"symbol":"BTCUSD","type":"Perpetual","priceScale":4,"baseCurrency":"BTC","quoteCurrency":"USD"}]}
        """

    func testScalesFromProducts() throws {
        let s = PhemexScales.parse(try JObject(string: json))
        // Spot ohne priceScale: 8 (Public-Spot-API), mit Angabe die Angabe; Kontrakte zählen nicht
        XCTAssertEqual(s.priceDivisor("sBTCUSDT"), 1e8)
        XCTAssertEqual(s.priceDivisor("sNEWUSDT"), 1e6)
        XCTAssertEqual(s.priceDivisor("BTCUSD"), 1e8)
        // Werte je Währung: SHIB 2, VPAD 4, NEW 6 aus der Liste
        XCTAssertEqual(s.valueDivisor("SHIB"), 1e2)
        XCTAssertEqual(s.valueDivisor("VPAD"), 1e4)
        XCTAssertEqual(s.valueDivisor("NEW"), 1e6)
        XCTAssertEqual(s.valueDivisor("USDT"), 1e8)
        let pair = s.currencies("sNEWUSDT", base: nil, quote: nil)
        XCTAssertEqual(pair.base, "NEW")
        XCTAssertEqual(pair.quote, "USDT")
    }

    func testFallbackWithoutProductList() {
        let s = PhemexScales()
        // Ohne Produktliste: 10^8, nur die dokumentierten Ausnahmen (SHIB 2, VPAD 4)
        XCTAssertEqual(s.priceDivisor("sSHIBUSDT"), 1e8)
        XCTAssertEqual(s.valueDivisor("SHIB"), 1e2)
        XCTAssertEqual(s.valueDivisor("vpad"), 1e4)
        XCTAssertEqual(s.valueDivisor("ETH"), 1e8)
        XCTAssertEqual(s.valueDivisor(nil), 1e8)
        // Basis/Kotierung aus dem Symbol geschätzt, Einzelabfrage mit bekannten Währungen
        let shib = s.currencies("sSHIBUSDT", base: nil, quote: nil)
        XCTAssertEqual(shib.base, "SHIB")
        XCTAssertEqual(shib.quote, "USDT")
        let vpad = s.currencies("sVPADUSDT", base: "VPAD", quote: "USDT")
        XCTAssertEqual(vpad.base, "VPAD")
        let none = s.currencies("sXYZ", base: nil, quote: nil)
        XCTAssertNil(none.base)
        XCTAssertNil(none.quote)
    }

    private let ticker = #"{"error":null,"id":0,"result":[{"lastEp":6200010000000,"symbol":"sBTCUSDT","timestamp":1728540000123456789}]}"#

    func testNeverGuessesScalesAndPersistsThemWithThePairList() throws {
        let file = FileManager.default.temporaryDirectory.appendingPathComponent("phemex_scales_\(UUID().uuidString).json")
        let previousURL = PhemexScales.storeURL
        defer {
            PhemexScales.storeURL = nil
            if let parsed = try? JObject(string: json) { PhemexScales.current = PhemexScales.parse(parsed) }
            PhemexScales.storeURL = previousURL
            try? FileManager.default.removeItem(at: file)
        }
        let market = Phemex()
        PhemexScales.storeURL = nil
        PhemexScales.current = PhemexScales()
        // Ohne Produktliste: kein Kurs (statt eines um Zehnerpotenzen falschen), Sammelabfrage holt zuerst die Liste
        XCTAssertEqual(market.bulkTickersNumOfRequests, 2)
        XCTAssertEqual(market.bulkTickersURL(call: 0), "https://api.phemex.com/public/products")
        XCTAssertEqual(market.bulkTickersURL(call: 1), "https://api.phemex.com/md/spot/ticker/24hr/all")
        XCTAssertThrowsError(try market.parseBulkTickersDetailed(call: 1, body: ticker))
        // Erste Anfrage = Produktliste: Faktoren merken und in der Datei ablegen
        PhemexScales.storeURL = file
        let products = #"{"code":0,"data":"# + json + "}"
        XCTAssertTrue(try market.parseBulkTickersDetailed(call: 0, body: products).tickers.isEmpty)
        XCTAssertTrue(PhemexScales.current.loaded)
        XCTAssertTrue(FileManager.default.fileExists(atPath: file.path))
        XCTAssertEqual(market.bulkTickersNumOfRequests, 1)
        let last = try market.parseBulkTickersDetailed(call: 1, body: ticker).tickers["sBTCUSDT"]?.last ?? 0
        XCTAssertEqual(last, 62000.1, accuracy: 1e-9)
        // «Neustart»: Speicher leer, die Datei liefert dieselben Faktoren
        PhemexScales.current = PhemexScales()
        PhemexScales.storeURL = file
        let restored = PhemexScales.current
        XCTAssertTrue(restored.loaded)
        XCTAssertEqual(restored.priceDivisor("sNEWUSDT"), 1e6)
        XCTAssertEqual(restored.valueDivisor("SHIB"), 1e2)
        XCTAssertEqual(restored.currencies("sNEWUSDT", base: nil, quote: nil).base, "NEW")
        // Kaputte oder fremde Datei: nichts geladen
        XCTAssertNil(PhemexScales.decode(#"{"v":99,"price":{"x":1}}"#))
        XCTAssertNil(PhemexScales.decode("nonsense"))
    }

    func testLoadsMissingScalesForSingleTickersAndRefreshesDaily() throws {
        let market = Phemex()
        let info = PairQuery("BTC", "USDT", "sBTCUSDT")
        let products = #"{"code":0,"data":"# + json + "}"
        let single = #"{"error":null,"id":0,"result":{"lastEp":6200010000000,"symbol":"sBTCUSDT"}}"#
        let previousURL = PhemexScales.storeURL
        defer {
            PhemexScales.storeURL = nil
            if let parsed = try? JObject(string: json) { PhemexScales.current = PhemexScales.parse(parsed) }
            PhemexScales.storeURL = previousURL
        }
        PhemexScales.storeURL = nil
        PhemexScales.current = PhemexScales()
        // Einzelabfrage ohne Faktoren (weniger Paare als für die Sammelabfrage): erste Anfrage = Produktliste
        XCTAssertEqual(market.tickerCallCount(info), 2)
        XCTAssertEqual(market.tickerAddress(call: 0, info: info), "https://api.phemex.com/public/products")
        var first = Ticker()
        XCTAssertThrowsError(try market.readTicker(call: 0, body: products, ticker: &first, info: info)) {
            XCTAssertEqual(($0 as? JSONError)?.message, PhemexScales.justLoaded)
        }
        // Danach geladen: nächste Abfrage liefert den Kurs (kein Dauerfehler)
        XCTAssertTrue(PhemexScales.current.loaded)
        XCTAssertEqual(market.tickerCallCount(info), 1)
        XCTAssertEqual(market.tickerAddress(call: 0, info: info), "https://api.phemex.com/md/spot/ticker/24hr?symbol=sBTCUSDT")
        var ticker = Ticker()
        try market.readTicker(call: 0, body: single, ticker: &ticker, info: info)
        XCTAssertEqual(ticker.last, 62000.1, accuracy: 1e-9)

        // Älter als einen Tag: Kurs zuerst, Produktliste als Zusatzanfrage (Einzel- und Sammelabfrage)
        let old = Int64(Date().timeIntervalSince1970 * 1000) - PhemexScales.refreshMillis - 1000
        PhemexScales.current = PhemexScales.parse(try JObject(string: json), now: old)
        XCTAssertTrue(PhemexScales.current.isStale())
        XCTAssertEqual(market.tickerCallCount(info), 2)
        XCTAssertEqual(market.tickerAddress(call: 0, info: info), "https://api.phemex.com/md/spot/ticker/24hr?symbol=sBTCUSDT")
        XCTAssertEqual(market.tickerAddress(call: 1, info: info), "https://api.phemex.com/public/products")
        var refreshed = Ticker()
        try market.readTicker(call: 0, body: single, ticker: &refreshed, info: info)
        try market.readTicker(call: 1, body: products, ticker: &refreshed, info: info)
        XCTAssertEqual(refreshed.last, 62000.1, accuracy: 1e-9)
        XCTAssertFalse(PhemexScales.current.isStale())

        PhemexScales.current = PhemexScales.parse(try JObject(string: json), now: old)
        XCTAssertEqual(market.bulkTickersNumOfRequests, 2)
        XCTAssertEqual(market.bulkTickersURL(call: 0), "https://api.phemex.com/md/spot/ticker/24hr/all")
        XCTAssertEqual(market.bulkTickersURL(call: 1), "https://api.phemex.com/public/products")
        // Scheitert die Auffrischung, bleiben die Kurse vollständig (eigene Gruppe)
        XCTAssertEqual(market.bulkTickersRequestGroup(call: 0), 0)
        XCTAssertEqual(market.bulkTickersRequestGroup(call: 1), 1)
        XCTAssertTrue(try market.parseBulkTickersDetailed(call: 1, body: products).tickers.isEmpty)
        XCTAssertEqual(market.bulkTickersNumOfRequests, 1)
        // Ältere Datei ohne Zeitpunkt: gilt als veraltet, Faktoren bleiben nutzbar
        let legacy = try XCTUnwrap(PhemexScales.decode(#"{"v":1,"price":{"sBTCUSDT":8}}"#))
        XCTAssertTrue(legacy.loaded && legacy.isStale())
        XCTAssertFalse(try XCTUnwrap(PhemexScales.decode(PhemexScales.current.encode())).isStale())
    }

    func testInactiveBulkRowsAreSkipped() throws {
        let previousURL = PhemexScales.storeURL
        defer { PhemexScales.storeURL = previousURL }
        PhemexScales.storeURL = nil
        PhemexScales.current = PhemexScales.parse(try JObject(string: json))
        let body = #"{"result":[{"symbol":"sBTCUSDT","lastEp":6200010000000},{"symbol":"sOLDUSDT","lastEp":0},"# +
            #"{"symbol":"sNONEUSDT","lastEp":null},{"symbol":"sBADUSDT"}]}"#
        let parsed = try Phemex().parseBulkTickersDetailed(call: 0, body: body)
        // Inaktiv (0/null) still weg; fehlendes Feld bleibt ein Fehler
        XCTAssertEqual(Set(parsed.tickers.keys), ["sBTCUSDT"])
        XCTAssertEqual(parsed.failed, ["sBADUSDT"])
    }
}
