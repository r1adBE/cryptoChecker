import Foundation

/// Buchhaltung je Coin nach der Einstandsmethode `CostMethod`: FIFO (Standard) in `PortfolioFifo`; Durchschnittskosten
/// (`.AVERAGE`, «Ø») — Regeln exakt wie `PortfolioCalculator.kt` (Ø-Rechnung in `PortfolioCalculatorAverage.swift`):
///  - Transaktionen je Coin zeitlich (bei Gleichstand nach Id) abarbeiten — dazu die Gebühren ANDERER
///    Transaktionen, die in diesem Coin bezahlt wurden (`PortfolioLedger.events`, «Gebühr-Abgang»).
///  - Gerechnet wird exakt mit `Decimal` (`PortfolioDecimal`): Bestand, Einstand, Gebühren und
///    realisierter Gewinn summieren sich ohne Gleitkomma-Fehler (1 Satoshi = 0.00000001 BTC bleibt
///    1 Satoshi). Nur Teilungen (Ø, anteiliger Verkauf) runden.
///  - Kauf, Belohnung, Einzahlung mit Preis: Einstand += Menge × Preis + Gebühr USDT + Wert der Gebühr im
///    dritten Coin, Bestand mit Preis += erhaltene Menge (Menge − Gebühr im Coin). Ø = Einstand / Bestand
///    mit Preis. Ohne Preis erhöht nur den Bestand (Teil «ohne Preis»).
///  - Verkauf: Bestand sinkt um Menge + Gebühr im Coin (nie unter 0; ein Verkauf grösser als der
///    Bestand wird gekappt und markiert). Beide Teile (mit/ohne Preis) sinken anteilig, der Ø bleibt.
///    Realisiert += Anteil MIT Kaufpreis × (Menge × Verkaufspreis − Gebühr USDT − Wert der Gebühr im
///    dritten Coin) − Einstand des abgegebenen Teils, wenn der Verkaufspreis bekannt ist. Der Anteil ohne
///    Kaufpreis (und Verkäufe ohne Preis) hat unbekannten Gewinn/Verlust (`CoinPosition.realizedUnknownAmount`).
///  - Auszahlung: wie ein Verkauf abgegeben (anteilig, Einstand zum Ø), aber KEIN realisierter Gewinn. Gebühr USDT +
///    Wert der Gebühr im dritten Coin: realisierter Aufwand (`PortfolioFeeExpenses`, Steuer-Export «Gebühr Auszahlung»).
///  - Gebühr im Coin verbraucht den ganzen Zugang (Kauf/Belohnung/Einzahlung, erhaltener Coin eines Tauschs): kein
///    Bestand, der Einstand bzw. Wert des Tauschs ist realisierter Aufwand (`PortfolioFeeExpenses`).
///  - Umbuchung: Menge und Einstand bleiben; Gebühr im Coin senkt nur die Menge (Ø steigt); Gebühr USDT +
///    Wert der Gebühr im dritten Coin erhöhen den Einstand des Teils mit Kaufpreis (Anteil mit Preis / Bestand).
///  - Gebühr-Abgang im Gebühr-Coin: wie eine Veräusserung zum Gebühr-Preis; ohne Preis «unbekannter Gewinn».
///  - Gebühr im dritten Coin ohne Preis: keine Gebühr in Einstand/Erlös, markiert (`feeValueMissing`,
///    beim Verkauf auch `realizedFeeMissing`).
///  - Gewinn/Verlust je Coin nur, wenn der ganze Restbestand einen Kaufpreis hat. Die Summe
///    (`PortfolioSummary.unrealized`) zählt nur Coins mit Einstand und Kurs.
///  - Aktueller Kurs nach der Stablecoin-Regel (`PortfolioStables`): USDT immer 1, andere
///    Stablecoins ihr Marktkurs, ohne Kurs kein Wert (wie jeder Coin: «—», «ohne Kurs»).
enum PortfolioCalculator {
    /// Darunter gilt eine Menge (Double) als null.
    static let eps = 1e-12

    static func normalizeCoin(_ coin: String) -> String {
        coin.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
    }

    /// Kennzahlen eines Coins aus seinen Transaktionen (fremde Coins zählen nur mit ihren Gebühren in diesem Coin)
    /// nach `method`. Adress-Guthaben (`AddressBalance.isAddressTrade`) zählen nur als eigener Teil mit
    /// (`CoinPosition.addressHoldingsExact`).
    static func position(_ coin: String, trades: [PortfolioTx], currentPrice: Double?,
                         method: CostMethod = .defaultMethod) -> CoinPosition {
        ledger(coin, trades: trades, currentPrice: currentPrice, method: method).position
    }

