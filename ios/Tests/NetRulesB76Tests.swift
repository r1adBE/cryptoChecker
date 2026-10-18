import XCTest
@testable import CryptoChecker

/// Netz, Aktualisierung und Alarme — gleiche Fälle wie Android (`NotTradedConfirmTest`, `SpokenPriceLocaleTest`,
/// `FetchOrderTest`, `AlarmFreshnessTest`, `BidAskSpreadTest`, `AlarmDeliveryRetractTest`, `MarketCallTimeoutTest`).
final class NetRulesB76Tests: XCTestCase {

    // MARK: «Nicht mehr gehandelt» nur bei klarer Aussage der Börse

    func testUnknownExceptionsNoLongerConfirmNotTraded() {
        XCTAssertFalse(NotTraded.confirmedBySingle(gotPrice: false, error: "The data couldn’t be read because it isn’t in the correct format."))
        XCTAssertFalse(NotTraded.confirmedBySingle(gotPrice: false, error: "Unknown error (empty)"))
        XCTAssertFalse(NotTraded.confirmedBySingle(gotPrice: false, error: "HTTP 410"))
        XCTAssertFalse(NotTraded.confirmedBySingle(gotPrice: false, error: "HTTP 401"))
    }

    func testExplicitUnknownInstrumentConfirmsNotTraded() {
        XCTAssertTrue(NotTraded.confirmedBySingle(gotPrice: false, error: "Invalid symbol."))
        XCTAssertTrue(NotTraded.confirmedBySingle(gotPrice: false, error: "Parameter symbol does not exist"))
        XCTAssertTrue(NotTraded.confirmedBySingle(gotPrice: false, error: "HTTP 404"))
        XCTAssertTrue(NotTraded.confirmedBySingle(gotPrice: false, error: nil))
        XCTAssertFalse(NotTraded.confirmedBySingle(gotPrice: true, error: nil))
    }

    // MARK: Vorgelesene Kurse

    func testSpokenPriceUsesSpeechLocaleSeparator() {
        XCTAssertEqual(PriceFormat.spokenPrice(1.5, locale: Locale(identifier: "en")), "1.50")
        XCTAssertEqual(PriceFormat.spokenPrice(1.5, locale: Locale(identifier: "de")), "1,50")
        XCTAssertEqual(PriceFormat.spokenPrice(0.00000712, locale: Locale(identifier: "fr")), "0,00000712")
        XCTAssertEqual(PriceFormat.spokenPrice(65000.4, locale: Locale(identifier: "de")), "65000")
        XCTAssertEqual(PriceFormat.spokenPrice(65000.4), "65000")
    }

    // MARK: Reihenfolge der Einzelabfragen

    func testAlarmedPairsAreFetchedFirst() {
        XCTAssertEqual(FetchOrder.alarmsFirst([1, 2, 3, 4, 5] as [Int64], alarmed: [5, 2]) { $0 }, [2, 5, 1, 3, 4])
        XCTAssertEqual(FetchOrder.alarmsFirst([3, 1, 2] as [Int64], alarmed: []) { $0 }, [3, 1, 2])
        XCTAssertEqual(FetchOrder.alarmsFirst([3, 1, 2] as [Int64], alarmed: [1, 2, 3]) { $0 }, [3, 1, 2])
        XCTAssertEqual(FetchOrder.alarmsFirst([3, 1, 2] as [Int64], alarmed: [9]) { $0 }, [3, 1, 2])
    }

    // MARK: Alter laut Börse

    func testExchangeTimeTooOldForAlarms() {
        let now: Int64 = 1_700_000_000_000
        let hour: Int64 = 60 * 60_000
        XCTAssertFalse(AlarmFreshness.exchangeTimeTooOld(nil, now: now))
        XCTAssertFalse(AlarmFreshness.exchangeTimeTooOld(0, now: now))
        XCTAssertTrue(AlarmFreshness.exchangeTimeTooOld(now - 2 * hour - 1, now: now))
        XCTAssertFalse(AlarmFreshness.exchangeTimeTooOld(now - 2 * hour, now: now))
        XCTAssertFalse(AlarmFreshness.exchangeTimeTooOld(now + 60_000, now: now))
    }

