import XCTest
@testable import CryptoChecker

/// Gemeinsame Portfolio-Fälle (`Parity/portfolio.json`) — wie `PortfolioParityTest.kt`: Buchhaltung,
/// Gewinn im Zeitraum und Skalieren auf die eingetragene Börse.
final class PortfolioParityTests: XCTestCase {

    private func dec(_ text: String) -> Decimal { Decimal(string: text, locale: Locale(identifier: "en_US_POSIX"))! }

    private func trade(_ raw: Any) -> PortfolioTx {
        let o = raw as? [String: Any] ?? [:]
        var t = PortfolioTx(id: Int64(TestSupport.number(o["id"]) ?? 0),
                            coin: o["coin"] as? String ?? "",
                            type: PortfolioTxType(rawValue: o["type"] as? String ?? "") ?? .BUY,
                            amountExact: dec(o["amount"] as? String ?? "0"),
                            priceExact: (o["price"] as? String).map(dec),
                            time: Int64(TestSupport.number(o["time"]) ?? 0),
                            feeExact: (o["fee"] as? String).map(dec),
                            feeUnit: (o["feeUnit"] as? String).flatMap(PortfolioFeeUnit.init(rawValue:)))
        // Build 46: Gebühr in einem dritten Coin
        t.feeCoin = o["feeCoin"] as? String
        t.feeCoinAmountExact = (o["feeCoinAmount"] as? String).map(dec)
        t.feeCoinPriceExact = (o["feeCoinPrice"] as? String).map(dec)
        // Einzahlung: ursprüngliches Kaufdatum (optional)
        t.acquiredTime = TestSupport.number(o["acquiredTime"]).map { Int64($0) }
        // Build 46 (optional): Börse/Wallet (FIFO je Wallet), Umbuchung, Tausch
        t.exchange = o["exchange"] as? String
        t.transferFrom = o["transferFrom"] as? String
        t.transferTo = o["transferTo"] as? String
        t.toCoin = o["toCoin"] as? String
        t.toAmountExact = (o["toAmount"] as? String).map(dec)
        t.swapValueExact = (o["swapValue"] as? String).map(dec)
        t.importKey = o["importKey"] as? String
        // Gebühr-Zeile: eigenes Feld (Build 76); fehlt es im Fixture, die frühere Regel (wie beim Lesen alter Dateien)
        t.feeRow = o["feeRow"] as? Bool ?? LegacyStorageMigration.legacyFeeRow(t.type, importKey: t.importKey, price: t.priceExact)
        return t
    }

    private func near(_ label: String, _ expected: Double?, _ actual: Double?) {
        guard let expected else { return XCTAssertNil(actual, "\(label): expected nil") }
        guard let actual else { return XCTFail("\(label): expected \(expected), got nil") }
        XCTAssertLessThanOrEqual(abs(expected - actual), max(abs(expected) * 1e-9, 1e-9), label)
    }

    /// Exakter Vergleich, nur wenn der Fall den Text nennt.
    private func exact(_ label: String, _ expected: Any?, _ actual: Decimal?) {
        guard let text = expected as? String else { return }
        XCTAssertEqual(actual, dec(text), label)
    }

    private func prices(_ raw: Any?) -> [String: Double] {
        (raw as? [String: Any] ?? [:]).compactMapValues { TestSupport.number($0) }
    }

    private func series(_ raw: Any?) -> [String: [Int: Double]] {
        (raw as? [String: Any] ?? [:]).mapValues { v in
            var out: [Int: Double] = [:]
            for (k, x) in v as? [String: Any] ?? [:] { if let d = Int(k), let n = TestSupport.number(x) { out[d] = n } }
            return out
        }
    }