    /// Wie `position`, dazu offene Posten (nur FIFO) und Veräusserungen für den Steuer-Export. Adress-Einträge
    /// (negative Id, `AddressBalance.withAddresses`) sind KEINE Posten — ihr Guthaben steht als eigener Teil in
    /// `CoinPosition.addressHoldingsExact`: Menge und Wert zählen mit, Einstand, Ø, «Seit Kauf», realisiert und
    /// Steuer nur aus den eigenen Transaktionen. Wie Android.
    /// `feePrice`: Marktkurs für Gebühr-Zeilen (Tageskurs, wie `summarize` `withdrawalPrice`); nil = Erlös unbekannt.
    /// `withParts`: Herkunft des Ø-Einstands führen (`average`); ohne sind nur die Herkunftsteile leer.
    static func ledger(_ coin: String, trades: [PortfolioTx], currentPrice: Double?,
                       method: CostMethod = .defaultMethod,
                       feePrice: ((String, Int64) -> Double?)? = nil, withParts: Bool = true) -> CoinLedger {
        let symbol = normalizeCoin(coin)
        let split = AddressBalance.split(feeValued(trades, feePrice))
        return ledgerOf(symbol, events: PortfolioLedger.events(symbol, trades: split.real),
                        addressAmount: split.address[symbol], currentPrice: currentPrice, method: method,
                        withParts: withParts)
    }

    /// `ledger` (ohne aktuellen Kurs, ohne `feePrice`) für jeden Coin von `PortfolioLedger.coins` in dieser Reihenfolge —
    /// die Schritte je Coin einmal gesammelt (`PortfolioLedger.eventsByCoin`) statt je Coin über alle Transaktionen.
    static func ledgers(_ trades: [PortfolioTx], method: CostMethod, withParts: Bool = true) -> [CoinLedger] {
        let split = AddressBalance.split(feeValued(trades, nil))
        var byCoin: [String: [PortfolioLedgerEvent]] = [:]
        for entry in PortfolioLedger.eventsByCoin(split.real) { byCoin[entry.coin] = entry.events }
        return PortfolioLedger.coins(trades).map {
            ledgerOf($0, events: byCoin[$0] ?? [], addressAmount: split.address[$0], currentPrice: nil, method: method,
                     withParts: withParts)
        }
    }

    /// Gebühr-Zeilen, Verschenkt/bezahlt ohne Marktwert und Tausch ohne Wert zum Tageskurs `price` (> 0, endlich), sonst
    /// unbekannt (`PortfolioLedger.valueAtDayClose`).
    private static func feeValued(_ trades: [PortfolioTx], _ price: ((String, Int64) -> Double?)?) -> [PortfolioTx] {
        var exact: ((String, Int64) -> Decimal?)? = nil
        if let f = price {
            exact = { coin, time in
                guard let v = f(coin, time), v > 0, v.isFinite else { return nil }
                return PortfolioDecimal.of(v)
            }
        }
        return PortfolioLedger.valueAtDayClose(trades, price: exact)
    }

