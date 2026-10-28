import XCTest
@testable import CryptoChecker

/// FIFO-Posten, Veräusserungen und Adress-Teil — wie `PortfolioFifoTest.kt`.
final class PortfolioFifoTests: XCTestCase {

    private let day: Int64 = 86_400_000

    private func dec(_ text: String) -> Decimal { Decimal(string: text, locale: Locale(identifier: "en_US_POSIX"))! }

    private func tx(_ id: Int64, _ coin: String, _ type: PortfolioTxType, _ amount: String, _ price: String?,
                    _ time: Int64) -> PortfolioTx {
        PortfolioTx(id: id, coin: coin, type: type, amountExact: dec(amount), priceExact: price.map(dec), time: time)
    }

    func testFifoIsTheDefaultAndDiffersFromTheAverage() {
        let trades = [tx(1, "BTC", .BUY, "2", "100", day), tx(2, "BTC", .BUY, "2", "200", 2 * day),
                      tx(3, "BTC", .SELL, "1", "300", 3 * day)]
        XCTAssertEqual(CostMethod.defaultMethod, .FIFO)
        XCTAssertEqual(CostMethod.fromName(nil), .FIFO)
        XCTAssertEqual(CostMethod.fromName("AVERAGE"), .AVERAGE)
        let fifo = PortfolioCalculator.position("BTC", trades: trades, currentPrice: 150)
        XCTAssertEqual(fifo.realizedExact, dec("200"))
        XCTAssertEqual(fifo.costBasisExact, dec("500"))
        XCTAssertEqual(fifo.avgCost ?? 0, 500.0 / 3.0, accuracy: 1e-9)
        XCTAssertEqual(fifo.unrealizedExact, dec("-50"))
        let avg = PortfolioCalculator.position("BTC", trades: trades, currentPrice: 150, method: .AVERAGE)
        XCTAssertEqual(avg.realizedExact, dec("150"))
        XCTAssertEqual(avg.costBasisExact, dec("450"))
        XCTAssertEqual(avg.method, .AVERAGE)
    }

    func testLedgerListsOpenLotsAndOneMatchPerConsumedLot() throws {
        let trades = [tx(1, "ETH", .BUY, "1", "100", day), tx(2, "ETH", .REWARD, "1", "150", 2 * day),
                      tx(3, "ETH", .BUY, "1", "200", 3 * day), tx(4, "ETH", .SELL, "1.5", "300", 10 * day)]
        let l = PortfolioCalculator.ledger("ETH", trades: trades, currentPrice: nil)
        XCTAssertEqual(l.lots.count, 2)
        XCTAssertEqual(l.lots.first?.txId, 2)
        XCTAssertEqual(l.lots.first?.amount, dec("0.5"))
        XCTAssertEqual(l.lots.first?.costUsdt, dec("75"))
        XCTAssertEqual(l.lots.first?.kind, .REWARD)
        XCTAssertEqual(l.disposals.count, 2)
        let a = try XCTUnwrap(l.disposals.first)
        XCTAssertEqual(a.lotTxId, 1)
        XCTAssertEqual(a.amount, dec("1"))
        XCTAssertEqual(a.costUsdt, dec("100"))
        XCTAssertEqual(a.proceedsUsdt, dec("300"))
        XCTAssertEqual(a.gainUsdt, dec("200"))
        XCTAssertEqual(a.holdingDays, 9)
        XCTAssertEqual(a.lotKind, .BUY)
        let b = try XCTUnwrap(l.disposals.last)
        XCTAssertEqual(b.lotTxId, 2)
        XCTAssertEqual(b.amount, dec("0.5"))
        XCTAssertEqual(b.costUsdt, dec("75"))
        XCTAssertEqual(b.proceedsUsdt, dec("150"))
        XCTAssertEqual(b.gainUsdt, dec("75"))
        XCTAssertEqual(b.lotKind, .REWARD)
        XCTAssertTrue(l.disposals.allSatisfy { $0.sellTxId == 4 && $0.method == .FIFO && !$0.feeLeg })
    }

