import XCTest
@testable import CryptoChecker

/// Build 46: Umbuchung, Belohnung, Ein-/Auszahlung und Gebühr in einem dritten Coin (`PortfolioLedger`) —
/// wie `PortfolioTxTypesTest.kt`; gemeinsame Fälle in `Parity/portfolio.json`.
final class PortfolioTxTypesTests: XCTestCase {

    private func dec(_ text: String) -> Decimal { Decimal(string: text, locale: Locale(identifier: "en_US_POSIX"))! }

    private let day: Int64 = 86_400_000

    private func tx(_ id: Int64, _ coin: String, _ type: PortfolioTxType, _ amount: String, _ price: String?, _ time: Int64,
                    fee: String? = nil, unit: PortfolioFeeUnit? = nil,
                    feeCoin: String? = nil, feeAmount: String? = nil, feePrice: String? = nil) -> PortfolioTx {
        var t = PortfolioTx(id: id, coin: coin, type: type, amountExact: dec(amount), priceExact: price.map(dec),
                            time: time, feeExact: fee.map(dec), feeUnit: unit)
        t.feeCoin = feeCoin
        t.feeCoinAmountExact = feeAmount.map(dec)
        t.feeCoinPriceExact = feePrice.map(dec)
        return t
    }

    /// Double-Listen mit Toleranz (NSDecimalNumber → Double kann um 1 ulp abweichen).
    private func close(_ actual: [Double], _ expected: [Double], file: StaticString = #filePath, line: UInt = #line) {
        XCTAssertEqual(actual.count, expected.count, file: file, line: line)
        for (a, e) in zip(actual, expected) { XCTAssertEqual(a, e, accuracy: 1e-9, file: file, line: line) }
    }

    func testTypeNamesStayStable() {
        XCTAssertEqual(PortfolioTxType.allCases.map(\.rawValue),
                       ["BUY", "SELL", "TRANSFER", "REWARD", "DEPOSIT", "WITHDRAWAL", "SWAP", "GIFT", "PAYMENT"])
        // Build 47: «Verschenkt» und «Damit bezahlt» als Text gespeichert (neue Fälle am Ende, alte Daten unverändert)
        XCTAssertEqual(PortfolioTxType(rawValue: "GIFT"), .GIFT)
        XCTAssertEqual(PortfolioTxType(rawValue: "PAYMENT"), .PAYMENT)
        XCTAssertTrue(PortfolioTxType.GIFT.isOutflow && PortfolioTxType.GIFT.isWithdrawalLike && !PortfolioTxType.GIFT.isSaleLike)
        XCTAssertTrue(PortfolioTxType.PAYMENT.isOutflow && PortfolioTxType.PAYMENT.isSaleLike && !PortfolioTxType.PAYMENT.isInflow)
        XCTAssertNil(PortfolioTxType(rawValue: "STAKE"))
        XCTAssertTrue(PortfolioTxType.DEPOSIT.isInflow)
        XCTAssertTrue(PortfolioTxType.WITHDRAWAL.isOutflow)
        XCTAssertFalse(PortfolioTxType.TRANSFER.isInflow || PortfolioTxType.TRANSFER.isOutflow)
    }

    func testThirdCoinFeeOnlyForAnotherCoinWithAmount() {
        XCTAssertNil(tx(1, "BNB", .BUY, "1", "300", 1, feeCoin: "bnb", feeAmount: "0.1").thirdFeeCoin)
        XCTAssertNil(tx(1, "BTC", .BUY, "1", "300", 1, feeCoin: "BNB").thirdFeeCoin)
        let usdt = tx(1, "BTC", .BUY, "1", "300", 1, feeCoin: " usdt ", feeAmount: "2")
        XCTAssertEqual(usdt.thirdFeeCoin, "USDT")
        // USDT gilt immer 1 — auch ohne erfassten Preis
        XCTAssertEqual(usdt.thirdFeeValue, dec("2"))
        XCTAssertNil(tx(1, "BTC", .BUY, "1", "300", 1, feeCoin: "BNB", feeAmount: "0.1").thirdFeeValue)
    }

