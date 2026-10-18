import Foundation

// AT: Altbestand (FIFO) und Neubestand (gleitender Durchschnitt) je Coin und Wallet — wie `TaxEngineAt.kt` (Android).

extension TaxEngines {

    /// Altbestand-Posten (FIFO).
    private struct AtLot {
        let txId: Int64
        let acquired: Int64
        let kind: PortfolioTxType
        var qty: Decimal
        var cost: Decimal?
    }

    /// Topf eines Coins in EINER Wallet (`wallet` = Anzeigename, leer = ohne Angabe).
    private struct AtCoin {
        let wallet: String
        var old: [AtLot] = []
        var neu: TaxPool
        var oldHeld: Decimal { old.reduce(Decimal(0)) { $0 + $1.qty } }

        init(wallet: String, zone: TimeZone) {
            self.wallet = wallet
            neu = TaxPool(zone: zone)
        }

        /// Altbestand-Posten nach Erwerb (dann Id) einsortieren (Umbuchung).
        mutating func insertOld(_ lot: AtLot) {
            if let i = old.firstIndex(where: { $0.acquired > lot.acquired || ($0.acquired == lot.acquired && $0.txId > lot.txId) }) {
                old.insert(lot, at: i)
            } else {
                old.append(lot)
            }
        }
    }

    /// Schlüssel eines Topfs: Coin und Wallet-Schlüssel (wie FIFO je Wallet).
    private struct AtKey: Hashable {
        let coin: String
        let wallet: String
    }

    /// Neubestand-Teil eines Tauschs, der auf den erhaltenen Coin übergeht; `realized` = Tausch vor dem 1.3.2022: ganz eine
    /// Veräusserung, der erhaltene Coin angeschafft zum Wert am Tag des Tauschs.
    private struct AtCarry {
        let total: Decimal
        let oldQty: Decimal
        let taken: TaxTaken
        let value: Decimal?
        let realized: Bool
    }

    /// Nimmt höchstens `qty` aus dem Altbestand (FIFO, früher erworben zuerst).
    private static func takeOld(_ c: inout AtCoin, _ qty: Decimal) -> [Piece] {
        var pieces: [Piece] = []
        var left = qty
        while left > 0, !c.old.isEmpty {
            var lot = c.old[0]
            let q = min(left, lot.qty)
            var cost: Decimal? = nil
            if let lc = lot.cost { cost = q == lot.qty ? lc : PortfolioDecimal.divide(lc * q, lot.qty) }
            lot.qty -= q
            if let lc = lot.cost, let cost { lot.cost = lc - cost }
            if lot.qty <= 0 { c.old.removeFirst() } else { c.old[0] = lot }
            left -= q
            pieces.append(Piece(amount: q, cost: cost, acquiredTime: lot.acquired, lotTxId: lot.txId, lotKind: lot.kind,
                                oldHolding: true))
        }
        return pieces
    }

    /// Nimmt `qty` zuerst aus dem Altbestand (FIFO), dann aus dem Neubestand.
    private static func takeBoth(_ c: inout AtCoin, _ qty: Decimal) -> (pieces: [Piece], taken: TaxTaken) {
        let pieces = takeOld(&c, qty)
        let taken = c.neu.take(qty - pieces.reduce(Decimal(0)) { $0 + $1.amount })
        return (pieces, taken)
    }

    /// Anschaffungskosten eines Zugangs im Neubestand — wie Android `newCost`: Menge × Preis ohne Gebühren (§ 27a Abs. 4 Z 2
    /// EStG: ohne Anschaffungsnebenkosten; `s` hat schon die erhaltene Menge, Gebühr USDT bzw. im dritten Coin kommt nicht
    /// dazu). Belohnung: 0 (BMF: Staking, Airdrop — § 27b Abs. 2 EStG); Zins (`interestReward`) zum Wert beim Zufluss, denn
    /// er ist dort schon Einkunft (§ 27b Abs. 2 Z 1 EStG).
    private static func atNewCost(_ s: TaxStep) -> Decimal? {
        if s.trade.type == .REWARD && !s.trade.interest { return 0 }
        return s.trade.validPriceExact.map { s.qty * $0 }
    }