    func testWithdrawalConsumesTheOldestLotWithoutADisposal() {
        let trades = [tx(1, "SOL", .BUY, "1", "10", day), tx(2, "SOL", .BUY, "1", "30", 2 * day),
                      tx(3, "SOL", .WITHDRAWAL, "1", nil, 3 * day)]
        let l = PortfolioCalculator.ledger("SOL", trades: trades, currentPrice: 30)
        XCTAssertTrue(l.disposals.isEmpty)
        XCTAssertEqual(l.position.costBasisExact, dec("30"))
        XCTAssertEqual(l.position.withdrawnAmount, 1)
        XCTAssertEqual(l.position.realizedExact, 0)
    }

    func testAverageMethodRecordsOneDisposalPerSellWithoutLots() {
        let trades = [tx(1, "BTC", .BUY, "1", "100", day), tx(2, "BTC", .BUY, "1", nil, 2 * day),
                      tx(3, "BTC", .SELL, "1", "300", 3 * day)]
        let l = PortfolioCalculator.ledger("BTC", trades: trades, currentPrice: nil, method: .AVERAGE)
        XCTAssertTrue(l.lots.isEmpty)
        XCTAssertEqual(l.disposals.count, 2)
        XCTAssertEqual(l.disposals.first?.amount, dec("0.5"))
        XCTAssertEqual(l.disposals.first?.costUsdt, dec("50"))
        XCTAssertEqual(l.disposals.first?.proceedsUsdt, dec("150"))
        XCTAssertEqual(l.disposals.first?.gainUsdt, dec("100"))
        XCTAssertEqual(l.disposals.last?.amount, dec("0.5"))
        XCTAssertNil(l.disposals.last?.costUsdt)
        XCTAssertEqual(l.disposals.last?.proceedsUsdt, dec("150"))
        XCTAssertNil(l.disposals.last?.gainUsdt)
        XCTAssertTrue(l.disposals.allSatisfy { $0.lotTxId == nil && $0.holdingDays == nil && $0.method == .AVERAGE })
    }

    func testDisposalsOverAllCoinsAreOrderedBySellTime() {
        var feeBuy = tx(3, "BTC", .BUY, "1", "1000", 3 * day)
        feeBuy.feeCoin = "BNB"
        feeBuy.feeCoinAmountExact = dec("0.1")
        feeBuy.feeCoinPriceExact = dec("400")
        let trades = [tx(1, "BNB", .BUY, "1", "300", day), tx(2, "ETH", .BUY, "1", "100", day),
                      tx(4, "ETH", .SELL, "1", "120", 5 * day), feeBuy]
        let all = PortfolioCalculator.disposals(trades)
        XCTAssertEqual(all.map(\.coin), ["BNB", "ETH"])
        XCTAssertEqual(all.first?.feeLeg, true)
        XCTAssertEqual(all.first?.sellTxId, 3)
        XCTAssertEqual(all.first?.gainUsdt, dec("10"))
    }

    func testAddressBalanceIsItsOwnPartAndLeavesTheCostBasisIntact() {
        let real = [tx(1, "BTC", .BUY, "1", "20000", day)]
        let merged = real + [tx(AddressBalance.tradeId(.BTC), "BTC", .DEPOSIT, "0.5", nil, 0)]
        let p = PortfolioCalculator.position("BTC", trades: merged, currentPrice: 30_000)
        XCTAssertEqual(p.holdingsExact, dec("1.5"))
        XCTAssertEqual(p.addressHoldingsExact, dec("0.5"))
        XCTAssertEqual(p.ledgerHoldingsExact, dec("1"))
        XCTAssertEqual(p.costBasisExact, dec("20000"))
        XCTAssertEqual(p.unrealizedExact, dec("10000"))
        XCTAssertEqual(p.valueExact, dec("45000"))
        XCTAssertEqual(p.addressValueExact, dec("15000"))
        XCTAssertFalse(p.priceMissing)
        let s = PortfolioCalculator.summarize(merged, prices: ["BTC": 30_000])
        XCTAssertFalse(s.costMissing)
        XCTAssertEqual(s.addressValue, 15_000, accuracy: 1e-9)
        XCTAssertEqual(s.unrealized ?? 0, 10_000, accuracy: 1e-9)
        // Nur Adresse: offen, aber nicht «ohne Kaufpreis»
        let only = PortfolioCalculator.summarize(Array(merged.dropFirst()), prices: ["BTC": 30_000])
        XCTAssertEqual(only.open.map(\.coin), ["BTC"])
        XCTAssertTrue(only.missingCostCoins.isEmpty)
        XCTAssertNil(only.unrealized)
        XCTAssertTrue(PortfolioCalculator.disposals(merged).isEmpty)
    }