    func testTransferKeepsCostBasisAndRaisesAverage() {
        let trades = [tx(1, "BTC", .BUY, "1", "100", day),
                      tx(2, "BTC", .TRANSFER, "1", nil, 2 * day, fee: "0.25", unit: .COIN)]
        let p = PortfolioCalculator.position("BTC", trades: trades, currentPrice: 100)
        XCTAssertEqual(p.holdingsExact, dec("0.75"))
        XCTAssertEqual(p.costBasisExact, dec("100"))
        XCTAssertEqual(p.avgCost ?? 0, 133.33333333333334, accuracy: 1e-9)
        XCTAssertEqual(p.realizedExact, 0)
        XCTAssertEqual(p.soldAmount, 0)
        XCTAssertFalse(p.oversold)
        XCTAssertEqual(p.tradeCount, 2)
    }

    func testWithdrawnCoinStaysVisibleAsClosed() {
        let trades = [tx(1, "ADA", .DEPOSIT, "100", "0.5", day), tx(2, "ADA", .WITHDRAWAL, "100", nil, 2 * day)]
        let s = PortfolioCalculator.summarize(trades, prices: ["ADA": 0.6])
        XCTAssertTrue(s.open.isEmpty)
        XCTAssertEqual(s.closed.map(\.coin), ["ADA"])
        XCTAssertEqual(s.closed.first?.withdrawnAmount, 100)
        XCTAssertEqual(s.realizedExact, 0)
        XCTAssertFalse(s.realizedIncomplete)
    }

    func testFeeOnlyCoinBecomesPosition() {
        var trades = [tx(1, "BNB", .DEPOSIT, "1", nil, day),
                      tx(2, "BTC", .BUY, "1", "100", 2 * day, feeCoin: "BNB", feeAmount: "0.1", feePrice: "300")]
        let s = PortfolioCalculator.summarize(trades, prices: ["BTC": 100, "BNB": 300])
        let bnb = s.open.first { $0.coin == "BNB" }
        XCTAssertEqual(bnb?.holdingsExact, dec("0.9"))
        // Gebühr aus dem Teil ohne Kaufpreis: Gewinn unbekannt
        XCTAssertEqual(bnb?.realizedIncomplete, true)
        XCTAssertEqual(s.open.first { $0.coin == "BTC" }?.costBasisExact, dec("130"))
        XCTAssertTrue(s.feeValueMissingCoins.isEmpty)
        trades[1] = tx(2, "BTC", .BUY, "1", "100", 2 * day, feeCoin: "BNB", feeAmount: "0.1")
        XCTAssertEqual(PortfolioCalculator.summarize(trades, prices: [:]).feeValueMissingCoins, ["BTC"])
    }

    func testTimelineAndCutoffIncludeAllTypesAndFeeCoins() {
        let trades = [tx(1, "BNB", .BUY, "2", "300", day),
                      tx(2, "ETH", .REWARD, "1", nil, 2 * day),
                      tx(3, "ETH", .TRANSFER, "1", nil, 3 * day, fee: "0.01", unit: .COIN, feeCoin: "BNB", feeAmount: "0.5"),
                      tx(4, "ETH", .WITHDRAWAL, "0.49", nil, 4 * day)]
        let eth = PortfolioHistory.holdingsTimeline("ETH", trades: trades)
        XCTAssertEqual(eth.map(\.time), [2 * day, 3 * day, 4 * day])
        close(eth.map(\.holdings), [1.0, 0.99, 0.5])
        close(PortfolioHistory.holdingsTimeline("BNB", trades: trades).map(\.holdings), [2.0, 1.5])
        let cut = CutoffExport.holdingsAt(trades, cutoffMillis: 3 * day)
        XCTAssertEqual(cut.map(\.coin), ["BNB", "ETH"])
        XCTAssertEqual(cut.first?.amount, dec("1.5"))
        XCTAssertEqual(cut.last?.amount, dec("0.99"))
    }

