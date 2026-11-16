import Foundation

// Teile von `PortfolioWidgetStore.compute` (Portfolio-Widget): Stundenkurse, ehrliches «Heute», Pausengründe der
// Portfolio-Alarme und 7 T / 30 T / 1 J — wie `PortfolioSnapshot.kt` (Android).

extension PortfolioWidgetStore {
    /// Stundenkurse: gemerkte, dazu was ohnehin schon geladen ist — keine eigene Abfrage (auch USDC, DAI …: ihr Verlauf
    /// zeigt eine Abkopplung; nur USDT gilt immer 1).
    static func freshHourly(holdings: [String: Double], extraHourly: [String: [PortfolioTimedPrice]],
                            past: [PricePoint], current: [String: Double], staleCoins: Set<String>,
                            stamp: Int64) -> [String: [PortfolioTimedPrice]] {
        var fresh: [String: [PortfolioTimedPrice]] = [:]
        for coin in holdings.keys where PortfolioStables.needsQuote(coin) {
            var list = extraHourly[coin] ?? []
            list += widgetSparkPrices(coin, now: stamp)
            for sample in past where stamp - sample.at <= PortfolioWidgetSeries.keepMillis {
                if let price = sample.prices[coin] { list.append(PortfolioTimedPrice(at: sample.at, price: price)) }
            }
            if let price = current[coin], !staleCoins.contains(PortfolioCalculator.normalizeCoin(coin)) {
                list.append(PortfolioTimedPrice(at: stamp, price: price))
            }
            if !list.isEmpty { fresh[coin] = list }
        }
        return fresh
    }

    /// «Heute» für Karte, Widget und Portfolio-Alarm aus EINER Rechnung: Kursänderung je Coin mit Vergleichskurs (Coins
    /// ohne fehlen statt flach mitzuzählen), um Käufe/Verkäufe seit Beginn der %-Basis berichtigt, je Coin seit der Zeit
    /// seines Vergleichskurses (rollend und seit Tagesbeginn, dort bis 90 min vor 00:00) — wie Android
    /// `withHonestToday`. Rückgabe: dieselbe Rechnung je Coin (Summe = «Heute»).
    static func applyHonestToday(_ snapshot: inout PortfolioWidgetSnapshot, summary: PortfolioSummary,
                                 transactions: [PortfolioTx], current: [String: Double], changes: [String: Double],
                                 basis: ChangeBasis, now: Int64, dayStart: Int64?, ref: PricePoint?,
                                 hourlyPrices: [String: [PortfolioTimedPrice]], stamp: Int64,
                                 addresses: [AddressBalanceHistory], method: CostMethod,
                                 factor: Double) -> [String: PortfolioWidgetChange] {
        let todayStart = ChangeBasisMath.dayStart(basis, now: now) ?? (now - 86_400_000)
        let since: [String: Int64] = dayStart.map {
            PortfolioWidgetSeries.changeSinceDay(current: current, baseTime: ref?.at, basePrices: ref?.prices,
                                                 prices: hourlyPrices, dayStart: $0)
        } ?? PortfolioWidgetSeries.changeSince(current: current, baseTime: ref?.at, basePrices: ref?.prices,
                                               prices: hourlyPrices, now: stamp)
        // «Heute»: Bestand der Karte (eigener samt Posten + Adress-Teil = letzte Stufe der Reihe ohne Gebuchtes); die
        // Reihe rechnet Änderungen seit Beginn der %-Basis als Zu-/Abfluss heraus (nie rückwirkend) — wie Android
        let observed = AddressBalanceHistory.latestByCoin(addresses)
        var ledgerHoldings: [String: Double] = [:]
        for p in summary.open {
            ledgerHoldings[p.coin] = PortfolioDecimal.double(
                p.ledgerHoldingsExact + (observed[PortfolioCalculator.normalizeCoin(p.coin)] ?? 0))
        }
        let adjusted = PortfolioPeriodChange.todayFromCoinChanges(holdings: ledgerHoldings, current: current,
                                                                  coinChanges: changes)
            .flatMap { PortfolioPeriodChange.todayAdjusted($0, trades: transactions, current: current,
                                                           coinChanges: changes, windowStart: todayStart,
                                                           addresses: addresses, method: method, coinStart: since) }
        snapshot.changeAmount = adjusted.flatMap { $0.amount.isFinite ? $0.amount * factor : nil }
        snapshot.changePercent = adjusted?.percent.flatMap { $0.isFinite ? $0 : nil }
        snapshot.todaySkipped = adjusted?.skipped ?? []
        // Dieselbe Rechnung je Coin für die Zeilen (Summe = «Heute»): USDT für den Portfolio-Tab, in der
        // Anzeigewährung an den Positionen des Widgets — wie Android `withHonestToday`
        let byCoin = PortfolioPeriodChange.todayByCoin(holdings: ledgerHoldings, trades: transactions, current: current,
                                                       coinChanges: changes, windowStart: todayStart,
                                                       addresses: addresses, method: method, coinStart: since)
        var coinPnl: [String: PortfolioWidgetChange] = [:]
        for (coin, c) in byCoin {
            coinPnl[coin] = PortfolioWidgetChange(amount: c.amount, percent: c.percent.flatMap { $0.isFinite ? $0 : nil })
        }
        snapshot.coinPnl = coinPnl
        return coinPnl
    }