    func testHoldingDaysCountUtcCalendarDays() {
        XCTAssertEqual(PortfolioFifo.holdingDays(from: 10 * day + 1, to: 375 * day), 365)
        XCTAssertEqual(PortfolioFifo.holdingDays(from: 10 * day + day - 1, to: 376 * day), 366)
        XCTAssertEqual(PortfolioFifo.holdingDays(from: day, to: 2 * day - 1), 0)
    }

    func testSellBeyondHoldingsKeepsTheUncoveredRestAsDisposalWithoutBuyPrice() {
        let trades = [tx(1, "BTC", .BUY, "1", "100", day), tx(2, "BTC", .SELL, "3", "50", 2 * day),
                      tx(3, "ETH", .SELL, "2", "10", 3 * day)] // ETH: nichts gehalten
        for method in CostMethod.allCases {
            let all = PortfolioCalculator.disposals(trades, method: method)
            // Gesamterlös fehlt nirgends: 150 BTC + 20 ETH
            XCTAssertEqual(all.reduce(Decimal(0)) { $0 + ($1.proceedsUsdt ?? 0) }, dec("170"), "\(method)")
            let uncovered = all.filter(\.uncovered)
            XCTAssertEqual(uncovered.count, 2, "\(method)")
            XCTAssertTrue(uncovered.allSatisfy { $0.costUsdt == nil && $0.gainUsdt == nil && $0.lotTxId == nil })
            XCTAssertEqual(uncovered.first { $0.coin == "BTC" }?.amount, dec("2"))
            XCTAssertEqual(uncovered.first { $0.coin == "BTC" }?.proceedsUsdt, dec("100"))
            let p = PortfolioCalculator.position("BTC", trades: trades, currentPrice: 50, method: method)
            XCTAssertTrue(p.oversold)
            XCTAssertEqual(p.holdingsExact, 0)
            XCTAssertEqual(p.realizedExact, dec("-50"))
        }
    }

    func testDepositWithOriginalPurchaseDateSortsBeforeYoungerLots() throws {
        var deposit = tx(2, "BTC", .DEPOSIT, "1", nil, 20 * day)
        deposit.acquiredTime = 5 * day
        let trades = [tx(1, "BTC", .BUY, "1", "100", 10 * day), deposit, tx(3, "BTC", .SELL, "1", "200", 30 * day)]
        let all = PortfolioCalculator.disposals(trades)
        XCTAssertEqual(all.count, 1)
        let d = try XCTUnwrap(all.first)
        XCTAssertEqual(d.lotTxId, 2)
        XCTAssertEqual(d.holdingDays, 25)
        XCTAssertEqual(d.acquiredTime, 5 * day)
        XCTAssertNil(d.gainUsdt) // Einzahlung ohne Preis: Einstand unbekannt
        // Späteres «Kaufdatum» als die Einzahlung zählt nicht; nur bei Einzahlungen
        var late = deposit
        late.acquiredTime = 40 * day
        XCTAssertEqual(late.acquisitionTime, 20 * day)
        var buy = tx(1, "BTC", .BUY, "1", "100", 10 * day)
        buy.acquiredTime = day
        XCTAssertEqual(buy.acquisitionTime, 10 * day)
    }

