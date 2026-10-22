import Foundation

// FR: Erwerbspreis-Summe (PTA) über das ganze Portfolio — wie `TaxEngineFr.kt` (Android).

extension TaxEngines {

    static func frPta(_ real: [PortfolioTx], _ priceAt: ((String, Int64) -> Decimal?)?,
                              _ outflows: inout [TaxOutflow],
                              addressTrades: [PortfolioTx] = []) -> [PortfolioDisposal] {
        var books: [String: PortfolioHoldingsBook] = [:]
        var bookOrder: [String] = []
        // Guthaben beobachteter Adressen je Coin bis zum laufenden Zeitpunkt (Einzahlung +, Auszahlung −, nie unter 0)
        let addrSorted: [PortfolioTx] = addressTrades.filter { $0.amountExact > 0 }.enumerated().sorted { a, b in
            PortfolioLedger.precedes(PortfolioLedgerEvent(trade: a.element, feeLeg: false),
                                     PortfolioLedgerEvent(trade: b.element, feeLeg: false)) ?? (a.offset < b.offset)
        }.map(\.element)
        var addr: [String: Decimal] = [:]
        var addrOrder: [String] = []
        var ai = 0
        func advanceAddresses(_ time: Int64) {
            while ai < addrSorted.count && addrSorted[ai].time <= time {
                let a = addrSorted[ai]
                ai += 1
                let c = PortfolioCalculator.normalizeCoin(a.coin)
                let d: Decimal = a.type == .WITHDRAWAL ? -a.amountExact : a.amountExact
                if addr[c] == nil { addrOrder.append(c) }
                addr[c] = max(0, (addr[c] ?? 0) + d)
            }
        }
        var pta: Decimal = 0
        var ptaParts: [PortfolioCostPart] = []
        var incomplete = false
        var out: [PortfolioDisposal] = []
        let events = mergedEvents(real)

        // Der zuletzt gerechnete Gesamtwert enthielt Guthaben beobachteter Adressen (für `addressValueIncluded`)
        var valueHadAddresses = false

        /// Gesamtwert aller Bestände unmittelbar vor `time` — eigene Bestände und Guthaben beobachteter Adressen (zum selben
        /// Kurs); `coin` zu `price`; nil = ein Kurs fehlt. Setzt `valueHadAddresses`. Wie Android.
        func portfolioValue(_ coin: String, _ price: Decimal?, _ time: Int64) -> Decimal? {
            var value: Decimal? = 0
            valueHadAddresses = false
            for c in bookOrder + addrOrder.filter({ books[$0] == nil }) {
                let q = (books[c]?.total ?? 0) + (addr[c] ?? 0)
                if q <= 0 || PortfolioDecimal.isDust(q) { continue }
                if (addr[c] ?? 0) > 0 { valueHadAddresses = true }
                let p: Decimal?
                if c == coin {
                    p = price
                } else if !PortfolioStables.needsQuote(c) {
                    p = 1
                } else {
                    p = priceAt?(c, time).flatMap { $0 >= 0 ? $0 : nil }
                }
                if let p, let v = value { value = v + PortfolioDecimal.multiply(q, p) } else { value = nil }
            }
            return value
        }

        /// Kürzt die PTA um den Anteil `part` / `value` (gekappt auf 1); Rückgabe = Kürzung samt Herkunft.
        func reduce(_ part: Decimal, _ value: Decimal) -> (cost: Decimal, parts: [PortfolioCostPart]) {
            let f = min(PortfolioDecimal.divide(part, value), 1)
            let c: Decimal = f == 1 ? pta : PortfolioDecimal.multiply(pta, f)
            let parts = scaleParts(ptaParts, total: pta, target: c)
            ptaParts = scaleParts(ptaParts, total: pta, target: pta - c)
            pta -= c
            return (c, parts)
        }

        var i = 0
        while i < events.count {
            let t = events[i].event.trade
            var j = i
            while j < events.count && events[j].event.trade.id == t.id { j += 1 }
            let coin = PortfolioCalculator.normalizeCoin(t.coin)
            advanceAddresses(t.time)
            if t.amountExact > 0 {
                let price = t.validPriceExact
                let feeValue = t.thirdFeeValue
                // Kurs des Haupt-Coins für Gebühr und Abgang: Preis, Wert des Tauschs je Coin, Stablecoin 1, sonst priceAt
                var unit: Decimal? = price
                if unit == nil, let sv = t.swapValue { unit = PortfolioDecimal.divide(sv, t.amountExact) }
                if unit == nil && !PortfolioStables.needsQuote(coin) { unit = 1 }
                if unit == nil { unit = priceAt?(coin, t.time).flatMap { $0 >= 0 ? $0 : nil } }
                // Wert der Gebühr-Abtretungen dieser Transaktion: der Gesamtwert danach ist um so viel kleiner
                var spent: Decimal = 0

                /// Gebühr in Krypto ausserhalb eines Verkaufs: eigene Abtretung gegen eine Dienstleistung
                /// (BOI-RPPM-PVBMC-30-10 § 70 Remarque 1) — Abtretungspreis = Wert der Gebühr, keine weiteren Gebühren.
                func feeCession(_ feeCoin: String, _ qty: Decimal, _ p: Decimal?, feeLeg: Bool) {
                    guard qty > 0 else { return }
                    let held: Decimal = books[feeCoin]?.total ?? 0
                    // Nur der gedeckte Anteil ist eine Abtretung mit Einstand (wie im Verkaufspfad): eine Gebühr in einem nie
                    // erfassten Coin darf die PTA nicht kürzen und keinen Gewinn ergeben
                    let covered: Decimal = min(qty, max(0, held))
                    if covered > 0 {
                        let coveredGross: Decimal? = p.map { PortfolioDecimal.multiply(covered, $0) }
                        let value: Decimal? = portfolioValue(feeCoin, p, t.time).map { $0 - spent }
                        let withAddresses = value != nil && valueHadAddresses
                        let wasIncomplete = incomplete
                        var cost: Decimal? = nil
                        var parts: [PortfolioCostPart] = []
                        if let coveredGross, let value, value > 0 {
                            let r = reduce(coveredGross, value)
                            cost = r.cost
                            parts = r.parts
                        } else {
                            incomplete = true
                        }
                        if let coveredGross { spent += coveredGross }
                        var gain: Decimal? = nil
                        if let coveredGross, let cost { gain = coveredGross - cost }
                        var d = PortfolioDisposal(
                            coin: feeCoin, sellTxId: t.id, sellTime: t.time, lotTxId: nil, acquiredTime: nil, lotKind: nil,
                            amount: covered, costUsdt: cost, proceedsUsdt: coveredGross, gainUsdt: gain, holdingDays: nil,
                            feeLeg: feeLeg, feeValueMissing: false, method: .AVERAGE, costParts: parts, uncovered: false)
                        d.costIncomplete = wasIncomplete || cost == nil
                        d.portfolioValueUsdt = value
                        d.grossProceedsUsdt = coveredGross
                        d.addressValueIncluded = withAddresses
                        out.append(d)
                    }
                    if covered < qty {
                        // Rest ohne erfassten Bestand: Wert bekannt, Einstand unbekannt — zählt nicht in der Summe, PTA unverändert
                        let rest = qty - covered
                        let restGross: Decimal? = p.map { PortfolioDecimal.multiply(rest, $0) }
                        var d = PortfolioDisposal(
                            coin: feeCoin, sellTxId: t.id, sellTime: t.time, lotTxId: nil, acquiredTime: nil, lotKind: nil,
                            amount: rest, costUsdt: nil, proceedsUsdt: restGross, gainUsdt: nil, holdingDays: nil,
                            feeLeg: feeLeg, feeValueMissing: false, method: .AVERAGE, costParts: [], uncovered: true)
                        d.costIncomplete = true
                        d.grossProceedsUsdt = restGross
                        out.append(d)
                    }
                }

                /// Gebühr im dritten Coin (ausser beim Verkauf): eigene Abtretung zum erfassten Preis bzw. priceAt.
                func thirdFeeCession() {
                    guard let tc = t.thirdFeeCoin else { return }
                    let p: Decimal? = t.thirdFeePrice ?? priceAt?(tc, t.time).flatMap { $0 >= 0 ? $0 : nil }
                    feeCession(tc, t.thirdFeeAmount, p, feeLeg: true)
                }

                switch t.type {
                case .BUY, .REWARD, .DEPOSIT:
                    // Gebühr im dritten Coin: Abtretung vor dem Zugang (Gesamtwert ohne den neuen Coin)
                    thirdFeeCession()
                    if let price {
                        let c: Decimal = t.amountExact * price + t.quoteFeeExact + (feeValue ?? 0)
                        pta += c
                        if c != 0 { ptaParts.append(PortfolioCostPart(time: t.acquisitionTime, usdt: c)) }
                    } else {
                        // Ohne Preis (z. B. Einzahlung ohne Kaufpreis): zum Wert 0 (BOFiP § 90), aber unvollständig
                        incomplete = true
                    }
                case .SELL, .PAYMENT:
                    // Damit bezahlt: Abtretung zum Marktwert wie ein Verkauf (Zahlung mit Krypto = cession à titre onéreux)
                    let held: Decimal = books[coin]?.total ?? 0
                    // Eine Operation (BOFiP § 50): Preis vor Gebühren = (Menge + Gebühr im Coin) × Preis + Wert der Gebühr
                    // im dritten Coin; Gebühren = Gebühr USDT + dieselben Gebühr-Werte → Erlös = Menge × Preis − Gebühr USDT
                    let qty: Decimal = t.amountExact + t.coinFeeExact
                    let gross: Decimal? = price.map { qty * $0 + (feeValue ?? 0) }
                    let net: Decimal? = price.map { t.amountExact * $0 - t.quoteFeeExact }
                    // Über den erfassten Bestand hinaus: nur der gedeckte Anteil ist eine Abtretung mit Einstand
                    let removed: Decimal = min(qty, max(0, held))
                    let share: Decimal = removed <= 0 ? 0 : (removed == qty ? 1 : PortfolioDecimal.divide(removed, qty))
                    func part(_ v: Decimal?) -> Decimal? { v.map { share == 1 ? $0 : PortfolioDecimal.multiply($0, share) } }
                    let grossCovered = part(gross)
                    let netCovered = part(net)
                    let feeMissing = gross != nil && feeValue == nil
                    if removed > 0 {
                        // Gesamtwert unmittelbar vor der Abtretung (verkaufter Coin zum Verkaufspreis)
                        let value = portfolioValue(coin, price, t.time)
                        let withAddresses = value != nil && valueHadAddresses
                        let wasIncomplete = incomplete
                        var cost: Decimal? = nil
                        var parts: [PortfolioCostPart] = []
                        if let grossCovered, let value, value > 0 {
                            let r = reduce(grossCovered, value)
                            cost = r.cost
                            parts = r.parts
                        } else {
                            // Formel nicht anwendbar: die Erwerbspreis-Summe danach ist nicht mehr gesichert
                            incomplete = true
                        }
                        var gain: Decimal? = nil
                        if let netCovered, let cost { gain = netCovered - cost }
                        var d = PortfolioDisposal(
                            coin: coin, sellTxId: t.id, sellTime: t.time, lotTxId: nil, acquiredTime: nil, lotKind: nil,
                            amount: removed, costUsdt: cost, proceedsUsdt: netCovered, gainUsdt: gain, holdingDays: nil,
                            feeLeg: false, feeValueMissing: feeMissing, method: .AVERAGE,
                            costParts: parts, uncovered: false)
                        // Ohne Gesamtwert (Kurs fehlt) ist schon diese Abtretung unvollständig
                        d.costIncomplete = wasIncomplete || cost == nil
                        d.portfolioValueUsdt = value
                        d.grossProceedsUsdt = grossCovered
                        d.addressValueIncluded = withAddresses
                        out.append(d)
                    }
                    if removed < qty {
                        // Rest ohne erfassten Bestand: Erlös bekannt, Einstand unbekannt — zählt nicht in der Summe
                        var d = PortfolioDisposal(
                            coin: coin, sellTxId: t.id, sellTime: t.time, lotTxId: nil, acquiredTime: nil, lotKind: nil,
                            amount: qty - removed, costUsdt: nil, proceedsUsdt: net.map { $0 - (netCovered ?? 0) },
                            gainUsdt: nil, holdingDays: nil, feeLeg: false, feeValueMissing: feeMissing, method: .AVERAGE,
                            costParts: [], uncovered: true)
                        d.costIncomplete = true
                        d.grossProceedsUsdt = gross.map { $0 - (grossCovered ?? 0) }
                        out.append(d)
                    }
                case .WITHDRAWAL, .GIFT:
                    // Verschenkt = Abtretung «à titre gratuit»: keine Veräusserung, PTA anteilig gekürzt wie die Auszahlung
                    // Gebühren in Krypto zuerst als eigene Abtretungen
                    feeCession(coin, t.coinFeeExact, unit, feeLeg: false)
                    thirdFeeCession()
                    // Abgang ohne Verkauf (Schenkung, Verlust, unbekannt): PTA anteilig kürzen (BOFiP § 100,
                    // «à titre gratuit»), Wert = Menge × Kurs (erfasst, Stablecoin oder priceAt)
                    let qty: Decimal = t.amountExact
                    let v: Decimal? = unit.map { PortfolioDecimal.multiply(qty, $0) }
                    let value: Decimal? = portfolioValue(coin, unit, t.time).map { $0 - spent }
                    var reduction: Decimal? = nil
                    var reductionParts: [PortfolioCostPart] = []
                    if let v, let value, value > 0 {
                        let r = reduce(v, value)
                        reduction = r.cost
                        reductionParts = r.parts
                    } else {
                        // Kurs fehlt: Kürzung unbekannt, die PTA danach nicht mehr gesichert
                        incomplete = true
                    }
                    outflows.append(TaxOutflow(coin: coin, txId: t.id, time: t.time, amount: qty, valueUsdt: v,
                                               ptaReductionUsdt: reduction, ptaReductionParts: reductionParts))
                case .SWAP, .TRANSFER:
                    // Tausch Krypto↔Krypto: aufgeschoben (sursis d'imposition), PTA unverändert — auch in einen Stablecoin
                    // (USDT, USDC …): er bleibt im Gesamtwert, steuerbar erst sein Verkauf gegen Euro; Umbuchung zwischen
                    // eigenen Wallets: bleibt im Portfolio — Gebühren in Krypto (Coin, dritter Coin) sind eigene Abtretungen
                    feeCession(coin, t.coinFeeExact, unit, feeLeg: false)
                    thirdFeeCession()
                }
            }
            for k in i..<j {
                let c = events[k].coin
                if books[c] == nil {
                    books[c] = PortfolioHoldingsBook(method: .FIFO)
                    bookOrder.append(c)
                }
                books[c]?.apply(events[k].event)
            }
            i = j
        }
        return out
    }
}

