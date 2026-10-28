import XCTest
@testable import CryptoChecker

// Kleine Testklassen dieses Themas in einer Datei; jede Klasse unverändert.

// ---- aus PortfolioFifoPartsTests

/// Herkunftsteile der FIFO-Posten erst beim Auslesen (PortfolioFifoParts.swift): Die Gebühr USDT einer Umbuchung steht
/// einmal im Gebühren-Strom der Warteschlange; Veräusserungen und offener Einstand lesen ihre Teile daraus. Gleich wie
/// Android (`PortfolioFifoPartsTest`):
///  - jede Veräusserung: Summe der Teile = Einstand (exakt), kein Teil nach dem Abgang;
///  - offene Posten: Summe der Teile = Einstand mit Kaufpreis (exakt);
///  - Umbuchung auf zwei Posten mit Kaufpreis und einen ohne: Anteil nach Menge, der Anteil ohne Kaufpreis vermerkt.
final class PortfolioFifoPartsTests: XCTestCase {
    private let tolerance = Decimal(string: "1E-18")!

    private func sum<C: Collection>(_ parts: C) -> Decimal where C.Element == PortfolioCostPart {
        parts.reduce(Decimal(0)) { $0 + $1.usdt }
    }

    func testPartsAddUpToCostOnLargePortfolio() {
        let trades = PortfolioPerformanceData.trades(PerformanceTiming.transactions.small)
        for method in [CostMethod.FIFO, .FIFO_WALLET] {
            let books = PortfolioCalculator.ledgers(trades, method: method)
            var checked = 0
            for book in books {
                for d in book.disposals {
                    guard let cost = d.costUsdt, !d.costParts.isEmpty else { continue }
                    // Decimal hat 38 Stellen: Summen ab der 38. Stelle gerundet (Toleranz wie die übrigen Summen)
                    XCTAssertLessThanOrEqual(abs(cost - sum(d.costParts)), tolerance, "\(method) \(d.coin) \(d.sellTxId)")
                    XCTAssertTrue(d.costParts.allSatisfy { $0.time <= d.sellTime })
                    checked += 1
                }
                let open = book.position.pricedCostParts
                if !open.isEmpty, let cost = book.position.pricedCostExact {
                    XCTAssertLessThanOrEqual(abs(cost - sum(open)), tolerance, "\(method) \(book.position.coin) open")
                }
            }
            XCTAssertGreaterThan(checked, 100, "\(method): Veräusserungen mit Teilen geprüft")
        }
    }

    func testTransferFeeSharedByAmountAcrossQueue() throws {
        let t0: Int64 = 1_700_000_000_000
        let day: Int64 = 86_400_000
        func tx(_ id: Int64, _ type: PortfolioTxType, _ amount: Decimal, _ price: Decimal?, _ time: Int64,
                fee: Decimal? = nil) -> PortfolioTx {
            PortfolioTx(id: id, coin: "BTC", type: type, amountExact: amount, priceExact: price, time: time,
                        feeExact: fee, feeUnit: fee == nil ? nil : .QUOTE)
        }
        let list = [
            tx(1, .BUY, 1, 100, t0),
            tx(2, .DEPOSIT, 2, nil, t0 + day),
            tx(3, .BUY, 1, 200, t0 + 2 * day),
            // Gebühr 8 USDT auf 4 Coins: je Coin 2 — Posten 1 und 3 je 2, Posten 2 (ohne Kaufpreis) 4 vermerkt
            tx(4, .TRANSFER, 1, nil, t0 + 3 * day, fee: 8),
            tx(5, .SELL, 1, 500, t0 + 4 * day),
        ]
        let books = PortfolioCalculator.ledgers(list, method: .FIFO)
        XCTAssertEqual(books.count, 1)
        let book = try XCTUnwrap(books.first)
        XCTAssertEqual(book.disposals.count, 1)
        let sale = try XCTUnwrap(book.disposals.first)
        XCTAssertEqual(sale.costUsdt, 102)
        XCTAssertEqual(sale.costParts.map(\.time), [t0, t0 + 3 * day])
        XCTAssertEqual(sale.costParts.map(\.usdt), [100, 2])
        XCTAssertEqual(book.unassignedFees.map(\.usdt), [4])
        // Offen: Posten 3 mit 200 + 2 (und Posten 2 ohne Kaufpreis)
        XCTAssertEqual(book.position.pricedCostExact, 202)
        XCTAssertEqual(book.position.pricedCostParts.map(\.time), [t0 + 2 * day, t0 + 3 * day])
    }
}