    func testSummaryGroupsTradesOnceAndReportsWithdrawnGain() {
        let trades = [tx(1, "SOL", .BUY, "2", "10", day), tx(2, "SOL", .WITHDRAWAL, "1", nil, 2 * day),
                      tx(3, "ETH", .BUY, "1", "1000", day)]
        let prices = ["SOL": 20.0, "ETH": 1000.0]
        // Ohne Kurs zur Auszahlung: unbekannt
        let plain = PortfolioCalculator.summarize(trades, prices: prices)
        XCTAssertNil(plain.withdrawnGainExact)
        XCTAssertTrue(plain.withdrawnGainIncomplete)
        // Mit Kurs zum Zeitpunkt (z. B. Tagesschluss): 1 × 25 − 10 = +15
        let at = 2 * day
        let withMarket = PortfolioCalculator.summarize(trades, prices: prices,
                                                       withdrawalPrice: { coin, time in coin == "SOL" && time == at ? 25 : nil })
        XCTAssertEqual(withMarket.withdrawnGainExact, dec("15"))
        XCTAssertFalse(withMarket.withdrawnGainIncomplete)
        // Gleiche Positionen wie einzeln gerechnet
        XCTAssertEqual(withMarket.open.first { $0.coin == "SOL" },
                       PortfolioCalculator.position("SOL", trades: trades, currentPrice: 20))
    }

    private func utc(_ y: Int, _ m: Int, _ d: Int) -> Int64 { Int64(LocalDay(y, m, d).epochDay) * day }

    private func fee(_ t: PortfolioTx, _ amount: String) -> PortfolioTx {
        var t = t
        t.feeExact = dec(amount)
        t.feeUnit = .COIN
        return t
    }

    /// Netzwerkgebühr verbraucht den letzten bezahlten Posten, übrig nur eine Einzahlung ohne Preis.
    func testNetworkFeeEatingTheLastPaidLotReportsItsCostInsteadOfLosingIt() {
        let trades = [tx(1, "BTC", .BUY, "0.001", "100000", day), tx(2, "BTC", .DEPOSIT, "1", nil, 2 * day),
                      fee(tx(3, "BTC", .TRANSFER, "1", nil, 3 * day), "0.001")]
        for method in [CostMethod.FIFO, .FIFO_WALLET, .AVERAGE] {
            let l = PortfolioCalculator.ledger("BTC", trades: trades, currentPrice: 50000, method: method)
            XCTAssertEqual(l.position.holdingsExact, dec("1"))
            XCTAssertNil(l.position.costBasis, "\(method)") // nur Bestand ohne Kaufpreis übrig
            XCTAssertEqual(l.position.realizedExact, 0) // kein realisierter Verlust
            if method == .AVERAGE {
                // Ø: Gebühr anteilig aus beiden Teilen — der bezahlte Teil bleibt, nichts verloren
                XCTAssertEqual(l.position.lostFeeCostUsdt, 0)
            } else {
                XCTAssertEqual(l.position.lostFeeCostUsdt, dec("100"))
                XCTAssertEqual(l.lostFeeCosts, [PortfolioLostFeeCost(coin: "BTC", txId: 3, time: 3 * day, costUsdt: dec("100"))])
            }
        }
        // Ø: Gebühr nimmt den GANZEN Bestand mit Kaufpreis → vermerkt
        let avgAll = PortfolioCalculator.position("ETH", trades: [tx(1, "ETH", .BUY, "0.01", "2000", day),
                                                                 fee(tx(2, "ETH", .TRANSFER, "0.01", nil, 2 * day), "0.01")],
                                                  currentPrice: nil, method: .AVERAGE)
        XCTAssertEqual(avgAll.lostFeeCostUsdt, dec("20"))
        // Steuer-Export: Vermerk je Gebühr im Jahr
        let zone = TimeZone(identifier: "UTC")!
        let lost = TaxExport.lostFeeCosts(in: trades, method: .FIFO, year: 1970, timeZone: zone)
        XCTAssertEqual(lost.count, 1)
        XCTAssertTrue(TaxExport.lostFeeCosts(in: trades, method: .FIFO, year: 1971, timeZone: zone).isEmpty)
        var texts = TaxCsvTexts.localized(currency: "USD", zone: zone)
        texts.lostFeeCost = "Netzwerkgebühr-Einstand ohne bezahlten Posten"
        let csv = TaxExport.gainsCsv([], texts: texts, header: [], lostFeeCosts: lost, timeZone: zone)
        XCTAssertTrue(csv.contains("BTC;;1970-01-04;;;;100.00;;;;;;Netzwerkgebühr-Einstand ohne bezahlten Posten"), csv)
        texts.lostFeeCost = ""
        XCTAssertFalse(TaxExport.gainsCsv([], texts: texts, header: [], lostFeeCosts: lost).contains("BTC;"))
    }

