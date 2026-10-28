import XCTest
@testable import CryptoChecker

/// Gemeinsame Fälle mit Android (`FiatAlarmGuardTest.kt`, `fiat_alarm_guard.json`).
final class FiatAlarmGuardTests: XCTestCase {
    private func cases(_ key: String) throws -> [[String: Any]] {
        let list = TestSupport.list(try TestSupport.fixture("fiat_alarm_guard")[key])
        XCTAssertFalse(list.isEmpty)
        return list
    }

    private func optionalMillis(_ value: Any?) -> Int64? {
        TestSupport.number(value).map { Int64($0) }
    }

    func testRateTimeOfEcbDate() throws {
        for c in try cases("rateTimeOf") {
            XCTAssertEqual(FiatAlarmGuard.rateTime(ofIsoDate: c["date"] as? String), optionalMillis(c["expected"]),
                           "rateTimeOf: \(c["name"] ?? "")")
        }
    }

    func testBusinessDaysSkipWeekendAndTargetHolidays() throws {
        for c in try cases("businessDay") {
            XCTAssertEqual(FiatAlarmGuard.isBusinessDay(epochDay: TestSupport.millis(c["epochDay"])),
                           c["expected"] as? Bool, "businessDay: \(c["name"] ?? "")")
        }
        // 2026-04-05 = Tag 20548 seit 1970
        XCTAssertEqual(FiatAlarmGuard.easterSundayEpochDay(2026), 20548)
    }

    func testFxTooOldCountsBusinessHoursOnly() throws {
        for c in try cases("fxTooOld") {
            let actual = FiatAlarmGuard.fxTooOld(rateTime: optionalMillis(c["rateTime"]), now: TestSupport.millis(c["now"]))
            XCTAssertEqual(actual, c["expected"] as? Bool, "fxTooOld: \(c["name"] ?? "")")
        }
    }

    /// Echte Fiat-Währung ohne Devisenkurs (NGN, ARS …) = `.fiatUnsupported`, nie Krypto über USDT — wie
    /// Android `quoteKindKnowsFiatWithoutFxRate`.
    func testQuoteKindKnowsFiatWithoutFxRate() throws {
        func name(_ kind: CurrencyConversion.QuoteKind) -> String {
            switch kind {
            case .usd: return "USD"
            case .fiat: return "FIAT"
            case .fiatUnsupported: return "FIAT_UNSUPPORTED"
            case .crypto: return "CRYPTO"
            case .invalid: return "INVALID"
            }
        }
        for c in try cases("classify") {
            let actual = CurrencyConversion.classify(c["quote"] as? String ?? "", fiat: FxRateSource.currencies)
            XCTAssertEqual(name(actual), c["expected"] as? String, "classify: \(c["name"] ?? "")")
        }
        // Keine Umrechnung, auch mit Kursen
        XCTAssertNil(CurrencyConversion.quoteToUsd(.fiatUnsupported, usdToQuote: 1500, cryptoUsdt: 0.0007))
    }

    func testInvolvesUsdtFollowsConversionRule() throws {
        for c in try cases("involvesUsdt") {
            let actual = FiatAlarmGuard.involvesUsdt(from: c["from"] as? String ?? "", to: c["to"] as? String ?? "")
            XCTAssertEqual(actual, c["expected"] as? Bool, "involvesUsdt: \(c["name"] ?? "")")
        }
    }

    func testUsdtStateAssumedMeasuredOrUnsure() throws {
        for c in try cases("usdtState") {
            var reading: DepegAlarm.Reading?
            if let r = c["reading"] as? [String: Any] {
                reading = DepegAlarm.Reading(price: TestSupport.number(r["price"]) ?? 0, atMillis: TestSupport.millis(r["at"]),
                                             viaUsdc: r["viaUsdc"] as? Bool ?? false)
            }
            let actual = FiatAlarmGuard.usdtState(involved: c["involved"] as? Bool ?? false, reading: reading,
                                                  now: TestSupport.millis(c["now"]))
            XCTAssertEqual(actual.rawValue, c["expected"] as? String, "usdtState: \(c["name"] ?? "")")
        }
    }

    func testNeedsUsdtMeasurementForFiatConversionOverUsdt() throws {
        for c in try cases("needsMeasurement") {
            let alarms = (c["alarms"] as? [[String]] ?? []).map { (quote: $0[0], currency: $0[1]) }
            let actual = FiatAlarmGuard.needsUsdtMeasurement(
                showConverted: c["showConverted"] as? Bool ?? false, displayCurrency: c["displayCurrency"] as? String ?? "",
                watchQuotes: c["watchQuotes"] as? [String] ?? [], alarmConversions: alarms)
            XCTAssertEqual(actual, c["expected"] as? Bool, "needsMeasurement: \(c["name"] ?? "")")
        }
    }

    func testSkipFxStaleBeforeUnsure() throws {
        for c in try cases("skip") {
            let usdt = try XCTUnwrap(FiatAlarmGuard.UsdtState(rawValue: c["usdt"] as? String ?? ""))
            let actual = FiatAlarmGuard.skip(fxRateTime: optionalMillis(c["rateTime"]), usdt: usdt,
                                             now: TestSupport.millis(c["now"]))
            XCTAssertEqual(actual?.rawValue, c["expected"] as? String, "skip: \(c["name"] ?? "")")
        }
    }

