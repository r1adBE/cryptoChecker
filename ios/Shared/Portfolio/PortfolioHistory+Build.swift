import Foundation

// Verlauf bauen (Teil von `PortfolioHistory`): Punkte je Tag aus Bestand × Tagesschluss, Gewinn/Verlust-Zuflüsse
// und Zuflüsse im Zeitraum — wie `PortfolioHistory.kt` (Android).

extension PortfolioHistory {
    /// Exakter Bestand zum Zeitpunkt `atMillis` (einschliesslich) aus `holdingsTimelineExact`.
    private static func holdingsAtExact(_ timeline: [(time: Int64, holdings: Decimal)], _ atMillis: Int64) -> Decimal {
        var result: Decimal = 0
        for entry in timeline {
            if entry.time > atMillis { break }
            result = entry.holdings
        }
        return result
    }

    /// Verlauf für `range` bis `todayEpochDay` — Parameter wie in Android.
    /// `ranges`: Eröffnung/Hoch/Tief der Tageskerzen je Coin und UTC-Tag (optional). Damit bekommt
    /// jeder Punkt `open`/`high`/`low` = Σ Bestand am Tagesende × Eröffnung/Hoch/Tief des Coins.
    /// Näherung: Hoch und Tief der Coins fallen kaum je auf denselben Zeitpunkt — die Summe ist eine
    /// obere bzw. untere SCHRANKE des tatsächlichen Tageshochs/-tiefs des Portfolios, kein gemessener
    /// Wert. Coins ohne Kerze an dem Tag (oder USDT) zählen mit ihrem Tageswert in allen dreien.
    /// `addresses`: beobachtete Adress-Guthaben (`AddressBalanceHistory`) — Adress-Einträge in `trades`
    /// (`AddressBalance.withAddresses`) zählen nie rückwirkend, nur die Reihe ab ihrer ersten Beobachtung;
    /// Änderungen und Auszahlungen ohne Preis zum Marktkurs ihres Tags. Wie Android.
    static func build(trades: [PortfolioTx],
                      closes: [String: [Int: Double]],
                      livePrices: [String: Double],
                      range: PortfolioHistoryRange,
                      todayEpochDay: Int,
                      dayEndMillis: (Int) -> Int64 = { PortfolioHistory.utcDayEnd($0) },
                      fxRate: Double = 1,
                      ranges: [String: [Int: PortfolioDayRange]] = [:],
                      addresses: [AddressBalanceHistory] = [],
                      method: CostMethod = .defaultMethod) -> PortfolioHistorySeries {
        // EINE Tagesgrenze: UTC-Tag (wie Tageskerzen und «7 T/1 J»), nicht die lokale Zeitzone des Aufrufers
        let dayEndMillis: (Int) -> Int64 = { PortfolioHistory.utcDayEnd($0) }
        // Adress-Einträge nie rückwirkend; nur die beobachtete Reihe. Tausch ohne Wert (und die übrigen Fälle von
        // `PortfolioLedger.valueAtDayClose`) zum selben Tageskurs wie die Karte (`PortfolioPeriodChange.marketPriceAt`):
        // EIN Wert für beide Seiten, sonst bewerteten Kurve und Karte denselben Tausch verschieden
        let raw = trades.filter { $0.amount > 0 && !$0.amount.isInfinite && !AddressBalance.isAddressTrade($0.id) }
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

        let start = startDay(range: range, firstTradeMillis: firstTime, todayEpochDay: todayEpochDay,
                             dayEndMillis: dayEndMillis)
        let capped = isCapped(range: range, firstTradeMillis: firstTime, todayEpochDay: todayEpochDay,
                              dayEndMillis: dayEndMillis)

        let coins = PortfolioLedger.coins(valid).sorted()
        // Bestände exakt (Wert = Menge × Kurs ohne Gleitkomma-Rundung der Menge); als Double nur für die Prüfungen «gehalten»
        var exactTimelines: [String: [(time: Int64, holdings: Decimal)]] = [:]
        var timelines: [String: [(time: Int64, holdings: Double)]] = [:]
        for coin in coins {
            let exact = holdingsTimelineExact(coin, trades: valid, method: method)
            exactTimelines[coin] = exact
            timelines[coin] = exact.map { (time: $0.time, holdings: PortfolioDecimal.double($0.holdings)) }
        }

        let startMillis = dayEndMillis(start - 1) + 1
        let held = coins.filter { heldWithin(timelines[$0] ?? [], from: startMillis) }

        // Je Coin die Kerzen aufsteigend (USDT braucht keine). Identitätsprüfung: liegt der jüngste
        // Schluss um mehr als Faktor 3 neben dem aktuellen Kurs, zählt die Reihe nicht (grosse echte
        // Tagesbewegungen bleiben drin; der heutige Punkt nimmt den aktuellen Kurs).
        var sortedCloses: [String: [(day: Int, close: Double)]] = [:]
        for coin in held where PortfolioStables.needsQuote(coin) {
            let series = (closes[coin] ?? [:])
                .filter { $0.value > 0 && $0.value.isFinite }
                .sorted { $0.key < $1.key }
                .map { (day: $0.key, close: $0.value) }
            // Heute gehaltener Coin ohne aktuellen Kurs: Token nicht bestätigt → fehlt (strenge Prüfung);
            // ohne heutigen Bestand gibt es keinen Kurs zum Vergleichen, die Reihe bleibt.
            let live = livePrices[coin].flatMap { $0 > 0 && $0.isFinite ? $0 : nil }
            let heldToday = (timelines[coin]?.last?.holdings ?? 0) > 0
            sortedCloses[coin] = (live == nil && !heldToday) || PricePlausibility.closesMatchLive(series, live: live)
                ? series : []
        }
        // Nur USDT fehlt nie (immer 1); alle anderen Coins ohne (passende) Kerzen schon — auch
        // Stablecoins wie USDC (kein stilles 1)
        let skipped = held.filter { PortfolioStables.needsQuote($0) && (sortedCloses[$0]?.isEmpty ?? true) }
        let included = held.filter { !skipped.contains($0) }
        guard !included.isEmpty else {
            return PortfolioHistorySeries(points: [], skipped: skipped, change: nil, changePercent: nil,
                                          tradesInRange: false, capped: capped)
        }

        let dayCount = Swift.max(1, todayEpochDay - start + 1)
        // Je Coin und Tag der Wert samt Eröffnung/Hoch/Tief; nil = nicht gehalten oder ohne Kurs
        var cells: [String: [DayCell?]] = [:]
        // Gehalten, aber an einem Tag ohne Kurs (vor der ersten Kerze): fehlt im Gewinn/Verlust
        var unpriced: Set<String> = []
        for coin in included {
            let timeline = exactTimelines[coin] ?? []
            let series = sortedCloses[coin] ?? []
            var row = [DayCell?](repeating: nil, count: dayCount)
            for index in 0..<dayCount {
                let day = start + index
                let isToday = day == todayEpochDay
                let amount = isToday ? (timeline.last?.holdings ?? 0) : holdingsAtExact(timeline, dayEndMillis(day))
                guard amount > 0 else { continue }
                let dayMarket: Double?
                if isToday, let live = livePrices[coin], live > 0, live.isFinite {
                    dayMarket = live
                } else {
                    dayMarket = closeOnOrBefore(series, day: day)
                }
                // Stablecoin-Regel: USDT 1, andere Stablecoins Kurs des Tags, sonst kein Wert
                guard let price = PortfolioStables.price(coin, market: dayMarket) else {
                    unpriced.insert(coin)
                    continue
                }
                // Kerze genau dieses Tags (nicht fortgeschrieben); Hoch/Tief schliessen den Tageswert ein
                // Menge × Kurs exakt (Kurs über seine kürzeste Darstellung); Double erst in der Summe je Tag (`sumCells`)
                func times(_ p: Double) -> Decimal { amount * (PortfolioDecimal.of(p) ?? 0) }
                if PortfolioStables.needsQuote(coin), !series.isEmpty, let r = ranges[coin]?[day], r.valid {
                    row[index] = DayCell(value: times(price), open: times(r.open),
                                         high: times(Swift.max(r.high, price)), low: times(Swift.min(r.low, price)),
                                         withRange: true)
                } else {
                    let v = times(price)
                    row[index] = DayCell(value: v, open: v, high: v, low: v, withRange: false)
                }
            }
            cells[coin] = row
        }

        let points = sumCells(included, cells: cells, start: start, dayCount: dayCount, rate: rate)
        let first = points.first?.value ?? 0
        let last = points.last?.value ?? 0
        let change: Double? = points.count >= 2 ? last - first : nil
        let percent: Double? = change.flatMap { first > PortfolioCalculator.eps ? $0 / first * 100 : nil }
        let firstDayEnd = dayEndMillis(start)
        let tradesInRange = valid.contains { $0.time > firstDayEnd }
        // Marktkurs eines Tags (Auszahlung/Belohnung ohne Preis, Adress-Änderung, Gebühr im dritten Coin ohne Preis):
        // heute der aktuelle Kurs, sonst der Schluss. Ein Gebühr-Coin ohne Bestand im Zeitraum: seine Kerzen mit
        // derselben Identitätsprüfung
        let market: (String, Int) -> Double? = { coin, day in
            let live = livePrices[coin].flatMap { $0 > 0 && $0.isFinite ? $0 : nil }
            let series: [(day: Int, close: Double)]
            if let known = sortedCloses[coin] {
                series = known
            } else {
                let raw = (closes[coin] ?? [:]).filter { $0.value > 0 && $0.value.isFinite }
                    .sorted { $0.key < $1.key }.map { (day: $0.key, close: $0.value) }
                series = live == nil || PricePlausibility.closesMatchLive(raw, live: live) ? raw : []
            }
            if day == todayEpochDay, let live { return PortfolioStables.price(coin, market: live) }
            return PortfolioStables.price(coin, market: closeOnOrBefore(series, day: day))
        }
        let flows = cashFlows(valid, coins: included, start: start, todayEpochDay: todayEpochDay,
                              dayEndMillis: dayEndMillis, rate: rate, market: market, method: method)

        // Gewinn/Verlust: nur Coins mit Kurs an jedem gehaltenen Tag und bekannten Zuflüssen. Ab 0 mit allen Zuflüssen
        // auch, wenn der erste Kauf erst im Zeitraum liegt (junges Portfolio): sonst fehlte der erste Tag (Kursbewegung
        // und Gebühren des ersten Kaufs), der Kopf «7 T/1 J» (`PortfolioPeriodChange.gain`) zählt ihn
        let sinceFirst = range == .sinceFirst || start > todayEpochDay - range.days
        var pnlFlowList: [PortfolioCashFlow] = []
        var pnlSkipped: [String] = []
        var pnlCoins: [String] = []
        for coin in included {
            let coinFlows = unpriced.contains(coin) ? nil
                : pnlFlows(coin, trades: valid, start: start, todayEpochDay: todayEpochDay, dayEndMillis: dayEndMillis,
                           rate: rate, sinceFirst: sinceFirst, market: market, method: method)
            if let coinFlows {
                pnlCoins.append(coin)
                pnlFlowList += coinFlows
            } else {
                pnlSkipped.append(coin)
            }
        }
        let pnlValues = pnlCoins.isEmpty ? []
            : sumCells(pnlCoins, cells: cells, start: start, dayCount: dayCount, rate: rate)
        let pnlRangeEnd = dayEndMillis(start)
        let withdrawalsInRange = valid.contains { t in
            t.type.isWithdrawalLike && !AddressBalance.isAddressTrade(t.id)
                && pnlCoins.contains(PortfolioCalculator.normalizeCoin(t.coin)) && (sinceFirst || t.time > pnlRangeEnd)
        }
        return PortfolioHistorySeries(points: points, skipped: skipped, change: change,
                                      changePercent: percent, tradesInRange: tradesInRange, capped: capped,
                                      cashFlows: flows, pnlValues: pnlValues,
                                      pnlFlows: pnlFlowList.sorted { $0.epochDay < $1.epochDay },
                                      pnlSkipped: pnlSkipped.sorted(), pnlSinceFirst: sinceFirst,
                                      withdrawalsInRange: withdrawalsInRange)
    }