// ---- aus PortfolioFifoQueueTests

/// `PortfolioFifo.Queue.insert` (binäre Suche) ordnet Posten genau wie das schrittweise Suchen von hinten: hinter allen
/// Posten, vor denen der neue nicht liegt — auch bei gleichem Erwerb, gleicher Zeit und gleicher Id (verschobene Teile
/// eines Postens) und nach verbrauchten Posten vorn.
final class PortfolioFifoQueueTests: XCTestCase {
    private typealias Lot = PortfolioFifo.Lot

    /// Referenz: von hinten suchen, solange der neue Posten vor dem davor liegt.
    private static func insertLinear(_ list: inout [Lot], _ lot: Lot) {
        var i = list.count
        while i > 0 && lot.isBefore(list[i - 1]) { i -= 1 }
        list.insert(lot, at: i)
    }

    func testInsertOrderMatchesLinearSearch() {
        var state: UInt64 = 7
        func next(_ bound: Int) -> Int {
            state = state &* 6_364_136_223_846_793_005 &+ 1_442_695_040_888_963_407
            return Int((state >> 33) % UInt64(bound))
        }
        let queue = PortfolioFifo.Queue(key: "")
        var reference: [Lot] = []
        for step in 0..<3_000 {
            if next(4) == 0, !reference.isEmpty {
                queue.removeFirst()
                reference.removeFirst()
                continue
            }
            // Wenige verschiedene Werte: viele Gleichstände bei Erwerb, Zeit und Id
            let acquired = Int64(next(40))
            let lot = Lot(txId: Int64(next(6)), acquired: acquired, time: acquired + Int64(next(3)), kind: .BUY,
                          amount: Decimal(step + 1), cost: nil)
            queue.insert(lot)
            PortfolioFifoQueueTests.insertLinear(&reference, lot)
            let open = Array(queue.lots)
            XCTAssertEqual(open.count, reference.count)
            XCTAssertTrue(zip(open, reference).allSatisfy { pair in pair.0 === pair.1 }, "Schritt \(step)")
        }
        let expected = reference.reduce(Decimal(0)) { $0 + $1.amount }
        XCTAssertEqual(queue.lots.reduce(Decimal(0)) { $0 + $1.amount }, expected)
    }
}

// ---- aus PortfolioHistoryAsOfTests

/// Älterer Stand im Wertverlauf (eigene Börse nicht erreichbar); Gegenstück `PortfolioHistoryAsOfTest`.
final class PortfolioHistoryAsOfTests: XCTestCase {
    private let now: Int64 = 1_700_000_000_000
    private let ttl: Int64 = 12 * 60 * 60_000

    func testOldestStaleTimeAmongDrawnCoins() {
        let stale: [String: Int64] = ["BTC": now - 3 * ttl, "ETH": now - 2 * ttl, "SOL": now - 5 * ttl]
        // SOL ist nicht gezeichnet (ohne Verlauf) und zählt nicht
        XCTAssertEqual(PortfolioHistoryAsOf.of(ownStale: stale, drawn: ["BTC", "ETH", "ADA"]), now - 3 * ttl)
        XCTAssertNil(PortfolioHistoryAsOf.of(ownStale: stale, drawn: ["ADA"]))
        XCTAssertNil(PortfolioHistoryAsOf.of(ownStale: [:], drawn: ["BTC"]))
        XCTAssertNil(PortfolioHistoryAsOf.of(ownStale: ["BTC": 0], drawn: ["BTC"]))
    }

    func testFallbackIsStaleOnlyWhenOlderThanTheCacheTime() {
        // Jünger als 12 h: heutiger Verlauf (nur evtl. kürzer), kein «Stand …»
        XCTAssertNil(PortfolioHistoryAsOf.staleSince(savedAt: now - 60_000, now: now, ttl: ttl))
        // Älter: mit seiner Zeit markiert
        XCTAssertEqual(PortfolioHistoryAsOf.staleSince(savedAt: now - ttl, now: now, ttl: ttl), now - ttl)
        XCTAssertEqual(PortfolioHistoryAsOf.staleSince(savedAt: now - 3 * ttl, now: now, ttl: ttl), now - 3 * ttl)
        // Unbekannte Zeit gilt nicht als frisch; künftige (Uhr zurückgestellt) wie frisch, nie eine künftige Anzeige
        XCTAssertEqual(PortfolioHistoryAsOf.staleSince(savedAt: 0, now: now, ttl: ttl), 0)
        XCTAssertNil(PortfolioHistoryAsOf.staleSince(savedAt: now + 60_000, now: now, ttl: ttl))
    }
}

