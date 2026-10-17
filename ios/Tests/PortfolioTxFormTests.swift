import XCTest
@testable import CryptoChecker

/// Erfassen-Blatt (heutiger Kurs nur für heute, Verkaufswarnung mit Gebühr im Coin), Merklisten-Paar statt
/// ungeprüfter Kerze, Ø über den Teil mit Kaufpreis, exakter unrealisierter Gewinn und 34 Stellen bei Teilungen.
/// Wie `PortfolioTxFormTest.kt`, `PortfolioWatchPairTest.kt` und `PortfolioLedgerTest.kt` (Android).
final class PortfolioTxFormTests: XCTestCase {

    private func dec(_ s: String) -> Decimal { Decimal(string: s, locale: Locale(identifier: "en_US_POSIX")) ?? .nan }

    private func trade(_ id: Int64, _ coin: String, _ type: PortfolioTxType, _ amount: String, _ price: String?,
                       _ time: Int64, fee: String? = nil, unit: PortfolioFeeUnit? = nil) -> PortfolioTx {
        PortfolioTx(id: id, coin: coin, type: type, amountExact: dec(amount), priceExact: price.map(dec),
                    time: time, feeExact: fee.map(dec), feeUnit: unit)
    }

    // MARK: Preis vorbelegen

    func testPrefillOnlyForNewTransactionsOfToday() {
        XCTAssertTrue(PortfolioTxForm.mayPrefill(isEdit: false, isToday: true))
        XCTAssertFalse(PortfolioTxForm.mayPrefill(isEdit: false, isToday: false))
        XCTAssertFalse(PortfolioTxForm.mayPrefill(isEdit: true, isToday: true))
        XCTAssertFalse(PortfolioTxForm.mayPrefill(isEdit: true, isToday: false))
    }

    func testPastDateClearsAutoFilledPriceButKeepsTypedOne() {
        XCTAssertEqual(PortfolioTxForm.onDateChange(isEdit: false, isToday: false, priceAuto: true, priceBlank: false), .clear)
        XCTAssertEqual(PortfolioTxForm.onDateChange(isEdit: false, isToday: false, priceAuto: false, priceBlank: false), .keep)
        XCTAssertEqual(PortfolioTxForm.onDateChange(isEdit: false, isToday: false, priceAuto: false, priceBlank: true), .keep)
    }

    func testBackToTodayWithEmptyFieldPrefillsAgain() {
        XCTAssertEqual(PortfolioTxForm.onDateChange(isEdit: false, isToday: true, priceAuto: false, priceBlank: true), .prefill)
        XCTAssertEqual(PortfolioTxForm.onDateChange(isEdit: false, isToday: true, priceAuto: false, priceBlank: false), .keep)
        XCTAssertEqual(PortfolioTxForm.onDateChange(isEdit: false, isToday: true, priceAuto: true, priceBlank: false), .keep)
    }

    func testEditModeNeverTouchesThePrice() {
        for today in [true, false] {
            for auto in [true, false] {
                for blank in [true, false] {
                    XCTAssertEqual(PortfolioTxForm.onDateChange(isEdit: true, isToday: today, priceAuto: auto, priceBlank: blank), .keep)
                }
            }
        }
        XCTAssertFalse(PortfolioTxForm.showPastPriceHint(isEdit: true, isToday: false))
    }

    func testPastPriceHintOnlyForNewTransactionsNotOfToday() {
        XCTAssertTrue(PortfolioTxForm.showPastPriceHint(isEdit: false, isToday: false))
        XCTAssertFalse(PortfolioTxForm.showPastPriceHint(isEdit: false, isToday: true))
    }

    // MARK: Verkaufswarnung

