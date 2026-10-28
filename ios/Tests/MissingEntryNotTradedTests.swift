import XCTest
@testable import CryptoChecker

/// «Kein Eintrag für das Paar» in einer gültigen Antwort («No data», Hyperliquid «Unknown coin») bestätigt nach dem
/// Fehlen in der vollständigen Liste «nicht mehr gehandelt»; Lighter ohne gespeicherte Kennung sucht das richtige
/// Symbol statt des leeren. Wie `MissingEntryNotTradedTest.kt`; Textfälle in `not_traded.json`.
final class MissingEntryNotTradedTests: XCTestCase {

    func testMissingEntryIsAStatementAboutThePair() {
        let noData = JSONError(message: JSONError.noData)
        let unknownCoin = JSONError(message: "Unknown coin: FOO")
        XCTAssertEqual(ExchangeFailure.of(noData), .unknownInstrument)
        XCTAssertEqual(ExchangeFailure.of(unknownCoin), .unknownInstrument)
        // Angezeigter Text ist übersetzt: die Art entscheidet
        XCTAssertTrue(NotTraded.confirmedBySingle(gotPrice: false, error: ConnectionErrors.describe(noData),
                                                  failure: ExchangeFailure.of(noData)))
        XCTAssertTrue(NotTraded.confirmedBySingle(gotPrice: false, error: ConnectionErrors.describe(unknownCoin),
                                                  failure: ExchangeFailure.of(unknownCoin)))
        // Gespeicherter Text ohne Art: gleich
        XCTAssertTrue(NotTraded.confirmedBySingle(gotPrice: false, error: "No data"))
        XCTAssertTrue(NotTraded.confirmedBySingle(gotPrice: false, error: "Unknown coin: FOO"))
    }

    func testOtherParseErrorsStayErrors() {
        let other = JSONError(message: "No data for field last")
        XCTAssertEqual(ExchangeFailure.of(other), .parse)
        XCTAssertFalse(NotTraded.confirmedBySingle(gotPrice: false, error: ConnectionErrors.describe(other),
                                                   failure: ExchangeFailure.of(other)))
        XCTAssertFalse(NotTraded.isMissingEntry("no data available"))
        XCTAssertFalse(NotTraded.isMissingEntry(nil))
        // Kurs da: nie
        XCTAssertFalse(NotTraded.confirmedBySingle(gotPrice: true, error: "No data", failure: .unknownInstrument))
    }

    func testLighterWithoutPairIdDerivesTheSymbol() throws {
        let body = """
        {"code":200,"order_book_stats":[
            {"symbol":"BTC","last_trade_price":67000.5,"daily_price_change":1.2},
            {"symbol":"ETH/USDC","last_trade_price":3200.25,"daily_price_change":-0.4}
        ]}
        """
        var perp = Ticker()
        try Lighter().readTicker(call: 0, body: body, ticker: &perp, info: PairQuery("BTC", "USDC", nil, .perpetual))
        XCTAssertEqual(perp.last, 67000.5)
        var spot = Ticker()
        try Lighter().readTicker(call: 0, body: body, ticker: &spot, info: PairQuery("ETH", "USDC", nil, .none))
        XCTAssertEqual(spot.last, 3200.25)
        // Gespeicherte Kennung hat Vorrang
        var stored = Ticker()
        try Lighter().readTicker(call: 0, body: body, ticker: &stored, info: PairQuery("X", "USDC", "BTC", .none))
        XCTAssertEqual(stored.last, 67000.5)
        // Wirklich fehlendes Symbol bleibt «unbekannt» (bestätigt nach dem Fehlen in der Liste)
        var gone = Ticker()
        XCTAssertThrowsError(try Lighter().readTicker(call: 0, body: body, ticker: &gone,
                                                      info: PairQuery("GONE", "USDC", nil, .perpetual))) { error in
            XCTAssertEqual((error as? JSONError)?.message, "Unknown symbol: GONE")
        }
    }

    /// Lighter Kurs 0 (nie gehandelt): «No data» bestätigt «nicht mehr gehandelt» statt eines Dauer-Lesefehlers. Wie Android.
    func testLighterPriceZeroIsNoData() {
        let body = #"{"code":200,"order_book_stats":[{"symbol":"ARM","last_trade_price":0,"daily_price_change":0}]}"#
        var ticker = Ticker()
        XCTAssertThrowsError(try Lighter().readTicker(call: 0, body: body, ticker: &ticker,
                                                      info: PairQuery("ARM", "USDC", "ARM", .perpetual))) { error in
            XCTAssertEqual((error as? JSONError)?.message, JSONError.noData)
            XCTAssertEqual(ExchangeFailure.of(error), .unknownInstrument)
            XCTAssertTrue(NotTraded.confirmedBySingle(gotPrice: false, error: ConnectionErrors.describe(error),
                                                      failure: ExchangeFailure.of(error)))
        }
    }

    /// Ohne Sammelabfrage (1–2 Paare): nur wo die Einzelabfrage die vollständige Liste liest (Lighter, Hyperliquid).
    func testConfirmWithoutBulk() throws {
        let cases = TestSupport.list(try TestSupport.fixture("not_traded")["confirmWithoutBulk"])
        XCTAssertFalse(cases.isEmpty)
        for c in cases {
            let actual = NotTraded.confirmWithoutBulk(bulkTried: c["bulkTried"] as? Bool ?? true,
                                                      singleReadsFullList: c["singleReadsFullList"] as? Bool ?? false)
            XCTAssertEqual(actual, c["confirm"] as? Bool, c["name"] as? String ?? "")
        }
        let flagged = ExchangesConfig.all.filter(\.singleTickerReadsFullList)
        XCTAssertEqual(Set(flagged.map(\.key)), ["Lighter", "Hyperliquid"])
        // Nur sinnvoll, wo die Liste vollständig ist
        XCTAssertTrue(flagged.allSatisfy(\.bulkTickersComplete))
    }

    func testHyperliquidValidAnswerHasNoErrorText() throws {
        let info = PairQuery("FOO", "USDC", "FOO", .perpetual)
        // Gültige Antwort (Liste): kein Fehlertext, damit «Unknown coin» bleibt
        XCTAssertThrowsError(try Hyperliquid().readError(call: 0, body: " [{\"universe\":[]},[]]", info: info)) { error in
            XCTAssertEqual((error as? JSONError)?.message, Exchange.noErrorTextMessage)
        }
        // Fehler als blosser Text: dessen Anfang
        XCTAssertEqual(try Hyperliquid().readError(call: 0, body: "Failed to deserialize", info: info),
                       "Failed to deserialize")
    }
}