// ---- aus PortfolioLoadStateTests

/// Portfolio beim Start im Hintergrund gelesen (`PortfolioLoadState`, AppData+PortfolioLoad.swift): Nur das erste
/// Lesen wird übernommen — ein späteres (das im Hintergrund nach einem sofortigen Zugriff) ersetzt nie einen schon
/// übernommenen oder inzwischen geänderten Stand; Sortierung wie bisher (`PortfolioStore.newestFirst`).
@MainActor
final class PortfolioLoadStateTests: XCTestCase {

    private func tx(_ id: Int64, _ time: Int64) -> PortfolioTx {
        PortfolioTx(id: id, coin: "BTC", type: .BUY, amountExact: 1, priceExact: 100, time: time)
    }

    private func read(_ txs: [PortfolioTx], intact: Bool = true) -> PortfolioRead {
        var file = PortfolioFile()
        for t in txs { PortfolioStore.insert(t, into: &file) }
        return PortfolioRead(file: file, intact: intact, newestFirst: PortfolioStore.newestFirst(file.transactions),
                             restoreData: nil)
    }

    func testOnlyFirstReadIsAdopted() {
        var state = PortfolioLoadState()
        XCTAssertFalse(state.loaded)
        let first = read([tx(1, 1_000), tx(2, 3_000), tx(3, 2_000)])
        XCTAssertTrue(state.adopt(first))
        XCTAssertTrue(state.loaded)
        XCTAssertEqual(state.list.map(\.id), [2, 3, 1])
        XCTAssertEqual(state.file.transactions.count, 3)
        XCTAssertTrue(state.intact)
        // Seither geändert (z. B. Transaktion hinzugefügt), dann kommt das Lesen im Hintergrund: bleibt der neue Stand
        state.list = [tx(4, 4_000)] + state.list
        XCTAssertFalse(state.adopt(read([], intact: false)))
        XCTAssertEqual(state.list.map(\.id), [4, 2, 3, 1])
        XCTAssertTrue(state.intact)
    }

    func testUnreadableReadKeepsIntactFalse() {
        var state = PortfolioLoadState()
        XCTAssertTrue(state.adopt(read([], intact: false)))
        XCTAssertFalse(state.intact)
        XCTAssertTrue(state.list.isEmpty)
    }

    /// Zugriff über `AppData` liefert immer einen gelesenen Stand (nie «noch nicht gelesen»), sortiert wie die Datei.
    func testAppDataAccessReadsBeforeUse() {
        let data = AppData.shared
        let list = data.portfolio
        XCTAssertTrue(data.portfolioState.loaded)
        XCTAssertEqual(list, PortfolioStore.newestFirst(data.portfolioFile.transactions))
    }
}

// ---- aus PortfolioOffTests

/// Portfolio ausgeschaltet — gleiche Fälle wie `PortfolioOffTest`.
final class PortfolioOffTests: XCTestCase {

    func testOnlyNewTransactionEnablesPortfolio() {
        XCTAssertTrue(PortfolioOff.enableOnSave(isNew: true, portfolioEnabled: false))
        XCTAssertFalse(PortfolioOff.enableOnSave(isNew: true, portfolioEnabled: true))
        XCTAssertFalse(PortfolioOff.enableOnSave(isNew: false, portfolioEnabled: false))
        XCTAssertFalse(PortfolioOff.enableOnSave(isNew: false, portfolioEnabled: true))
    }

    func testOnlyFirstNewTransactionAsksForTab() {
        XCTAssertTrue(PortfolioOff.askEnableOnSave(isNew: true, portfolioEnabled: false, hadTransactions: false))
        XCTAssertFalse(PortfolioOff.askEnableOnSave(isNew: true, portfolioEnabled: false, hadTransactions: true))
        XCTAssertFalse(PortfolioOff.askEnableOnSave(isNew: true, portfolioEnabled: true, hadTransactions: false))
        XCTAssertFalse(PortfolioOff.askEnableOnSave(isNew: false, portfolioEnabled: false, hadTransactions: false))
    }