    func testPositions() throws {
        let cases = TestSupport.list(try TestSupport.fixture("portfolio")["positions"])
        XCTAssertGreaterThanOrEqual(cases.count, 5)
        for c in cases {
            let name = c["name"] as? String ?? ""
            let e = c["expected"] as? [String: Any] ?? [:]
            let trades = (c["trades"] as? [Any] ?? []).map(trade)
            guard let method = CostMethod(rawValue: c["method"] as? String ?? "") else {
                XCTFail("\(name): method missing")
                continue
            }
            let ledger = PortfolioCalculator.ledger(c["coin"] as? String ?? "", trades: trades,
                                                    currentPrice: TestSupport.number(c["currentPrice"]), method: method)
            let p = ledger.position
            XCTAssertEqual(p.method, method, "\(name) method")
            XCTAssertEqual(p.holdingsExact, dec(e["holdings"] as? String ?? "0"), "\(name) holdings")
            near("\(name) costBasis", TestSupport.number(e["costBasis"]), p.costBasis)
            near("\(name) avgCost", TestSupport.number(e["avgCost"]), p.avgCost)
            near("\(name) realized", TestSupport.number(e["realized"]), NSDecimalNumber(decimal: p.realizedExact).doubleValue)
            XCTAssertEqual(p.oversold, e["oversold"] as? Bool, "\(name) oversold")
            XCTAssertEqual(p.priceMissing, e["priceMissing"] as? Bool, "\(name) priceMissing")
            // Exakte Texte (optional): Teilungen auf 34 Stellen wie Android, unrealisiert exakt
            exact("\(name) costBasisExact", e["costBasisExact"], p.costBasisExact)
            exact("\(name) realizedExact", e["realizedExact"], p.realizedExact)
            exact("\(name) unrealizedExact", e["unrealizedExact"], p.unrealizedExact)
            exact("\(name) pricedHoldings", e["pricedHoldings"], p.pricedHoldingsExact)
            // Netzwerkgebühr-Einstand ohne bezahlten Posten (optional)
            exact("\(name) lostFeeCost", e["lostFeeCost"], p.lostFeeCostUsdt)
            // Build 58 (optional): Umbuchungsgebühr auf Bestand ohne Kaufpreis — vermerkt statt still verworfen
            exact("\(name) unassignedFee", e["unassignedFee"], p.unassignedFeeUsdt)
            if let fees = e["unassignedFees"] as? [[String: Any]] {
                XCTAssertEqual(ledger.unassignedFees.count, fees.count, "\(name) unassignedFees")
                for (i, (x, f)) in zip(fees, ledger.unassignedFees).enumerated() {
                    XCTAssertEqual(f.txId, (x["txId"] as? NSNumber)?.int64Value, "\(name) unassignedFee \(i) txId")
                    exact("\(name) unassignedFee \(i) usdt", x["usdt"], f.usdt)
                }
            }
            // Build 46 (optional): «Seit Kauf» des Teils mit Kaufpreis, auch wenn ein Teil ohne Kaufpreis ist
            exact("\(name) pricedCost", e["pricedCost"], p.pricedCostExact)
            exact("\(name) unpricedHoldings", e["unpricedHoldings"], p.unpricedHoldingsExact)
            if let partial = e["unrealizedPartial"] as? Bool { XCTAssertEqual(p.unrealizedPartial, partial, "\(name) unrealizedPartial") }
            if let percent = TestSupport.number(e["unrealizedPercent"]) { near("\(name) unrealizedPercent", percent, p.unrealizedPercent) }
            if let x = e["summary"] as? [String: Any] {
                let s = PortfolioCalculator.summarize(trades, prices: prices(c["summaryPrices"]), method: method)
                near("\(name) summary unrealized", (x["unrealized"] as? String).flatMap { Double($0) }, s.unrealized)
                near("\(name) summary unrealizedPercent", TestSupport.number(x["unrealizedPercent"]), s.unrealizedPercent)
                if x.keys.contains("invested") {
                    if let text = x["invested"] as? String { exact("\(name) summary invested", text, s.investedExact) }
                    else { XCTAssertNil(s.investedExact, "\(name) summary invested") }
                }
                XCTAssertEqual(s.missingCostCoins, x["missingCostCoins"] as? [String] ?? [], "\(name) summary missingCostCoins")
                XCTAssertEqual(s.costMissing, x["costMissing"] as? Bool, "\(name) summary costMissing")
                exact("\(name) summary unassignedFee", x["unassignedFee"], s.unassignedFeeUsdt)
                let parts: [[String: Any]] = (x["partialCost"] as? [Any] ?? []).compactMap { $0 as? [String: Any] }
                XCTAssertEqual(s.partialCostParts.count, parts.count, "\(name) summary partialCost")
                for (y, part) in zip(parts, s.partialCostParts) {
                    XCTAssertEqual(part.coin, y["coin"] as? String, "\(name) summary partial coin")
                    exact("\(name) summary partial amount", y["amount"], part.amount)
                }
            }
            // Build 46 (optional): Auszahlung, Gebühr im dritten Coin
            if let withdrawn = TestSupport.number(e["withdrawn"]) { near("\(name) withdrawn", withdrawn, p.withdrawnAmount) }
            if let missing = e["feeValueMissing"] as? Bool { XCTAssertEqual(p.feeValueMissing, missing, "\(name) feeValueMissing") }
            if let incomplete = e["realizedIncomplete"] as? Bool {
                XCTAssertEqual(p.realizedIncomplete, incomplete, "\(name) realizedIncomplete")
            }
            // Build 46 (optional): Einstandsmethode — offene Posten und Veräusserungen
            if let unknown = TestSupport.number(e["realizedUnknownAmount"]) {
                near("\(name) realizedUnknownAmount", unknown, p.realizedUnknownAmount)
            }
            if let lots = e["lots"] as? [Any] {
                XCTAssertEqual(ledger.lots.count, lots.count, "\(name) lots")
                for (i, pair) in zip(lots, ledger.lots).enumerated() {
                    let x = pair.0 as? [String: Any] ?? [:]
                    let lot = pair.1
                    XCTAssertEqual(lot.txId, Int64(TestSupport.number(x["txId"]) ?? 0), "\(name) lot \(i) txId")
                    exact("\(name) lot \(i) amount", x["amount"], lot.amount)
                    optionalExact("\(name) lot \(i) cost", x["costUsdt"], lot.costUsdt)
                    if x.keys.contains("wallet") { XCTAssertEqual(lot.wallet, x["wallet"] as? String, "\(name) lot \(i) wallet") }
                    if let kind = x["kind"] as? String { XCTAssertEqual(lot.kind.rawValue, kind, "\(name) lot \(i) kind") }
                }
            }
            // Build 46 (optional): offene Posten für die Anzeige, Fehlbestand je Wallet
            if let expected = e["openLots"] as? [Any] {
                XCTAssertEqual(ledger.openLots.count, expected.count, "\(name) openLots")
                for (i, pair) in zip(expected, ledger.openLots).enumerated() {
                    let x = pair.0 as? [String: Any] ?? [:]
                    let lot = pair.1
                    XCTAssertEqual(lot.txId, TestSupport.number(x["txId"]).map { Int64($0) }, "\(name) openLot \(i) txId")
                    exact("\(name) openLot \(i) amount", x["amount"], lot.amount)
                    optionalExact("\(name) openLot \(i) cost", x["costUsdt"], lot.costUsdt)
                    XCTAssertEqual(lot.wallet, x["wallet"] as? String, "\(name) openLot \(i) wallet")
                    XCTAssertEqual(lot.aggregated, x["aggregated"] as? Bool, "\(name) openLot \(i) aggregated")
                }
            }
            if let expected = e["shortfalls"] as? [Any] {
                XCTAssertEqual(ledger.walletShortfalls.count, expected.count, "\(name) shortfalls")
                for (i, pair) in zip(expected, ledger.walletShortfalls).enumerated() {
                    let x = pair.0 as? [String: Any] ?? [:]
                    XCTAssertEqual(pair.1.wallet, x["wallet"] as? String, "\(name) shortfall \(i) wallet")
                    XCTAssertEqual(pair.1.txId, Int64(TestSupport.number(x["txId"]) ?? 0), "\(name) shortfall \(i) txId")
                    exact("\(name) shortfall \(i) missing", x["missing"], pair.1.missing)
                }
                let all = PortfolioCalculator.walletShortfalls(trades, method: method)
                XCTAssertEqual(ledger.walletShortfalls, all.filter { $0.coin == p.coin }, "\(name) walletShortfalls")
            }
            if let expected = e["disposals"] as? [Any] {
                XCTAssertEqual(ledger.disposals.count, expected.count, "\(name) disposals")
                for (i, pair) in zip(expected, ledger.disposals).enumerated() {
                    let x = pair.0 as? [String: Any] ?? [:]
                    let m = pair.1
                    let label = "\(name) disposal \(i)"
                    XCTAssertEqual(m.coin, x["coin"] as? String, "\(label) coin")
                    XCTAssertEqual(m.sellTxId, Int64(TestSupport.number(x["sellTxId"]) ?? 0), "\(label) sellTxId")
                    XCTAssertEqual(m.lotTxId, TestSupport.number(x["lotTxId"]).map { Int64($0) }, "\(label) lotTxId")
                    exact("\(label) amount", x["amount"], m.amount)
                    optionalExact("\(label) cost", x["costUsdt"], m.costUsdt)
                    optionalExact("\(label) proceeds", x["proceedsUsdt"], m.proceedsUsdt)
                    optionalExact("\(label) gain", x["gainUsdt"], m.gainUsdt)
                    XCTAssertEqual(m.holdingDays, TestSupport.number(x["holdingDays"]).map { Int($0) }, "\(label) holdingDays")
                    XCTAssertEqual(m.lotKind?.rawValue, x["lotKind"] as? String, "\(label) lotKind")
                    XCTAssertEqual(m.feeLeg, x["feeLeg"] as? Bool, "\(label) feeLeg")
                    XCTAssertEqual(m.method, method, "\(label) method")
                    XCTAssertEqual(m.uncovered, x["uncovered"] as? Bool ?? false, "\(label) uncovered")
                    XCTAssertEqual(m.swap, x["swap"] as? Bool ?? false, "\(label) swap")
                    XCTAssertEqual(m.feeExpense?.rawValue, x["feeExpense"] as? String, "\(label) feeExpense")
                    if x.keys.contains("wallet") { XCTAssertEqual(m.wallet, x["wallet"] as? String, "\(label) wallet") }
                    if let parts = x["costParts"] as? [Any] {
                        XCTAssertEqual(m.costParts.count, parts.count, "\(label) costParts")
                        for (j, part) in zip(parts, m.costParts).enumerated() {
                            let y = part.0 as? [String: Any] ?? [:]
                            XCTAssertEqual(part.1.time, Int64(TestSupport.number(y["time"]) ?? 0), "\(label) part \(j) time")
                            exact("\(label) part \(j) usdt", y["usdt"], part.1.usdt)
                        }
                    }
                }
            }
            if let gain = e["withdrawnGain"] as? String {
                let w = PortfolioCalculator.withdrawnGain(ledger.withdrawals)
                XCTAssertEqual(w.amount, dec(gain), "\(name) withdrawnGain")
                XCTAssertEqual(w.incomplete, e["withdrawnGainIncomplete"] as? Bool ?? false, "\(name) withdrawnGainIncomplete")
            }
        }
    }