    func testSellWarningIncludesCoinFee() {
        let holdings = dec("1")
        XCTAssertFalse(PortfolioTxForm.sellExceeds(amount: dec("1"), fee: nil, unit: .COIN, holdings: holdings))
        XCTAssertTrue(PortfolioTxForm.sellExceeds(amount: dec("1"), fee: dec("0.001"), unit: .COIN, holdings: holdings))
        XCTAssertTrue(PortfolioTxForm.sellExceedsIncludesFee(dec("0.001"), unit: .COIN))
        // Gebühr in USDT verringert den Erlös, nicht die Menge
        XCTAssertFalse(PortfolioTxForm.sellExceeds(amount: dec("1"), fee: dec("5"), unit: .QUOTE, holdings: holdings))
        XCTAssertFalse(PortfolioTxForm.sellExceedsIncludesFee(dec("5"), unit: .QUOTE))
        XCTAssertFalse(PortfolioTxForm.sellExceeds(amount: dec("1"), fee: dec("0"), unit: .COIN, holdings: holdings))
        XCTAssertFalse(PortfolioTxForm.sellExceedsIncludesFee(dec("-1"), unit: .COIN))
        XCTAssertFalse(PortfolioTxForm.sellExceeds(amount: dec("0.999"), fee: dec("0.001"), unit: .COIN, holdings: holdings))
        XCTAssertFalse(PortfolioTxForm.sellExceeds(amount: nil, fee: dec("1"), unit: .COIN, holdings: holdings))
        XCTAssertFalse(PortfolioTxForm.sellExceeds(amount: dec("5"), fee: nil, unit: .COIN, holdings: nil))
    }

    func testSellWarningMatchesCalculatorOversold() {
        let p = PortfolioCalculator.position("BTC", trades: [
            trade(1, "BTC", .BUY, "1", "100", 1),
            trade(2, "BTC", .SELL, "1", "100", 2, fee: "0.001", unit: .COIN),
        ], currentPrice: 100)
        XCTAssertTrue(p.oversold)
        XCTAssertTrue(PortfolioTxForm.sellExceeds(amount: dec("1"), fee: dec("0.001"), unit: .COIN, holdings: dec("1")))
    }

    // MARK: Merklisten-Paar statt ungeprüfter Kerze

    private func w(_ market: String, _ base: String, _ quote: String, spot: Bool = true, disabled: Bool = false,
                   at: Int64 = 0) -> PortfolioWatchPair.Candidate {
        PortfolioWatchPair.Candidate(marketKey: market, base: base, quote: quote, pairId: nil, spot: spot,
                                     disabled: disabled, lastUpdate: at)
    }

    func testWatchPairPicksActiveSpotPairOnAnotherExchange() {
        let kraken = w("Kraken", "xyz", "EUR")
        XCTAssertEqual(PortfolioWatchPair.pick("XYZ", watches: [kraken, w("Kraken", "ABC", "USD")]), kraken)
    }

    func testWatchPairIgnoresBinanceFuturesDisabledAndUnknownQuotes() {
        XCTAssertNil(PortfolioWatchPair.pick("XYZ", watches: [w("Binance", "XYZ", "USDT")]))
        XCTAssertNil(PortfolioWatchPair.pick("XYZ", watches: [w("BybitFutures", "XYZ", "USDT")]))
        XCTAssertNil(PortfolioWatchPair.pick("XYZ", watches: [w("Kraken", "XYZ", "USD", spot: false)]))
        XCTAssertNil(PortfolioWatchPair.pick("XYZ", watches: [w("Kraken", "XYZ", "USD", disabled: true)]))
        XCTAssertNil(PortfolioWatchPair.pick("XYZ", watches: [w("DexScreener", "XYZ", "USD")]))
        XCTAssertNil(PortfolioWatchPair.pick("XYZ", watches: [w("Kraken", "XYZ", "USDC"), w("Kraken", "XYZ", "BTC")]))
    }