    /// Veräusserung ohne Tausch (Verkauf, Zahlung, Gebühr im dritten Coin) — wie Android `sale`. Altbestand (§ 31 EStG a. F.)
    /// mit Gebühren: Erlös nach Gebühren, die Gebühr im Coin geht mit ihrem Einstand ab. Neubestand ohne Gebühren (§ 20 Abs. 2
    /// EStG, BMF): Erlös vor Gebühren; die Gebühr im Coin (anteilig nach Menge) ist keine Veräusserung — Menge sinkt, der
    /// Einstand bleibt im Rest wie bei der Netzwerkgebühr einer Umbuchung.
    private static func atSale(_ coin: String, _ c: inout AtCoin, _ s: TaxStep,
                               _ lost: inout [PortfolioLostFeeCost]) -> [PortfolioDisposal] {
        let oldPieces = takeOld(&c, s.qty)
        let oldQty: Decimal = oldPieces.reduce(Decimal(0)) { $0 + $1.amount }
        let newQty = s.qty - oldQty
        // Gebühr im dritten Coin: der ganze Schritt ist die Gebühr, darin steckt keine Gebühr im Coin
        let fee: Decimal = s.feeLeg ? 0 : s.trade.coinFeeExact
        let newFee: Decimal = fee == 0 || newQty <= 0 ? 0 : PortfolioDecimal.divide(newQty * fee, s.qty)
        let taken = c.neu.take(newQty - newFee)
        note(&lost, coin, s, c.neu.dropQty(newFee))
        func oldShare(_ v: Decimal) -> Decimal { oldQty == s.qty ? v : PortfolioDecimal.divide(v * oldQty, s.qty) }
        var newPieces = piecesOf(taken, oldHolding: false)
        let rest = newQty - newFee - taken.removed
        if rest > 0 { newPieces.append(Piece(amount: rest, cost: nil, uncovered: true)) }
        var rows = emit(coin, s, oldPieces, proceeds: s.proceeds.map(oldShare))
            + emit(coin, s, newPieces, proceeds: s.gross.map { $0 - oldShare($0) })
        for i in rows.indices { rows[i].wallet = c.wallet }
        return rows
    }

    /// Netzwerkgebühr: zuerst Altbestand (Einstand auf den nächsten Altbestand-Posten mit Kaufpreis), dann Neubestand.
    private static func atNetworkFee(_ coin: String, _ c: inout AtCoin, _ s: TaxStep, _ lost: inout [PortfolioLostFeeCost]) {
        let fromOld = min(s.qty, c.oldHeld)
        if fromOld > 0 {
            let pieces = takeBoth(&c, fromOld).pieces
            let carried: Decimal = pieces.reduce(Decimal(0)) { $0 + ($1.cost ?? 0) }
            if let i = c.old.firstIndex(where: { $0.cost != nil }), let tc = c.old[i].cost {
                c.old[i].cost = tc + carried
            } else {
                note(&lost, coin, s, carried)
            }
        }
        note(&lost, coin, s, c.neu.dropQty(s.qty - fromOld))
    }

