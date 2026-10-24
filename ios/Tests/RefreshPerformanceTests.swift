import XCTest
@testable import CryptoChecker

/// Leistung einer Aktualisierung mit bis zu 2000 Paaren an einer grossen Börse, ohne Netz und Datei: Sammelantwort
/// von Binance lesen, Kurse den Paaren zuordnen und Alarme prüfen (echtes `PriceRefresher.checkAlarms`), Ergebnis auf
/// den Stand übertragen (`Outcome.apply`) und der Vermerk «nicht geprüft» (`AlarmCheckStatus`). Grenzen und
/// Skalierung: `PerformanceTiming`. Wie `RefreshPerformanceTest.kt`.
final class RefreshPerformanceTests: XCTestCase {
    private typealias T = PerformanceTiming
    private typealias Outcome = PriceRefresher.Outcome
    private let now: Int64 = PerformanceData.updatedAt + 60_000

    private func binance() throws -> Market { try XCTUnwrap(MarketsConfig.market(PerformanceData.binance.key)) }

    private func binanceWatches(_ n: Int) -> [Watch] { PerformanceData.watches(n, market: PerformanceData.binance) }

    private func parsedTickers(_ watches: [Watch]) throws -> [String: Ticker] {
        let body = PerformanceData.binanceBulkBody(PerformanceData.newPrices(watches))
        return try binance().parseBulkTickersDetailed(call: 0, body: body).tickers
    }

    private func snapshot(_ watches: [Watch], _ alarms: [Alarm]) -> SharedStorage.Snapshot {
        var snapshot = SharedStorage.Snapshot()
        snapshot.watches = watches
        snapshot.alarms = alarms
        return snapshot
    }

    /// Kurse zuordnen (Kennung → Zeile, wie `PriceFetcher`) und Alarme prüfen wie die Schleife in `PriceRefresher`
    /// (ohne Mitteilungen und Ansagen).
    private static func checkAll(_ watches: [Watch], tickers: [String: Ticker], alarmsByWatch: [Int64: [Alarm]],
                                 now: Int64) -> Outcome {
        var inputs = PriceRefresher.AlarmInputs()
        inputs.alarmsDeliverable = true
        let settings = AppSettings()
        var outcome = Outcome()
        for watch in watches {
            guard let ticker = watch.pairId.flatMap({ tickers[$0] }), ticker.last.isFinite, ticker.last > 0 else {
                outcome.failed += 1
                outcome.prices[watch.id] = PriceRefresher.PriceUpdate(price: nil, time: now,
                                                                      error: BulkTickerRows.rowError,
                                                                      pair: watch.identity)
                continue
            }
            outcome.checked += 1
            let price = ticker.last
            let time = ticker.timestamp > 0 ? ticker.timestamp : now
            let changedAt = PriceFreshness.nextPriceChangedAt(previousPrice: watch.lastPrice,
                                                              previousChangedAt: watch.priceChangedAt,
                                                              price: price, time: time)
            outcome.prices[watch.id] = PriceRefresher.PriceUpdate(price: price, time: time, error: nil,
                                                                  change24h: ticker.change24hPercent,
                                                                  priceChangedAt: changedAt,
                                                                  exchangeTime: ticker.exchangeTime,
                                                                  pair: watch.identity)
            var updated = watch
            updated.previousPrice = watch.lastPrice
            updated.lastPrice = price
            updated.lastUpdate = time
            updated.lastError = nil
            updated.change24h = ticker.change24hPercent
            updated.priceChangedAt = changedAt
            updated.exchangeTime = ticker.exchangeTime
            _ = PriceRefresher.checkAlarms(alarmsByWatch[watch.id] ?? [], watch: watch, updated: updated, price: price,
                                           now: now, settings: settings, speechAllowed: false, inputs: inputs,
                                           outcome: &outcome)
        }
        return outcome
    }

    func testBinanceBulkResponseParses() throws {
        let market = try binance()
        // Rund 1,3 MB JSON mit 2000 Zeilen: erwartet um 100–200 ms (Debug)
        try T.assertScales("Sammelantwort Binance lesen", limitMillis: 3_000) { n in
            let body = PerformanceData.binanceBulkBody(PerformanceData.newPrices(binanceWatches(n)))
            return try T.medianMillis { try market.parseBulkTickersDetailed(call: 0, body: body) }
        }
        let prices = PerformanceData.newPrices(binanceWatches(T.large))
        let parsed = try market.parseBulkTickersDetailed(call: 0, body: PerformanceData.binanceBulkBody(prices))
        XCTAssertEqual(parsed.tickers.count, T.large)
        XCTAssertTrue(parsed.failed.isEmpty)
        let btc = try XCTUnwrap(prices.first { $0.pairId == "BTCUSDT" }).price
        // Acht Nachkommastellen wie bei Binance
        XCTAssertEqual(try XCTUnwrap(parsed.tickers["BTCUSDT"]).last, btc, accuracy: 1e-8 + btc * 1e-12)
    }