    /// Tausch: Bestand und Zuflüsse je Schritt beider Seiten — wie `ledgerFlows` (Kotlin).
    func testLedgerFlows() throws {
        let cases = TestSupport.list(try TestSupport.fixture("portfolio")["ledgerFlows"])
        XCTAssertFalse(cases.isEmpty)
        for c in cases {
            let name = c["name"] as? String ?? ""
            let trades = (c["trades"] as? [Any] ?? []).map(trade)
            let market = (c["market"] as? [String: Any] ?? [:]).mapValues { prices($0) }
            XCTAssertEqual(PortfolioLedger.coins(trades), c["coins"] as? [String], "\(name) coins")
            for (coin, rawSteps) in c["expected"] as? [String: Any] ?? [:] {
                let steps = rawSteps as? [Any] ?? []
                let events = PortfolioLedger.events(coin, trades: trades)
                XCTAssertEqual(events.count, steps.count, "\(name) \(coin) events")
                var holdings: Decimal = 0
                for (i, pair) in zip(steps, events).enumerated() {
                    let x = pair.0 as? [String: Any] ?? [:]
                    let e = pair.1
                    let label = "\(name) \(coin) step \(i)"
                    XCTAssertEqual(e.trade.id, Int64(TestSupport.number(x["txId"]) ?? 0), "\(label) txId")
                    XCTAssertEqual(e.swapIn, x["swapIn"] as? Bool ?? false, "\(label) swapIn")
                    XCTAssertEqual(PortfolioLedger.needsMarketPrice(e), x["needsMarket"] as? Bool ?? false, "\(label) needsMarket")
                    let price = market[String(e.trade.id)]?[coin]
                    near("\(label) flow", TestSupport.number(x["flow"]),
                         PortfolioLedger.flow(e, holdingsBefore: holdings, marketPrice: price))
                    holdings = PortfolioLedger.holdingsAfter(holdings, e)
                    exact("\(label) holdings", x["holdings"], holdings)
                }
            }
        }
    }