    /// Wert eines Coins an einem Tag (USDT) mit Eröffnung/Hoch/Tief; `withRange` = aus einer Tageskerze.
    private struct DayCell {
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
    private static func pnlFlows(_ coin: String, trades: [PortfolioTx], start: Int, todayEpochDay: Int,
                                 dayEndMillis: (Int) -> Int64, rate: Double, sinceFirst: Bool,
                                 market: (String, Int) -> Double?, method: CostMethod) -> [PortfolioCashFlow]? {
        let rangeEnd = dayEndMillis(start)
        var out: [PortfolioCashFlow] = []
        var book = PortfolioHoldingsBook(method: method)
        for e in PortfolioLedger.events(coin, trades: trades) {
            if sinceFirst || e.trade.time > rangeEnd {
                let day = localDay(e.trade.time, from: start, to: todayEpochDay, dayEndMillis: dayEndMillis)
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
    private static func cashFlows(_ trades: [PortfolioTx], coins: [String], start: Int, todayEpochDay: Int,
                                  dayEndMillis: (Int) -> Int64, rate: Double,
                                  market: (String, Int) -> Double?, method: CostMethod) -> [PortfolioCashFlow]? {
        let rangeEnd = dayEndMillis(start)
        var out: [PortfolioCashFlow] = []
        for coin in coins {
            var book = PortfolioHoldingsBook(method: method)
            for e in PortfolioLedger.events(coin, trades: trades) {
                if e.trade.time > rangeEnd {
                    // Gleiche Zuflüsse wie «7 T» (`PortfolioLedger.flow`); unbekannt → keine Zuflüsse
                    let day = localDay(e.trade.time, from: start, to: todayEpochDay, dayEndMillis: dayEndMillis)
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
