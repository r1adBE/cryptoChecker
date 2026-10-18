import Foundation

/// Ein Schritt im Verlauf eines Coins: eine eigene Transaktion, (`feeLeg`) die Gebühr einer
/// Transaktion eines ANDEREN Coins, die in diesem Coin bezahlt wurde (`PortfolioTx.thirdFeeCoin`), oder (`swapIn`) der
/// Zugang eines Tauschs in seinem erhaltenen Coin (`PortfolioTx.swapToCoin`).
struct PortfolioLedgerEvent: Sendable {
    let trade: PortfolioTx
    let feeLeg: Bool
    var swapIn = false

    init(trade: PortfolioTx, feeLeg: Bool, swapIn: Bool = false) {
        self.trade = trade
        self.feeLeg = feeLeg
        self.swapIn = swapIn
    }

    /// Zugang (Kauf, Belohnung, Einzahlung, Zugang aus Tausch) — wie `isAcquisition` (Android).
    var isAcquisition: Bool { !feeLeg && (swapIn || trade.type.isInflow) }
}

/// Gemeinsame Regeln für Bestand und Zuflüsse aller Transaktionsarten — wie `PortfolioLedger.kt` (Android,
/// dort ausführlich beschrieben und getestet). Genutzt von `PortfolioCalculator`, `PortfolioPeriodChange`,
/// `PortfolioHistory` und `CutoffExport`.
///
/// Bestand nach einem Schritt (`holdingsAfter`, nie unter 0, Abgänge gekappt): Kauf/Belohnung/Einzahlung
/// + Menge − Gebühr im Coin; Verkauf/Verschenkt/Auszahlung − (Menge + Gebühr im Coin); Umbuchung − Gebühr im Coin;
/// Gebühr-Abgang − Gebühr.
///
/// Zufluss in USDT (`flow`; + = hinein, − = heraus, nil = unbekannt): Kauf/Belohnung/Einzahlung
/// + (Menge × Preis + Gebühr USDT + Wert der Gebühr im dritten Coin); Verkauf − (verkaufte Menge × Preis −
/// Gebühren), Damit bezahlt ebenso zum Marktwert (ohne: zum MARKTKURS), Verschenkt wie eine Auszahlung; Gebühr-Zeile (`isFeeDisposal`) wie ein Verkauf zum MARKTKURS (Abfluss, kein Sprung am Gebühr-Tag; ohne
/// Marktkurs nil); Auszahlung wie ein Verkauf zum erfassten Preis, sonst zum MARKTKURS ihres Zeitpunkts (`marketPrice`,
/// kein Gewinn/Verlust aus dem Abgang; ohne Marktkurs nil; über den Bestand hinaus Wert und Gebühren zum gedeckten
/// Anteil `withdrawnFraction`); Einzahlung ohne Preis ebenso zum Marktkurs (eingebrachtes
/// Geld, kein Gewinn — der Einstand bleibt unbekannt); Adress-Einträge (negative Id, ohne Preis) zum Marktkurs;
/// Umbuchung + (Gebühr USDT + Wert der Gebühr im dritten Coin), ohne Bestand in der Quelle 0; Gebühr-Abgang − (Gebühr × Gebühr-Preis).
/// Belohnung ohne Wert und Gebühr im dritten Coin ohne Preis (Gebühr-Abgang wie Haupttransaktion, `feeMarketPrice`)
/// zum MARKTKURS ihres Zeitpunkts — eine Buchung ohne Preis lässt den Coin nicht mehr aus «Heute/7 T/1 J» fallen;
/// nur ohne Marktkurs nil. Wie Android.
/// Tausch (neutral): abgegebener Coin wie ein Verkauf zum Wert des Tauschs, erhaltener Coin + Wert; ohne Wert je zum
/// Marktkurs des Coins (erhalten: Menge vor Gebühr × Kurs); Bestand abgegeben − (Menge + Gebühr im Coin), erhalten
/// + (Menge − Gebühr im erhaltenen Coin).
enum PortfolioLedger {

    /// Reihenfolge der Schritte wie Android `ORDER`: Zeit, bei gleicher Zeit Zugänge (`isAcquisition`) vor Abgängen, dann
    /// Id. `true` = `a` vor `b`; nil = gleichrangig (dann Reihenfolge der Eingabe).
    static func precedes(_ a: PortfolioLedgerEvent, _ b: PortfolioLedgerEvent) -> Bool? {
        if a.trade.time != b.trade.time { return a.trade.time < b.trade.time }
        if a.isAcquisition != b.isAcquisition { return a.isAcquisition }
        if a.trade.id != b.trade.id { return a.trade.id < b.trade.id }
        return nil
    }