    /// Gebühr im Coin über zwei Posten mit verschiedener Haltedauer — kurz/lang getrennt richtig.
    func testCoinFeeOfASellIsConsumedAfterTheSoldAmountAndKeepsTheShortAndLongSplit() throws {
        let trades = [tx(1, "BTC", .BUY, "1", "100", utc(2020, 1, 1)), tx(2, "BTC", .BUY, "1", "200", utc(2021, 6, 1)),
                      fee(tx(3, "BTC", .SELL, "1", "300", utc(2021, 12, 1)), "0.5")]
        let d = PortfolioCalculator.disposals(trades)
        XCTAssertEqual(d.count, 2)
        // Verkaufte Menge aus dem ältesten Posten: voller Erlös, langfristig
        XCTAssertEqual(d[0].lotTxId, 1)
        XCTAssertEqual(d[0].amount, dec("1"))
        XCTAssertEqual(d[0].proceedsUsdt, dec("300"))
        XCTAssertEqual(d[0].gainUsdt, dec("200"))
        // Gebühr-Coins aus dem nächsten Posten: Erlös 0, Einstand = Kosten der Veräusserung, kurzfristig
        XCTAssertEqual(d[1].lotTxId, 2)
        XCTAssertEqual(d[1].amount, dec("0.5"))
        XCTAssertEqual(d[1].proceedsUsdt, 0)
        XCTAssertEqual(d[1].gainUsdt, dec("-100"))
        let zone = TimeZone(identifier: "UTC")!
        func long(_ x: PortfolioDisposal) -> Bool {
            TaxExport.isLongTerm(acquired: TaxExport.localDay(x.acquiredTime ?? 0, timeZone: zone),
                                 sold: TaxExport.localDay(x.sellTime, timeZone: zone))
        }
        XCTAssertEqual(d.filter(long).reduce(Decimal(0)) { $0 + ($1.gainUsdt ?? 0) }, dec("200"))
        XCTAssertEqual(d.filter { !long($0) }.reduce(Decimal(0)) { $0 + ($1.gainUsdt ?? 0) }, dec("-100"))
        XCTAssertEqual(PortfolioCalculator.position("BTC", trades: trades, currentPrice: nil).realizedExact, dec("100"))
        // Mehr abgegeben als erfasst: zuerst die verkaufte Menge, der ungedeckte Rest sind Gebühr-Coins (Erlös 0)
        let short = [tx(1, "BTC", .BUY, "1.2", "100", utc(2020, 1, 1)),
                     fee(tx(2, "BTC", .SELL, "1", "300", utc(2021, 12, 1)), "0.5")]
        let s = PortfolioCalculator.disposals(short)
        XCTAssertEqual(s.reduce(Decimal(0)) { $0 + ($1.proceedsUsdt ?? 0) }, dec("300"))
        let uncovered = try XCTUnwrap(s.first { $0.uncovered })
        XCTAssertEqual(uncovered.amount, dec("0.3"))
        XCTAssertEqual(uncovered.proceedsUsdt, 0)
        XCTAssertEqual(PortfolioCalculator.position("BTC", trades: short, currentPrice: nil).soldAmount, 1)
    }

    /// Rückdatierter oder geänderter Kauf rechnet spätere Verkäufe neu (nichts zwischengespeichert).
    func testBackdatedOrEditedBuyRecomputesLaterSellsFromTheFullLedger() {
        let buy = tx(1, "BTC", .BUY, "1", "100", 2 * day)
        let sell = tx(2, "BTC", .SELL, "1", "300", 5 * day)
        XCTAssertEqual(PortfolioCalculator.position("BTC", trades: [buy, sell], currentPrice: nil).realizedExact, dec("200"))
        var edited = buy
        edited.priceExact = dec("150")
        XCTAssertEqual(PortfolioCalculator.position("BTC", trades: [edited, sell], currentPrice: nil).realizedExact, dec("150"))
        XCTAssertEqual(PortfolioCalculator.disposals([sell, edited]).first?.gainUsdt, dec("150"))
        let backdated = tx(3, "BTC", .BUY, "1", "50", day)
        let l = PortfolioCalculator.ledger("BTC", trades: [sell, edited, backdated], currentPrice: nil)
        XCTAssertEqual(l.position.realizedExact, dec("250"))
        XCTAssertEqual(l.disposals.map(\.lotTxId), [3])
        XCTAssertEqual(l.lots.map(\.txId), [1])
        XCTAssertEqual(l.position.costBasisExact, dec("150"))
    }