    /// `ledger` aus den schon gesammelten Schritten `events` des Coins (ohne Adress-Einträge).
    private static func ledgerOf(_ symbol: String, events: [PortfolioLedgerEvent], addressAmount: Decimal?,
                                 currentPrice: Double?, method: CostMethod, withParts: Bool = true) -> CoinLedger {
        let book: PortfolioBookResult
        switch method {
        case .FIFO: book = PortfolioFifo.run(symbol, events: events)
        case .FIFO_WALLET: book = PortfolioFifo.run(symbol, events: events, perWallet: true)
        case .AVERAGE: book = average(symbol, events: events, withParts: withParts)
        }
        let priced = book.priced
        let unpriced = book.unpriced
        let cost = book.cost
        let total = priced + unpriced
        let ledgerHoldings: Decimal = PortfolioDecimal.isDust(total) ? 0 : total
        let addressHoldings: Decimal = max(0, addressAmount ?? 0)
        let holdings = ledgerHoldings + addressHoldings
        // Stablecoin-Regel: USDT = 1, andere Stablecoins Marktkurs, sonst kein Wert (`PortfolioStables`)
        let price = PortfolioStables.price(symbol, market: currentPrice)
        let ledgerOpen = ledgerHoldings > 0
        let priceMissing = ledgerOpen && unpriced > 0
        let avgCost: Double? = priced > 0 ? PortfolioDecimal.double(PortfolioDecimal.divide(cost, priced)) : nil
        let costBasisExact: Decimal? = ledgerOpen && !priceMissing ? cost : nil
        let costBasis = costBasisExact.map(PortfolioDecimal.double)
        // Einstand des Teils mit Kaufpreis: «Seit Kauf» auch dann, wenn nur ein Teil ohne Kaufpreis ist
        let pricedHoldings: Decimal = ledgerOpen ? priced : 0
        let pricedCostExact: Decimal? = pricedHoldings > 0 ? cost : nil
        // Wert und unrealisierter Gewinn exakt (Kurs über seine kürzeste Darstellung); Double nur für die Anzeige.
        // Wert = ganzer Bestand (mit Adressen), «Seit Kauf» nur auf den eigenen Teil mit Kaufpreis.
        let priceExact: Decimal? = price.flatMap { PortfolioDecimal.of($0) }
        let valueExact: Decimal? = priceExact.map { holdings * $0 }
        let value = valueExact.map(PortfolioDecimal.double)
        // Ganz mit Kaufpreis: ganzer eigener Bestand (wie bisher, Staub inklusive), sonst nur der Teil mit Kaufpreis
        let pricedBase: Decimal = priceMissing ? pricedHoldings : ledgerHoldings
        let pricedValueExact: Decimal? = priceExact.map { pricedBase * $0 }
        var unrealizedExact: Decimal?
        if let pricedCostExact, let pricedValueExact { unrealizedExact = pricedValueExact - pricedCostExact }
        let unrealized = unrealizedExact.map(PortfolioDecimal.double)
        var unrealizedPercent: Double?
        if let unrealizedExact, let pricedCostExact, pricedCostExact > 0 {
            unrealizedPercent = PortfolioDecimal.double(PortfolioDecimal.divide(unrealizedExact, pricedCostExact)) * 100
        }

        let position = CoinPosition(
            coin: symbol,
            holdings: PortfolioDecimal.double(holdings),
            avgCost: avgCost,
            costBasis: costBasis,
            currentPrice: price,
            value: value,
            unrealized: unrealized,
            unrealizedPercent: unrealizedPercent,
            realized: PortfolioDecimal.double(book.realized),
            priceMissing: priceMissing,
            oversold: book.oversold,
            tradeCount: events.filter { !$0.feeLeg }.count,
            realizedUnknownAmount: PortfolioDecimal.isDust(book.unknownSold) ? 0 : PortfolioDecimal.double(book.unknownSold),
            soldAmount: PortfolioDecimal.isDust(book.soldTotal) ? 0 : PortfolioDecimal.double(book.soldTotal),
            holdingsExact: holdings,
            costBasisExact: costBasisExact,
            realizedExact: book.realized,
            pricedHoldingsExact: pricedHoldings,
            valueExact: valueExact,
            unrealizedExact: unrealizedExact,
            feeValueMissing: book.feeValueMissing,
            realizedFeeMissing: book.realizedFeeMissing,
            withdrawnAmount: PortfolioDecimal.isDust(book.withdrawn) ? 0 : PortfolioDecimal.double(book.withdrawn),
            addressHoldingsExact: addressHoldings,
            addressValueExact: priceExact.map { addressHoldings * $0 },
            method: method,
            lostFeeCostUsdt: book.lostFeeCosts.reduce(Decimal(0)) { $0 + $1.costUsdt },
            unassignedFeeUsdt: book.unassignedFees.reduce(Decimal(0)) { $0 + $1.usdt },
            // Aus denselben Veräusserungen wie der Steuer-Export: ein Teil ohne Einstand/Erlös → unvollständig
            disposalsIncomplete: book.disposals.contains { $0.costUsdt == nil || $0.proceedsUsdt == nil || $0.feeValueMissing },
            pricedCostExact: pricedCostExact,
            pricedCostParts: pricedCostExact != nil ? book.openParts : []
        )
        // Offene Posten für die Anzeige: FIFO je Posten, Ø zusammengefasst (Teil mit / ohne Kaufpreis) — wie Android
        var openLots: [PortfolioOpenLot] = []
        if method.isFifo {
            openLots = book.lots.map {
                PortfolioOpenLot(coin: symbol, txId: $0.txId, acquiredTime: $0.acquiredTime, kind: $0.kind, amount: $0.amount,
                                 costUsdt: $0.costUsdt, wallet: $0.wallet, aggregated: false)
            }
        } else if ledgerOpen {
            if priced > 0 {
                openLots.append(PortfolioOpenLot(coin: symbol, txId: nil, acquiredTime: nil, kind: nil, amount: priced,
                                                 costUsdt: cost, wallet: nil, aggregated: true))
            }
            if unpriced > 0 {
                openLots.append(PortfolioOpenLot(coin: symbol, txId: nil, acquiredTime: nil, kind: nil, amount: unpriced,
                                                 costUsdt: nil, wallet: nil, aggregated: true))
            }
        }
        return CoinLedger(position: position, lots: book.lots, disposals: book.disposals, withdrawals: book.withdrawals,
                          openLots: openLots, walletShortfalls: book.shortfalls, lostFeeCosts: book.lostFeeCosts,
                          unassignedFees: book.unassignedFees)
    }