    func testWatchPairPrefersQuoteOrderThenFreshestThenMarket() {
        let eur = w("Kraken", "XYZ", "EUR", at: 50)
        let usdOld = w("Bitstamp", "XYZ", "USD", at: 10)
        let usdNew = w("Coinbase", "XYZ", "USD", at: 20)
        let usdt = w("Kucoin", "XYZ", "USDT", at: 1)
        XCTAssertEqual(PortfolioWatchPair.pick("XYZ", watches: [eur, usdOld, usdNew, usdt]), usdt)
        XCTAssertEqual(PortfolioWatchPair.pick("XYZ", watches: [eur, usdOld, usdNew]), usdNew)
        let a = w("Alpha", "XYZ", "USD", at: 5)
        let b = w("Beta", "XYZ", "USD", at: 5)
        XCTAssertEqual(PortfolioWatchPair.pick("XYZ", watches: [b, a]), a)
    }

    func testWatchPairConflictingNamesGiveNoPick() {
        // K-8: zwei verschiedene Token unter «CAT» in der Merkliste → unklar, welcher im Portfolio steht
        let gate = PortfolioWatchPair.Candidate(marketKey: "GateIo", base: "CAT", quote: "USDT", pairId: nil, spot: true,
                                                disabled: false, lastUpdate: 5, name: "Simon's Cat")
        func upbit(_ name: String?, disabled: Bool = false) -> PortfolioWatchPair.Candidate {
            PortfolioWatchPair.Candidate(marketKey: "Upbit", base: "CAT", quote: "KRW", pairId: nil, spot: true,
                                         disabled: disabled, lastUpdate: 9, name: name)
        }
        XCTAssertNil(PortfolioWatchPair.pick("CAT", watches: [gate, upbit("Catcoin")]))
        // Gleicher Name in anderer Schreibweise oder unbekannt: wie bisher
        XCTAssertEqual(PortfolioWatchPair.pick("CAT", watches: [gate, upbit("Simons Cat")]), gate)
        XCTAssertEqual(PortfolioWatchPair.pick("CAT", watches: [gate, upbit(nil)]), gate)
        // Unbenutzbares Paar (deaktiviert) zählt nicht als Widerspruch
        XCTAssertEqual(PortfolioWatchPair.pick("CAT", watches: [gate, upbit("Catcoin", disabled: true)]), gate)
    }

    // MARK: Buchhaltung

    func testUnrealizedGainIsExactDecimal() {
        let trades = [trade(1, "BTC", .BUY, "0.1", "0.1", 1), trade(2, "BTC", .BUY, "0.2", "0.2", 2)]
        let p = PortfolioCalculator.position("BTC", trades: trades, currentPrice: 0.3)
        XCTAssertEqual(p.valueExact, dec("0.09"))
        XCTAssertEqual(p.unrealizedExact, dec("0.04"))
        XCTAssertEqual(p.unrealized ?? .nan, 0.04, accuracy: 1e-15)
        let s = PortfolioCalculator.summarize(trades + [trade(3, "ETH", .BUY, "1", "0.7", 3)],
                                              prices: ["BTC": 0.3, "ETH": 0.8])
        XCTAssertEqual(s.unrealized ?? .nan, 0.14, accuracy: 1e-15)
    }

    func testAverageCostNamesThePartWithABuyPrice() {
        let p = PortfolioCalculator.position("ETH", trades: [
            trade(1, "ETH", .BUY, "0.5", "100", 1),
            trade(2, "ETH", .BUY, "1.5", nil, 2),
        ], currentPrice: 150)
        XCTAssertTrue(p.priceMissing)
        XCTAssertTrue(p.avgCostPartial)
        XCTAssertEqual(p.pricedHoldingsExact, dec("0.5"))
        // «Seit Kauf» des Teils mit Kaufpreis: 0.5 × 150 − 50 — wie Android
        XCTAssertEqual(p.unrealizedExact, dec("25"))
        XCTAssertEqual(p.unpricedHoldingsExact, dec("1.5"))
        let full = PortfolioCalculator.position("ETH", trades: [trade(1, "ETH", .BUY, "2", "100", 1)], currentPrice: 150)
        XCTAssertFalse(full.avgCostPartial)
        XCTAssertEqual(full.pricedHoldingsExact, dec("2"))
        let none = PortfolioCalculator.position("ETH", trades: [trade(1, "ETH", .BUY, "2", nil, 1)], currentPrice: 150)
        XCTAssertFalse(none.avgCostPartial)
    }