    /// Gebühr als Aufwand — dieselbe Regel auf der Karte (realisiert), im Zeitraum (Wert Ende − Wert Anfang − Zuflüsse)
    /// und im Steuer-Export (Gewinne + Vermerk). Wie `PortfolioFifoTest.kt`.
    func testFeeExpensesCountTheSameOnTheCardInThePeriodAndInTheTaxExport() {
        var swap = PortfolioTx(id: 2, coin: "ETH", type: .SWAP, amountExact: dec("0.5"), time: 2 * day)
        swap.toCoin = "BTC"
        swap.toAmountExact = dec("0.01")
        swap.swapValueExact = dec("600")
        swap.feeCoin = "BTC"
        swap.feeCoinAmountExact = dec("0.01")
        let trades: [PortfolioTx] = [
            tx(1, "ETH", .BUY, "1", "1000", day),
            swap,
            PortfolioTx(id: 3, coin: "SOL", type: .BUY, amountExact: dec("1"), priceExact: dec("100"), time: 3 * day,
                        feeExact: dec("1"), feeUnit: .COIN),
            tx(4, "ADA", .BUY, "100", "1", 4 * day),
            PortfolioTx(id: 5, coin: "ADA", type: .WITHDRAWAL, amountExact: dec("50"), time: 5 * day,
                        feeExact: dec("2"), feeUnit: .QUOTE),
        ]
        let prices: [String: Double] = ["ETH": 1000, "BTC": 60000, "SOL": 100, "ADA": 1]
        let zone = TimeZone(identifier: "UTC")!
        for method in CostMethod.allCases {
            // Karte: Σ realisiert = +100 (ETH) − 600 (BTC-Zugang verbraucht) − 100 (SOL) − 2 (Gebühr Auszahlung)
            let card: Decimal = PortfolioLedger.coins(trades).reduce(Decimal(0)) { a, c -> Decimal in
                a + PortfolioCalculator.position(c, trades: trades, currentPrice: prices[c], method: method).realizedExact
            }
            XCTAssertEqual(card, dec("-602"), method.rawValue)
            // Zeitraum (Kurse = Einstand, keine Kursbewegung): Gewinn = −602
            var flows = 0.0
            var endValue = 0.0
            for entry in PortfolioLedger.eventsByCoin(trades) {
                var book = PortfolioHoldingsBook(method: method)
                for e in entry.events {
                    flows += PortfolioLedger.flow(e, holdingsBefore: book.cap(for: e), marketPrice: prices[entry.coin]) ?? .nan
                    book.apply(e)
                }
                endValue += NSDecimalNumber(decimal: book.total).doubleValue * (prices[entry.coin] ?? .nan)
            }
            XCTAssertEqual(endValue - flows, -602, accuracy: 1e-9, method.rawValue)
            // Steuer-Export: Gewinne summieren gleich, je Aufwand eine Zeile mit Vermerk
            let rows = TaxExport.gainRows(trades, method: method, year: 1970, timeZone: zone, currency: "USD", rates: [:])
            XCTAssertEqual(rows.reduce(Decimal(0)) { $0 + ($1.gainUsdt ?? .nan) }, dec("-602"), method.rawValue)
            XCTAssertEqual(rows.filter { $0.feeExpense != nil }.map { "\($0.coin):\($0.feeExpense?.rawValue ?? "")" },
                           ["BTC:CONSUMED", "SOL:CONSUMED", "ADA:WITHDRAWAL"], method.rawValue)
            // Texte wie im Android-Test (sonst kämen bei «FIFO je Wallet» Wallet-Vermerke dazu)
            let x = "x"
            var texts = TaxCsvTexts(
                coin: x, acquired: x, sold: x, holdingDays: x, longTerm: x, amount: x, costUsdt: x, proceedsUsdt: x,
                gainUsdt: x, costTarget: x, proceedsTarget: x, gainTarget: x, date: x, valueUsdt: x, valueTarget: x,
                note: x, total: "Total", yes: x, no: x, costMissing: x, proceedsMissing: x, feeLeg: x, feeValueMissing: x,
                avgCostRates: "AVG", valueMissing: x, noFx: x, incomplete: x)
            texts.feeConsumed = "Gebühr verbraucht den ganzen Zugang"
            texts.withdrawalFee = "Gebühr Auszahlung"
            let csv = TaxExport.gainsCsv(rows, texts: texts, header: [], timeZone: zone)
            let lines: [String] = csv.components(separatedBy: .newlines)
            // Meldung = die betroffene Zeile (die CI-Anzeige zeigt nur die erste Zeile einer Meldung)
            let sol = lines.first { $0.hasPrefix("SOL;") } ?? "keine SOL-Zeile"
            let ada = lines.first { $0.hasPrefix("ADA;") } ?? "keine ADA-Zeile"
            XCTAssertTrue(sol.hasPrefix("SOL;1970-01-04;1970-01-04;0;"), "\(method.rawValue): \(sol)")
            XCTAssertTrue(sol.hasSuffix(";Gebühr verbraucht den ganzen Zugang"), "\(method.rawValue): \(sol)")
            XCTAssertTrue(ada.hasSuffix(";Gebühr Auszahlung"), "\(method.rawValue): \(ada)")
        }
    }

