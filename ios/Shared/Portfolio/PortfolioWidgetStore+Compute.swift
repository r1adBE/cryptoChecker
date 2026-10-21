import Foundation

// Reine Berechnung des Portfolio-Widgets (Teil von `PortfolioWidgetStore`): Vergleichsstand, Veränderung je Coin,
// grösste Positionen, Wertverlauf und der ganze Stand (`compute`) — gleiche Regeln wie `PortfolioSnapshotMath.kt`.

extension PortfolioWidgetStore {
    // MARK: Reine Berechnung

    /// Tages-Basis: Stand zum Tagesbeginn — der jüngste davor, höchstens 90 Minuten vorher.
    static func reference(in history: [PricePoint], dayStart: Int64) -> PricePoint? {
        history
            .filter { $0.at <= dayStart && dayStart - $0.at <= PortfolioWidgetSeries.toleranceMillis }
            .max { $0.at < $1.at }
    }

    /// Neuester Stand, der mindestens 20 h alt ist (und nicht älter als 48 h).
    static func reference(in history: [PricePoint], now: Int64) -> PricePoint? {
        history
            .filter { now - $0.at >= referenceMinAgeMillis && now - $0.at <= historyMaxAgeMillis }
            .max { $0.at < $1.at }
    }

    /// Stand anhängen, wenn der letzte mindestens 30 Minuten alt ist; Altes (und
    /// «Zukünftiges» nach Zurückstellen der Uhr) verwerfen.
    static func record(_ history: [PricePoint], prices: [String: Double], at now: Int64) -> [PricePoint] {
        var list = history.filter { now - $0.at <= historyMaxAgeMillis && $0.at <= now }
        let clean = prices.filter { $0.value > 0 && $0.value.isFinite }
        guard !clean.isEmpty else { return list }
        list.sort { $0.at < $1.at }
        if let newest = list.last, now - newest.at < historyStepMillis { return list }
        list.append(PricePoint(at: now, prices: clean))
        return list
    }

    /// Zweite Zeile «≈ … USDT» nur, wenn gewünscht und die Anzeigewährung nicht selbst ein USD-Stablecoin ist (ohne
    /// Devisenkurs zeigt das Widget USDT). USD zeigt sie wie jede Fiat-Währung (USDT zum Marktkurs USDT/USD). Wie Android.
    static func showsUsdt(enabled: Bool, currency: String) -> Bool {
        enabled && !PortfolioWidgetChart.collapsesToUsdt(currency)
    }

    /// Anteil von `value` an `total` in Prozent (0–100); ohne Gesamtwert 0.
    static func sharePercent(_ value: Double, total: Double) -> Double {
        guard total > 0, value.isFinite else { return 0 }
        return Swift.min(100, Swift.max(0, value / total * 100))
    }

    /// Kursveränderung je Coin seit `reference` in Prozent (nur Coins mit beiden Kursen).
    static func coinChanges(current: [String: Double], reference: [String: Double]?) -> [String: Double] {
        guard let reference else { return [:] }
        var out: [String: Double] = [:]
        for (coin, now) in current where now > 0 && now.isFinite {
            guard let then = reference[coin], then > 0, then.isFinite else { continue }
            out[coin] = (now / then - 1) * 100
        }
        return out
    }

    /// Die `limit` wertvollsten Positionen (absteigend; gleicher Wert nach Kürzel) und die
    /// Zahl der übrigen. Positionen ohne Kurs (Wert nil) zählen nur zu den übrigen.
    /// - Parameters:
    ///   - valuesUsd: Wert je offenem Coin in USDT
    ///   - factor: USD → Anzeigewährung
    ///   - amounts: gehaltene Menge je Coin (inkl. beobachteter Adressen) für `quantity`
    static func topPositions(valuesUsd: [String: Double?], totalUsd: Double, factor: Double,
                             changes: [String: Double], limit: Int = maxPositions,
                             amounts: [String: Double] = [:])
        -> (positions: [PortfolioWidgetPosition], others: Int) {
        var priced: [(coin: String, value: Double)] = []
        for (coin, value) in valuesUsd {
            if let value, value.isFinite, value >= 0 { priced.append((coin, value)) }
        }
        priced.sort { $0.value != $1.value ? $0.value > $1.value : $0.coin < $1.coin }
        let top = priced.prefix(Swift.max(0, limit)).map { item in
            PortfolioWidgetPosition(symbol: item.coin, value: item.value * factor,
                                    sharePercent: sharePercent(item.value, total: totalUsd),
                                    change24hPercent: changes[item.coin],
                                    quantity: amounts[item.coin].map { DecimalText.plain($0) }.flatMap { $0.isEmpty ? nil : $0 })
        }
        return (Array(top), valuesUsd.count - top.count)
    }