    func testDivisionsRoundToThirtyFourSignificantDigits() {
        XCTAssertEqual(PortfolioDecimal.divide(31, 3), dec("10.33333333333333333333333333333333"))
        XCTAssertEqual(PortfolioDecimal.divide(2, 3), dec("0.6666666666666666666666666666666667"))
        // Halbe auf gerade wie MathContext.DECIMAL128
        XCTAssertEqual(PortfolioDecimal.roundSignificant(dec("1.0000000000000000000000000000000025")),
                       dec("1.000000000000000000000000000000002"))
        XCTAssertEqual(PortfolioDecimal.roundSignificant(dec("1.0000000000000000000000000000000035")),
                       dec("1.000000000000000000000000000000004"))
        // Kürzere Werte unverändert
        XCTAssertEqual(PortfolioDecimal.roundSignificant(dec("0.00000001")), dec("0.00000001"))
        XCTAssertEqual(PortfolioDecimal.roundSignificant(0), 0)
        let p = PortfolioCalculator.position("BTC", trades: [
            trade(1, "BTC", .BUY, "3", "10", 1, fee: "1", unit: .QUOTE),
            trade(2, "BTC", .SELL, "1", "20", 2),
        ], currentPrice: 15)
        XCTAssertEqual(p.costBasisExact, dec("20.66666666666666666666666666666667"))
        XCTAssertEqual(p.realizedExact, dec("9.66666666666666666666666666666667"))
    }
    func testFieldsPerType() {
        XCTAssertEqual(PortfolioTxForm.types.count, 9)
        XCTAssertEqual(PortfolioTxForm.types.last, .SWAP)
        let transfer = PortfolioTxForm.fields(.TRANSFER)
        XCTAssertFalse(transfer.price); XCTAssertTrue(transfer.route); XCTAssertTrue(transfer.coinFeeOnly)
        XCTAssertFalse(transfer.exchange)
        let reward = PortfolioTxForm.fields(.REWARD)
        XCTAssertTrue(reward.price); XCTAssertFalse(reward.feeWithUnit); XCTAssertFalse(reward.coinFeeOnly)
        XCTAssertFalse(reward.thirdCoinFee)
        XCTAssertTrue(PortfolioTxForm.fields(.WITHDRAWAL).coinFeeOnly)
        XCTAssertTrue(PortfolioTxForm.fields(.BUY).feeWithUnit)
        XCTAssertEqual(PortfolioTxForm.feeUnit(for: .WITHDRAWAL, chosen: .QUOTE), .COIN)
        XCTAssertEqual(PortfolioTxForm.feeUnit(for: .BUY, chosen: .QUOTE), .QUOTE)
    }

    func testOutflowWarningForSellWithdrawalAndTransfer() {
        let h = dec("1")
        XCTAssertTrue(PortfolioTxForm.outflowExceeds(type: .WITHDRAWAL, amount: dec("1"), fee: dec("0.1"), unit: .QUOTE, holdings: h))
        XCTAssertTrue(PortfolioTxForm.outflowExceeds(type: .TRANSFER, amount: dec("1.1"), fee: nil, unit: .QUOTE, holdings: h))
        XCTAssertFalse(PortfolioTxForm.outflowExceeds(type: .TRANSFER, amount: dec("1"), fee: nil, unit: .QUOTE, holdings: h))
        XCTAssertFalse(PortfolioTxForm.outflowExceeds(type: .REWARD, amount: dec("5"), fee: nil, unit: .QUOTE, holdings: h))
        XCTAssertFalse(PortfolioTxForm.outflowExceeds(type: .SELL, amount: dec("1"), fee: dec("1"), unit: .QUOTE, holdings: h))
    }