    // MARK: Abstand Geld/Brief

    func testBidAskSpread() throws {
        XCTAssertEqual(try XCTUnwrap(BidAskSpread.percentOfMid(bid: 99, ask: 101)), 2.0, accuracy: 1e-9)
        XCTAssertNil(BidAskSpread.widePercent(bid: 99, ask: 101))
        XCTAssertEqual(try XCTUnwrap(BidAskSpread.widePercent(bid: 100, ask: 105)), 4.878, accuracy: 1e-3)
        XCTAssertNil(BidAskSpread.percentOfMid(bid: 0, ask: 101))
        XCTAssertNil(BidAskSpread.percentOfMid(bid: 102, ask: 101))
        XCTAssertNil(BidAskSpread.percentOfMid(bid: .nan, ask: 101))
    }

    // MARK: Paarliste Binance Futures

    func testBinanceFuturesNeedsBothPairLists() {
        XCTAssertTrue(BinanceFutures().pairListAllCallsRequired)
        XCTAssertFalse(Kraken().pairListAllCallsRequired)
    }

    // MARK: Gezeigte Meldung zurückziehen

    func testNotSavedAlarmRetractsNotification() async {
        let suite = "NetRulesB76Tests-\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }
        let delivery = AlarmDelivery(storeKey: "pairs", store: defaults)
        let retracted = RetractCounter()

        let notSaved = await delivery.deliver(alarmId: 7, now: 1_000_000, cooldownMinutes: 0, holdAlarms: false,
                                              canShow: { true }, show: { true }, mark: { false },
                                              retract: { retracted.add() })
        XCTAssertFalse(notSaved)
        XCTAssertEqual(retracted.value, 1)

        let saved = await delivery.deliver(alarmId: 8, now: 1_000_000, cooldownMinutes: 0, holdAlarms: false,
                                           canShow: { true }, show: { true }, mark: { true },
                                           retract: { retracted.add() })
        XCTAssertTrue(saved)
        XCTAssertEqual(retracted.value, 1)

        // Nicht bestätigt: nichts zu entfernen
        _ = await delivery.deliver(alarmId: 9, now: 1_000_000, cooldownMinutes: 0, holdAlarms: false,
                                   canShow: { true }, show: { false }, mark: { true },
                                   retract: { retracted.add() })
        XCTAssertEqual(retracted.value, 1)
    }

    // MARK: Rate-Limit nur in Fehlerfeldern lesbarer Antworten

    func testReadableOkBodiesCountOnlyInErrorFields() {
        XCTAssertFalse(RateLimitSignal.okBodyRateLimited(#"[{"symbol":"X","name":"Rate limit exceeded token"}]"#))
        XCTAssertFalse(RateLimitSignal.okBodyRateLimited(#"{"result":{"desc":"too many requests coin"},"error":[]}"#))
        XCTAssertTrue(RateLimitSignal.okBodyRateLimited(#"{"error":["EAPI:Rate limit exceeded"]}"#))
        XCTAssertTrue(RateLimitSignal.okBodyRateLimited(#"{"msg":"Too many requests"}"#))
        XCTAssertTrue(RateLimitSignal.okBodyRateLimited(#"{"retCode":"10006"}"#))
        XCTAssertTrue(RateLimitSignal.okBodyRateLimited("Too many requests"))
        XCTAssertTrue(RateLimitSignal.okBodyRateLimited(#"[{"msg":"Too many requests"#))
    }
}

private final class RetractCounter: @unchecked Sendable {
    private let lock = NSLock()
    private var count = 0
    var value: Int { lock.withLock { count } }
    func add() { lock.withLock { count += 1 } }
}