    func testLedgerFlowsPerType() {
        let buy = tx(1, "SOL", .BUY, "2", "10", day, fee: "1", unit: .QUOTE, feeCoin: "BNB", feeAmount: "0.01", feePrice: "300")
        XCTAssertEqual(PortfolioLedger.flow(PortfolioLedgerEvent(trade: buy, feeLeg: false), holdingsBefore: 0) ?? 0,
                       24, accuracy: 1e-12)
        let leg = PortfolioLedgerEvent(trade: buy, feeLeg: true)
        XCTAssertEqual(PortfolioLedger.flow(leg, holdingsBefore: 1) ?? 0, -3, accuracy: 1e-12)
        XCTAssertEqual(PortfolioLedger.flow(leg, holdingsBefore: 0), 0)
        let transfer = tx(2, "SOL", .TRANSFER, "2", nil, 2 * day, fee: "0.5", unit: .QUOTE)
        XCTAssertEqual(PortfolioLedger.flow(PortfolioLedgerEvent(trade: transfer, feeLeg: false), holdingsBefore: 2) ?? 0,
                       0.5, accuracy: 1e-12)
        // Auszahlung ohne Preis zum Marktkurs (nicht zum Einstand): kein Gewinn/Verlust aus dem Abgang
        let out = PortfolioLedgerEvent(trade: tx(3, "SOL", .WITHDRAWAL, "1", nil, 3 * day), feeLeg: false)
        XCTAssertTrue(PortfolioLedger.needsMarketPrice(out))
        XCTAssertEqual(PortfolioLedger.flow(out, holdingsBefore: 2, marketPrice: 20) ?? 0, -20, accuracy: 1e-12)
        XCTAssertNil(PortfolioLedger.flow(out, holdingsBefore: 2))
        // Belohnung ohne Preis: zum Marktkurs (neutral wie eine Einzahlung ohne Preis), ohne Marktkurs unbekannt
        let reward = PortfolioLedgerEvent(trade: tx(4, "SOL", .REWARD, "1", nil, 4 * day), feeLeg: false)
        XCTAssertTrue(PortfolioLedger.needsMarketPrice(reward))
        XCTAssertEqual(PortfolioLedger.flow(reward, holdingsBefore: 1, marketPrice: 20) ?? 0, 20, accuracy: 1e-12)
        XCTAssertNil(PortfolioLedger.flow(reward, holdingsBefore: 1))
        // Kauf ohne Preis bleibt unbekannt (kein Marktkurs)
        let unpricedBuy = PortfolioLedgerEvent(trade: tx(6, "SOL", .BUY, "1", nil, 4 * day), feeLeg: false)
        XCTAssertFalse(PortfolioLedger.needsMarketPrice(unpricedBuy))
        XCTAssertNil(PortfolioLedger.flow(unpricedBuy, holdingsBefore: 1, marketPrice: 20))
        // Gebühr im dritten Coin ohne Preis: Gebühr-Abgang und Haupttransaktion zum Marktkurs des Gebühr-Coins
        let noFeePrice = tx(7, "SOL", .BUY, "2", "10", day, feeCoin: "BNB", feeAmount: "0.01")
        let noFeeLeg = PortfolioLedgerEvent(trade: noFeePrice, feeLeg: true)
        XCTAssertTrue(PortfolioLedger.needsMarketPrice(noFeeLeg))
        XCTAssertEqual(PortfolioLedger.flow(noFeeLeg, holdingsBefore: 1, marketPrice: 300) ?? 0, -3, accuracy: 1e-12)
        XCTAssertNil(PortfolioLedger.flow(noFeeLeg, holdingsBefore: 1))
        let noFeeMain = PortfolioLedgerEvent(trade: noFeePrice, feeLeg: false)
        XCTAssertTrue(PortfolioLedger.needsFeeMarketPrice(noFeeMain))
        XCTAssertFalse(PortfolioLedger.needsFeeMarketPrice(PortfolioLedgerEvent(trade: buy, feeLeg: false)))
        XCTAssertEqual(PortfolioLedger.flow(noFeeMain, holdingsBefore: 0, feeMarketPrice: 300) ?? 0, 23, accuracy: 1e-12)
        XCTAssertNil(PortfolioLedger.flow(noFeeMain, holdingsBefore: 0))
        // Adress-Änderung (negative Id) zum Marktkurs
        let address = PortfolioLedgerEvent(trade: tx(-5, "SOL", .DEPOSIT, "0.5", nil, 4 * day), feeLeg: false)
        XCTAssertTrue(PortfolioLedger.needsMarketPrice(address))
        XCTAssertEqual(PortfolioLedger.flow(address, holdingsBefore: 1, marketPrice: 20) ?? 0, 10, accuracy: 1e-12)
    }

