import Foundation

// Verlauf bauen (Teil von `PortfolioHistory`): Punkte je Tag aus Bestand × Tagesschluss, Gewinn/Verlust-Zuflüsse
// und Zuflüsse im Zeitraum — wie `PortfolioHistory.kt` (Android).

extension PortfolioHistory {
    /// Verlauf für `range` bis `todayEpochDay` — Parameter wie in Android.
    /// `ranges`: Eröffnung/Hoch/Tief der Tageskerzen je Coin und UTC-Tag (optional). Damit bekommt
    /// jeder Punkt `open`/`high`/`low` = Σ Bestand am Tagesende × Eröffnung/Hoch/Tief des Coins.
    /// Näherung: Hoch und Tief der Coins fallen kaum je auf denselben Zeitpunkt — die Summe ist eine
    /// obere bzw. untere SCHRANKE des tatsächlichen Tageshochs/-tiefs des Portfolios, kein gemessener
    /// Wert. Coins ohne Kerze an dem Tag (oder USDT) zählen mit ihrem Tageswert in allen dreien.
    /// `addresses`: beobachtete Adress-Guthaben (`AddressBalanceHistory`, von `AddressBooked.histories` ohne das schon
    /// Gebuchte) — der Adress-Teil in `trades` (`AddressBalance.withAddresses`) zählt nie rückwirkend, Eröffnungsposten
    /// wie Käufe, die Reihe ab ihrer ersten Beobachtung; Änderungen und Auszahlungen ohne Preis zum Marktkurs ihres Tags.
    /// Heute endet die Kurve so beim Bestand der Karte. Wie Android.
    static func build(trades: [PortfolioTx],
                      closes: [String: [Int: Double]],
                      livePrices: [String: Double],
                      range: PortfolioHistoryRange,
                      todayEpochDay: Int,
                      fxRate: Double = 1,
                      ranges: [String: [Int: PortfolioDayRange]] = [:],
                      addresses: [AddressBalanceHistory] = [],
                      method: CostMethod = .defaultMethod) -> PortfolioHistorySeries {
        // EINE Tagesgrenze: UTC-Tag (wie Tageskerzen und «7 T/1 J»), nicht die lokale Zeitzone
        let dayEndMillis: (Int) -> Int64 = { PortfolioHistory.utcDayEnd($0) }
        // Adress-Teil nie rückwirkend; dafür die Reihe ohne Gebuchtes (`AddressBooked`), Posten wie Käufe. Tausch ohne
        // Wert (und die übrigen Fälle von `PortfolioLedger.valueAtDayClose`) zum selben Tageskurs wie die Karte
        // (`PortfolioPeriodChange.marketPriceAt`): EIN Wert für beide Seiten, sonst bewerteten Kurve und Karte denselben
        // Tausch verschieden
        let raw = trades.filter { $0.amount > 0 && !$0.amount.isInfinite && !AddressBalance.isAddressPart($0.id) }
            + AddressBalanceHistory.trades(addresses)
        let valid = PortfolioLedger.valueAtDayClose(
            raw,
            price: { coin, time in
                guard let p = PortfolioPeriodChange.marketPriceAt(coin, live: livePrices[coin], closes: closes, time: time),
                      p > 0, p.isFinite else { return nil }
                return PortfolioDecimal.of(p)
            })
        guard let firstTime = valid.map(\.time).min() else { return .empty }
        let rate = fxRate > 0 && fxRate.isFinite ? fxRate : 1

        let start = startDay(range: range, firstTradeMillis: firstTime, todayEpochDay: todayEpochDay)
        let capped = isCapped(range: range, firstTradeMillis: firstTime, todayEpochDay: todayEpochDay)

        // Schritte aller Coins in einem Durchgang (Bestand, Zuflüsse, Gewinn/Verlust nutzen dieselben Listen)
        var eventsOf: [String: [PortfolioLedgerEvent]] = [:]
        for entry in PortfolioLedger.eventsByCoin(valid) { eventsOf[entry.coin] = entry.events }
        let coins = PortfolioLedger.coins(valid).sorted()
        // Bestände exakt (Wert = Menge × Kurs ohne Gleitkomma-Rundung der Menge); als Double nur für die Prüfungen «gehalten»
        var exactTimelines: [String: [(time: Int64, holdings: Decimal)]] = [:]
        var timelines: [String: [(time: Int64, holdings: Double)]] = [:]
        for coin in coins {
            let exact = holdingsTimelineExact(events: eventsOf[coin] ?? [], method: method)
            exactTimelines[coin] = exact
            timelines[coin] = exact.map { (time: $0.time, holdings: PortfolioDecimal.double($0.holdings)) }
        }

        let startMillis = dayEndMillis(start - 1) + 1
        let held = coins.filter { heldWithin(timelines[$0] ?? [], from: startMillis) }

        // Je Coin die Kerzen aufsteigend mit Identitätsprüfung (`verifiedCloses`)
        let sortedCloses = verifiedCloses(held, timelines: timelines, closes: closes, livePrices: livePrices)
        // Nur USDT fehlt nie (immer 1); alle anderen Coins ohne (passende) Kerzen schon — auch
        // Stablecoins wie USDC (kein stilles 1)
        let skipped = held.filter { PortfolioStables.needsQuote($0) && (sortedCloses[$0]?.isEmpty ?? true) }
        let included = held.filter { !skipped.contains($0) }
        guard !included.isEmpty else {
            return PortfolioHistorySeries(points: [], skipped: skipped, change: nil, changePercent: nil,
                                          tradesInRange: false, capped: capped)
        }

        let dayCount = Swift.max(1, todayEpochDay - start + 1)
        // Je Coin und Tag der Wert samt Eröffnung/Hoch/Tief; nil = nicht gehalten oder ohne Kurs (`dayCells`)
        let (cells, unpriced) = dayCells(included, exactTimelines: exactTimelines, sortedCloses: sortedCloses,
                                         livePrices: livePrices, ranges: ranges, start: start, dayCount: dayCount,
                                         todayEpochDay: todayEpochDay)
        // «Einstieg»: ab dem ersten Kurs-Tag gezählt, der Wert dort als Zufluss — «Veränderung ohne Käufe» und
        // Gewinn/Verlust zählen den Sprung nicht; früher fehlte der Coin im ganzen Zeitraum (wie Android)
        let entryFlows = entries(unpriced, cells: cells, start: start, rate: rate)
        let drawn = included.filter { !unpriced.contains($0) || entryFlows[$0] != nil }
        let left = held.filter { skipped.contains($0) || (unpriced.contains($0) && entryFlows[$0] == nil) }
        guard !drawn.isEmpty else {
            return PortfolioHistorySeries(points: [], skipped: left, change: nil, changePercent: nil,
                                          tradesInRange: false, capped: capped)
        }
        // Nur Coins mit Einstieg und davor gar kein Wert: der Zeitraum beginnt am ersten Tag mit Wert (keine Nullen davor);
        // ein Einstieg an diesem ersten Tag ist dann der Anfangswert, kein Zufluss (wie Android)
        let lead = entryFlows.isEmpty ? 0
            : (0..<dayCount).first { i in drawn.contains { cells[$0]?[i] != nil } } ?? 0
        let from = start + lead
        let days = dayCount - lead
        let shown: [String: [DayCell?]] = lead == 0 ? cells : cells.mapValues { Array($0[lead...]) }
        let points = sumCells(drawn, cells: shown, start: from, dayCount: days, rate: rate)
        let first = points.first?.value ?? 0
        let last = points.last?.value ?? 0
        let change: Double? = points.count >= 2 ? last - first : nil
        let percent: Double? = change.flatMap { first > PortfolioCalculator.eps ? $0 / first * 100 : nil }
        let firstDayEnd = dayEndMillis(from)
        let inflows = entryFlows.filter { $0.value.epochDay > from }
        let tradesInRange = valid.contains { $0.time > firstDayEnd } || !inflows.isEmpty
        // Marktkurs eines Tags (`marketAt`)
        let market = marketAt(sortedCloses: sortedCloses, closes: closes, livePrices: livePrices, todayEpochDay: todayEpochDay)
        let flows = cashFlows(eventsOf, coins: drawn, start: from, todayEpochDay: todayEpochDay,
                              dayEndMillis: dayEndMillis, rate: rate, market: market, method: method, entries: inflows)

        // Gewinn/Verlust: nur Coins mit Kurs an jedem gehaltenen Tag und bekannten Zuflüssen. Ab 0 mit allen Zuflüssen
        // auch, wenn der erste Kauf erst im Zeitraum liegt (junges Portfolio): sonst fehlte der erste Tag (Kursbewegung
        // und Gebühren des ersten Kaufs), der Kopf «7 T/1 J» (`PortfolioPeriodChange.gain`) zählt ihn
        let sinceFirst = range == .sinceFirst || from > todayEpochDay - range.days
        let (pnlCoins, pnlSkipped, pnlFlowList) = pnlParts(drawn, eventsOf: eventsOf, from: from, todayEpochDay: todayEpochDay,
                                                           dayEndMillis: dayEndMillis, rate: rate, sinceFirst: sinceFirst,
                                                           market: market, method: method, entryFlows: entryFlows)
        let pnlValues = pnlCoins.isEmpty ? []
            : sumCells(pnlCoins, cells: shown, start: from, dayCount: days, rate: rate)
        let pnlRangeEnd = dayEndMillis(from)
        let withdrawalsInRange = valid.contains { t in
            t.type.isWithdrawalLike && !AddressBalance.isAddressTrade(t.id)
                && pnlCoins.contains(PortfolioCalculator.normalizeCoin(t.coin)) && (sinceFirst || t.time > pnlRangeEnd)
        }
        return PortfolioHistorySeries(points: points, skipped: left, change: change,
                                      changePercent: percent, tradesInRange: tradesInRange, capped: capped,
                                      cashFlows: flows, pnlValues: pnlValues,
                                      pnlFlows: pnlFlowList.sorted { $0.epochDay < $1.epochDay },
                                      pnlSkipped: pnlSkipped.sorted(), pnlSinceFirst: sinceFirst,
                                      withdrawalsInRange: withdrawalsInRange, entries: entryFlows.mapValues(\.epochDay))
    }

