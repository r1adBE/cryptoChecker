import XCTest
@testable import CryptoChecker

/// Die gemeinsamen Bausteine aller Börsen: `SimpleExchange` (Adressen, Fehlerfeld), `Exchange.bulkRowIfActive` und der
/// Standard-Fehlertext sowie die JSON-Helfer. Die Börsen selbst prüft ExchangeContractTests; hier geht es um die
/// Regeln, auf die sich alle verlassen. Gegenstück: `ExchangeBuildingBlocksTest.kt`.
final class ExchangeBuildingBlocksTests: XCTestCase {

    /// Kleinste SimpleExchange-Börse: Fehlertext im Feld «err».
    private final class Probe: SimpleExchange, @unchecked Sendable { // @unchecked: wie `Exchange` (keine veränderlichen Eigenschaften)
        init(errorField: String? = "err") {
            super.init(key: "Probe", name: "Probe", pairListURL: "https://example.test/pairs",
                       tickerURL: "https://example.test/ticker/%1$s?depth=1", errorField: errorField)
        }

        override func fillTicker(call: Int, json: JObject, ticker: inout Ticker, info: PairQuery) throws {
            ticker.last = try json.double("price")
            ticker.timestamp = json.optLong("time")
        }
    }

    /// Wie `Probe`, bildet die Kennung aber selbst («BASE-QUOTE»).
    private final class DashProbe: SimpleExchange, @unchecked Sendable { // @unchecked: wie `Exchange` (keine veränderlichen Eigenschaften)
        init() {
            super.init(key: "DashProbe", name: "DashProbe", pairListURL: "https://example.test/pairs",
                       tickerURL: "https://example.test/ticker?symbol=%1$s")
        }

        override func pairId(_ info: PairQuery) -> String? { info.pairId ?? info.base + "-" + info.quote }
    }

    /// Sammelabfrage aus Zeilen {"id", "last"} über `bulkRowIfActive`.
    private final class BulkProbe: Exchange, @unchecked Sendable { // @unchecked: wie `Exchange` (keine veränderlichen Eigenschaften)
        init() { super.init(key: "BulkProbe", name: "BulkProbe") }

        override func tickerAddress(call: Int, info: PairQuery) -> String { "" }

        override func parseBulkTickers(call: Int, body: String, failed: inout Set<String>) throws -> [String: Ticker] {
            var tickers: [String: Ticker] = [:]
            for row in try JArray(string: body).allObjects() {
                bulkRowIfActive(row.optString("id"), row: row, priceField: "last", tickers: &tickers,
                                failed: &failed) { $0.last = try row.double("last") }
            }
            return tickers
        }
    }

    private func info(_ id: String?) -> PairQuery { PairQuery("BTC", "USD", id, .none) }

    func testSimpleExchangeInsertsThePairIdIntoTheTickerUrl() {
        let exchange = Probe()
        XCTAssertEqual(exchange.tickerAddress(call: 0, info: info("BTCUSD")), "https://example.test/ticker/BTCUSD?depth=1")
        // Ohne Kennung bleibt die Stelle leer (Android schreibt «null»); die Börse antwortet mit ihrem Fehler
        XCTAssertEqual(exchange.tickerAddress(call: 0, info: info(nil)), "https://example.test/ticker/?depth=1")
        XCTAssertEqual(DashProbe().tickerAddress(call: 0, info: info(nil)), "https://example.test/ticker?symbol=BTC-USD")
        XCTAssertEqual(DashProbe().tickerAddress(call: 0, info: info("X_Y")), "https://example.test/ticker?symbol=X_Y")
    }

    func testSimpleExchangeHasOneFixedPairListAddress() {
        let exchange = Probe()
        XCTAssertEqual(exchange.pairListCallCount, 1)
        XCTAssertEqual(exchange.pairListAddress(call: 0), "https://example.test/pairs")
        XCTAssertEqual(exchange.pairListAddress(call: 3), "https://example.test/pairs")
    }