    /// Offene Posten eines Coins nach `method` (`PortfolioOpenLot`): FIFO und FIFO je Wallet die tatsächlichen Posten, Ø
    /// zusammengefasst. Ohne Adress-Guthaben. Wie `openLots` (Android).
    static func openLots(_ coin: String, trades: [PortfolioTx], method: CostMethod = .defaultMethod) -> [PortfolioOpenLot] {
        ledger(coin, trades: trades, currentPrice: nil, method: method).openLots
    }

    /// `.FIFO_WALLET`: alle Abgänge über den Bestand ihrer Wallet, zeitlich, dann Id, Coin; leer bei anderen Methoden.
    /// Wie `walletShortfalls` (Android).
    static func walletShortfalls(_ trades: [PortfolioTx], method: CostMethod = .FIFO_WALLET) -> [PortfolioWalletShortfall] {
        guard method == .FIFO_WALLET else { return [] }
        let real = AddressBalance.split(trades).real
        let all = PortfolioLedger.eventsByCoin(real).flatMap { entry in
            PortfolioFifo.run(entry.coin, events: entry.events, perWallet: true).shortfalls
        }
        // Stabil sortiert wie Android (`sortedWith`)
        return all.enumerated().sorted { a, b in
            let x = a.element, y = b.element
            if x.time != y.time { return x.time < y.time }
            if x.txId != y.txId { return x.txId < y.txId }
            if x.coin != y.coin { return x.coin < y.coin }
            return a.offset < b.offset
        }.map(\.element)
    }

    /// Eigener Bestand je Coin (Grossschreibung) aus den Transaktionen mit Zeit ≤ `time` nach `method` — dieselben Mengen
    /// wie `ledgerHoldingsExact` der Karte (`PortfolioHoldingsBook`: FIFO je Wallet kappt Abgänge auf die Wallet). Adress-
    /// Einträge zählen nicht; Staub (`PortfolioDecimal.isDust`) = 0. Nur Coins mit Bestand > 0, Reihenfolge wie
    /// `PortfolioLedger.coins`. Wie Android.
    static func holdingsAt(_ trades: [PortfolioTx], time: Int64 = .max,
                           method: CostMethod = .defaultMethod) -> [(coin: String, amount: Decimal)] {
        let real = trades.filter { $0.time <= time && !AddressBalance.isAddressTrade($0.id) }
        var out: [(coin: String, amount: Decimal)] = []
        for entry in PortfolioLedger.eventsByCoin(real) {
            var book = PortfolioHoldingsBook(method: method)
            for e in entry.events { book.apply(e) }
            if book.total > 0 && !PortfolioDecimal.isDust(book.total) { out.append((coin: entry.coin, amount: book.total)) }
        }
        return out
    }

    /// Alle Veräusserungen (Steuer-Export) nach `method`: zeitlich (Verkauf), dann Id des Verkaufs, Coin; je Verkauf
    /// die Posten in FIFO-Reihenfolge. Adress-Guthaben kommen nicht vor. Wie Android.
    static func disposals(_ trades: [PortfolioTx], method: CostMethod = .defaultMethod) -> [PortfolioDisposal] {
        let real = AddressBalance.split(trades).real
        // Schritte je Coin einmal gesammelt (nicht je Coin über alle Transaktionen)
        let byCoin = PortfolioLedger.eventsByCoin(real)
        let all = byCoin.flatMap { entry in
            ledgerOf(entry.coin, events: entry.events, addressAmount: nil, currentPrice: nil, method: method).disposals
        }
        // Stabil sortiert wie Android (`sortedWith`)
        return all.enumerated().sorted { a, b in
            let x = a.element, y = b.element
            if x.sellTime != y.sellTime { return x.sellTime < y.sellTime }
            if x.sellTxId != y.sellTxId { return x.sellTxId < y.sellTxId }
            if x.coin != y.coin { return x.coin < y.coin }
            return a.offset < b.offset
        }.map(\.element)
    }