    /// CSV-Importlauf: Kennung (FNV-1a) und Liste der Läufe — wie `importBatch` (Kotlin).
    func testImportBatch() throws {
        let d = try TestSupport.fixture("portfolio")["importBatch"] as? [String: Any] ?? [:]
        for x in TestSupport.list(d["newId"]) {
            let id = PortfolioImportBatch.newId(format: x["format"] as? String ?? "",
                                                time: Int64(TestSupport.number(x["time"]) ?? 0),
                                                keys: x["keys"] as? [String] ?? [])
            XCTAssertEqual(id, x["expected"] as? String)
        }
        let s = d["summarize"] as? [String: Any] ?? [:]
        let input = (s["input"] as? [Any] ?? []).map { $0 as? String }
        let got = PortfolioImportBatch.summarize(input)
        let expected = TestSupport.list(s["expected"])
        XCTAssertEqual(got.count, expected.count)
        for (x, b) in zip(expected, got) {
            XCTAssertEqual(b.id, x["id"] as? String)
            XCTAssertEqual(b.format, x["format"] as? String)
            XCTAssertEqual(b.time, TestSupport.number(x["time"]).map { Int64($0) })
            XCTAssertEqual(b.count, Int(TestSupport.number(x["count"]) ?? -1))
        }
    }