    /// FIFO je Wallet: Netzwerkgebühr frisst den einzigen Posten mit Kaufpreis der Quelle, verschoben werden nur Coins
    /// ohne Kaufpreis — der Einstand geht auf den nächsten Posten mit Kaufpreis (im Ziel), nicht in «verloren»; gibt es
    /// keinen, wartet er bei den verschobenen Coins im Ziel. Wie `PortfolioFifoTest.kt`.
    func testWalletTransferFeeCostGoesToTheNextPricedLotBeforeItCountsAsLost() {
        func inWallet(_ t: PortfolioTx, _ w: String) -> PortfolioTx {
            var t = t
            t.exchange = w
            return t
        }
        var move = fee(tx(4, "BTC", .TRANSFER, "0.5", nil, 4 * day), "0.1")
        move.transferFrom = "Binance"
        move.transferTo = "Ledger"
        let base: [PortfolioTx] = [inWallet(tx(2, "BTC", .BUY, "0.1", "2000", 2 * day), "Binance"),
                                   inWallet(tx(3, "BTC", .DEPOSIT, "1", nil, 3 * day), "Binance"), move]
        let first = inWallet(tx(1, "BTC", .BUY, "1", "1000", day), "Ledger")
        // Posten mit Kaufpreis im Ziel: übernimmt den Einstand (200)
        let withTarget = PortfolioCalculator.ledger("BTC", trades: [first] + base, currentPrice: nil, method: .FIFO_WALLET)
        XCTAssertEqual(withTarget.position.lostFeeCostUsdt, 0)
        XCTAssertEqual(withTarget.lots.first { $0.txId == 1 }?.costUsdt, dec("1200"))
        // Keiner da: wartet im Ziel — ein späterer Kauf in der Quelle übernimmt ihn nicht, einer im Ziel schon
        let laterSrc = PortfolioCalculator.ledger("BTC", trades: base + [inWallet(tx(5, "BTC", .BUY, "1", "3000", 5 * day), "Binance")],
                                                  currentPrice: nil, method: .FIFO_WALLET)
        XCTAssertEqual(laterSrc.position.lostFeeCostUsdt, dec("200"))
        XCTAssertEqual(laterSrc.lots.first { $0.txId == 5 }?.costUsdt, dec("3000"))
        let laterDst = PortfolioCalculator.ledger("BTC", trades: base + [inWallet(tx(5, "BTC", .BUY, "1", "3000", 5 * day), "Ledger")],
                                                  currentPrice: nil, method: .FIFO_WALLET)
        XCTAssertEqual(laterDst.position.lostFeeCostUsdt, 0)
        XCTAssertEqual(laterDst.lots.first { $0.txId == 5 }?.costUsdt, dec("3200"))
        // Universelles FIFO: der verbleibende Posten mit Kaufpreis übernimmt ihn (unverändert)
        let universal = PortfolioCalculator.ledger("BTC", trades: [first] + base, currentPrice: nil, method: .FIFO)
        XCTAssertEqual(universal.position.lostFeeCostUsdt, 0)
    }