    func testThirdCoinFeeStates() {
        XCTAssertEqual(PortfolioTxForm.thirdFee(feeCoin: "", coin: "BTC", amount: nil, amountBlank: true, price: nil, priceBlank: true), .none)
        XCTAssertEqual(PortfolioTxForm.thirdFee(feeCoin: "bnb", coin: "BTC", amount: dec("0.1"), amountBlank: false, price: nil, priceBlank: true), .incomplete)
        XCTAssertEqual(PortfolioTxForm.thirdFee(feeCoin: "BNB", coin: "BTC", amount: dec("0.1"), amountBlank: false, price: dec("600"), priceBlank: false), .complete)
        XCTAssertEqual(PortfolioTxForm.thirdFee(feeCoin: "BTC", coin: "btc", amount: dec("0.1"), amountBlank: false, price: nil, priceBlank: true), .invalid)
        XCTAssertEqual(PortfolioTxForm.thirdFee(feeCoin: "BNB", coin: "BTC", amount: nil, amountBlank: false, price: nil, priceBlank: true), .invalid)
        XCTAssertEqual(PortfolioTxForm.thirdFee(feeCoin: "BNB", coin: "BTC", amount: dec("0.1"), amountBlank: false, price: nil, priceBlank: false), .invalid)
        XCTAssertEqual(PortfolioTxForm.thirdFee(feeCoin: "", coin: "BTC", amount: dec("0.1"), amountBlank: false, price: nil, priceBlank: true), .invalid)
    }

    func testPrefillRules() {
        XCTAssertTrue(PortfolioTxForm.mayPrefillFeeCoinPrice(isEdit: false, isToday: true, priceBlank: true))
        XCTAssertFalse(PortfolioTxForm.mayPrefillFeeCoinPrice(isEdit: false, isToday: false, priceBlank: true))
        XCTAssertTrue(PortfolioTxForm.mayPrefillHistoricClose(type: .REWARD, isEdit: false, isToday: false, priceBlank: true))
        XCTAssertTrue(PortfolioTxForm.mayPrefillHistoricClose(type: .DEPOSIT, isEdit: false, isToday: false, priceBlank: true))
        XCTAssertFalse(PortfolioTxForm.mayPrefillHistoricClose(type: .BUY, isEdit: false, isToday: false, priceBlank: true))
        XCTAssertFalse(PortfolioTxForm.mayPrefillHistoricClose(type: .REWARD, isEdit: true, isToday: false, priceBlank: true))
        XCTAssertFalse(PortfolioTxForm.mayPrefillHistoricClose(type: .REWARD, isEdit: false, isToday: true, priceBlank: true))
    }