    /// Schritte eines Coins zeitlich (`precedes`), samt Gebühr-Abgängen anderer Transaktionen.
    static func events(_ coin: String, trades: [PortfolioTx]) -> [PortfolioLedgerEvent] {
        let symbol = PortfolioCalculator.normalizeCoin(coin)
        var out: [PortfolioLedgerEvent] = []
        for t in trades {
            if PortfolioCalculator.normalizeCoin(t.coin) == symbol {
                out.append(PortfolioLedgerEvent(trade: t, feeLeg: false))
            } else if t.swapToCoin == symbol {
                out.append(PortfolioLedgerEvent(trade: t, feeLeg: false, swapIn: true))
            } else if t.thirdFeeCoin == symbol {
                out.append(PortfolioLedgerEvent(trade: t, feeLeg: true))
            }
        }
        // Stabil sortiert wie Android (`sortedWith`): `precedes`, sonst Reihenfolge der Eingabe
        return out.enumerated().sorted { a, b in
            precedes(a.element, b.element) ?? (a.offset < b.offset)
        }.map(\.element)
    }

    /// `events` aller Coins in einem Durchgang (Reihenfolge der Coins wie `coins`): je Coin dieselbe Liste wie `events`
    /// — ohne für jeden Coin alle Transaktionen zu durchlaufen. Wie `eventsByCoin` (Android).
    static func eventsByCoin(_ trades: [PortfolioTx]) -> [(coin: String, events: [PortfolioLedgerEvent])] {
        let order = coins(trades)
        var index: [String: Int] = [:]
        for (i, c) in order.enumerated() { index[c] = i }
        var lists: [[(offset: Int, event: PortfolioLedgerEvent)]] = Array(repeating: [], count: order.count)
        for (n, t) in trades.enumerated() {
            if let i = index[PortfolioCalculator.normalizeCoin(t.coin)] {
                lists[i].append((offset: n, event: PortfolioLedgerEvent(trade: t, feeLeg: false)))
            }
            if let c = t.swapToCoin, let i = index[c] {
                lists[i].append((offset: n, event: PortfolioLedgerEvent(trade: t, feeLeg: false, swapIn: true)))
            }
            if let c = t.thirdFeeCoin, let i = index[c] {
                lists[i].append((offset: n, event: PortfolioLedgerEvent(trade: t, feeLeg: true)))
            }
        }
        // Stabil sortiert wie `events`: `precedes`, sonst Reihenfolge der Eingabe
        return order.enumerated().map { item -> (coin: String, events: [PortfolioLedgerEvent]) in
            let sorted = lists[item.offset].sorted { a, b in
                precedes(a.event, b.event) ?? (a.offset < b.offset)
            }
            return (coin: item.element, events: sorted.map(\.event))
        }
    }

    /// Alle Coins: zuerst die der Transaktionen, dann die erhaltenen Coins eines Tauschs, dann die Gebühr-Coins
    /// (normalisiert, ohne Doppel).
    static func coins(_ trades: [PortfolioTx]) -> [String] {
        var seen = Set<String>()
        var out: [String] = []
        for t in trades {
            let c = PortfolioCalculator.normalizeCoin(t.coin)
            if seen.insert(c).inserted { out.append(c) }
        }
        for t in trades {
            if let c = t.swapToCoin, seen.insert(c).inserted { out.append(c) }
        }
        for t in trades {
            if let c = t.thirdFeeCoin, seen.insert(c).inserted { out.append(c) }
        }
        return out
    }

    /// Bestand nach `e`, exakt; ungültige Menge ändert nichts.
    static func holdingsAfter(_ holdings: Decimal, _ e: PortfolioLedgerEvent) -> Decimal {
        let t = e.trade
        guard t.amountExact > 0 else { return holdings }
        let h = max(0, holdings)
        if e.feeLeg { return h - min(t.thirdFeeAmount, h) }
        if e.swapIn { return h + t.swapReceived }
        switch t.type {
        case .BUY, .REWARD, .DEPOSIT: return h + t.receivedExact
        case .SELL, .PAYMENT, .GIFT, .WITHDRAWAL, .SWAP: return h - min(t.amountExact + t.coinFeeExact, h)
        case .TRANSFER: return h - min(t.coinFeeExact, h)
        }
    }

