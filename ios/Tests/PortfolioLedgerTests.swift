import XCTest
@testable import CryptoChecker

/// Runde 35: exakte Buchhaltung (`Decimal`, 1 Satoshi = 0.00000001 BTC), Gebühren und Gewinn je
/// Zeitraum mit Käufen/Verkäufen. Wie `PortfolioLedgerTest.kt`.
final class PortfolioLedgerTests: XCTestCase {

    private func dec(_ s: String) -> Decimal { Decimal(string: s, locale: Locale(identifier: "en_US_POSIX")) ?? .nan }

    private func trade(_ id: Int64, _ coin: String, _ type: PortfolioTxType, _ amount: String, _ price: String?,
                       _ time: Int64, fee: String? = nil, unit: PortfolioFeeUnit? = nil) -> PortfolioTx {
        PortfolioTx(id: id, coin: coin, type: type, amountExact: dec(amount), priceExact: price.map(dec),
                    time: time, feeExact: fee.map(dec), feeUnit: unit)
    }

    // MARK: exakt

    func testOneSatoshiStaysOneSatoshi() {
        let p = PortfolioCalculator.position("BTC", trades: [
            trade(1, "BTC", .BUY, "1", "60000", 1),
            trade(2, "BTC", .SELL, "0.99999999", "61000", 2),
        ], currentPrice: 61_000)
        XCTAssertEqual(p.holdingsExact, dec("0.00000001"))
        XCTAssertTrue(p.isOpen)
        XCTAssertEqual(PortfolioDecimal.text(p.holdingsExact), "0.00000001")
    }

    func testTenthsAddUpExactlyAndClosePosition() {
        let p = PortfolioCalculator.position("ETH", trades: [
            trade(1, "ETH", .BUY, "0.1", "2000", 1),
            trade(2, "ETH", .BUY, "0.2", "2000", 2),
            trade(3, "ETH", .SELL, "0.3", "2100", 3),
        ], currentPrice: 2_100)
        XCTAssertEqual(p.holdingsExact, 0)
        XCTAssertFalse(p.isOpen)
        XCTAssertFalse(p.oversold)
        XCTAssertEqual(p.realizedExact, dec("30"))
    }

    func testEighteenDecimalsAreKept() {
        let p = PortfolioCalculator.position("ETH", trades: [trade(1, "ETH", .BUY, "0.000000000000000001", "2000", 1)],
                                             currentPrice: 2_000)
        XCTAssertEqual(PortfolioDecimal.text(p.holdingsExact), "0.000000000000000001")
    }

    func testDoubleInitUsesShortestRepresentation() {
        let t = PortfolioTx(id: 1, coin: "BTC", type: .BUY, amount: 0.1, priceUsdt: 0.3, time: 1)
        XCTAssertEqual(t.amountExact, dec("0.1"))
        XCTAssertEqual(t.priceExact, dec("0.3"))
        XCTAssertNil(PortfolioTx(id: 1, coin: "BTC", type: .BUY, amount: 1, priceUsdt: .nan, time: 1).priceExact)
    }

    func testTypedAmountsAreReadExactly() {
        XCTAssertEqual(ThresholdParser.parseDecimal("0.00000001", decimalSeparator: ".").map(PortfolioDecimal.text), "0.00000001")
        XCTAssertEqual(ThresholdParser.parseDecimal("0,00000001", decimalSeparator: ",").map(PortfolioDecimal.text), "0.00000001")
        XCTAssertEqual(ThresholdParser.parseDecimal("1\u{2019}234.56789012", decimalSeparator: ".").map(PortfolioDecimal.text),
                       "1234.56789012")
        XCTAssertEqual(ThresholdParser.parseDecimal("21'000'000.00000001", decimalSeparator: ".").map(PortfolioDecimal.text),
                       "21000000.00000001")
        XCTAssertEqual(ThresholdParser.parseDecimalAllowingZero("0,00", decimalSeparator: ","), 0)
        XCTAssertNil(ThresholdParser.parseDecimal("1e-8", decimalSeparator: "."))
        XCTAssertNil(ThresholdParser.parseDecimal("-1", decimalSeparator: "."))
    }