    /// Vermerk an der ≈ Zeile der Merkliste — wie Android `WatchlistConversionNoteTest`.
    func testWatchlistUsdtNote() {
        func watch(_ quote: String) -> Watch {
            Watch(id: 1, exchangeKey: "Binance", exchangeName: "Binance", baseAsset: "BTC", quoteAsset: quote, lastPrice: 100)
        }
        let notes: [String: FiatAlarmGuard.UsdtState] = ["USDT": .ASSUMED, "BTC": .UNSURE]
        XCTAssertEqual(WatchlistConversion.usdtNote(watch("usdt"), target: "CHF", notes: notes), .ASSUMED)
        XCTAssertEqual(WatchlistConversion.usdtNote(watch("BTC"), target: "CHF", notes: notes), .UNSURE)
        XCTAssertNil(WatchlistConversion.usdtNote(watch("EUR"), target: "CHF", notes: notes))
        XCTAssertNil(WatchlistConversion.usdtNote(watch("USDT"), target: nil, notes: notes))
        let text = WatchlistConversion.text(watch("USDT"), target: "CHF", rates: ["USDT": 0.8], usdtNotes: notes) ?? ""
        // Zeile kurz «Umrechnung unsicher» (passt neben das Paar), VoiceOver der ganze Satz — wie Android
        XCTAssertTrue(text.hasSuffix(" · " + L("converted_unsure")), text)
        let spoken = WatchlistConversion.accessibilityText(watch("USDT"), target: "CHF", rates: ["USDT": 0.8],
                                                           usdtNotes: notes) ?? ""
        XCTAssertTrue(spoken.hasSuffix(" · " + L("portfolio_usdt_assumed")), spoken)
        XCTAssertNil(WatchlistConversion.accessibilityText(watch("BTC"), target: "CHF", rates: ["BTC": 0.8],
                                                           usdtNotes: notes), "unsicher: Zeilentext genügt")
    }

    /// Erste USDT/USD-Messung beendet das Warten sofort, ohne Vermerk wird normal gewartet — wie Android `usdtMeasured`.
    func testWaitEndsWhenUsdtMeasured() async {
        let start = Date()
        await WatchlistConversion.waitForNextRound(usdtAssumed: true, measured: { true })
        XCTAssertLessThan(Date().timeIntervalSince(start), 10)
    }

    /// Aster «U» (United Stables) zählt 1:1 als USD, anderswo bleibt U ein Coin — wie Android
    /// `CurrencyConversionTest.asterUIsUsdStableOnlyAtAster`.
    func testAsterUIsUsdStableOnlyAtAster() {
        XCTAssertEqual(CurrencyConversion.conversionQuote(exchangeKey: "Aster", quote: "U"), "USD")
        XCTAssertEqual(CurrencyConversion.conversionQuote(exchangeKey: "aster", quote: " u "), "USD")
        XCTAssertEqual(CurrencyConversion.conversionQuote(exchangeKey: "CoinDCX", quote: "U"), "U")
        XCTAssertEqual(CurrencyConversion.conversionQuote(exchangeKey: "Binance", quote: "U"), "U")
        XCTAssertEqual(CurrencyConversion.classify("U", fiat: ["CHF"]), .crypto)
        XCTAssertEqual(CurrencyConversion.conversionQuote(exchangeKey: "Aster", quote: "usdt"), "USDT")
        let aster = Watch(id: 3, exchangeKey: "Aster", exchangeName: "Aster", baseAsset: "BTC", quoteAsset: "U", lastPrice: 100)
        XCTAssertEqual(WatchlistConversion.quote(aster), "USD")
        XCTAssertNil(WatchlistConversion.text(aster, target: "USD", rates: [:]), "USD-Ziel: nichts umzurechnen")
    }

    /// Portfolio-Alarm in anderer Währung ohne Messung USDT/USD: Vermerk — wie Android
    /// `PortfolioAlarmLogicTest.usdtAssumedOnlyForValueAlarmInOtherCurrencyWithoutMeasurement`.
    func testPortfolioAlarmUsdtAssumed() {
        let reading = PortfolioReading(total: 1000, currency: "CHF", totalUsdt: 1100, changePercent: nil, empty: false)
        func alarm(_ kind: PortfolioAlarmKind, _ currency: String?) -> PortfolioAlarm {
            PortfolioAlarm(id: 1, kind: kind, threshold: 900, currency: currency)
        }
        XCTAssertTrue(PortfolioAlarmLogic.usdtAssumed(alarm(.VALUE_ABOVE, "EUR"), reading))
        var measured = reading
        measured.usdtUsdLive = 0.999
        XCTAssertFalse(PortfolioAlarmLogic.usdtAssumed(alarm(.VALUE_ABOVE, "EUR"), measured))
        XCTAssertFalse(PortfolioAlarmLogic.usdtAssumed(alarm(.VALUE_ABOVE, "CHF"), reading))
        XCTAssertFalse(PortfolioAlarmLogic.usdtAssumed(alarm(.VALUE_BELOW, "USDT"), reading))
        XCTAssertFalse(PortfolioAlarmLogic.usdtAssumed(alarm(.CHANGE_UP, "EUR"), reading))
        var settings = AppSettings()
        settings.appLock = false
        settings.hidePortfolioAmounts = false
        let body = PortfolioAlarmNotice.body(alarm(.VALUE_ABOVE, "EUR"), measured: 950, settings: settings, usdtAssumed: true)
        XCTAssertTrue(body.hasSuffix(" · " + L("portfolio_usdt_assumed")), body)
    }
}