    /// Exakt wie `exact`, aber nil erwartet (JSON null) = nil.
    private func optionalExact(_ label: String, _ expected: Any?, _ actual: Decimal?) {
        if expected is String { exact(label, expected, actual) } else { XCTAssertNil(actual, "\(label): expected nil") }
    }

    func testExchangeByLedger() throws {
        let cases = TestSupport.list(try TestSupport.fixture("portfolio")["exchangeByLedger"])
        XCTAssertGreaterThanOrEqual(cases.count, 5)
        for c in cases {
            let name = c["name"] as? String ?? ""
            let entries = (c["entries"] as? [Any] ?? []).map { raw -> PortfolioExchangeRoute.RouteEntry in
                let o = raw as? [String: Any] ?? [:]
                return PortfolioExchangeRoute.RouteEntry(
                    coin: o["coin"] as? String ?? "",
                    exchange: o["exchange"] as? String,
                    type: PortfolioTxType(rawValue: o["type"] as? String ?? "") ?? .BUY,
                    transferTo: o["transferTo"] as? String,
                    time: Int64(TestSupport.number(o["time"]) ?? 0),
                    id: Int64(TestSupport.number(o["id"]) ?? 0))
            }
            let result = PortfolioExchangeRoute.exchangeByLedger(entries)
            XCTAssertEqual(result.single, c["single"] as? [String: String] ?? [:], "\(name) single")
            XCTAssertEqual(result.mixed, Set(c["mixed"] as? [String] ?? []), "\(name) mixed")
        }
    }

    func testPeriodGain() throws {
        let data = try TestSupport.fixture("portfolio")
        let baseDay = Int(TestSupport.number(data["baseDay"]) ?? 0)
        for c in TestSupport.list(data["periodGain"]) {
            let name = c["name"] as? String ?? ""
            let gain = PortfolioPeriodChange.gain(trades: (c["trades"] as? [Any] ?? []).map(trade),
                                                  current: prices(c["current"]), closes: series(c["closes"]), baseDay: baseDay)
            guard let e = c["expected"] as? [String: Any] else {
                XCTAssertNil(gain, "\(name): expected no number")
                continue
            }
            guard let gain else { XCTFail("\(name): expected a number"); continue }
            near("\(name) amount", TestSupport.number(e["amount"]), gain.amount)
            near("\(name) percent", TestSupport.number(e["percent"]), gain.percent)
            XCTAssertEqual(gain.skipped, e["skipped"] as? [String] ?? [], "\(name) skipped")
        }
    }