    /// Warum Portfolio-Alarme pausieren: alte und ungeprüfte Kurse (Namen, Wert; unter 1 %: prüfen trotzdem), fehlende
    /// Kurse und nie geladene Adressen.
    static func applyPauseReasons(_ snapshot: inout PortfolioWidgetSnapshot, summary: PortfolioSummary,
                                  staleCoins: Set<String>, pricedValues: [String: Double], prices: PortfolioPrices,
                                  addressState: AddressAlarmState, current: [String: Double]) {
        snapshot.stalePrices = staleCoins.isEmpty ? nil : staleCoins.count
        // Warum Portfolio-Alarme pausieren: Namen, Wert der alten Kurse (unter 1 %: prüfen trotzdem), nie geladene Adressen
        let missing = Array(Set(summary.missingCurrentPrices.map(PortfolioCalculator.normalizeCoin))).sorted()
        snapshot.missingCoins = missing.isEmpty ? nil : missing
        snapshot.staleCoins = staleCoins.isEmpty ? nil : staleCoins.sorted()
        snapshot.staleValueUsdt = staleCoins.isEmpty ? nil : staleCoins.reduce(0) { $0 + (pricedValues[$1] ?? 0) }
        // «Kurs ungeprüft» (Token nicht bestätigt): zählt mit dem Kurs, für die Alarme aber wie ein alter Kurs — wie Android
        let unverified = Set(prices.unverified.map(PortfolioCalculator.normalizeCoin))
            .filter { pricedValues[$0] != nil && !staleCoins.contains($0) }.sorted()
        snapshot.unverifiedCoins = unverified.isEmpty ? nil : unverified
        snapshot.unverifiedValueUsdt = unverified.isEmpty ? nil : unverified.reduce(0) { $0 + (pricedValues[$1] ?? 0) }
        snapshot.addressesPending = addressState.pending > 0 ? addressState.pending : nil
        snapshot.addressesFailing = addressState.failing.isEmpty ? nil : addressState.failing
        snapshot.staleTokenAddresses = addressState.staleTokenAddresses.isEmpty ? nil : addressState.staleTokenAddresses
        let staleTokenValue = addressState.staleTokenValue(prices: current)
        snapshot.staleTokenValueUsdt = staleTokenValue > 0 ? staleTokenValue : nil
    }