    func testSwapAndFiatEntry() {
        XCTAssertTrue(PortfolioTxForm.swapToCoinValid("eth", coin: "BTC"))
        XCTAssertFalse(PortfolioTxForm.swapToCoinValid("btc", coin: "BTC"))
        XCTAssertFalse(PortfolioTxForm.swapToCoinValid("", coin: "BTC"))
        XCTAssertFalse(PortfolioTxForm.swapToCoinValid("ET-H", coin: "BTC"))
        XCTAssertTrue(PortfolioTxForm.feeInReceivedCoin(type: .SWAP, feeCoin: "eth", toCoin: "ETH"))
        XCTAssertFalse(PortfolioTxForm.feeInReceivedCoin(type: .SWAP, feeCoin: "BNB", toCoin: "ETH"))
        XCTAssertFalse(PortfolioTxForm.feeInReceivedCoin(type: .BUY, feeCoin: "ETH", toCoin: "ETH"))
        // 1’000 CHF bei USD → CHF 0.8 = 1’250 USDT; zurück wieder 1’000
        XCTAssertEqual(PortfolioTxForm.fiatToUsdt(dec("1000"), usdToFiat: dec("0.8")), dec("1250"))
        XCTAssertEqual(PortfolioTxForm.usdtToFiat(dec("1250"), usdToFiat: dec("0.8")), dec("1000"))
        XCTAssertNil(PortfolioTxForm.fiatToUsdt(dec("1000"), usdToFiat: nil))
        XCTAssertNil(PortfolioTxForm.fiatToUsdt(dec("1000"), usdToFiat: 0))
        XCTAssertNil(PortfolioTxForm.fiatToUsdt(nil, usdToFiat: dec("0.8")))
        // USDT zum Marktkurs (wie CSV-Import und Export): Faktor = Devisenkurs × USDT/USD; 898.2 CHF bei 0.9 × 0.998 = 1000 USDT
        let rate = PortfolioTxForm.entryRate(usdToFiat: dec("0.9"), usdtUsd: dec("0.998"))
        XCTAssertEqual(rate, dec("0.8982"))
        XCTAssertEqual(PortfolioTxForm.fiatToUsdt(dec("898.2"), usdToFiat: rate), dec("1000"))
        // ohne bzw. unplausibler USDT/USD-Kurs: × 1; ohne Devisenkurs: keiner
        XCTAssertEqual(PortfolioTxForm.entryRate(usdToFiat: dec("0.9"), usdtUsd: nil), dec("0.9"))
        XCTAssertEqual(PortfolioTxForm.entryRate(usdToFiat: dec("0.9"), usdtUsd: dec("3")), dec("0.9"))
        XCTAssertNil(PortfolioTxForm.entryRate(usdToFiat: nil, usdtUsd: dec("0.998")))
    }

    func testWalletHoldingsFromOpenLots() {
        var a = trade(1, "BTC", .BUY, "1", "100", 1); a.exchange = "Binance"
        var b = trade(2, "BTC", .BUY, "0.5", "100", 2); b.exchange = "binance "
        let c = trade(3, "BTC", .BUY, "0.25", nil, 3)
        let lots = PortfolioCalculator.openLots("BTC", trades: [a, b, c], method: .FIFO_WALLET)
        XCTAssertEqual(PortfolioTxForm.walletHoldings(lots, wallet: "BINANCE"), dec("1.5"))
        XCTAssertEqual(PortfolioTxForm.walletHoldings(lots, wallet: nil), dec("0.25"))
        XCTAssertEqual(PortfolioTxForm.walletHoldings(lots, wallet: ""), dec("0.25"))
        XCTAssertEqual(PortfolioTxForm.walletHoldings(lots, wallet: "Kraken"), 0)
    }

    /// Gebühr ≥ erhaltene Menge sperrt das Speichern — wie `feeConsumingTheReceivedAmountBlocksSaving` (Android).
    func testFeeConsumingTheReceivedAmountBlocksSaving() {
        func f(_ type: PortfolioTxType, _ amount: String?, _ fee: String?, _ unit: PortfolioFeeUnit,
               _ toAmount: String? = nil, _ received: String? = nil) -> Bool {
            PortfolioTxForm.feeConsumesReceived(type: type, amount: amount.map(dec), fee: fee.map(dec), unit: unit,
                                                toAmount: toAmount.map(dec), receivedCoinFee: received.map(dec))
        }
        XCTAssertTrue(f(.BUY, "0.001", "0.001", .COIN))
        XCTAssertTrue(f(.BUY, "0.001", "0.002", .COIN))
        XCTAssertFalse(f(.BUY, "0.001", "0.0009", .COIN))
        XCTAssertFalse(f(.BUY, "0.001", "5", .QUOTE)) // USDT-Gebühr
        XCTAssertTrue(f(.DEPOSIT, "1", "1", .COIN))
        XCTAssertTrue(f(.REWARD, "1", "1", .COIN))
        XCTAssertFalse(f(.BUY, nil, "1", .COIN))
        // Abgänge: Gebühr über der Menge ist keine Sperre (Bestandswarnung)
        XCTAssertFalse(f(.SELL, "1", "2", .COIN))
        XCTAssertFalse(f(.WITHDRAWAL, "1", "2", .COIN))
        // Tausch: Gebühr im erhaltenen Coin gegen die erhaltene Menge
        XCTAssertTrue(f(.SWAP, "1", nil, .QUOTE, "0.01", "0.01"))
        XCTAssertFalse(f(.SWAP, "1", nil, .QUOTE, "0.01", "0.005"))
        XCTAssertFalse(f(.SWAP, "1", nil, .QUOTE, nil, "0.005"))
        XCTAssertFalse(f(.SWAP, "1", nil, .QUOTE, "0.01", nil))
    }