    /// M-4: EINE Tagesgrenze (UTC) — «7 T» und Wertverlauf zählen einen Kauf um Mitternacht gleich.
    func testDayBoundary() throws {
        let cases = TestSupport.list(try TestSupport.fixture("portfolio")["dayBoundary"])
        XCTAssertGreaterThanOrEqual(cases.count, 2)
        for c in cases {
            let name = c["name"] as? String ?? ""
            let trades = (c["trades"] as? [Any] ?? []).map(trade)
            let today = Int(TestSupport.number(c["today"]) ?? 0)
            let zone = TimeZone(identifier: c["zone"] as? String ?? "UTC")!
            let e = c["expected"] as? [String: Any] ?? [:]
            let gain = PortfolioPeriodChange.gain(trades: trades, current: prices(c["current"]), closes: series(c["closes"]),
                                                  baseDay: Int(TestSupport.number(c["baseDay"]) ?? 0))
            near("\(name) gain", TestSupport.number(e["gain"]), gain?.amount)
            near("\(name) percent", TestSupport.number(e["percent"]), gain?.percent)
            // Lokales Tagesende des Aufrufers zählt für die Zuordnung nicht (UTC-Tage)
            let history = PortfolioHistory.build(trades: trades, closes: series(c["closes"]), livePrices: prices(c["current"]),
                                                 range: .week, todayEpochDay: today,
                                                 dayEndMillis: { PortfolioHistory.dayEndMillis($0, timeZone: zone) })
            near("\(name) first point", TestSupport.number(e["firstPoint"]), history.points.first?.value)
            near("\(name) history pnl", TestSupport.number(e["historyPnl"]), history.pnlChange)
            near("\(name) same number", gain?.amount, history.pnlChange)
        }
    }

    func testTodayAdjusted() throws {
        let cases = TestSupport.list(try TestSupport.fixture("portfolio")["todayAdjusted"])
        XCTAssertGreaterThanOrEqual(cases.count, 3)
        for c in cases {
            let name = c["name"] as? String ?? ""
            let current = prices(c["current"])
            let coinChanges = prices(c["coinChanges"])
            let change = PortfolioPeriodChange.todayFromCoinChanges(holdings: prices(c["holdings"]), current: current,
                                                                    coinChanges: coinChanges)
            let today = PortfolioPeriodChange.todayAdjusted(change, trades: (c["trades"] as? [Any] ?? []).map(trade),
                                                            current: current, coinChanges: coinChanges,
                                                            windowStart: Int64(TestSupport.number(c["windowStart"]) ?? 0))
            // Zeilen: Gewinn/Verlust je Coin, Summe = «Heute»
            let byCoin = PortfolioPeriodChange.todayByCoin(holdings: prices(c["holdings"]),
                                                           trades: (c["trades"] as? [Any] ?? []).map(trade),
                                                           current: current, coinChanges: coinChanges,
                                                           windowStart: Int64(TestSupport.number(c["windowStart"]) ?? 0))
            if let rows = c["byCoin"] as? [String: Any] {
                XCTAssertEqual(Set(byCoin.keys), Set(rows.keys), "\(name) byCoin coins")
                for (coin, raw) in rows {
                    let row = raw as? [String: Any] ?? [:]
                    near("\(name) \(coin) amount", TestSupport.number(row["amount"]), byCoin[coin]?.amount)
                    near("\(name) \(coin) percent", TestSupport.number(row["percent"]), byCoin[coin]?.percent)
                }
                if let today {
                    near("\(name) rows sum", today.amount, byCoin.values.reduce(0) { $0 + $1.amount })
                }
            }
            guard let e = c["expected"] as? [String: Any] else {
                XCTAssertNil(today, "\(name): expected no number")
                continue
            }
            guard let today else { XCTFail("\(name): expected a number"); continue }
            near("\(name) amount", TestSupport.number(e["amount"]), today.amount)
            near("\(name) percent", TestSupport.number(e["percent"]), today.percent)
            XCTAssertEqual(today.skipped, e["skipped"] as? [String] ?? [], "\(name) skipped")
        }
    }