    func testSimpleExchangeReadsTheErrorField() throws {
        let exchange = Probe()
        XCTAssertEqual(try exchange.readError(call: 0, body: #"{"err":"boom"}"#, info: info("X")), "boom")
        // JSON null ist kein Fehlertext «null»
        XCTAssertThrowsError(try exchange.readError(call: 0, body: #"{"err":null}"#, info: info("X")))
        XCTAssertThrowsError(try exchange.readError(call: 0, body: #"{"other":"x"}"#, info: info("X")))
    }

    func testWithoutErrorFieldThereIsNoErrorText() {
        XCTAssertThrowsError(try Probe(errorField: nil).readError(call: 0, body: #"{"err":"boom"}"#, info: info("X"))) {
            XCTAssertEqual(($0 as? JSONError)?.message, Exchange.noErrorTextMessage)
        }
    }

    func testReadTickerUsesTheSubclassReader() throws {
        var ticker = Ticker()
        try Probe().readTicker(call: 0, body: #"{"price":"1.5"}"#, ticker: &ticker, info: info("X"))
        XCTAssertEqual(ticker.last, 1.5)
        XCTAssertNil(ticker.exchangeTime)
    }

    func testExchangeTimeFarInTheFutureIsDiscarded() throws {
        let now = EpochTime.nowMillis
        // Eine Minute voraus: Uhren gehen nie ganz gleich, die Zeit bleibt
        var soon = Ticker()
        try Probe().readTicker(call: 0, body: #"{"price":1,"time":\#(now + 60_000)}"#, ticker: &soon, info: info("X"))
        XCTAssertEqual(soon.exchangeTime, now + 60_000)
        // 9 h voraus (Ortszeit als Epoche wie Bithumbs KST): verworfen statt «eben gehandelt», der Kurs bleibt
        var kst = Ticker()
        try Probe().readTicker(call: 0, body: #"{"price":1,"time":\#(now + 9 * 3_600_000)}"#, ticker: &kst, info: info("X"))
        XCTAssertNil(kst.exchangeTime)
        XCTAssertEqual(kst.last, 1)
        XCTAssertGreaterThanOrEqual(kst.timestamp, now)
    }

    func testBithumbTradeTimeComesFromTheUtcFields() {
        XCTAssertEqual(UpbitStyleExchange.utcTradeMillis(date: "20261016", time: "232853"), 1_792_193_333_000)
        XCTAssertEqual(UpbitStyleExchange.utcTradeMillis(date: "20240229", time: "000000"), 1_709_164_800_000)
        // Kein Schalttag, unmögliche Uhrzeit, falscher Aufbau, fehlend: keine Börsenzeit
        XCTAssertEqual(UpbitStyleExchange.utcTradeMillis(date: "20230229", time: "000000"), 0)
        XCTAssertEqual(UpbitStyleExchange.utcTradeMillis(date: "20261016", time: "246000"), 0)
        XCTAssertEqual(UpbitStyleExchange.utcTradeMillis(date: "2026-10-", time: "232853"), 0)
        XCTAssertEqual(UpbitStyleExchange.utcTradeMillis(date: "2026101", time: "232853"), 0)
        XCTAssertEqual(UpbitStyleExchange.utcTradeMillis(date: "", time: ""), 0)
    }

    func testBulkRowIfActiveSkipsIdleRowsAndReportsUnreadableOnes() throws {
        let body = """
            [
                {"id":"A","last":"10"},
                {"id":"B","last":2},
                {"id":"","last":"5"},
                {"last":"5"},
                {"id":"IDLE1","last":null},
                {"id":"IDLE2","last":""},
                {"id":"IDLE3","last":"0"},
                {"id":"IDLE4","last":0},
                {"id":"MISSING"},
                {"id":"TEXT","last":"abc"}
            ]
            """
        let parsed = try BulkProbe().parseBulkTickersDetailed(call: 0, body: body)
        XCTAssertEqual(Set(parsed.tickers.keys), ["A", "B"])
        XCTAssertEqual(parsed.tickers["A"]?.last, 10)
        // Feld fehlt oder ist Unsinn: Fehler je Paar statt still «unverändert»
        XCTAssertEqual(parsed.failed, ["MISSING", "TEXT"])
    }

    /// Sammelzeile: dieselbe Hürde wie die Einzelabfrage — nur ein endlicher Kurs grösser als 0 zählt. Wie Android.
    func testBulkRowNeedsAPriceAboveZero() {
        func usable(_ last: Double) -> Bool {
            var ticker = Ticker()
            ticker.last = last
            return BulkTickerRows.usable(ticker)
        }
        XCTAssertTrue(usable(0.0001))
        XCTAssertFalse(usable(0))
        XCTAssertFalse(usable(-0.5))
        XCTAssertFalse(usable(Ticker.missing))
        XCTAssertFalse(usable(.nan))
        XCTAssertFalse(usable(.infinity))
    }

    func testMostlyUnreadableRowsFailTheWholeBatch() {
        let body = #"[{"id":"A","last":"1"},{"id":"X"},{"id":"Y","last":"abc"}]"#
        XCTAssertThrowsError(try BulkProbe().parseBulkTickersDetailed(call: 0, body: body)) {
            XCTAssertTrue(BulkTickerRows.isFormatError(($0 as? BulkFormatError)?.message))
        }
    }

    func testJsonIterationHelpersVisitEveryEntryInOrder() throws {
        var seen: [String] = []
        seen += try JArray(string: #"["a","b"]"#).allStrings()
        seen += try JArray(string: #"[{"k":"c"},{"k":"d"}]"#).allObjects().map { try $0.string("k") }
        seen += try JArray(string: #"[["e",1],["f",2]]"#).allArrays().map { try $0.string(0) }
        XCTAssertEqual(seen, ["a", "b", "c", "d", "e", "f"])
        let named = try JObject(string: #"{"g":{},"h":{}}"#).allNamedObjects().map(\.name)
        XCTAssertEqual(Set(named), ["g", "h"])
        XCTAssertTrue(try JObject(string: "{}").allNamedObjects().isEmpty)
        // Ein Eintrag vom falschen Typ wirft, statt still zu fehlen — ausser bei `optObjects()`
        XCTAssertThrowsError(try JArray(string: "[1]").allObjects())
        XCTAssertEqual(try JArray(string: #"[1,{"k":"z"},"x"]"#).optObjects().map { try $0.string("k") }, ["z"])
    }

    func testJsonFieldHelpersTreatNullAsMissing() throws {
        let row = try JObject(string: #"{"t":"x","n":null,"d":"2.5","list":["TradFi","","Pre-IPO"]}"#)
        XCTAssertEqual(row.optString("t"), "x")
        XCTAssertEqual(row.optString("n"), "")
        XCTAssertEqual(row.optString("absent"), "")
        XCTAssertEqual(try row.string("t"), "x")
        XCTAssertThrowsError(try row.string("n"))
        XCTAssertEqual(row.optDoubleOrMissing("d"), 2.5)
        XCTAssertEqual(row.optDoubleOrMissing("absent"), Ticker.missing)
        XCTAssertEqual(row.optStrings("list"), ["TradFi", "Pre-IPO"])
        XCTAssertEqual(row.optStrings("absent"), [])
    }
}