    /// Preis 0 ausdrücklich eingegeben: nur bei einer importierten Transaktion abgelehnt (Kennzeichen der Gebühr-Zeile); von
    /// Hand erfasst ist 0 ein echter Preis (Build 47); leer und > 0 sind erlaubt. Wie Android.
    func testPriceZeroIsRejectedOnlyForImported() {
        XCTAssertTrue(PortfolioTxForm.priceZero(dec("0"), imported: true))
        XCTAssertTrue(PortfolioTxForm.priceZero(dec("0.000"), imported: true))
        XCTAssertFalse(PortfolioTxForm.priceZero(nil, imported: true))
        XCTAssertFalse(PortfolioTxForm.priceZero(dec("0.0001"), imported: true))
        XCTAssertFalse(PortfolioTxForm.priceZero(dec("95000"), imported: true))
        // Von Hand: 0 erlaubt
        XCTAssertFalse(PortfolioTxForm.priceZero(dec("0"), imported: false))
        XCTAssertFalse(PortfolioTxForm.priceZero(dec("0.000"), imported: false))
    }

    /// Verschenkt und Damit bezahlt (Build 47): nach der Auszahlung; Verschenkt mit den Feldern der Auszahlung, Damit bezahlt
    /// mit Preisfeld (Marktwert) und Gebühren wie ein Verkauf. Wie Android.
    func testGiftAndPaymentFieldsAndOrder() {
        let types = PortfolioTxForm.types
        XCTAssertEqual(types.firstIndex(of: .GIFT), types.firstIndex(of: .WITHDRAWAL).map { $0 + 1 })
        XCTAssertEqual(types.firstIndex(of: .PAYMENT), types.firstIndex(of: .GIFT).map { $0 + 1 })
        XCTAssertEqual(types.count, PortfolioTxType.allCases.count)
        XCTAssertEqual(PortfolioTxForm.fields(.WITHDRAWAL), PortfolioTxForm.fields(.GIFT))
        XCTAssertTrue(PortfolioTxForm.mayPrefillHistoricClose(type: .PAYMENT, isEdit: false, isToday: false, priceBlank: true))
        let f = PortfolioTxForm.fields(.PAYMENT)
        XCTAssertTrue(f.price)
        XCTAssertTrue(f.feeWithUnit)
        XCTAssertTrue(f.thirdCoinFee)
        XCTAssertTrue(f.outflow)
        XCTAssertFalse(f.route)
        XCTAssertTrue(PortfolioTxForm.mayPrefillHistoricClose(type: .GIFT, isEdit: false, isToday: false, priceBlank: true))
        XCTAssertFalse(PortfolioTxForm.mayPrefillHistoricClose(type: .GIFT, isEdit: true, isToday: false, priceBlank: true))
        XCTAssertFalse(PortfolioTxForm.mayPrefillHistoricClose(type: .SELL, isEdit: false, isToday: false, priceBlank: true))
    }
}