    func testScaleCloses() throws {
        for c in TestSupport.list(try TestSupport.fixture("portfolio")["scaleCloses"]) {
            let name = c["name"] as? String ?? ""
            let result = PortfolioExchangeRoute.scaleCloses(series(c["closes"]), prices: prices(c["prices"]),
                                                            binance: prices(c["binance"]))
            let expected = series(c["expected"])
            XCTAssertEqual(Set(result.keys), Set(expected.keys), "\(name) coins")
            for (coin, values) in expected {
                for (day, value) in values { near("\(name) \(coin) \(day)", value, result[coin]?[day]) }
            }
        }
    }

    private func daily(_ raw: Any?) -> [Int: Double] {
        var out: [Int: Double] = [:]
        for (k, x) in raw as? [String: Any] ?? [:] { if let d = Int(k), let n = TestSupport.number(x) { out[d] = n } }
        return out
    }

    private func names(_ raw: Any?) -> Set<String> { Set((raw as? [Any] ?? []).compactMap { $0 as? String }) }

    func testHistoryCloses() throws {
        for c in TestSupport.list(try TestSupport.fixture("portfolio")["historyCloses"]) {
            let name = c["name"] as? String ?? ""
            let result = PortfolioExchangeRoute.historyCloses(series(c["closes"]), ranges: [:], own: names(c["own"]),
                                                              routed: names(c["routed"]), failed: names(c["failed"]),
                                                              prices: prices(c["prices"]), binance: prices(c["binance"]))
            let expected = series(c["expected"])
            XCTAssertEqual(Set(result.closes.keys), Set(expected.keys), "\(name) coins")
            for (coin, values) in expected {
                XCTAssertEqual(Set(result.closes[coin]?.keys.map { $0 } ?? []), Set(values.keys), "\(name) \(coin) days")
                for (day, value) in values { near("\(name) \(coin) \(day)", value, result.closes[coin]?[day]) }
            }
            XCTAssertEqual(result.scaled, names(c["expectedScaled"]), "\(name) scaled")
        }
    }

    func testOwnDailyUsdt() throws {
        for c in TestSupport.list(try TestSupport.fixture("portfolio")["ownDailyUsdt"]) {
            let name = c["name"] as? String ?? ""
            let rates: [Int: Double]? = c["rates"] is [String: Any] ? daily(c["rates"]) : nil
            // USDT zum Marktkurs (optional): USDT/USD-Tagesschluss
            let usdtUsd: [Int: Double]? = c["usdtUsd"] is [String: Any] ? daily(c["usdtUsd"]) : nil
            let result = PortfolioExchangeRoute.ownDailyUsdt(PortfolioCoinDaily(closes: daily(c["closes"])),
                                                             quote: c["quote"] as? String ?? "", usdToByDay: rates,
                                                             usdtUsdByDay: usdtUsd)
            guard c["expected"] is [String: Any] else {
                XCTAssertNil(result, "\(name): expected nil")
                continue
            }
            let expected = daily(c["expected"])
            XCTAssertEqual(Set(result?.closes.keys.map { $0 } ?? []), Set(expected.keys), "\(name) days")
            for (day, value) in expected { near("\(name) \(day)", value, result?.closes[day]) }
        }
    }