    /// «Einstieg»: ein gehaltener Coin, dem an den ersten gehaltenen Tagen der Kurs fehlt (`unpriced`), zählt ab seinem
    /// ersten Tag mit Wert; sein Wert dort (× `rate`) ist ein Zufluss am selben Tag (wie eine Einzahlung zum Marktwert),
    /// Schritte bis dahin stecken darin. Ohne jeden Tag mit Wert fehlt der Coin («ohne …»). Wie Android
    /// `PortfolioHistoryEntries`.
    private static func entries(_ unpriced: Set<String>, cells: [String: [DayCell?]], start: Int,
                                rate: Double) -> [String: PortfolioCashFlow] {
        var out: [String: PortfolioCashFlow] = [:]
        for coin in unpriced {
            guard let row = cells[coin], let index = row.firstIndex(where: { $0 != nil }), let cell = row[index] else { continue }
            let value = PortfolioDecimal.double(cell.value) * rate
            if value.isFinite { out[coin] = PortfolioCashFlow(epochDay: start + index, amount: value) }
        }
        return out
    }

    /// Wert eines Coins an einem Tag (USDT) mit Eröffnung/Hoch/Tief; `withRange` = aus einer Tageskerze (auch in
    /// `PortfolioHistory+Cells.swift`).
    struct DayCell {
        let value: Decimal
        let open: Decimal
        let high: Decimal
        let low: Decimal
        let withRange: Bool
    }