    func testHistoryCashFlowsCoverRewardsTransferFeesAndFeeCoins() {
        let dayEnd: (Int) -> Int64 = { d in Int64(d + 1) * 86_400_000 - 1 }
        let today = 20_000
        var dot: [Int: Double] = [:]
        var bnb: [Int: Double] = [:]
        for d in (today - 30)...today { dot[d] = 10; bnb[d] = 300 }
        let closes = ["DOT": dot, "BNB": bnb]
        let live = ["DOT": 10.0, "BNB": 300.0]
        // Zeiten mit festen Typen (lange Literale mit Rechnungen sind für den Type-Checker teuer)
        let buyTime: Int64 = Int64(today - 20) * day
        let twoDaysAgo: Int64 = Int64(today - 2) * day + 1
        let oneDayAgo: Int64 = Int64(today - 1) * day + 1
        let base: [PortfolioTx] = [tx(1, "BNB", .BUY, "1", "300", buyTime),
                                   tx(2, "DOT", .BUY, "1", "10", buyTime)]
        let flowTxs: [PortfolioTx] = [
            tx(3, "DOT", .REWARD, "1", "10", twoDaysAgo),
            tx(4, "DOT", .TRANSFER, "2", nil, oneDayAgo, feeCoin: "BNB", feeAmount: "0.01", feePrice: "300"),
        ]
        let withFlows: [PortfolioTx] = base + flowTxs
        let s = PortfolioHistory.build(trades: withFlows, closes: closes, livePrices: live, range: .week,
                                       todayEpochDay: today, dayEndMillis: dayEnd)
        let flows = (s.cashFlows ?? []).sorted { $0.epochDay != $1.epochDay ? $0.epochDay < $1.epochDay : $0.amount < $1.amount }
        XCTAssertEqual(flows.map(\.epochDay), [today - 2, today - 1, today - 1])
        close(flows.map(\.amount), [10, -3, 3])
        // Belohnung ist Zufluss, die Gebühr der Umbuchung (0.01 BNB × 300) bleibt als Kosten: netto −3
        XCTAssertEqual(s.netChange ?? 0, -3, accuracy: 1e-9)

        // Belohnung ohne Wert: zum Tagesschluss (10) — der Coin bleibt drin
        let unpricedReward: [PortfolioTx] = base + [tx(3, "DOT", .REWARD, "1", nil, twoDaysAgo)]
        let rewardFlows = PortfolioHistory.build(trades: unpricedReward, closes: closes, livePrices: live, range: .week,
                                                 todayEpochDay: today, dayEndMillis: dayEnd).cashFlows
        XCTAssertEqual(rewardFlows, [PortfolioCashFlow(epochDay: today - 2, amount: 10)])
        // Kauf ohne Preis bleibt unbekannt
        let unknown: [PortfolioTx] = base + [tx(3, "DOT", .BUY, "1", nil, twoDaysAgo)]
        XCTAssertNil(PortfolioHistory.build(trades: unknown, closes: closes, livePrices: live, range: .week,
                                            todayEpochDay: today, dayEndMillis: dayEnd).cashFlows)
    }