    func testAssignAndCheckAlarms() throws {
        let now = self.now
        // Je Paar: Zuordnen, Frische, 1–3 Alarme prüfen, Protokoll — erwartet einige 100 ms (Debug)
        try T.assertScales("Kurse zuordnen und Alarme prüfen", limitMillis: 3_000) { n in
            let watches = binanceWatches(n)
            let byWatch = Dictionary(grouping: PerformanceData.alarms(watches), by: \.watchId)
            let tickers = try parsedTickers(watches)
            return T.medianMillis { Self.checkAll(watches, tickers: tickers, alarmsByWatch: byWatch, now: now) }
        }
        let watches = binanceWatches(T.large)
        let byWatch = Dictionary(grouping: PerformanceData.alarms(watches), by: \.watchId)
        let outcome = Self.checkAll(watches, tickers: try parsedTickers(watches), alarmsByWatch: byWatch, now: now)
        XCTAssertEqual(outcome.checked, T.large)
        XCTAssertEqual(outcome.failed, 0)
        // Etwa jedes hundertste Paar hat eine schon überschrittene Kursmarke; Prozent-Alarme ohne Bezug bekommen einen
        XCTAssertTrue((10...100).contains(outcome.pendingAlarms.count), "ausgelöst: \(outcome.pendingAlarms.count)")
        XCTAssertFalse(outcome.alarms.isEmpty)
    }

    func testApplyPricesToSnapshot() throws {
        let now = self.now
        // Kurse aller Paare übertragen (ohne Alarm-Änderungen): erwartet einige 10 ms (Debug)
        try T.assertScales("Kurse auf den Stand übertragen", limitMillis: 1_000) { n in
            let watches = binanceWatches(n)
            let alarms = PerformanceData.alarms(watches)
            var outcome = Self.checkAll(watches, tickers: try parsedTickers(watches),
                                        alarmsByWatch: Dictionary(grouping: alarms, by: \.watchId), now: now)
            outcome.alarms = [:]
            let stored = snapshot(watches, alarms)
            return T.medianMillis(setup: { stored }) { input in
                var target = input
                outcome.apply(to: &target)
                return target.watches.count
            }
        }
    }

    func testApplyAlarmUpdatesToSnapshot() {
        let now = self.now
        // Jeder Alarm mit neuem Bezug (z. B. nach einem starken Ausschlag). `Outcome.apply(to:)` baut einmal einen Index
        // (`AlarmUpdate.Index`) und findet jeden Alarm direkt — wächst linear, darum mit Skalierungs-Prüfung.
        T.assertScales("Alarm-Änderungen auf den Stand übertragen", limitMillis: 10_000) { n in
            let watches = binanceWatches(n)
            let alarms = PerformanceData.alarms(watches)
            var outcome = Outcome()
            for alarm in alarms {
                var after = alarm
                after.referencePrice = 1
                after.referenceAt = now
                outcome.alarms[alarm.id] = PriceRefresher.AlarmUpdate(evaluated: alarm, result: after,
                                                                      setsEnabled: false)
            }
            let stored = snapshot(watches, alarms)
            return T.medianMillis(setup: { stored }) { input in
                var target = input
                outcome.apply(to: &target)
                return target.alarms.count
            }
        }
        var stored = snapshot(binanceWatches(T.small), PerformanceData.alarms(binanceWatches(T.small)))
        var outcome = Outcome()
        for alarm in stored.alarms {
            var after = alarm
            after.referenceAt = now
            outcome.alarms[alarm.id] = PriceRefresher.AlarmUpdate(evaluated: alarm, result: after, setsEnabled: false)
        }
        outcome.apply(to: &stored)
        XCTAssertTrue(stored.alarms.allSatisfy { $0.referenceAt == now })
    }

    func testUncheckedMarksWithoutExchangeRate() {
        let now = self.now
        // Kursmarken in EUR ohne Devisenkurs: jede Prüfung vermerkt «nicht geprüft» erneut. Jeder Vermerk ändert nur
        // seinen Eintrag (`AlarmCheckStatus.markUnchecked`) — wächst linear, darum mit Skalierungs-Prüfung. Gemessen: eine
        // Prüfung, wenn die Vermerke schon stehen.
        T.assertScales("Vermerk «nicht geprüft» je Alarm (zweite Prüfung)", limitMillis: 10_000) { n in
            let alarms = PerformanceData.alarms(binanceWatches(n), currency: "EUR")
            let mark: (AlarmCheckStatus) -> Int = { status in
                for alarm in alarms {
                    if alarm.currency != nil {
                        status.markUnchecked(alarm.id, reason: .noRate, now: now)
                    } else {
                        status.markChecked(alarm.id)
                    }
                }
                return alarms.count
            }
            return T.medianMillis(setup: { () -> AlarmCheckStatus in
                let status = AlarmCheckStatus()
                _ = mark(status)
                return status
            }, mark)
        }
        let status = AlarmCheckStatus()
        let alarms = PerformanceData.alarms(binanceWatches(T.small), currency: "EUR")
        for alarm in alarms where alarm.currency != nil { status.markUnchecked(alarm.id, reason: .noRate, now: now) }
        XCTAssertTrue(alarms.allSatisfy { ($0.currency != nil) == (status.unchecked($0.id) != nil) })
    }
}