    /// Umbuchungsgebühr auf Bestand ohne Kaufpreis im Pool der Steuer-Vorlagen (`TaxPool.addFee`, UK/CA ohne
    /// Vorlage-Regeln) — derselbe Anteil wie im Portfolio vermerkt; mit den UK-Regeln zählt die Gebühr USDT gar nicht zum
    /// Einstand (nur Information). Wie Android.
    func testTransferFeeShareOnCoinsWithoutPurchasePriceIsNotedInTheTaxPools() throws {
        let zone = try XCTUnwrap(TimeZone(identifier: "UTC"))
        let trades = [
            PortfolioTx(id: 1, coin: "ETH", type: .DEPOSIT, amountExact: dec("1"), priceExact: nil, time: day),
            tx(2, "ETH", .BUY, "1", "2000", 2 * day),
            PortfolioTx(id: 3, coin: "ETH", type: .TRANSFER, amountExact: dec("2"), priceExact: nil, time: 3 * day,
                        feeExact: dec("10"), feeUnit: .QUOTE),
        ]
        for m in [TaxMethod.UK_POOL, .CA_ACB] {
            let fees = TaxEngines.unassignedFees(trades, method: m, timeZone: zone, rules: TaxEventRules.none)
            XCTAssertEqual(fees.count, 1, "\(m)")
            XCTAssertEqual(fees.first?.txId, 3)
            XCTAssertEqual(fees.first?.usdt, dec("5"))
            XCTAssertEqual(TaxExport.unassignedFees(in: trades, method: m, period: TaxTemplate.NEUTRAL.period(1970),
                                                    timeZone: zone, rules: TaxEventRules.none).first?.usdt, dec("5"))
        }
        XCTAssertTrue(TaxEngines.unassignedFees(trades, method: .UK_POOL, timeZone: zone).isEmpty)
        // Portfolio-Methoden über das Buch (gleicher Anteil), andere Jahre leer
        XCTAssertEqual(TaxExport.unassignedFees(in: trades, method: CostMethod.FIFO, year: 1970, timeZone: zone).first?.usdt, dec("5"))
        XCTAssertTrue(TaxExport.unassignedFees(in: trades, method: CostMethod.AVERAGE, year: 1971, timeZone: zone).isEmpty)
        // Pool direkt: ohne Bestand und ohne Teil mit Kaufpreis die ganze Gebühr (wie FIFO je Wallet vermerkt)
        var pool = TaxPool()
        let empty = pool.addFee(day, dec("4"), amount: dec("1"), coinFee: 0)
        XCTAssertEqual(empty, dec("4"))
        pool.add(dec("1"), cost: nil, time: day)
        let unpricedOnly = pool.addFee(day, dec("4"), amount: dec("1"), coinFee: 0)
        XCTAssertEqual(unpricedOnly, dec("4"))
        pool.add(dec("1"), cost: dec("100"), time: day)
        let half = pool.addFee(day, dec("4"), amount: dec("2"), coinFee: 0)
        XCTAssertEqual(half, dec("2"))
        XCTAssertEqual(pool.cost, dec("102"))
        // Menge 2 + Netzwerkgebühr 0,5 bei Bestand 2: gedeckt 1,5 → 4 × 0,5 / 2 = 1 vermerkt, vom Rest 3 die Hälfte
        // zum Einstand (1,5), die andere vermerkt: 2,5
        let overHoldings = pool.addFee(day, dec("4"), amount: dec("2"), coinFee: dec("0.5"))
        XCTAssertEqual(overHoldings, dec("2.5"))
        XCTAssertEqual(pool.cost, dec("103.5"))
    }
}