    func testRouteFollowsLatestTransferDestination() {
        let r = PortfolioExchangeRoute.exchangeByLedger([
            .init(coin: "BTC", exchange: "Binance", type: .BUY, transferTo: nil, time: 1, id: 1),
            .init(coin: "BTC", exchange: nil, type: .TRANSFER, transferTo: "Kraken", time: 2, id: 2),
            .init(coin: "ETH", exchange: "Kraken", type: .BUY, transferTo: nil, time: 1, id: 3),
            .init(coin: "ETH", exchange: nil, type: .TRANSFER, transferTo: "Ledger Nano", time: 2, id: 4),
        ])
        XCTAssertEqual(r.single, ["BTC": "Kraken", "ETH": "Ledger Nano"])
        XCTAssertTrue(r.mixed.isEmpty)
        XCTAssertNil(PortfolioExchangeRoute.market("Ledger Nano", markets: ["Kraken": "Kraken", "Binance": "Binance"]))
    }

    func testCodableKeepsNewFieldsAndDropsInvalidFee() throws {
        var t = tx(5, "BTC", .TRANSFER, "0.5", nil, 1_000, fee: "0.0001", unit: .COIN,
                   feeCoin: "BNB", feeAmount: "0.00000001", feePrice: "612.5")
        t.transferFrom = "Binance"
        t.transferTo = "Kraken"
        let back = try JSONDecoder().decode(PortfolioTx.self, from: JSONEncoder().encode(t))
        XCTAssertEqual(back, t)
        // Gleicher Coin wie die Transaktion: keine Gebühr im dritten Coin
        var same = t
        same.feeCoin = "btc"
        let read = try JSONDecoder().decode(PortfolioTx.self, from: JSONEncoder().encode(same))
        XCTAssertNil(read.feeCoin)
        XCTAssertNil(read.feeCoinAmountExact)
        XCTAssertNil(read.feeCoinPriceExact)
        // Unbekannte Art (neuere Version): Eintrag wird verworfen und gezählt, die Datei bleibt lesbar
        let json = #"{"transactions":[{"id":1,"coin":"BTC","type":"LOAN","amountText":"1","time":1},"#
            + #"{"id":2,"coin":"BTC","type":"REWARD","amountText":"1","time":2}],"nextId":3}"#
        let file = try JSONDecoder().decode(PortfolioFile.self, from: Data(json.utf8))
        XCTAssertEqual(file.transactions.map(\.type), [.REWARD])
        XCTAssertEqual(file.dropped, 1)
    }

    /// Build 58: Schenkung an Ehe-/Lebenspartner als eigenes Feld — übersteht Datei und Sicherung. Wie Android.
    func testSpouseGiftFlagRoundTrips() throws {
        var gift = PortfolioTx(id: 8, coin: "BTC", type: .GIFT, amountExact: dec("0.1"), time: 12, exchange: "Ledger")
        gift.giftToSpouse = true
        let back = try JSONDecoder().decode(PortfolioTx.self, from: JSONEncoder().encode(gift))
        XCTAssertEqual(back, gift)
        XCTAssertNil(back.transferTo)
        XCTAssertTrue(back.isGiftToSpouse)
        let json = BackupManager.txToJson(gift)
        XCTAssertEqual(json["giftToSpouse"] as? Bool, true)
        XCTAssertEqual(BackupManager.jsonToTx(json), gift)
        // Bei einer anderen Art gibt es den Merker nicht (Datei bzw. Sicherung von aussen)
        var withdrawal = gift
        withdrawal.type = .WITHDRAWAL
        XCTAssertFalse(withdrawal.isGiftToSpouse)
        XCTAssertFalse(try JSONDecoder().decode(PortfolioTx.self, from: JSONEncoder().encode(withdrawal)).giftToSpouse)
        var otherJson = json
        otherJson["type"] = "WITHDRAWAL"
        XCTAssertEqual(BackupManager.jsonToTx(otherJson)?.giftToSpouse, false)
    }