    static func atSplit(_ real: [PortfolioTx], _ zone: TimeZone,
                                _ lost: inout [PortfolioLostFeeCost]) -> [PortfolioDisposal] {
        let newFrom = startMillis(TaxTemplate.atNewFrom, zone)
        let neutralFrom = startMillis(TaxTemplate.atSwapNeutralFrom, zone)
        // Topf je (Coin, Wallet-Schlüssel wie FIFO je Wallet); Anzeigename = erste Schreibweise
        var coins: [AtKey: AtCoin] = [:]
        var names: [String: String] = [:]
        var carries: [Int64: AtCarry] = [:]
        var out: [PortfolioDisposal] = []
        func key(_ coin: String, _ raw: String?) -> AtKey {
            let k = PortfolioWallets.key(raw)
            if names[k] == nil { names[k] = raw?.trimmingCharacters(in: .whitespacesAndNewlines) ?? "" }
            return AtKey(coin: coin, wallet: k)
        }
        func pot(_ k: AtKey) -> AtCoin { coins[k] ?? AtCoin(wallet: names[k.wallet] ?? "", zone: zone) }
        for entry in mergedEvents(real) {
            let coin = entry.coin
            let t = entry.event.trade
            // Umbuchung: Gebühr in der Quelle, dann wandern Altbestand-Posten (FIFO) und Neubestand-Anteil zum Ziel
            if !entry.event.feeLeg && !entry.event.swapIn && t.type == .TRANSFER {
                let srcKey = key(coin, t.transferFrom)
                let dstKey = key(coin, t.transferTo)
                var src = pot(srcKey)
                for s in steps([entry.event]) {
                    switch s.kind {
                    case .networkFee:
                        atNetworkFee(coin, &src, s, &lost)
                    case .addFee, .acquire, .dispose, .remove:
                        // Gebühren einer Umbuchung erhöhen den Einstand nicht
                        break
                    }
                }
                if srcKey != dstKey && t.amountExact > 0 {
                    let both = takeBoth(&src, t.amountExact)
                    var dst = pot(dstKey)
                    for p in both.pieces {
                        if let txId = p.lotTxId, let acquired = p.acquiredTime, let kind = p.lotKind {
                            dst.insertOld(AtLot(txId: txId, acquired: acquired, kind: kind, qty: p.amount, cost: p.cost))
                        }
                    }
                    dst.neu.addCarried(both.taken.removedPriced, from: both.taken.parts)
                    dst.neu.add(both.taken.removedUnpriced, cost: nil, time: t.time)
                    coins[dstKey] = dst
                }
                coins[srcKey] = src
                continue
            }
            let k = key(coin, PortfolioWallets.of(entry.event))
            var c = pot(k)
            for s in steps([entry.event]) {
                switch s.kind {
                case .acquire:
                    let carry: AtCarry? = s.swap ? carries.removeValue(forKey: s.id) : nil
                    if let carry, carry.realized {
                        // Tausch vor dem 1.3.2022 = Anschaffung zum Wert des Tauschs: vor dem 1.3.2021 Altbestand (Frist 1 Jahr
                        // ab dem Tausch), danach Neubestand
                        if s.time < newFrom {
                            c.insertOld(AtLot(txId: s.id, acquired: s.time, kind: s.trade.type, qty: s.qty, cost: carry.value))
                        } else {
                            c.neu.add(s.qty, cost: carry.value, time: s.time)
                        }
                    } else if let carry {
                        if carry.total > 0 {
                            // Erhaltene Menge anteilig: aus Altbestand (Einstand = Wert), aus Neubestand fortgeführt, Rest ohne Kaufpreis
                            func part(_ q: Decimal) -> Decimal { q == carry.total ? s.qty : PortfolioDecimal.divide(s.qty * q, carry.total) }
                            let fromOld = part(carry.oldQty)
                            if fromOld > 0 {
                                let v: Decimal? = carry.value.map {
                                    carry.oldQty == carry.total ? $0 : PortfolioDecimal.divide($0 * carry.oldQty, carry.total)
                                }
                                c.neu.add(fromOld, cost: v, time: s.time)
                            }
                            let fromPriced = part(carry.taken.removedPriced)
                            if fromPriced > 0 { c.neu.addCarried(fromPriced, from: carry.taken.parts) }
                            let restQty = s.qty - fromOld - fromPriced
                            if restQty > 0 { c.neu.add(restQty, cost: nil, time: s.time) }
                        } else {
                            c.neu.add(s.qty, cost: nil, time: s.time)
                        }
                    } else if s.acquired < newFrom {
                        c.old.append(AtLot(txId: s.id, acquired: s.acquired, kind: s.trade.type, qty: s.qty, cost: s.cost))
                    } else {
                        c.neu.add(s.qty, cost: atNewCost(s), time: s.acquired)
                    }
                case .dispose:
                    guard s.swap else {
                        out += atSale(coin, &c, s, &lost)
                        break
                    }
                    let both = takeBoth(&c, s.qty)
                    let oldQty: Decimal = both.pieces.reduce(Decimal(0)) { $0 + $1.amount }
                    var rows: [PortfolioDisposal] = []
                    if s.time < neutralFrom {
                        // Tausch vor dem 1.3.2022: alte Rechtslage (§ 31 EStG a. F.) — Tausch = Veräusserung des ganzen
                        // abgegebenen Coins (Alt- und Neubestand, EStR Rz 6103k), der erhaltene Coin ist neu angeschafft
                        var list = both.pieces
                        list += piecesOf(both.taken, oldHolding: false)
                        let rest = s.qty - oldQty - both.taken.removed
                        if rest > 0 { list.append(Piece(amount: rest, cost: nil, uncovered: true)) }
                        rows = emit(coin, s, list, proceeds: s.proceeds)
                        carries[s.id] = AtCarry(total: s.qty, oldQty: oldQty, taken: both.taken, value: s.gross, realized: true)
                    } else {
                        // Tausch ab 1.3.2022: nur der Altbestand-Teil ist eine Veräusserung (§ 31 EStG a. F., Rz 6103k);
                        // Neubestand geht steuerneutral über (§ 27b Abs. 3 Z 2 EStG) — auch in einen Stablecoin (USDT, USDC …):
                        // der Einstand geht mit, steuerbar erst der Verkauf gegen Euro
                        let proceedsOld: Decimal? = s.proceeds.map {
                            oldQty == s.qty ? $0 : PortfolioDecimal.divide($0 * oldQty, s.qty)
                        }
                        if oldQty > 0 { rows = emit(coin, s, both.pieces, proceeds: proceedsOld) }
                        carries[s.id] = AtCarry(total: s.qty, oldQty: oldQty, taken: both.taken, value: s.gross, realized: false)
                    }
                    for i in rows.indices { rows[i].wallet = c.wallet }
                    out += rows
                case .remove:
                    _ = takeBoth(&c, s.qty)
                case .networkFee:
                    atNetworkFee(coin, &c, s, &lost)
                case .addFee:
                    break
                }
            }
            coins[k] = c
        }
        return out
    }
}