    func testAbsurdMagnitudesAreInvalid() {
        // Manipulierte Sicherung — gleiche Grenzen wie Android
        XCTAssertNil(PortfolioDecimal.parse("1E300000000"))
        XCTAssertNil(PortfolioDecimal.parse("-1E300000000"))
        XCTAssertNil(PortfolioDecimal.parse("1E-300000000"))
        XCTAssertNil(PortfolioDecimal.parse("1E30"))
        XCTAssertNil(PortfolioDecimal.parse("1" + String(repeating: "0", count: 30)))
        XCTAssertNil(PortfolioDecimal.parse("1E-41"))
        // Grenzen: 30 Stellen vor dem Komma, 40 Nachkommastellen (Nullen am Ende zählen nicht)
        XCTAssertEqual(PortfolioDecimal.parse(String(repeating: "9", count: 30)), dec(String(repeating: "9", count: 30)))
        XCTAssertEqual(PortfolioDecimal.parse("1E-40"), dec("1E-40"))
        XCTAssertEqual(PortfolioDecimal.parse("2.5" + String(repeating: "0", count: 30)), dec("2.5"))
        XCTAssertEqual(PortfolioDecimal.parse("1e-08"), dec("0.00000001"))
        XCTAssertNil(ThresholdParser.parseDecimal("1" + String(repeating: "0", count: 30), decimalSeparator: "."))
        // Eintrag mit absurder Menge ist ungültig (wird beim Laden übersprungen)
        let crafted = #"{"id":3,"coin":"BTC","type":"BUY","amountText":"1E300000000","time":1}"#
        XCTAssertThrowsError(try JSONDecoder().decode(PortfolioTx.self, from: Data(crafted.utf8)))
    }

    func testFileKeepsExactTextAndReadsOldNumbers() throws {
        let tx = trade(7, "BTC", .BUY, "0.00000001", "60000.5", 1, fee: "0.1", unit: .QUOTE)
        let data = try JSONEncoder().encode(tx)
        let back = try JSONDecoder().decode(PortfolioTx.self, from: data)
        XCTAssertEqual(back.amountExact, dec("0.00000001"))
        XCTAssertEqual(back.priceExact, dec("60000.5"))
        XCTAssertEqual(back.feeExact, dec("0.1"))
        // Nur exakter Text, keine Zahlen daneben
        let object = try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
        XCTAssertNil(object["amount"])
        XCTAssertNil(object["priceUsdt"])
        XCTAssertNil(object["fee"])
        let text = #"{"id":3,"coin":"btc","type":"BUY","amountText":"0.1","priceText":"100","time":1}"#
        let parsed = try JSONDecoder().decode(PortfolioTx.self, from: Data(text.utf8))
        XCTAssertEqual(parsed.amountExact, dec("0.1"))
        XCTAssertEqual(parsed.coin, "BTC")
    }

    // MARK: Gebühren

    func testQuoteFeeOnBuyRaisesCostBasis() {
        let p = PortfolioCalculator.position("BTC", trades: [trade(1, "BTC", .BUY, "0.5", "60000", 1, fee: "12.5", unit: .QUOTE)],
                                             currentPrice: 60_000)
        XCTAssertEqual(p.costBasisExact, dec("30012.5"))
        XCTAssertEqual(p.avgCost ?? .nan, 60_025, accuracy: 1e-9)
        XCTAssertEqual(p.unrealized ?? .nan, -12.5, accuracy: 1e-9)
    }

    func testCoinFeeOnBuyReducesHoldingsNotCost() {
        let p = PortfolioCalculator.position("BTC", trades: [trade(1, "BTC", .BUY, "1", "100", 1, fee: "0.001", unit: .COIN)],
                                             currentPrice: 100)
        XCTAssertEqual(p.holdingsExact, dec("0.999"))
        XCTAssertEqual(p.costBasisExact, dec("100"))
    }

    func testQuoteFeeOnSellReducesRealized() {
        let p = PortfolioCalculator.position("BTC", trades: [
            trade(1, "BTC", .BUY, "1", "100", 1),
            trade(2, "BTC", .SELL, "1", "150", 2, fee: "2", unit: .QUOTE),
        ], currentPrice: 150)
        XCTAssertEqual(p.realizedExact, dec("48"))
    }

    func testCoinFeeOnSellRemovesAmountPlusFee() {
        let p = PortfolioCalculator.position("BTC", trades: [
            trade(1, "BTC", .BUY, "2", "100", 1),
            trade(2, "BTC", .SELL, "1", "150", 2, fee: "0.1", unit: .COIN),
        ], currentPrice: 150)
        XCTAssertEqual(p.holdingsExact, dec("0.9"))
        XCTAssertEqual(p.realizedExact, dec("40"))
    }