    func testOriginalPurchaseDateRoundTripsOnlyForDeposits() throws {
        var deposit = PortfolioTx(id: 4, coin: "BTC", type: .DEPOSIT, amountExact: 1, time: 9)
        deposit.acquiredTime = 3
        let back = try JSONDecoder().decode(PortfolioTx.self, from: JSONEncoder().encode(deposit))
        XCTAssertEqual(back.acquiredTime, 3)
        XCTAssertEqual(BackupManager.jsonToTx(BackupManager.txToJson(deposit))?.acquiredTime, 3)
        // Andere Arten: verworfen
        var buy = deposit
        buy.type = .BUY
        XCTAssertNil(try JSONDecoder().decode(PortfolioTx.self, from: JSONEncoder().encode(buy)).acquiredTime)
        XCTAssertNil(BackupManager.jsonToTx(BackupManager.txToJson(buy))?.acquiredTime)
        XCTAssertNil(PortfolioTx.cleanAcquiredTime(.DEPOSIT, 0))
        // Ältere Sicherung ohne Feld
        var old = BackupManager.txToJson(deposit)
        old.removeValue(forKey: "acquiredTime")
        XCTAssertNil(BackupManager.jsonToTx(old)?.acquiredTime)
    }

    /// Tausch und Importlauf in Datei und Sicherung — wie `swapAndImportBatchRoundTripExactly` (Kotlin).
    func testSwapAndImportBatchRoundTrip() throws {
        var swap = PortfolioTx(id: 7, coin: "BTC", type: .SWAP, amountExact: Decimal(string: "0.01")!, time: 11,
                               exchange: "Kraken")
        swap.toCoin = "ETH"
        swap.toAmountExact = Decimal(string: "0.25000001")!
        swap.swapValueExact = Decimal(string: "600.5")!
        swap.feeCoin = "ETH"
        swap.feeCoinAmountExact = Decimal(string: "0.001")!
        swap.importBatch = "RELAI:5:0000abcd"
        let back = try JSONDecoder().decode(PortfolioTx.self, from: JSONEncoder().encode(swap))
        XCTAssertEqual(back, swap)
        XCTAssertEqual(BackupManager.jsonToTx(BackupManager.txToJson(swap)), swap)
        // Gebühr im erhaltenen Coin ist keine dritte Gebühr; Tausch geht in beide Coins
        XCTAssertNil(swap.thirdFeeCoin)
        XCTAssertEqual(swap.swapReceived, Decimal(string: "0.24900001")!)
        XCTAssertEqual(swap.coins, ["BTC", "ETH"])
        XCTAssertEqual(swap.routeEntries.map(\.coin), ["BTC", "ETH"])
        // Ohne erhaltenen Coin oder Menge: ungültig (gezählt)
        var broken = BackupManager.txToJson(swap)
        broken["toCoin"] = NSNull()
        XCTAssertNil(BackupManager.jsonToTx(broken))
        var sameCoin = BackupManager.txToJson(swap)
        sameCoin["toCoin"] = "btc"
        XCTAssertNil(BackupManager.jsonToTx(sameCoin))
        let json = #"{"transactions":[{"id":1,"coin":"BTC","type":"SWAP","amountText":"1","time":1},"#
            + #"{"id":2,"coin":"BTC","type":"SWAP","amountText":"1","time":2,"toCoin":"ETH","toAmountText":"3"}],"nextId":3}"#
        let file = try JSONDecoder().decode(PortfolioFile.self, from: Data(json.utf8))
        XCTAssertEqual(file.transactions.map(\.id), [2])
        XCTAssertEqual(file.dropped, 1)
        XCTAssertNil(file.transactions.first?.swapValueExact)
        // Zu lange Kennung des Importlaufs: weggelassen
        var long = BackupManager.txToJson(swap)
        long["importBatch"] = String(repeating: "x", count: 65)
        XCTAssertNil(BackupManager.jsonToTx(long)?.importBatch)
    }