extension TaxEngines {
    /// FR: Coins mit eigenem Bestand unmittelbar vor `t` in der Reihenfolge der Rechnung (`PortfolioLedger.precedes`: Zeit,
    /// bei gleicher Zeit Zugänge vor Abgängen, dann Id) — genau die Bestände, die `frPta` im Gesamtwert vor `t` zählt. Für
    /// die Kursabfrage des Exports: ein Kauf derselben Millisekunde, den die Rechnung davor bucht, braucht ebenfalls einen
    /// Kurs. `own` ohne Adress-Einträge. Wie `frHeldBefore` (Android).
    static func frHeldBefore(_ own: [PortfolioTx], _ t: PortfolioTx) -> Set<String> {
        let key = PortfolioLedgerEvent(trade: t, feeLeg: false)
        let before = own.filter {
            $0.time <= t.time && PortfolioLedger.precedes(PortfolioLedgerEvent(trade: $0, feeLeg: false), key) == true
        }
        return Set(PortfolioCalculator.holdingsAt(before, time: .max, method: .FIFO).map(\.coin))
    }
}

/// FR: Abgang ohne Verkauf (Auszahlung) — wie `TaxOutflow` (Android): Menge ohne Gebühr (die ist eine Abtretung), Wert beim Abgang,
/// Kürzung der Erwerbspreis-Summe (USDT); nil = Kurs fehlt. `ptaReductionParts` = Herkunft der Kürzung (Anteil der
/// Erwerbspreis-Summe je Kaufdatum, Summe = `ptaReductionUsdt`) — in der Steuerwährung zum Kurs der Kaufdaten wie der
/// Einstand einer Abtretung, nicht zum Kurs des Abgangs.
struct TaxOutflow: Sendable, Equatable {
    let coin: String
    let txId: Int64
    let time: Int64
    let amount: Decimal
    let valueUsdt: Decimal?
    let ptaReductionUsdt: Decimal?
    var ptaReductionParts: [PortfolioCostPart] = []
}