    /// Wertänderung ausgebuchter Coins bis zur Auszahlung: bekannter Teil und ob etwas fehlt — wie Android.
    struct WithdrawnGain: Equatable {
        let amount: Decimal
        let incomplete: Bool
        let count: Int
    }

    /// Wertänderung ausgebuchter Coins: je Auszahlung (Wert beim Abgang − Einstand der verbrauchten Posten bzw. zum Ø).
    /// Wert beim Abgang = ausgezahlte Menge × Preis der Auszahlung (sonst `marketPrice`, Kurs zum Zeitpunkt). Gebühr USDT
    /// und Wert der Gebühr im dritten Coin zählen NICHT hier, sondern als realisierter Aufwand (`PortfolioFeeExpenses`);
    /// zusammen ergibt das den Abfluss im Wertverlauf. Nur der Teil mit Kaufpreis zählt (Wert anteilig); fehlt ein Teil
    /// oder ein Kurs: `incomplete`. Wie `withdrawnGain` (Android).
    static func withdrawnGain(_ withdrawals: [PortfolioWithdrawalCost],
                              marketPrice: ((String, Int64) -> Double?)? = nil) -> WithdrawnGain {
        var sum: Decimal = 0
        var incomplete = false
        var count = 0
        for w in withdrawals where w.removed > 0 {
            count += 1
            let t = w.trade
            var known = t.validPriceExact
            if known == nil, let market = marketPrice?(normalizeCoin(t.coin), t.time), market >= 0, market.isFinite {
                known = PortfolioDecimal.of(market)
            }
            guard let price = known, w.pricedRemoved > 0 else {
                incomplete = true
                continue
            }
            if w.pricedRemoved < w.removed { incomplete = true }
            // Gebühren: realisierter Aufwand, nicht hier
            let value: Decimal = w.sold * price
            let pricedValue = w.pricedRemoved == w.removed ? value
                : PortfolioDecimal.divide(value * w.pricedRemoved, w.removed)
            sum += pricedValue - w.cost
        }
        return WithdrawnGain(amount: sum, incomplete: incomplete, count: count)
    }