    /// Build 47: Damit bezahlt = Veräusserung zum Marktwert in allen Methoden; Abfluss wie ein Verkauf. Wie Android.
    func testPaymentIsDisposalAtMarketValueInEveryMethod() {
        let trades = [tx(1, "BTC", .BUY, "1", "100", day), tx(2, "BTC", .PAYMENT, "0.4", "300", 2 * day)]
        for m in [CostMethod.FIFO, .FIFO_WALLET, .AVERAGE] {
            let l = PortfolioCalculator.ledger("BTC", trades: trades, currentPrice: 300, method: m)
            XCTAssertEqual(l.position.holdingsExact, dec("0.6"), "\(m)")
            // Erlös 0.4 × 300 = 120 − Einstand 40 = 80 (kein Geld erhalten, trotzdem realisiert)
            XCTAssertEqual(l.position.realizedExact, dec("80"), "\(m)")
            XCTAssertTrue(!l.disposals.isEmpty && l.disposals.allSatisfy { $0.payment && !$0.gift && !$0.valueEstimated }, "\(m)")
        }
        let e = PortfolioLedger.events("BTC", trades: trades)
        XCTAssertFalse(PortfolioLedger.needsMarketPrice(e[1]))
        XCTAssertEqual(PortfolioLedger.flow(e[1], holdingsBefore: dec("1")) ?? .nan, -120, accuracy: 1e-9)
        XCTAssertEqual(PortfolioLedger.holdingsAfter(dec("1"), e[1]), dec("0.6"))
    }

    /// Damit bezahlt ohne Marktwert: Verlauf zum Marktkurs, Karte/Steuer zum Tagesschluss geschätzt. Wie Android.
    func testPaymentWithoutValueIsEstimatedAtDayClose() {
        let trades = [tx(1, "BTC", .BUY, "1", "100", day), tx(2, "BTC", .PAYMENT, "0.4", nil, 2 * day)]
        let e = PortfolioLedger.events("BTC", trades: trades)
        XCTAssertTrue(PortfolioLedger.needsMarketPrice(e[1]))
        XCTAssertEqual(PortfolioLedger.flow(e[1], holdingsBefore: dec("1"), marketPrice: 250) ?? .nan, -100, accuracy: 1e-9)
        XCTAssertNil(PortfolioLedger.flow(e[1], holdingsBefore: dec("1")))
        XCTAssertTrue(PortfolioCalculator.ledger("BTC", trades: trades, currentPrice: nil).position.realizedIncomplete)
        let l = PortfolioCalculator.ledger("BTC", trades: trades, currentPrice: nil, feePrice: { _, _ in 250 })
        XCTAssertEqual(l.position.realizedExact, dec("60"))
        XCTAssertFalse(l.position.realizedIncomplete)
        XCTAssertTrue(l.disposals.allSatisfy { $0.payment && $0.valueEstimated })
        let needs = PortfolioLedger.dayCloseNeeds(trades)
        XCTAssertEqual(needs.map(\.coin), ["BTC"])
        XCTAssertEqual(needs.map(\.time), [2 * day])
    }

    /// Build 47: Verschenkt = beim Schenkenden keine Veräusserung — Karte und Einstandsmethoden wie eine Auszahlung; nur für
    /// UK/CA/AU als Veräusserung (`TaxEngines.applyRules`). Wie Android.
    func testGiftLeavesLikeWithdrawalAndIsDisposalOnlyWhereTemplateSaysSo() {
        let trades = [tx(1, "BTC", .BUY, "1", "100", day), tx(2, "BTC", .GIFT, "0.4", nil, 2 * day)]
        for m in [CostMethod.FIFO, .FIFO_WALLET, .AVERAGE] {
            let l = PortfolioCalculator.ledger("BTC", trades: trades, currentPrice: 300, method: m, feePrice: { _, _ in 250 })
            XCTAssertEqual(l.position.holdingsExact, dec("0.6"), "\(m)")
            XCTAssertEqual(l.position.realizedExact, 0, "\(m)")
            XCTAssertTrue(l.disposals.isEmpty, "\(m)")
            XCTAssertEqual(l.withdrawals.count, 1, "\(m)")
        }
        let e = PortfolioLedger.events("BTC", trades: trades)
        XCTAssertTrue(PortfolioLedger.needsMarketPrice(e[1]))
        XCTAssertEqual(PortfolioLedger.flow(e[1], holdingsBefore: dec("1"), marketPrice: 250) ?? .nan, -100, accuracy: 1e-9)
        XCTAssertEqual(PortfolioLedger.dayCloseNeeds(trades).map(\.coin), ["BTC"])
        let asDisposal = TaxEngines.applyRules(trades, rules: TaxTemplate.AU.eventRules, priceAt: nil)
        XCTAssertEqual(asDisposal[1].type, .PAYMENT)
        XCTAssertTrue(asDisposal[1].fromGift)
        let l = PortfolioCalculator.ledger("BTC", trades: asDisposal, currentPrice: nil, feePrice: { _, _ in 250 })
        XCTAssertEqual(l.position.realizedExact, dec("60"))
        XCTAssertTrue(l.disposals.allSatisfy { $0.gift && !$0.payment && $0.valueEstimated })
    }