    // MARK: Gewinn im Zeitraum

    private let today = 20_000
    private var base: Int { today - PortfolioPeriodChange.days }
    private let dayMs: Int64 = 86_400_000

    private func flat(_ close: Double) -> [Int: Double] {
        Dictionary(uniqueKeysWithValues: (today - 30...today).map { ($0, close) })
    }

    func testBuyInsideWindowIsNotGain() throws {
        let trades: [PortfolioTx] = [
            PortfolioTx(id: 1, coin: "BTC", type: .BUY, amount: 1, priceUsdt: 100, time: Int64(base - 3) * dayMs),
            PortfolioTx(id: 2, coin: "BTC", type: .BUY, amount: 1, priceUsdt: 100, time: Int64(base + 2) * dayMs),
        ]
        let gain = try XCTUnwrap(PortfolioPeriodChange.gain(trades: trades, current: ["BTC": 100],
                                                            closes: ["BTC": flat(100)], baseDay: base))
        XCTAssertEqual(gain.amount, 0, accuracy: 1e-9)
    }

    func testSellInsideWindowIsNotLoss() throws {
        let trades: [PortfolioTx] = [
            PortfolioTx(id: 1, coin: "BTC", type: .BUY, amount: 2, priceUsdt: 100, time: Int64(base - 3) * dayMs),
            PortfolioTx(id: 2, coin: "BTC", type: .SELL, amount: 1, priceUsdt: 120, time: Int64(base + 2) * dayMs),
        ]
        let gain = try XCTUnwrap(PortfolioPeriodChange.gain(trades: trades, current: ["BTC": 120],
                                                            closes: ["BTC": flat(100)], baseDay: base))
        XCTAssertEqual(gain.amount, 40, accuracy: 1e-9)
        XCTAssertEqual(gain.percent ?? .nan, 20, accuracy: 1e-9)
    }

    func testFeeInWindowCountsAsCost() throws {
        let trades = [PortfolioTx(id: 1, coin: "BTC", type: .BUY, amount: 1, priceUsdt: 100,
                                  time: Int64(base + 2) * dayMs, fee: 1, feeUnit: .QUOTE)]
        let gain = try XCTUnwrap(PortfolioPeriodChange.gain(trades: trades, current: ["BTC": 100],
                                                            closes: ["BTC": flat(100)], baseDay: base))
        XCTAssertEqual(gain.amount, -1, accuracy: 1e-9)
    }

    func testFeeOfSellWithNothingLeftDoesNotCount() throws {
        let trades: [PortfolioTx] = [
            PortfolioTx(id: 1, coin: "BTC", type: .BUY, amount: 1, priceUsdt: 100, time: Int64(base - 3) * dayMs),
            PortfolioTx(id: 2, coin: "BTC", type: .SELL, amount: 1, priceUsdt: 120, time: Int64(base + 2) * dayMs),
            PortfolioTx(id: 3, coin: "BTC", type: .SELL, amount: 0.5, priceUsdt: 120, time: Int64(base + 3) * dayMs,
                        fee: 5, feeUnit: .QUOTE),
        ]
        let gain = try XCTUnwrap(PortfolioPeriodChange.gain(trades: trades, current: ["BTC": 120],
                                                            closes: ["BTC": flat(100)], baseDay: base))
        XCTAssertEqual(gain.amount, 20, accuracy: 1e-9)
    }

    func testCoinFeeBeyondHoldingsMarksOversold() {
        let p = PortfolioCalculator.position("BTC", trades: [
            trade(1, "BTC", .BUY, "1", "100", 1),
            trade(2, "BTC", .SELL, "1", "120", 2, fee: "0.01", unit: .COIN),
        ], currentPrice: 120)
        XCTAssertTrue(p.oversold)
    }

    func testNoHistoryForHeldCoinGivesUnknown() {
        let trades = [PortfolioTx(id: 1, coin: "BTC", type: .BUY, amount: 1, priceUsdt: 100, time: Int64(base - 3) * dayMs)]
        XCTAssertNil(PortfolioPeriodChange.gain(trades: trades, current: ["BTC": 100], closes: [:], baseDay: base))
    }