    /// Wertverlauf des HEUTIGEN Bestands über die gemerkten Stände (vor `stamp`) und den
    /// aktuellen Stand — wie «heute»: Käufe und Verkäufe seither verschieben die Linie
    /// nicht. Fehlt einem Coin in einem Stand der Kurs, zählt der aktuelle.
    /// `addressSteps`: Adress-Guthaben je Coin als Stufen (`PortfolioWidgetSeries.amountAt`) — nie rückwirkend.
    static func valueHistory(holdings: [String: Double], history: [PricePoint], current: [String: Double],
                             stamp: Int64, factor: Double,
                             addressSteps: [String: [AddressObservation]] = [:]) -> [PortfolioWidgetPoint] {
        let open = holdings.filter { $0.value > PortfolioCalculator.eps && $0.value.isFinite }
        guard !open.isEmpty else { return [] }
        func valid(_ price: Double?) -> Double? {
            guard let price, price > 0, price.isFinite else { return nil }
            return price
        }
        func value(_ prices: [String: Double], at time: Int64) -> Double {
            var sum = 0.0
            for (coin, amount) in open {
                let held = PortfolioWidgetSeries.amountAt(coin, amount: amount, time: time, addressSteps: addressSteps)
                sum += held * (valid(prices[coin]) ?? valid(current[coin]) ?? 0)
            }
            return sum
        }
        let past = history
            .filter { $0.at < stamp && stamp - $0.at <= historyMaxAgeMillis }
            .sorted { $0.at < $1.at }
            .map { PortfolioWidgetPoint(at: $0.at, value: value($0.prices, at: $0.at) * factor) }
        return past + [PortfolioWidgetPoint(at: stamp, value: value(current, at: stamp) * factor)]
    }

    /// Sichtbare Zeilen bei `maxLines` verfügbaren Zeilen (inklusive «+ n weitere»):
    /// Passen nicht alle, belegt «+ n weitere» die letzte Zeile; bliebe nur sie, keine Liste.
    static func rows(_ positions: [PortfolioWidgetPosition], others: Int,
                     maxLines: Int) -> (shown: [PortfolioWidgetPosition], more: Int) {
        let total = positions.count + Swift.max(0, others)
        guard maxLines > 0, !positions.isEmpty else { return ([], 0) }
        var shown = Swift.min(positions.count, maxLines)
        if shown < total && shown + 1 > maxLines { shown = maxLines - 1 }
        guard shown > 0 else { return ([], 0) }
        return (Array(positions.prefix(shown)), total - shown)
    }

    /// Veränderung in USDT (Betrag, Prozent) für die Bestände `holdings` zwischen
    /// `reference` und `prices`; nil, wenn kein Coin einen Vergleichskurs hat.
    static func change(holdings: [String: Double], prices: [String: Double],
                       reference: [String: Double]) -> (amount: Double, percent: Double?)? {
        var amount = 0.0
        var base = 0.0
        var any = false
        for (coin, held) in holdings where held > 0 {
            guard let now = prices[coin], now > 0, let then = reference[coin], then > 0 else { continue }
            amount += held * (now - then)
            base += held * then
            any = true
        }
        guard any else { return nil }
        let percent: Double? = base > 0 ? amount / base * 100 : nil
        return (amount, percent)
    }

    /// Kurse für Kurs-Stand und «heute»-Vergleich: die abgefragten Kurse, für die offenen Coins
    /// überschrieben mit dem Kurs nach der Stablecoin-Regel (`PortfolioStables`, z. B. USDC ohne
    /// USDCUSDT = 1) — wie `current` in `PortfolioSnapshotUpdater.kt`. Sonst fehlte ein solcher
    /// Stablecoin in den Ständen und «heute» rechnete ohne ihn (falsche Prozente).
    static func samplePrices(raw: [String: Double], open: [CoinPosition]) -> [String: Double] {
        var out = raw
        for p in open {
            if let price = p.currentPrice, price > 0, price.isFinite { out[p.coin] = price }
        }
        return out
    }