    /// Gesamtsicht nach `method`; `prices` = aktueller USDT-Kurs je Coin (Grossschreibung). Adress-Guthaben zählen
    /// im Gesamtwert, aber nicht in Einstand und «Seit Kauf» — ein Coin nur aus Adressen fehlt dort ohne
    /// «ohne Kaufpreis»-Hinweis (`missingCostCoins` nennt nur eigene Bestände ohne Kaufpreis). Wie Android.
    /// `withParts`: Herkunft des Ø-Einstands (`CoinPosition.pricedCostParts`) führen — nur für den Einstand in einer
    /// Anzeigewährung (`PortfolioCostFx`; `PortfolioCostPartPool`); ohne bleiben sie leer, alle anderen Zahlen gleich.
    static func summarize(_ trades: [PortfolioTx], prices: [String: Double],
                          method: CostMethod = .defaultMethod,
                          withdrawalPrice: ((String, Int64) -> Double?)? = nil,
                          withParts: Bool = true) -> PortfolioSummary {
        // Gebühr-Zeilen zum Tageskurs (gleicher Kurs wie der Abfluss im Verlauf); ohne Kurs Erlös unbekannt
        let trades = feeValued(trades, withdrawalPrice)
        // Auch Coins, die nur als Gebühr-Coin vorkommen (Bestand 0, ggf. «mehr als vorhanden»).
        // Schritte je Coin einmal gesammelt (statt je Coin über alle Transaktionen)
        let split = AddressBalance.split(trades)
        var byCoin: [String: [PortfolioLedgerEvent]] = [:]
        for entry in PortfolioLedger.eventsByCoin(split.real) { byCoin[entry.coin] = entry.events }
        let ledgers = PortfolioLedger.coins(trades).map {
            ledgerOf($0, events: byCoin[$0] ?? [], addressAmount: split.address[$0], currentPrice: prices[$0],
                     method: method, withParts: withParts)
        }
        let positions = ledgers.map(\.position)
        let withdrawn = withdrawnGain(ledgers.flatMap(\.withdrawals), marketPrice: withdrawalPrice)

        let open = positions.filter(\.isOpen).sorted { a, b in
            let va = a.value ?? -1, vb = b.value ?? -1
            return va != vb ? va > vb : a.coin < b.coin
        }
        // Ganz verkaufte Positionen bleiben sichtbar, auch wenn der Gewinn/Verlust 0 oder unbekannt ist
        let closed = positions.filter { !$0.isOpen && ($0.realizedExact != 0 || $0.soldAmount > 0 || $0.withdrawnAmount > 0) }
            .sorted { $0.coin < $1.coin }

        // Exakt summiert (Wert je Coin als Decimal), Double nur für die Anzeige — wie Android
        let totalValue = PortfolioDecimal.double(open.reduce(Decimal(0)) { sum, p in
            sum + (p.valueExact ?? PortfolioDecimal.of(p.value) ?? 0)
        })
        let missingCurrent = open.filter { $0.value == nil }.map(\.coin)
        // Nur eigene Bestände ohne Kaufpreis; reine Adress-Guthaben haben keinen Einstand und fehlen nicht.
        // Ganz ohne Kaufpreis: fehlt in «Seit Kauf»; nur ein Teil ohne: zählt mit dem Teil mit Kaufpreis.
        let missingCost = open.filter { $0.pricedCostExact == nil && $0.ledgerHoldingsExact > 0 }.map(\.coin)
        let partialCost: [PortfolioUnpricedPart] = open
            .filter { $0.priceMissing && $0.pricedCostExact != nil && $0.unpricedHoldingsExact > 0 }
            .map { PortfolioUnpricedPart(coin: $0.coin, amount: $0.unpricedHoldingsExact) }
        let withCost = open.filter { $0.costBasis != nil }
        let investedExact: Decimal? = withCost.isEmpty ? nil : withCost.reduce(Decimal(0)) { $0 + ($1.costBasisExact ?? 0) }
        let invested = investedExact.map(PortfolioDecimal.double)
        // Gewinn seit Kauf über die Teile mit Kaufpreis der Coins mit Kurs; die übrigen nennt der Hinweis
        // («ohne 1 Coin (kein Kaufpreis)», «ohne 0.5 BTC ohne Kaufpreis», «Kein aktueller Kurs für …»).
        // Prozent auf den Einstand dieser Teile.
        let included = open.filter { $0.unrealizedExact != nil && $0.pricedCostExact != nil }
        // Exakt summiert (Einstand und Gewinn je Coin als Decimal), Double nur für die Anzeige
        let includedCost = included.reduce(Decimal(0)) { $0 + ($1.pricedCostExact ?? 0) }
        let unrealizedExact: Decimal? = included.isEmpty ? nil
            : included.reduce(Decimal(0)) { $0 + ($1.unrealizedExact ?? 0) }
        let unrealized = unrealizedExact.map(PortfolioDecimal.double)
        var unrealizedPercent: Double?
        if let unrealizedExact, includedCost > 0 {
            unrealizedPercent = PortfolioDecimal.double(PortfolioDecimal.divide(unrealizedExact, includedCost)) * 100
        }
        let realizedExact = positions.reduce(Decimal(0)) { $0 + $1.realizedExact }

        return PortfolioSummary(
            open: open,
            closed: closed,
            totalValue: totalValue,
            invested: invested,
            unrealized: unrealized,
            unrealizedPercent: unrealizedPercent,
            realized: PortfolioDecimal.double(realizedExact),
            costMissing: !missingCost.isEmpty || !partialCost.isEmpty,
            missingCurrentPrices: missingCurrent,
            realizedIncomplete: positions.contains { $0.realizedIncomplete },
            missingCostCoins: missingCost,
            investedExact: investedExact,
            realizedExact: realizedExact,
            feeValueMissingCoins: positions.filter(\.feeValueMissing).map(\.coin).sorted(),
            addressValue: PortfolioDecimal.double(open.reduce(Decimal(0)) { $0 + ($1.addressValueExact ?? 0) }),
            method: method,
            withdrawnGainExact: withdrawn.count > 0 && (withdrawn.amount != 0 || !withdrawn.incomplete) ? withdrawn.amount : nil,
            withdrawnGainIncomplete: withdrawn.incomplete,
            partialCostParts: partialCost,
            unassignedFeeUsdt: positions.reduce(Decimal(0)) { $0 + $1.unassignedFeeUsdt }
        )
    }
}
