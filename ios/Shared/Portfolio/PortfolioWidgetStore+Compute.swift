import Foundation

// Reine Berechnung des Portfolio-Widgets (Teil von `PortfolioWidgetStore`): Vergleichsstand, Veränderung je Coin,
// grösste Positionen, Wertverlauf und der ganze Stand (`compute`) — gleiche Regeln wie `PortfolioSnapshotMath` in `PortfolioSnapshot.kt`.

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
    /// nicht. Fehlt einem Coin in einem Stand der Kurs, zählt der aktuelle; Coins ohne gültigen aktuellen Kurs fehlen in
    /// ALLEN Punkten (wie im Gesamtwert und im Stundenverlauf), sonst fiele die Linie ohne jede Bewegung.
    /// `addressSteps`: Adress-Guthaben je Coin als Stufen (`PortfolioWidgetSeries.amountAt`) — nie rückwirkend.
    static func valueHistory(holdings: [String: Double], history: [PricePoint], current: [String: Double],
                             stamp: Int64, factor: Double,
                             addressSteps: [String: [AddressObservation]] = [:]) -> [PortfolioWidgetPoint] {
        func valid(_ price: Double?) -> Double? {
            guard let price, price > 0, price.isFinite else { return nil }
            return price
        }
        let open = holdings.filter { $0.value > PortfolioCalculator.eps && $0.value.isFinite && valid(current[$0.key]) != nil }
        guard !open.isEmpty else { return [] }
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

    /// Wert je bewertetem Coin und die Coins darunter ohne frischen Kurs (`PortfolioPriceFallback.staleCoins`;
    /// `staleAfterMillis` nil = nicht prüfen) — Teil von `compute`.
    static func pricedAndStale(open: [CoinPosition], prices: PortfolioPrices, now: Int64,
                               staleAfterMillis: Int64?) -> (values: [String: Double], stale: Set<String>) {
        var pricedValues: [String: Double] = [:]
        for p in open {
            if let v = p.value { pricedValues[PortfolioCalculator.normalizeCoin(p.coin)] = v }
        }
        let stale: Set<String> = staleAfterMillis.map {
            Set(PortfolioPriceFallback.staleCoins(values: pricedValues, times: prices.times, now: now, staleAfterMillis: $0))
        } ?? []
        return (pricedValues, stale)
    }

    /// Faktor USDT → Währung und Beschriftung: mit gültigem Devisenkurs `currency`, sonst 1 und USDT (Teil von `compute`).
    static func valuation(rate: Double?, currency: String) -> (factor: Double, label: String) {
        if let rate, rate > 0, rate.isFinite { return (rate, currency) }
        return (1, PortfolioStables.valuation)
    }

    /// Merkzettel der App nur für dieselbe Anzeigewährung (in USDT gelten die USDT-Werte) — Teil von `compute`.
    static func displayMemo(_ memo: PortfolioDisplayFx?, label: String) -> PortfolioDisplayFx? {
        memo.flatMap { m -> PortfolioDisplayFx? in
            label != PortfolioStables.valuation && m.currency == label.trimmingCharacters(in: .whitespaces).uppercased()
                ? m : nil
        }
    }

    /// Gewinn/Verlust «heute» je gezeigter Position in der Währung (`factor`) — Teil von `compute`.
    static func withCoinPnl(_ positions: [PortfolioWidgetPosition]?, coinPnl: [String: PortfolioWidgetChange],
                            factor: Double) -> [PortfolioWidgetPosition]? {
        positions?.map { p in
            var p = p
            let pnl = coinPnl[PortfolioCalculator.normalizeCoin(p.symbol)]
            p.pnlAmount = pnl.map { $0.amount * factor }
            p.pnlPercent = pnl?.percent
            return p
        }
    }

    /// Stundenkurse: gemerkte, dazu was ohnehin schon geladen ist — keine eigene Abfrage (`freshHourly`); nur gehaltene
    /// Coins, mit neuem Kursstand gespeichert (`save`) — Teil von `compute`.
    static func mergedHourly(holdings: [String: Double], extraHourly: [String: [PortfolioTimedPrice]], past: [PricePoint],
                             current: [String: Double], staleCoins: Set<String>, stamp: Int64,
                             save: Bool) -> [String: [PortfolioTimedPrice]] {
        let fresh = freshHourly(holdings: holdings, extraHourly: extraHourly, past: past, current: current,
                                staleCoins: staleCoins, stamp: stamp)
        let held = Set(holdings.keys.map { $0.uppercased() })
        let hourlyPrices = PortfolioWidgetSeries.merge(stored: loadHourly(), fresh: fresh, now: stamp)
            .filter { held.contains($0.key) }
        if save { saveHourly(hourlyPrices) }
        return hourlyPrices
    }

    /// Kurs-Stand merken, ohne Coins mit altem Kurs (deren Kurs gilt nie als Kurs «jetzt») — Teil von `compute`.
    static func recordSample(_ history: [PricePoint], sampled: [String: Double], staleCoins: Set<String>, now: Int64) {
        let freshSampled = sampled.filter { !staleCoins.contains(PortfolioCalculator.normalizeCoin($0.key)) }
        saveHistory(record(history, prices: freshSampled, at: now))
    }

    /// Aktueller Kurs (> 0) und Wert je offener Position — Teil von `compute`.
    static func currentAndValues(_ open: [CoinPosition]) -> (current: [String: Double], values: [String: Double?]) {
        var current: [String: Double] = [:]
        var values: [String: Double?] = [:]
        for p in open {
            if let price = p.currentPrice, price > 0 { current[p.coin] = price }
            values.updateValue(p.value, forKey: p.coin)
        }
        return (current, values)
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
                        rate: Double?, now: Int64 = EpochTime.nowMillis,
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
        // Ohne Herkunftsteile: «Seit Kauf» in der Währung kommt aus dem Merkzettel der App (`displayFx`)
        let summary = PortfolioCalculator.summarize(transactions, prices: prices.prices, method: method, withParts: false)
        var holdings: [String: Double] = [:]
        for p in summary.open { holdings[p.coin] = p.holdings }
        guard !holdings.isEmpty else { return .emptyPortfolio }

        let history = loadHistory()
        let changeStamp = ChangeBasisMath.stamp(basis, now: now)
        let dayStart: Int64? = basis.isDay ? changeStamp.dayStart : nil
        let ref = dayStart.map { reference(in: history, dayStart: $0) } ?? reference(in: history, now: now)
        // Stablecoin-Regel schon angewandt (wie Android), auch im gemerkten Stand
        let sampled = samplePrices(raw: prices.prices, open: summary.open)
        // Bewertete Coins ohne frischen Kurs (je Coin, `PortfolioPriceFallback.staleCoins`; nil = nicht prüfen):
        // zählen mit dem letzten Kurs, der Stand ist dann als «teilweise alter Kurs» markiert, Portfolio-Alarme
        // prüfen nicht, und ihr alter Kurs wird nie als Kurs «jetzt» gemerkt — wie Android `record`
        let (pricedValues, staleCoins) = pricedAndStale(open: summary.open, prices: prices, now: now,
                                                    staleAfterMillis: staleAfterMillis)
        let delta = ref.flatMap { change(holdings: holdings, prices: sampled, reference: $0.prices) }
        let past = history
        if prices.updatedAt > 0 { recordSample(history, sampled: sampled, staleCoins: staleCoins, now: now) }

        // Faktor USDT → Währung (Devisenkurs × USDT/USD, auch für USD); ohne Devisenkurs in USDT (1:1 gerechnet —
        // nicht «USD», das in USD nun USDT zum Marktkurs meint; wie Android).
        // Der aktuelle Kurs gilt auch für die Punkte des Verlaufs: er reicht nur 24 h zurück,
        // EZB-Tageskurse (`PortfolioHistoryFx`) brächten dort nur einen künstlichen Sprung.
        let (factor, label) = valuation(rate: rate, currency: currency)
        // Grösste Positionen (Wert = Menge × aktueller Kurs) und Wertverlauf; Zeitpunkt des
        // letzten Punkts = Kursstand, damit unveränderte Kurse den Stand nicht ändern.
        let (current, values) = currentAndValues(summary.open)
        let stamp = prices.updatedAt > 0 ? prices.updatedAt : now

        let hourlyPrices = mergedHourly(holdings: holdings, extraHourly: extraHourly, past: past, current: current,
                                        staleCoins: staleCoins, stamp: stamp, save: prices.updatedAt > 0)
        // Adress-Anteil im 24-h-Verlauf nur mit dem damals beobachteten Guthaben (nie rückwirkend) — wie Android
        let addressSteps = AddressBalanceHistory.stepsByCoin(addresses)
        var changes = coinChanges(current: current, reference: ref?.prices)
        let (hourly, hourlyChange, pastShown) = hourlySeries(holdings: holdings, current: current, hourlyPrices: hourlyPrices,
                                                             dayStart: dayStart, stamp: stamp, factor: factor,
                                                             addressSteps: addressSteps, past: past, changes: &changes)
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
        let memo = displayMemo(displayFx ?? load()?.displayFx, label: label)
        snapshot.displayFx = memo
        snapshot.unrealizedDisplay = PortfolioDisplayFigures.unrealized(open: summary.open, currency: label,
                                                                       currentRate: factor, memo: memo)
        // «Heute» für Karte, Widget und Portfolio-Alarm aus EINER Rechnung (`applyHonestToday`)
        let coinPnl = applyHonestToday(&snapshot, summary: summary, transactions: transactions, current: current,
                                       changes: changes, basis: basis, now: now, dayStart: dayStart, ref: ref,
                                       hourlyPrices: hourlyPrices, stamp: stamp, addresses: addresses, method: method,
                                       factor: factor)
        applyPauseReasons(&snapshot, summary: summary, staleCoins: staleCoins, pricedValues: pricedValues,
                          prices: prices, addressState: addressState, current: current)
        snapshot.positions = withCoinPnl(snapshot.positions, coinPnl: coinPnl, factor: factor)
        // Buchungen je gezeigtem Coin für die Liste (alle Zeilen = Bestand der Kopfzeile) — wie Android `withEntries`
        snapshot.positions = PortfolioWidgetEntries.withEntries(snapshot.positions, trades: transactions,
                                                                current: current, factor: factor, method: method)
        // 7 T / 30 T / 1 J: aus den Tageskursen, die der Wertverlauf der App schon geladen hat (`withDaily`)
        return withDaily(snapshot, transactions: transactions, holdings: holdings, current: current, prices: prices,
                         dailyPrices: dailyPrices, routed: routed, addresses: addresses, method: method, now: now,
                         memo: memo, label: label, factor: factor)
    }
}