    func testAddToPortfolioAlwaysOfferedExceptPreview() {
        XCTAssertTrue(PortfolioOff.offerAddToPortfolio(preview: false))
        XCTAssertFalse(PortfolioOff.offerAddToPortfolio(preview: true))
    }

    func testWidgetOffBeatsLockAndEmpty() {
        XCTAssertEqual(PortfolioOff.widgetState(portfolioEnabled: false, locked: true, empty: true), .off)
        XCTAssertEqual(PortfolioOff.widgetState(portfolioEnabled: false, locked: false, empty: false), .off)
        XCTAssertEqual(PortfolioOff.widgetState(portfolioEnabled: true, locked: true, empty: true), .locked)
        XCTAssertEqual(PortfolioOff.widgetState(portfolioEnabled: true, locked: false, empty: true), .empty)
        XCTAssertEqual(PortfolioOff.widgetState(portfolioEnabled: true, locked: false, empty: false), .values)
    }

    func testWidgetOpensTabOrSettings() {
        XCTAssertEqual(PortfolioOff.widgetTarget(portfolioEnabled: true), .portfolioTab)
        XCTAssertEqual(PortfolioOff.widgetTarget(portfolioEnabled: false), .portfolioSettings)
    }
}

// ---- aus PortfolioOpenLotItemTests

/// «Offene Kauf-Posten»: Zeilen der Karte nur, wenn sie gezeigt werden (`PortfolioOpenLotItem.shown`) — mit denselben
/// Ids und Posten wie aus der ganzen Liste, die das Blatt «Alle zeigen» zeigt (`PortfolioOpenLotItem.of`).
final class PortfolioOpenLotItemTests: XCTestCase {

    private func lots(_ count: Int) -> [PortfolioOpenLot] {
        // Gleiche Angaben mehrfach (fortlaufend gezählte Ids), teils ohne Kaufpreis, zwei Wallets. Werte einzeln
        // ausgerechnet: als ein Ausdruck braucht die Typprüfung zu lange
        var out: [PortfolioOpenLot] = []
        for i in 0..<count {
            let txId = Int64(i % 7)
            let time = Int64(i) * 1_000
            let amount = Decimal(i + 1)
            let cost: Decimal? = i % 5 == 0 ? nil : Decimal(i * 10)
            let wallet: String? = i % 2 == 0 ? "Kraken" : nil
            out.append(PortfolioOpenLot(coin: "BTC", txId: txId, acquiredTime: time, kind: .BUY, amount: amount,
                                        costUsdt: cost, wallet: wallet, aggregated: false))
        }
        return out
    }

    func testShownRowsSameAsFromAllLots() {
        // Wie `PortfolioOpenLotsSection.shownLots` (dort Hauptthread-gebunden)
        let limit = 50
        for count in [0, 1, limit - 1, limit, limit + 1, 3 * limit + 7] {
            let all = lots(count)
            let full = PortfolioOpenLotItem.of(all)
            XCTAssertTrue(PortfolioOpenLotItem.shown(all, expanded: false, limit: limit).isEmpty)
            let expanded = PortfolioOpenLotItem.shown(all, expanded: true, limit: limit)
            XCTAssertEqual(expanded.map(\.id), full.prefix(limit).map(\.id), "\(count)")
            XCTAssertEqual(expanded.map(\.lot), full.prefix(limit).map(\.lot), "\(count)")
            // Das Blatt «Alle zeigen» nimmt immer die ganze Liste (`of`), auch beim Schliessen
            XCTAssertEqual(full.map(\.lot), all, "\(count)")
            XCTAssertEqual(Set(full.map(\.id)).count, full.count, "Ids eindeutig")
        }
    }
}

// ---- aus PortfolioPriceSourceClearTests

/// Review IOS2-04: «Portfolio leeren» (`PortfolioPriceSource.clearStored`) vergisst auch die Coins
/// mit «Kurs nicht verfügbar» — sie nennen gehaltene Coins. Android: `PortfolioPriceSourceStore.kt` › `clearStored`.
final class PortfolioPriceSourceClearTests: XCTestCase {