    /// USDT zum Marktkurs USDT/USD in Fiat (Owner-Entscheid Okt. 2026); ohne Kurs 1, als Näherung markiert.
    func testUsdtMarket() throws {
        let m = try TestSupport.fixture("portfolio")["usdtMarket"] as? [String: Any] ?? [:]
        for c in TestSupport.list(m["usdtUsd"]) {
            let u = PortfolioStables.usdtUsd(TestSupport.number(c["market"]))
            near("usdtUsd \(c)", TestSupport.number(c["rate"]), u.rate)
            XCTAssertEqual(u.approximate, c["approximate"] as? Bool, "usdtUsd approximate \(c)")
        }
        for c in TestSupport.list(m["usdtTo"]) {
            near("usdtTo \(c)", TestSupport.number(c["expected"]),
                 PortfolioStables.usdtTo(TestSupport.number(c["usdToTarget"]),
                                         usdt: PortfolioStables.usdtUsd(TestSupport.number(c["market"]))))
        }
        for c in TestSupport.list(m["ratesOn"]) {
            let rates = UsdtUsdRates(live: TestSupport.number(c["live"]), daily: daily(c["daily"]))
            let u = rates.on(Int(TestSupport.number(c["day"]) ?? 0), todayEpochDay: Int(TestSupport.number(c["today"]) ?? 0))
            near("ratesOn \(c)", TestSupport.number(c["rate"]), u.rate)
            XCTAssertEqual(u.approximate, c["approximate"] as? Bool, "ratesOn approximate \(c)")
        }
        for c in TestSupport.list(m["toUsdt"]) {
            let usdTo = c["usdTo"] as? [String: Any] ?? [:]
            let result = PortfolioExchangeRoute.toUsdt(TestSupport.number(c["price"]) ?? 0, quote: c["quote"] as? String ?? "",
                                                       usdtUsd: TestSupport.number(c["usdtUsd"]) ?? 1) {
                TestSupport.number(usdTo[$0])
            }
            near("toUsdt \(c)", TestSupport.number(c["expected"]), result)
        }
        for c in TestSupport.list(m["holdings"]) {
            let name = c["name"] as? String ?? ""
            let trades = (c["trades"] as? [Any] ?? []).map(trade)
            let total = PortfolioCalculator.summarize(trades, prices: prices(c["prices"])).totalValue
            let usdt = PortfolioStables.usdtUsd(TestSupport.number(c["usdtMarket"]))
            let rate = PortfolioStables.usdtTo(TestSupport.number(c["usdToTarget"]), usdt: usdt) ?? .nan
            let par = PortfolioStables.usdtTo(TestSupport.number(c["usdToTarget"]), usdt: PortfolioStables.usdtUsd(1)) ?? .nan
            near("\(name) valueUsdt", TestSupport.number(c["valueUsdt"]), total)
            near("\(name) valueTarget", TestSupport.number(c["valueTarget"]), total * rate)
            XCTAssertEqual(usdt.approximate, c["approximate"] as? Bool, "\(name) approximate")
            near("\(name) change vs USDT = 1", TestSupport.number(c["changeVsParPercent"]), (rate / par - 1) * 100)
            // Anzeigewährung (auch USD): Faktor von Widget und Karte = derselbe Wert, Verlauf wird umgerechnet
            if let currency = c["currency"] as? String {
                let factor = PortfolioWidgetChart.usdtFactor(currency: currency, fx: TestSupport.number(c["usdToTarget"]) ?? .nan,
                                                             usdtLive: TestSupport.number(c["usdtMarket"]))
                near("\(name) valueTarget (\(currency), widget/card)", TestSupport.number(c["valueTarget"]), total * factor)
                XCTAssertTrue(PortfolioHistoryFx.convertsUsdt(currency, usdtMarket: true), "\(name) history converted")
            }
        }
        XCTAssertTrue(TestSupport.list(m["holdings"]).contains { $0["currency"] as? String == "USD" }, "USD display case")
    }

    func testStitch() throws {
        for c in TestSupport.list(try TestSupport.fixture("portfolio")["stitch"]) {
            let name = c["name"] as? String ?? ""
            let result = PortfolioExchangeRoute.stitch(PortfolioCoinDaily(closes: daily(c["own"])),
                                                       binance: PortfolioCoinDaily(closes: daily(c["binance"])))
            let expected = daily(c["expected"])
            XCTAssertEqual(Set(result.daily.closes.keys), Set(expected.keys), "\(name) days")
            for (day, value) in expected { near("\(name) \(day)", value, result.daily.closes[day]) }
            XCTAssertEqual(result.binanceBefore, TestSupport.number(c["binanceBefore"]).map { Int($0) }, "\(name) binanceBefore")
        }
    }

    func testScaledHint() throws {
        for c in TestSupport.list(try TestSupport.fixture("portfolio")["scaledHint"]) {
            let name = c["name"] as? String ?? ""
            var before: [String: Int] = [:]
            for (coin, x) in c["binanceBefore"] as? [String: Any] ?? [:] { if let n = TestSupport.number(x) { before[coin] = Int(n) } }
            let result = PortfolioExchangeRoute.scaledHint(scaled: names(c["scaled"]), binanceBefore: before,
                                                           fromDay: Int(TestSupport.number(c["fromDay"]) ?? 0),
                                                           coins: (c["coins"] as? [Any] ?? []).compactMap { $0 as? String })
            XCTAssertEqual(result, (c["expected"] as? [Any] ?? []).compactMap { $0 as? String }, name)
        }
    }
}