    /// Tausch ohne Wert (z. B. Kraken): zum Tagesschluss des abgegebenen, sonst des erhaltenen Coins geschätzt. Wie Android.
    func testSwapWithoutValueIsEstimatedAtDayClose() {
        var swap = tx(2, "ETH", .SWAP, "1", nil, 2 * day)
        swap.toCoin = "BTC"
        swap.toAmountExact = dec("0.05")
        let trades = [tx(1, "ETH", .BUY, "1", "1000", day), swap]
        XCTAssertEqual(PortfolioLedger.dayCloseNeeds(trades).map(\.coin), ["ETH", "BTC"])
        let byGiven = PortfolioLedger.valueAtDayClose(trades, price: { c, _ in c == "ETH" ? self.dec("2000") : self.dec("50000") })
        XCTAssertEqual(byGiven[1].swapValueExact, dec("2000"))
        XCTAssertTrue(byGiven[1].valueEstimated)
        let byReceived = PortfolioLedger.valueAtDayClose(trades, price: { c, _ in c == "BTC" ? self.dec("50000") : nil })
        XCTAssertEqual(byReceived[1].swapValueExact, dec("2500"))
        // Ohne Kurs unverändert (unbekannt), mit erfasstem Wert unverändert
        XCTAssertNil(PortfolioLedger.valueAtDayClose(trades, price: { _, _ in nil })[1].swapValueExact)
        var exactSwap = swap
        exactSwap.swapValueExact = dec("1800")
        let exact = PortfolioLedger.valueAtDayClose([trades[0], exactSwap], price: { _, _ in self.dec("2000") })
        XCTAssertEqual(exact[1].swapValueExact, dec("1800"))
        XCTAssertFalse(exact[1].valueEstimated)
        let closes: (String, Int64) -> Double? = { c, _ in c == "ETH" ? 2000 : 50000 }
        let eth = PortfolioCalculator.ledger("ETH", trades: trades, currentPrice: nil, feePrice: closes)
        XCTAssertEqual(eth.position.realizedExact, dec("1000"))
        XCTAssertTrue(eth.disposals.allSatisfy(\.valueEstimated))
        let btc = PortfolioCalculator.ledger("BTC", trades: trades, currentPrice: nil, feePrice: closes)
        XCTAssertEqual(btc.position.costBasisExact, dec("2000"))
    }

    /// Build 47: Verkauf zum Preis 0 nur MIT Import-Herkunft eine Gebühr-Zeile; von Hand ein Verkauf eines wertlosen Coins.
    func testSaleAtPriceZeroIsFeeRowOnlyWhenImported() {
        let buy = tx(1, "BTC", .BUY, "1", "100", day)
        let manual = tx(2, "BTC", .SELL, "0.4", "0", 2 * day)
        XCTAssertFalse(PortfolioLedger.isFeeDisposal(manual))
        let l = PortfolioCalculator.ledger("BTC", trades: [buy, manual], currentPrice: nil)
        // Erlös 0, Einstand 40: Verlust −40, vollständig
        XCTAssertEqual(l.position.realizedExact, dec("-40"))
        XCTAssertFalse(l.position.realizedIncomplete)
        var imported = manual
        imported.importKey = "csv:fee"
        XCTAssertTrue(PortfolioLedger.isFeeDisposal(imported))
        XCTAssertTrue(PortfolioCalculator.ledger("BTC", trades: [buy, imported], currentPrice: nil).position.realizedIncomplete)
    }
}