    override func tearDown() {
        PortfolioPriceSource.lock.lock()
        PortfolioPriceSource.unavailableCoins = []
        PortfolioPriceSource.uncheckedCoins = []
        PortfolioPriceSource.lock.unlock()
        super.tearDown()
    }

    func testClearStoredForgetsUnavailableCoins() {
        PortfolioPriceSource.lock.lock()
        PortfolioPriceSource.unavailableCoins = ["ZZTESTA", "ZZTESTB"]
        PortfolioPriceSource.uncheckedCoins = ["ZZTESTC"]
        PortfolioPriceSource.lock.unlock()
        // Gegenprobe: vor dem Leeren gesetzt (gelesen unter der Sperre wie jeder Zugriff)
        let before = PortfolioPriceSource.lock.withLock { PortfolioPriceSource.unavailableCoins }
        XCTAssertEqual(before, ["ZZTESTA", "ZZTESTB"])

        PortfolioPriceSource.clearStored()

        PortfolioPriceSource.lock.lock()
        let unavailable = PortfolioPriceSource.unavailableCoins
        let unchecked = PortfolioPriceSource.uncheckedCoins
        PortfolioPriceSource.lock.unlock()
        XCTAssertTrue(unavailable.isEmpty, "unavailableCoins nach clearStored: \(unavailable)")
        XCTAssertTrue(unchecked.isEmpty, "uncheckedCoins nach clearStored: \(unchecked)")
    }
}

// ---- aus PortfolioUnverifiedAlarmTests

/// «Kurs ungeprüft» (Binance-Kurs mit unbestätigtem Token) zählt für die Portfolio-Alarme wie ein alter
/// Kurs: wesentlich → keine Prüfung und Hinweis mit dem Coin, unwesentlich (unter 1 %) → Alarme prüfen. Wie
/// `PortfolioUnverifiedAlarmTest.kt`.
final class PortfolioUnverifiedAlarmTests: XCTestCase {
    private func snapshot(unverified: [String]? = nil, unverifiedValue: Double? = nil,
                          stale: [String]? = nil, staleValue: Double? = nil) -> PortfolioWidgetSnapshot {
        var s = PortfolioWidgetSnapshot(total: 10_000, changeAmount: nil, changePercent: 1, currency: "USDT",
                                        updatedAt: 1, empty: false, totalUsdt: 10_000)
        s.stalePrices = stale?.count
        s.staleCoins = stale
        s.staleValueUsdt = staleValue
        s.unverifiedCoins = unverified
        s.unverifiedValueUsdt = unverifiedValue
        return s
    }

    func testMaterialUnverifiedPausesAlarms() {
        let s = snapshot(unverified: ["BEAM"], unverifiedValue: 500)
        let reading = s.alarmReading()
        XCTAssertEqual(reading.stalePrices, 0)
        XCTAssertEqual(reading.unverifiedCoins, ["BEAM"])
        XCTAssertEqual(reading.unverifiedValueUsdt, 500, accuracy: 1e-9)
        XCTAssertTrue(PortfolioAlarmLogic.staleMaterial(reading))
        XCTAssertTrue(PortfolioAlarmLogic.incomplete(.VALUE_ABOVE, reading))
        // Eigener Hinweis «Kurs ungeprüft für BEAM»
        XCTAssertEqual(s.alarmPause([.VALUE_ABOVE]), .priceUnverified(["BEAM"]))
    }

    func testImmaterialUnverifiedKeepsAlarms() {
        let s = snapshot(unverified: ["BEAM"], unverifiedValue: 50)
        XCTAssertFalse(PortfolioAlarmLogic.staleMaterial(s.alarmReading()))
        XCTAssertNil(s.alarmPause([.CHANGE_UP]))
    }

    func testUnverifiedAddsToStaleValueAndKeepsStaleWording() {
        // Je 60 USDT: einzeln unwesentlich, zusammen 1.2 % → wesentlich; Satz «kein aktueller Kurs» mit dem alten Coin
        let s = snapshot(unverified: ["BEAM"], unverifiedValue: 60, stale: ["PEPE"], staleValue: 60)
        XCTAssertTrue(PortfolioAlarmLogic.staleMaterial(s.alarmReading()))
        XCTAssertEqual(s.alarmPause([.VALUE_BELOW]), .noPrice(["PEPE"]))
    }
}