    /// Punkte je Tag als Summe der `coins` (× `rate`); Eröffnung/Hoch/Tief nur, wenn ein Coin eine Kerze hatte.
    private static func sumCells(_ coins: [String], cells: [String: [DayCell?]], start: Int, dayCount: Int,
                                 rate: Double) -> [PortfolioHistoryPoint] {
        var out: [PortfolioHistoryPoint] = []
        out.reserveCapacity(dayCount)
        for index in 0..<dayCount {
            // Exakt summiert, als Double erst für den Punkt (Anzeige, Chart)
            var total: Decimal = 0
            var bounds: (open: Decimal, high: Decimal, low: Decimal) = (0, 0, 0)
            var withRange = false
            for coin in coins {
                guard let cell = cells[coin]?[index] else { continue }
                total += cell.value
                bounds.open += cell.open
                bounds.high += cell.high
                bounds.low += cell.low
                if cell.withRange { withRange = true }
            }
            let day = start + index
            if withRange {
                out.append(PortfolioHistoryPoint(epochDay: day, value: PortfolioDecimal.double(total) * rate,
                                                 open: PortfolioDecimal.double(bounds.open) * rate,
                                                 high: PortfolioDecimal.double(bounds.high) * rate,
                                                 low: PortfolioDecimal.double(bounds.low) * rate))
            } else {
                out.append(PortfolioHistoryPoint(epochDay: day, value: PortfolioDecimal.double(total) * rate))
            }
        }
        return out
    }