    /// 7 T / 30 T / 1 J aus den Tageskursen, die der Wertverlauf der App schon geladen hat (ohne Netz); «7 T» auch in der
    /// Anzeigewährung (`memo`).
    static func withDaily(_ snapshot: PortfolioWidgetSnapshot, transactions: [PortfolioTx], holdings: [String: Double],
                          current: [String: Double], prices: PortfolioPrices,
                          dailyPrices: [String: PortfolioWidgetDailyCoin]?, routed: Set<String>?,
                          addresses: [AddressBalanceHistory], method: CostMethod, now: Int64,
                          memo: PortfolioDisplayFx?, label: String, factor: Double) -> PortfolioWidgetSnapshot {
        let dailyMap = dailyPrices ?? PortfolioWidgetDailyStore.load()
        let cached = PortfolioWidgetDailyStore.prices(dailyMap)
        // Dieselben Reihen wie Verlauf, «7 T» und «1 J» der App: eigene Börse, sonst Binance skaliert —
        // ohne Binance-Bezugskurs jetzt fehlt der Coin (`PortfolioExchangeRoute.historyCloses`)
        let routes = routed ?? Set(PortfolioExchangeRouteStore.load().routes.keys)
        let routedHistory = PortfolioExchangeRoute.historyCloses(
            cached.closes, ranges: cached.ranges, own: PortfolioWidgetDailyStore.own(dailyMap),
            routed: routes.union(prices.sources.keys), failed: Set(prices.exchangeFailed.keys),
            prices: current, binance: prices.binance)
        let daily = PortfolioWidgetChart.dailyData(trades: transactions,
                                                   closes: routedHistory.closes,
                                                   ranges: routedHistory.ranges,
                                                   holdings: holdings, current: current,
                                                   // Stichtag «7 T/1 J» und Verlauf in UTC-Tagen wie die Tageskerzen
                                                   todayEpochDay: PortfolioHistory.epochDay(utcMillis: now),
                                                   todayUtcDay: PortfolioHistory.epochDay(utcMillis: now),
                                                   addresses: addresses, method: method)
        var result = snapshot.with(daily)
        // «7 T» in der Anzeigewährung wie die Gesamtkarte (Tagesfaktoren der App); ohne passende Faktoren nil — das
        // Widget zeigt dann die USDT-Zahl mit Einheit «USDT» (wie Android `withDaily`)
        let today = PortfolioHistory.epochDay(utcMillis: now)
        if let fx = PortfolioDisplayFigures.periodFx(memo: memo, currency: label, currentRate: factor, todayEpochDay: today) {
            result.weekDisplay = PortfolioDisplayFigures.periodChange(
                PortfolioPeriodChange.gain(trades: transactions, current: current,
                                           closes: PortfolioWidgetChart.usableCloses(routedHistory.closes, todayUtcDay: today),
                                           baseDay: today - PortfolioDisplayFigures.weekDays,
                                           addresses: addresses, method: method, fx: fx))
        }
        return result
    }

    /// Wertverlauf der letzten 24 h bzw. seit Tagesbeginn aus den Stundenkursen, seine Veränderung, die Kursaufnahmen
    /// für den Ersatz-Verlauf und die Veränderung je Coin aus den Stundenkursen (in `changes`). Teil von `compute`.
    static func hourlySeries(holdings: [String: Double], current: [String: Double],
                             hourlyPrices: [String: [PortfolioTimedPrice]], dayStart: Int64?, stamp: Int64, factor: Double,
                             addressSteps: [String: [AddressObservation]], past: [PricePoint],
                             changes: inout [String: Double])
        -> (hourly: [PortfolioWidgetPoint], change: PortfolioChange?, pastShown: [PricePoint]) {
        let hourly: [PortfolioWidgetPoint]
        let hourlyChange: PortfolioChange?
        let pastShown: [PricePoint]
        if let dayStart {
            // Seit Tagesbeginn: Verlauf ab 00:00, Veränderung gegen den Wert zum Tagesbeginn
            hourly = PortfolioWidgetSeries.hourlySince(holdings: holdings, current: current, prices: hourlyPrices,
                                                       dayStart: dayStart, now: stamp, factor: factor,
                                                       stables: PortfolioStables.coins, addressSteps: addressSteps)
            hourlyChange = PortfolioWidgetSeries.changeSince(hourly, dayStart: dayStart)
            for (coin, change) in PortfolioWidgetSeries.coinChangesSince(current: current, prices: hourlyPrices,
                                                                         dayStart: dayStart) {
                changes[coin] = change
            }
            pastShown = past.filter { $0.at >= dayStart }
        } else {
            hourly = PortfolioWidgetSeries.hourly(holdings: holdings, current: current, prices: hourlyPrices,
                                                  now: stamp, factor: factor,
                                                  stables: PortfolioStables.coins, addressSteps: addressSteps)
            hourlyChange = PortfolioWidgetSeries.drawable(hourly) ? PortfolioWidgetSeries.change(hourly) : nil
            // Stundenkurse vor der Vergleichsbasis (sie messen genau 24 h)
            for (coin, change) in PortfolioWidgetSeries.coinChanges(current: current, prices: hourlyPrices, now: stamp) {
                changes[coin] = change
            }
            pastShown = past
        }
        return (hourly, hourlyChange, pastShown)
    }
}