    /// Stand aus Transaktionen, Kursen (USDT) und Devisenkurs USD → `currency`.
    /// Merkt dabei den Kurs-Stand für spätere Vergleiche (höchstens alle 30 Minuten) und die
    /// Stundenkurse je Coin. Reichen diese (`PortfolioWidgetSeries.hourly`, mindestens 6 Punkte),
    /// stammen Wertverlauf und Veränderung über 24 h daraus, sonst aus den Kursaufnahmen.
    /// `extraHourly`: weitere schon geladene Stundenkurse (z. B. Mini-Charts der Merkliste).
    /// `basis`: %-Basis — bei «seit 00:00» Verlauf und Veränderung ab Tagesbeginn (wie Android).
    /// `displayFx`: Merkzettel der App für «Seit Kauf»/«7 T» in der Anzeigewährung; nil = der zuletzt gespeicherte.
    static func compute(transactions: [PortfolioTx], prices: PortfolioPrices, currency: String,
                        rate: Double?, now: Int64 = TimeUtils.nowMillis,
                        extraHourly: [String: [PortfolioTimedPrice]] = [:],
                        basis: ChangeBasis = .ROLLING_24H,
                        dailyPrices: [String: PortfolioWidgetDailyCoin]? = nil,
                        routed: Set<String>? = nil,
                        method: CostMethod = .defaultMethod,
                        addresses: [AddressBalanceHistory] = [],
                        staleAfterMillis: Int64? = nil,
                        displayFx: PortfolioDisplayFx? = nil,
                        addressState: AddressAlarmState = AddressAlarmState()) -> PortfolioWidgetSnapshot {
        guard !transactions.isEmpty else { return .emptyPortfolio }
        let summary = PortfolioCalculator.summarize(transactions, prices: prices.prices, method: method)
        var holdings: [String: Double] = [:]
        for p in summary.open { holdings[p.coin] = p.holdings }
        guard !holdings.isEmpty else { return .emptyPortfolio }

        var history = loadHistory()
        let changeStamp = ChangeBasisMath.stamp(basis, now: now)
        let dayStart: Int64? = basis.isDay ? changeStamp.dayStart : nil
        let ref = dayStart.map { reference(in: history, dayStart: $0) } ?? reference(in: history, now: now)
        // Stablecoin-Regel schon angewandt (wie Android), auch im gemerkten Stand
        let sampled = samplePrices(raw: prices.prices, open: summary.open)
        // Bewertete Coins ohne frischen Kurs (je Coin, `PortfolioPriceFallback.staleCoins`; nil = nicht prüfen):
        // zählen mit dem letzten Kurs, der Stand ist dann als «teilweise alter Kurs» markiert, Portfolio-Alarme
        // prüfen nicht, und ihr alter Kurs wird nie als Kurs «jetzt» gemerkt — wie Android `record`
        var pricedValues: [String: Double] = [:]
        for p in summary.open {
            if let v = p.value { pricedValues[PortfolioCalculator.normalizeCoin(p.coin)] = v }
        }
        let staleCoins: Set<String> = staleAfterMillis.map {
            Set(PortfolioPriceFallback.staleCoins(values: pricedValues, times: prices.times, now: now, staleAfterMillis: $0))
        } ?? []
        let delta = ref.flatMap { change(holdings: holdings, prices: sampled, reference: $0.prices) }
        let past = history
        if prices.updatedAt > 0 {
            let freshSampled = sampled.filter { !staleCoins.contains(PortfolioCalculator.normalizeCoin($0.key)) }
            history = record(history, prices: freshSampled, at: now)
            saveHistory(history)
        }

        // Faktor USDT → Währung (Devisenkurs × USDT/USD, auch für USD); ohne Devisenkurs in USDT (1:1 gerechnet —
        // nicht «USD», das in USD nun USDT zum Marktkurs meint; wie Android).
        // Der aktuelle Kurs gilt auch für die Punkte des Verlaufs: er reicht nur 24 h zurück,
        // EZB-Tageskurse (`PortfolioHistoryFx`) brächten dort nur einen künstlichen Sprung.
        let factor: Double
        let label: String
        if let rate, rate > 0, rate.isFinite {
            factor = rate
            label = currency
        } else {
            factor = 1
            label = PortfolioStables.valuation
        }
        // Grösste Positionen (Wert = Menge × aktueller Kurs) und Wertverlauf; Zeitpunkt des
        // letzten Punkts = Kursstand, damit unveränderte Kurse den Stand nicht ändern.
        var current: [String: Double] = [:]
        var values: [String: Double?] = [:]
        for p in summary.open {
            if let price = p.currentPrice, price > 0 { current[p.coin] = price }
            values.updateValue(p.value, forKey: p.coin)
        }
        let stamp = prices.updatedAt > 0 ? prices.updatedAt : now

        // Stundenkurse: gemerkte, dazu was ohnehin schon geladen ist — keine eigene Abfrage
        // (auch USDC, DAI …: ihr Verlauf zeigt eine Abkopplung; nur USDT gilt immer 1)
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
        let held = Set(holdings.keys.map { $0.uppercased() })
        let hourlyPrices = PortfolioWidgetSeries.merge(stored: loadHourly(), fresh: fresh, now: stamp)
            .filter { held.contains($0.key) }
        if prices.updatedAt > 0 { saveHourly(hourlyPrices) }
        // Adress-Anteil im 24-h-Verlauf nur mit dem damals beobachteten Guthaben (nie rückwirkend) — wie Android
        let addressSteps = AddressBalanceHistory.stepsByCoin(addresses)
        let hourly: [PortfolioWidgetPoint]
        let hourlyChange: PortfolioChange?
        var changes = coinChanges(current: current, reference: ref?.prices)
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
        // Seit Tagesbeginn schon ab zwei Punkten (der Chart wächst über den Tag)
        let useHourly = dayStart != nil ? PortfolioWidgetSeries.drawableDay(hourly) : PortfolioWidgetSeries.drawable(hourly)
        let top = topPositions(valuesUsd: values, totalUsd: summary.totalValue, factor: factor, changes: changes,
                               amounts: holdings)
        let points = useHourly
            ? hourly
            : valueHistory(holdings: holdings, history: pastShown, current: current, stamp: stamp, factor: factor,
                           addressSteps: addressSteps)
        var snapshot = PortfolioWidgetSnapshot(
            total: summary.totalValue * factor,
            changeAmount: hourlyChange.map { $0.amount } ?? delta.map { $0.amount * factor },
            changePercent: hourlyChange != nil ? hourlyChange?.percent : delta?.percent,
            currency: label,
            updatedAt: stamp,
            empty: false,
            totalUsdt: summary.totalValue,
            positions: top.positions,
            otherPositions: top.others,
            history: points,
            stamp: changeStamp,
            coinChanges: changes,
            missingPrices: summary.missingCurrentPrices.count,
            // «Gesamt» der Übersicht: ± seit Kauf in USDT wie in der Gesamtkarte des Portfolio-Tabs
            unrealized: summary.unrealized.flatMap { $0.isFinite
                ? PortfolioWidgetChange(amount: $0, percent: summary.unrealizedPercent) : nil }
        )
        // «Seit Kauf»/«7 T» in der Anzeigewährung: mit dem Merkzettel der App (frisch übergeben oder gespeichert), nur
        // für dieselbe Währung — in USDT gelten die USDT-Werte. «Seit Kauf» = Wert × Faktor jetzt − gemerkter Einstand
        // zum Kurs der Erwerbstage (wie Android `record`)
        let memo = (displayFx ?? load()?.displayFx).flatMap { m -> PortfolioDisplayFx? in
            label != PortfolioStables.valuation && m.currency == label.trimmingCharacters(in: .whitespaces).uppercased()
                ? m : nil
        }
        snapshot.displayFx = memo
        snapshot.unrealizedDisplay = PortfolioDisplayFigures.unrealized(open: summary.open, currency: label,
                                                                       currentRate: factor, memo: memo)
        // «Heute» für Karte, Widget und Portfolio-Alarm aus EINER Rechnung: Kursänderung je Coin mit
        // Vergleichskurs (Coins ohne fehlen statt flach mitzuzählen), um Käufe/Verkäufe seit Beginn der
        // %-Basis berichtigt — wie Android `withHonestToday`
        let todayStart = ChangeBasisMath.dayStart(basis, now: now) ?? (now - 86_400_000)
        // «Heute»: eigener Bestand + zuletzt beobachtetes Adress-Guthaben; die Reihe rechnet Änderungen seit Beginn
        // der %-Basis als Zu-/Abfluss heraus (nie rückwirkend) — wie Android `todayHoldings`
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
                                                           addresses: addresses, method: method) }
        snapshot.changeAmount = adjusted.flatMap { $0.amount.isFinite ? $0.amount * factor : nil }
        snapshot.changePercent = adjusted?.percent.flatMap { $0.isFinite ? $0 : nil }
        snapshot.todaySkipped = adjusted?.skipped ?? []
        // Dieselbe Rechnung je Coin für die Zeilen (Summe = «Heute»): USDT für den Portfolio-Tab, in der
        // Anzeigewährung an den Positionen des Widgets — wie Android `withHonestToday`
        let byCoin = PortfolioPeriodChange.todayByCoin(holdings: ledgerHoldings, trades: transactions, current: current,
                                                       coinChanges: changes, windowStart: todayStart,
                                                       addresses: addresses, method: method)
        var coinPnl: [String: PortfolioWidgetChange] = [:]
        for (coin, c) in byCoin {
            coinPnl[coin] = PortfolioWidgetChange(amount: c.amount, percent: c.percent.flatMap { $0.isFinite ? $0 : nil })
        }
        snapshot.coinPnl = coinPnl
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
        snapshot.positions = snapshot.positions?.map { p in
            var p = p
            let pnl = coinPnl[PortfolioCalculator.normalizeCoin(p.symbol)]
            p.pnlAmount = pnl.map { $0.amount * factor }
            p.pnlPercent = pnl?.percent
            return p
        }
        // 7 T / 30 T / 1 J: aus den Tageskursen, die der Wertverlauf der App schon geladen hat (ohne Netz)
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
                                                   dayEndMillis: { PortfolioHistory.dayEndMillis($0) },
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
}