    /// Zuflüsse eines Coins für den Gewinn/Verlust (`PortfolioLedger.flow`) in der Einheit des Verlaufs; nil =
    /// einer unbekannt (Coin fehlt, «ohne …»). Im Zeitraum die nach dem Ende von `start` (davor zählt der Wert
    /// am ersten Tag); «Seit 1. Kauf» (`sinceFirst`) alle, frühere auf `start` gelegt. Auszahlungen ohne Preis und
    /// Adress-Änderungen zum Marktkurs ihres (lokalen) Tags (`market`). Wie Android.
    static func pnlFlows(_ coin: String, events: [PortfolioLedgerEvent], start: Int, todayEpochDay: Int,
                         dayEndMillis: (Int) -> Int64, rate: Double, sinceFirst: Bool,
                         market: (String, Int) -> Double?, method: CostMethod,
                         entry: PortfolioCashFlow? = nil) -> [PortfolioCashFlow]? {
        let rangeEnd = dayEndMillis(start)
        var out: [PortfolioCashFlow] = []
        var book = PortfolioHoldingsBook(method: method)
        if let entry { out.append(entry) }
        for e in events {
            if sinceFirst || e.trade.time > rangeEnd {
                let day = localDay(e.trade.time, from: start, to: todayEpochDay, dayEndMillis: dayEndMillis)
                // Bis zum Einstieg steckt alles im Wert des Einstiegs (`entries`)
                if let entry, day <= entry.epochDay {
                    book.apply(e)
                    continue
                }
                let price = PortfolioLedger.needsMarketPrice(e) ? market(coin, day) : nil
                let feePrice = PortfolioLedger.needsFeeMarketPrice(e) ? e.trade.thirdFeeCoin.flatMap { market($0, day) } : nil
                guard let amount = PortfolioLedger.flow(e, holdingsBefore: book.cap(for: e), marketPrice: price,
                                                        feeMarketPrice: feePrice, method: book.method) else { return nil }
                if amount != 0 && amount.isFinite { out.append(PortfolioCashFlow(epochDay: day, amount: amount * rate)) }
            }
            book.apply(e)
        }
        return out
    }

    /// Zuflüsse der `coins` nach dem Ende von `start` (gleiche Regel wie `PortfolioPeriodChange.gain`,
    /// `PortfolioLedger.flow`: ein Verkauf ohne Bestand zählt nicht); nil, sobald einer davon unbekannt ist. Tag = lokaler Tag
    /// der Transaktion, höchstens `todayEpochDay`. Wie Android.
    private static func cashFlows(_ eventsOf: [String: [PortfolioLedgerEvent]], coins: [String], start: Int,
                                  todayEpochDay: Int,
                                  dayEndMillis: (Int) -> Int64, rate: Double,
                                  market: (String, Int) -> Double?, method: CostMethod,
                                  entries: [String: PortfolioCashFlow] = [:]) -> [PortfolioCashFlow]? {
        let rangeEnd = dayEndMillis(start)
        var out: [PortfolioCashFlow] = []
        for coin in coins {
            var book = PortfolioHoldingsBook(method: method)
            // Einstieg (`entries`): Wert am ersten Kurs-Tag als Zufluss, frühere Schritte stecken darin
            let entry = entries[coin]
            if let entry { out.append(entry) }
            for e in eventsOf[coin] ?? [] {
                if e.trade.time > rangeEnd {
                    // Gleiche Zuflüsse wie «7 T» (`PortfolioLedger.flow`); unbekannt → keine Zuflüsse
                    let day = localDay(e.trade.time, from: start, to: todayEpochDay, dayEndMillis: dayEndMillis)
                    if let entry, day <= entry.epochDay {
                        book.apply(e)
                        continue
                    }
                    let price = PortfolioLedger.needsMarketPrice(e) ? market(coin, day) : nil
                    let feePrice = PortfolioLedger.needsFeeMarketPrice(e) ? e.trade.thirdFeeCoin.flatMap { market($0, day) } : nil
                    guard let amount = PortfolioLedger.flow(e, holdingsBefore: book.cap(for: e), marketPrice: price,
                                                            feeMarketPrice: feePrice, method: book.method) else { return nil }
                    if amount != 0 && amount.isFinite {
                        out.append(PortfolioCashFlow(epochDay: day, amount: amount * rate))
                    }
                }
                book.apply(e)
            }
        }
        return out.sorted { $0.epochDay < $1.epochDay }
    }

    /// Tag (UTC, `dayEndMillis`) von `millis` zwischen `from` und `to` (kleinster Tag, dessen Ende nicht davor liegt).
    private static func localDay(_ millis: Int64, from: Int, to: Int, dayEndMillis: (Int) -> Int64) -> Int {
        var lo = from
        var hi = max(from, to)
        while lo < hi {
            let mid = lo + (hi - lo) / 2
            if dayEndMillis(mid) < millis { lo = mid + 1 } else { hi = mid }
        }
        return lo
    }

    /// Bestand > 0 irgendwann ab `from` (Bestand davor oder eine spätere Transaktion mit Bestand).
    private static func heldWithin(_ timeline: [(time: Int64, holdings: Double)], from: Int64) -> Bool {
        if holdingsAt(timeline, from - 1) > 0 { return true }
        return timeline.contains { $0.time >= from && $0.holdings > 0 }
    }
}