    /// Braucht `e` einen Marktkurs SEINES Coins für `flow`? Auszahlung, Einzahlung und Belohnung ohne Preis (auch
    /// Adress-Einträge), Tausch ohne Wert (beide Seiten, je Kurs ihres Coins), Gebühr-Abgang ohne Gebühr-Preis,
    /// Gebühr-Zeile (`isFeeDisposal`).
    static func needsMarketPrice(_ e: PortfolioLedgerEvent) -> Bool {
        let t = e.trade
        if e.feeLeg { return t.thirdFeePrice == nil }
        if t.type == .SWAP { return t.swapValue == nil }
        // Gebühr-Zeile: zum Tageskurs
        if isFeeDisposal(t) { return true }
        if t.validPriceExact != nil { return false }
        return t.type == .WITHDRAWAL || t.type == .GIFT || t.type == .PAYMENT || t.type == .DEPOSIT || t.type == .REWARD
    }

    /// Braucht `e` den Marktkurs des GEBÜHR-Coins (`thirdFeeCoin`) für `flow` (`feeMarketPrice`)? Gebühr in einem
    /// dritten Coin ohne Preis, auf der Seite der Haupttransaktion (nicht der Zugang eines Tauschs). Wie Android.
    static func needsFeeMarketPrice(_ e: PortfolioLedgerEvent) -> Bool {
        !e.feeLeg && !e.swapIn && e.trade.thirdFeeCoin != nil && e.trade.thirdFeePrice == nil
    }

    /// Zufluss von `e` in USDT bei Bestand `holdingsBefore`; nil = unbekannt. `marketPrice` = Marktkurs des Coins zum
    /// Zeitpunkt von `e` (nur nötig, wenn `needsMarketPrice`); `feeMarketPrice` = Marktkurs des Gebühr-Coins dann (nur
    /// nötig, wenn `needsFeeMarketPrice`). `method` = Einstandsmethode des Buchs: gedeckter Anteil eines Verkaufs/Tauschs
    /// über den Bestand hinaus wie «realisiert» und Steuer (`coveredFraction`). Tausch ohne gültigen Zugang: nil.
    /// Bewertet in Double wie Android.
    static func flow(_ e: PortfolioLedgerEvent, holdingsBefore: Decimal, marketPrice: Double? = nil,
                     feeMarketPrice: Double? = nil, method: CostMethod = .defaultMethod) -> Double? {
        flowExact(e, holdingsBefore: holdingsBefore, marketPrice: marketPrice, feeMarketPrice: feeMarketPrice,
                  method: method).map(PortfolioDecimal.double)
    }

    /// `flow` exakt (Decimal: Menge × Kurs, Gebühren, Anteil; Börsenkurse über ihre kürzeste Darstellung) — erst das
    /// Ergebnis wird zur Double-Sicht. nil = unbekannt. Wie Android `flowExact`.
    static func flowExact(_ e: PortfolioLedgerEvent, holdingsBefore: Decimal, marketPrice: Double? = nil,
                          feeMarketPrice: Double? = nil, method: CostMethod = .defaultMethod) -> Decimal? {
        let t = e.trade
        guard t.amountExact > 0 else { return 0 }
        let h = max(0, holdingsBefore)
        if e.feeLeg {
            // Ohne Gebühr-Preis zum Marktkurs des Gebühr-Coins (neutral wie eine Auszahlung ohne Preis)
            guard let feePrice = t.thirdFeePrice ?? exactPrice(marketPrice) else { return nil }
            let removed = min(t.thirdFeeAmount, h)
            return removed > 0 ? -(removed * feePrice) : 0
        }
        let market = exactPrice(marketPrice)
        if e.swapIn {
            // Zugang aus Tausch: zum Wert des Tauschs, sonst erhaltene Menge (vor Gebühr) × Marktkurs; ohne gültigen Zugang unbekannt
            guard t.swapToCoin != nil else { return nil }
            if let value = t.swapValue { return value }
            guard let market, let to = t.toAmountExact else { return nil }
            return to * market
        }
        // Umbuchung ohne Bestand (in ihrer Quelle): wie im Rechner (`PortfolioFifo`, Ø) ohne Wirkung — auch ihre Gebühren
        if t.type == .TRANSFER && h <= 0 { return 0 }
        // Gebühr im dritten Coin ohne Preis: zum Marktkurs des Gebühr-Coins
        guard let feeValue = t.thirdFeeValue ?? exactPrice(feeMarketPrice).map({ t.thirdFeeAmount * $0 }) else { return nil }
        // Gebühr-Zeile zum Marktkurs (Abfluss wie ein Verkauf, dessen Erlös das Portfolio verlässt)
        let price = isFeeDisposal(t) ? market : (t.validPriceExact ?? (needsMarketPrice(e) ? market : nil))
        switch t.type {
        case .BUY, .REWARD, .DEPOSIT:
            guard let price else { return nil }
            return t.amountExact * price + t.quoteFeeExact + feeValue
        case .SELL, .PAYMENT:
            // Damit bezahlt: Wert verlässt das Portfolio wie ein Verkauf (ohne Marktwert zum Marktkurs)
            guard let price else { return nil }
            // Nichts mehr da: der Verkauf zählt nicht, auch nicht seine Gebühr — wie «realisiert»
            guard let share = coveredFraction(t, holdings: h, method: method) else { return 0 }
            return -partOf(t.amountExact * price - t.quoteFeeExact - feeValue, share)
        case .WITHDRAWAL, .GIFT:
            // Verschenkt: wie eine Auszahlung (Wert verlässt das Portfolio zum Marktwert)
            guard let share = withdrawnFraction(t, holdings: h) else { return 0 }
            guard let price else { return nil }
            // Wert und Gebühren zum gedeckten Anteil wie beim Verkauf (ungedeckter Rest: nichts abgegeben)
            return -partOf(t.amountExact * price - t.quoteFeeExact - feeValue, share)
        case .TRANSFER:
            return t.quoteFeeExact + feeValue
        case .SWAP:
            // Abgegebener Coin wie ein Verkauf: zum Wert des Tauschs, sonst zum Marktkurs; gedeckter Anteil wie dort
            guard let share = coveredFraction(t, holdings: h, method: method) else { return 0 }
            // Zugang kaputt (kein gültiger erhaltener Coin): unbekannt statt still verloren
            guard t.swapToCoin != nil else { return nil }
            let gross: Decimal
            if let value = t.swapValue {
                gross = value
            } else {
                guard let market else { return nil }
                gross = t.amountExact * market
            }
            return -partOf(gross - t.quoteFeeExact - feeValue, share)
        }
    }