    func testTodayGainRemovesBuyMadeToday() throws {
        let start: Int64 = 1_000_000
        let trades: [PortfolioTx] = [
            PortfolioTx(id: 1, coin: "BTC", type: .BUY, amount: 1, priceUsdt: 100, time: start - 10),
            PortfolioTx(id: 2, coin: "BTC", type: .BUY, amount: 1, priceUsdt: 110, time: start + 10),
        ]
        let adjusted = try XCTUnwrap(PortfolioPeriodChange.todayAdjusted(
            PortfolioChange(amount: 20, percent: 10), trades: trades, current: ["BTC": 110],
            coinChanges: ["BTC": 10], windowStart: start))
        XCTAssertEqual(adjusted.amount, 10, accuracy: 1e-9)
    }

    func testTodayGainLeavesOutCoinWithUnpricedBuyLikeSevenDays() throws {
        let start: Int64 = 1_000_000
        let trades: [PortfolioTx] = [
            PortfolioTx(id: 1, coin: "BTC", type: .BUY, amount: 1, priceUsdt: 100, time: start - 10),
            PortfolioTx(id: 2, coin: "ETH", type: .BUY, amount: 2, priceUsdt: 200, time: start - 10),
            // übernommener Bestand ohne Preis: Zufluss unbekannt → ETH fehlt ganz
            PortfolioTx(id: 3, coin: "ETH", type: .BUY, amount: 1, priceUsdt: nil, time: start + 10),
        ]
        let current = ["BTC": 110.0, "ETH": 220.0]
        let changes = ["BTC": 10.0, "ETH": 10.0]
        let raw = try XCTUnwrap(PortfolioPeriodChange.todayFromCoinChanges(
            holdings: ["BTC": 1, "ETH": 3], current: current, coinChanges: changes))
        XCTAssertEqual(raw.amount, 70, accuracy: 1e-9)
        let adjusted = try XCTUnwrap(PortfolioPeriodChange.todayAdjusted(
            raw, trades: trades, current: current, coinChanges: changes, windowStart: start))
        XCTAssertEqual(adjusted.amount, 10, accuracy: 1e-9)
        XCTAssertEqual(adjusted.percent ?? .nan, 10, accuracy: 1e-9)
        XCTAssertEqual(adjusted.skipped, ["ETH"])
    }

    func testTodayGainWithoutCountedCoinIsUnknown() {
        let start: Int64 = 1_000_000
        let trades = [PortfolioTx(id: 1, coin: "ETH", type: .BUY, amount: 1, priceUsdt: nil, time: start + 10)]
        XCTAssertNil(PortfolioPeriodChange.todayAdjusted(
            PortfolioChange(amount: 5, percent: 2), trades: trades, current: ["ETH": 220],
            coinChanges: ["ETH": 10], windowStart: start))
    }

    func testTodayGainCountsCoinBoughtEntirelyTodayWithoutChange() throws {
        let start: Int64 = 1_000_000
        let trades: [PortfolioTx] = [
            PortfolioTx(id: 1, coin: "BTC", type: .BUY, amount: 1, priceUsdt: 100, time: start - 10),
            PortfolioTx(id: 2, coin: "NEW", type: .BUY, amount: 10, priceUsdt: 1, time: start + 10),
        ]
        let current = ["BTC": 110.0, "NEW": 1.5]
        let changes = ["BTC": 10.0]
        let raw = try XCTUnwrap(PortfolioPeriodChange.todayFromCoinChanges(
            holdings: ["BTC": 1, "NEW": 10], current: current, coinChanges: changes))
        XCTAssertEqual(raw.skipped, ["NEW"])
        let adjusted = try XCTUnwrap(PortfolioPeriodChange.todayAdjusted(
            raw, trades: trades, current: current, coinChanges: changes, windowStart: start))
        XCTAssertEqual(adjusted.amount, 15, accuracy: 1e-9)
        XCTAssertEqual(adjusted.percent ?? .nan, 15.0 / 110.0 * 100.0, accuracy: 1e-9)
        XCTAssertEqual(adjusted.skipped, [])
    }

    func testOneCoinWithoutHistoryDoesNotEraseThePeriod() throws {
        let trades: [PortfolioTx] = [
            PortfolioTx(id: 1, coin: "BTC", type: .BUY, amount: 1, priceUsdt: 100, time: Int64(base - 3) * dayMs),
            PortfolioTx(id: 2, coin: "NEWCOIN", type: .BUY, amount: 10, priceUsdt: 1, time: Int64(base - 3) * dayMs),
        ]
        let gain = try XCTUnwrap(PortfolioPeriodChange.gain(trades: trades, current: ["BTC": 110, "NEWCOIN": 1.5],
                                                            closes: ["BTC": flat(100)], baseDay: base))
        XCTAssertEqual(gain.amount, 10, accuracy: 1e-9)
        XCTAssertEqual(gain.percent ?? .nan, 10, accuracy: 1e-9)
        XCTAssertEqual(gain.skipped, ["NEWCOIN"])
    }

    // MARK: Heute = eine Zahl für Karte, Widget und Alarm

    func testTodayLeavesOutCoinWithoutChange() throws {
        let change = try XCTUnwrap(PortfolioPeriodChange.todayFromCoinChanges(
            holdings: ["BTC": 1, "ALT": 100], current: ["BTC": 99, "ALT": 0.2], coinChanges: ["BTC": 10]))
        XCTAssertEqual(change.amount, 9, accuracy: 1e-9)
        XCTAssertEqual(change.percent ?? .nan, 10, accuracy: 1e-9)
        XCTAssertEqual(change.skipped, ["ALT"])
    }

    // MARK: Frische je Coin

    func testOneOldCoinDoesNotStopRecording() throws {
        let now: Int64 = 10_000_000
        let staleAfter: Int64 = 900_000
        let values = ["BTC": 900.0, "ALT": 100.0]
        let times: [String: Int64] = ["BTC": now - 1_000, "ALT": now - 3_600_000]
        XCTAssertEqual(PortfolioPriceFallback.freshShare(values: values, times: times, now: now,
                                                         staleAfterMillis: staleAfter) ?? .nan, 0.9, accuracy: 1e-9)
        XCTAssertTrue(PortfolioPriceFallback.mayRecordShare(values: values, times: times, now: now,
                                                            staleAfterMillis: staleAfter, anyPrice: true))
        let oldBig: [String: Int64] = ["BTC": now - 3_600_000, "ALT": now - 1_000]
        XCTAssertFalse(PortfolioPriceFallback.mayRecordShare(values: values, times: oldBig, now: now,
                                                             staleAfterMillis: staleAfter, anyPrice: true))
    }

    func testRewardsInWindowAreReportedAsMoneyPutIn() throws {
        let trades: [PortfolioTx] = [
            PortfolioTx(id: 1, coin: "ETH", type: .BUY, amount: 1, priceUsdt: 100, time: Int64(base - 3) * dayMs),
            PortfolioTx(id: 2, coin: "ETH", type: .REWARD, amount: 0.1, priceUsdt: 110, time: Int64(base + 3) * dayMs),
        ]
        let gain = try XCTUnwrap(PortfolioPeriodChange.gain(trades: trades, current: ["ETH": 120],
                                                            closes: ["ETH": flat(100)], baseDay: base))
        // Wert 1.1 × 120 = 132 − Anfang 100 − Belohnung 11 = 21; Belohnungen +11 extra genannt
        XCTAssertEqual(gain.amount, 21, accuracy: 1e-9)
        XCTAssertEqual(gain.rewards, 11, accuracy: 1e-9)
    }

    // MARK: gleiche Zeit

    /// Export «neueste zuerst»: bei gleicher Zeit Zugänge vor Abgängen, dann Id — wie Android.
    func testSameTimeBooksInflowsBeforeOutflowsThenId() {
        var swap = trade(3, "ETH", .SWAP, "1", nil, 100)
        swap.toCoin = "BTC"
        swap.toAmountExact = dec("0.05")
        let trades = [
            trade(1, "BTC", .SELL, "0.5", "60000", 100),
            trade(2, "BTC", .BUY, "1", "59000", 100),
            swap,
            trade(4, "BTC", .WITHDRAWAL, "0.01", nil, 100),
            trade(5, "BTC", .DEPOSIT, "0.2", nil, 50),
        ]
        let ids = PortfolioLedger.events("BTC", trades: trades).map(\.trade.id)
        XCTAssertEqual(ids, [5, 2, 3, 1, 4])
        XCTAssertEqual(PortfolioLedger.eventsByCoin(trades).first { $0.coin == "BTC" }?.events.map(\.trade.id), ids)
        // Bestand: 0.2 + 1 + 0.05 − 0.5 − 0.01 (der Verkauf wird nicht mehr auf 0.2 gekappt)
        let holdings = PortfolioLedger.events("BTC", trades: trades).reduce(Decimal(0)) { PortfolioLedger.holdingsAfter($0, $1) }
        XCTAssertEqual(holdings, dec("0.74"))
    }
}