    /// Börsenkurs (≥ 0, endlich) exakt über seine kürzeste Darstellung; sonst nil.
    private static func exactPrice(_ price: Double?) -> Decimal? {
        guard let price, price >= 0, price.isFinite else { return nil }
        return PortfolioDecimal.of(price)
    }

    /// `value` × Anteil `fraction` (ganz gedeckt: unverändert).
    private static func partOf(_ value: Decimal, _ fraction: Double) -> Decimal {
        fraction == 1 ? value : value * (PortfolioDecimal.of(fraction) ?? 0)
    }

    /// Gedeckter Anteil (0…1] des ganzen Erlöses eines Verkaufs/Tauschs bei Bestand `holdings` — dieselbe Aufteilung wie
    /// «realisiert» und Steuer: FIFO (beide) zuerst die verkaufte Menge, dann die Gebühr-Coins → min(Menge, abgegeben) /
    /// Menge; Ø abgegeben / (Menge + Gebühr im Coin). Ganz gedeckt exakt 1; nil = nichts abgegeben. Wie Android.
    static func coveredFraction(_ t: PortfolioTx, holdings: Decimal, method: CostMethod) -> Double? {
        let requested = t.amountExact + t.coinFeeExact
        let removed = min(requested, max(0, holdings))
        guard removed > 0, requested > 0 else { return nil }
        if removed == requested { return 1 }
        if method.isFifo {
            return PortfolioDecimal.double(PortfolioDecimal.divide(min(t.amountExact, removed), t.amountExact))
        }
        return PortfolioDecimal.double(PortfolioDecimal.divide(removed, requested))
    }

    /// Gedeckter Anteil (0…1] einer Auszahlung bei Bestand `holdings`: abgegeben / (Menge + Gebühr im Coin) — wie die
    /// ausgezahlte Menge im Rechner (anteilig); gilt auch für ihre Gebühren (`PortfolioFeeExpenses.withdrawal`). Ganz
    /// gedeckt exakt 1; nil = nichts abgegeben. Wie Android.
    static func withdrawnFraction(_ t: PortfolioTx, holdings: Decimal) -> Double? {
        withdrawnShare(t, holdings: holdings).map { $0 == 1 ? 1 : PortfolioDecimal.double($0) }
    }

    /// `withdrawnFraction` exakt; nil = nichts abgegeben.
    static func withdrawnShare(_ t: PortfolioTx, holdings: Decimal) -> Decimal? {
        let requested = t.amountExact + t.coinFeeExact
        let removed = min(requested, max(0, holdings))
        guard removed > 0, requested > 0 else { return nil }
        // 34 Stellen wie Android (`PortfolioDecimal.MC`)
        return removed == requested ? 1 : PortfolioDecimal.divide(removed, requested)
    }
}
