import Foundation

// Rechnung der Steuer-Vorlagen — wie `TaxEngines.kt` (Android), dort beschrieben: UK Section-104-Pool mit Same-Day- und
// 30-Tage-Regel (Netzwerkgebühr im Coin = Veräusserung, HMRC CRYPTO22280), CA ACB mit Superficial Loss, FR prix total
// d'acquisition (Auszahlung kürzt die PTA, BOFiP § 100; Gebühr in Krypto = Abtretung, § 50 bzw. 30-10 § 70 Rem. 1),
// JP 総平均法, AT Alt-/Neubestand je Coin und Wallet (Belohnung im Neubestand mit Anschaffungskosten 0, Zins zum Wert beim
// Zufluss; Gebühren im Neubestand nicht abzugsfähig — § 27a Abs. 4 Z 2, § 20 Abs. 2 EStG, BMF-Info Kryptowährungen).
// Netzwerkgebühr ohne Rest mit Kaufpreis (AT; CA/JP nur ohne Vorlage-Regeln): `PortfolioLostFeeCost`; Anteil einer
// Umbuchungsgebühr auf Bestand ohne Kaufpreis im Pool (UK/CA ohne Vorlage-Regeln, `TaxPool.addFee`):
// `PortfolioUnassignedFee`. Gebühr-Zeile (CSV «Fee»)
// zum Tageskurs `priceAt` (alle Vorlagen, `PortfolioLedger.valueAtDayClose`; ebenso Verschenkt/bezahlt ohne Marktwert und
// Tausch ohne Wert, Build 47). Damit bezahlt (`.PAYMENT`) = Veräusserung zum Marktwert wie ein Verkauf (alle Vorlagen);
// Schenkung und Netzwerkgebühr nach den Regeln der Vorlage (`TaxEventRules`, `applyRules`, Build 56): Verschenkt nur für
// UK/CA/AU eine Veräusserung, an Ehe-/Lebenspartner in UK/CA ohne Gewinn/Verlust (TCGA 1992 s. 58, ITA 73(1));
// Netzwerkgebühr in Krypto einer Umbuchung/Auszahlung bei UK/US/CA/AU/JP eine eigene Veräusserung zum Marktwert.
// FR: Gesamtwert samt beobachteter Adressen. UK: Einzahlung mit früherem Kaufdatum erst ab Buchungstag
// im Pool (nicht «gleicher Tag»/«30 Tage»). CA: ein Ersatz-Zugang deckt nur einen nicht abziehbaren Verlust (verbraucht).
// FR: Verkauf über den Bestand — nur der gedeckte Anteil hat Einstand, der Rest eine eigene Zeile ohne Einstand.

/// FR: Abgang ohne Verkauf (Auszahlung) — wie `TaxOutflow` (Android): Menge ohne Gebühr (die ist eine Abtretung), Wert beim Abgang,
/// Kürzung der Erwerbspreis-Summe (USDT); nil = Kurs fehlt.
struct TaxOutflow: Sendable, Equatable {
    let coin: String
    let txId: Int64
    let time: Int64
    let amount: Decimal
    let valueUsdt: Decimal?
    let ptaReductionUsdt: Decimal?
}

/// Art eines Schritts der Steuer-Rechnung — wie `TaxStepKind` (Android).
enum TaxStepKind: Sendable {
    /// Zugang (Kauf, Belohnung, Einzahlung, Zugang aus Tausch).
    case acquire
    /// Veräusserung (Verkauf, Tausch, Gebühr im dritten Coin).
    case dispose
    /// Auszahlung: Menge und Einstand verlassen den Topf, keine Veräusserung.
    case remove
    /// Netzwerkgebühr im Coin (Umbuchung): Menge sinkt, Einstand bleibt.
    case networkFee
    /// Gebühr USDT + Wert der Gebühr im dritten Coin einer Umbuchung: erhöht den Einstand.
    case addFee
}

/// Ein Schritt eines Coins — wie `TaxStep` (Android).
struct TaxStep: Sendable {
    let kind: TaxStepKind
    let trade: PortfolioTx
    let qty: Decimal
    /// Zugang: Einstand (nil = ohne Kaufpreis); `.addFee`: Betrag.
    var cost: Decimal? = nil
    /// Zugang: Erwerb (`acquisitionTime`); sonst Zeit der Transaktion.
    var acquired: Int64
    /// Abgang: Erlös netto; nil = unbekannt.
    var proceeds: Decimal? = nil
    /// Abgang: Erlös vor Gebühren.
    var gross: Decimal? = nil
    var feeLeg = false
    var swap = false
    var feeMissing = false
    /// Schenkung als Veräusserung (`PortfolioTx.fromGift`) bzw. an Ehe-/Lebenspartner (`spouseGift`).
    var gift = false
    /// UK/CA: Schenkung an Ehe-/Lebenspartner — Erlös je Teil = sein Einstand, kein Gewinn/Verlust (`emit`).
    var spouseGift = false
    /// Zahlung mit Krypto (`.PAYMENT`).
    var payment = false
    /// Erlös aus geschätztem Wert (`PortfolioTx.valueEstimated`).
    var estimated = false

    init(_ kind: TaxStepKind, _ trade: PortfolioTx, qty: Decimal, cost: Decimal? = nil, acquired: Int64? = nil,
         proceeds: Decimal? = nil, gross: Decimal? = nil, feeLeg: Bool = false, swap: Bool = false, feeMissing: Bool = false,
         gift: Bool = false, spouseGift: Bool = false, payment: Bool = false, estimated: Bool = false) {
        self.kind = kind
        self.trade = trade
        self.qty = qty
        self.cost = cost
        self.acquired = acquired ?? trade.time
        self.proceeds = proceeds
        self.gross = gross
        self.feeLeg = feeLeg
        self.swap = swap
        self.feeMissing = feeMissing
        self.gift = gift
        self.spouseGift = spouseGift
        self.payment = payment
        self.estimated = estimated
    }

    var time: Int64 { trade.time }
    var id: Int64 { trade.id }
}

/// Ergebnis von `TaxPool.take` — wie `TaxTaken` (Android).
struct TaxTaken: Sendable {
    let removed: Decimal
    let removedPriced: Decimal
    let cost: Decimal
    let parts: [PortfolioCostPart]

    var removedUnpriced: Decimal { removed - removedPriced }
}

/// Durchschnitts-Topf (Menge mit/ohne Kaufpreis, Einstand mit Herkunft) — wie `TaxPool` (Android). Mit `zone`: Herkunftsteile
/// desselben Kalendertags zusammengefasst (gleicher Devisenkurs; kein O(Zugänge × Abgänge)).
struct TaxPool: Sendable {
    private(set) var priced: Decimal = 0
    private(set) var unpriced: Decimal = 0
    private(set) var cost: Decimal = 0
    private(set) var parts: [PortfolioCostPart] = []
    private let zone: TimeZone?
    /// Mit `zone`: Tag → Index in `parts` (erster Zeitpunkt des Tags bleibt).
    private var dayIndex: [LocalDay: Int] = [:]

    init(zone: TimeZone? = nil) {
        self.zone = zone
    }

    var held: Decimal { priced + unpriced }

    /// Zugang `qty` mit Einstand `cost` (nil = ohne Kaufpreis), Herkunft `time`.
    mutating func add(_ qty: Decimal, cost c: Decimal?, time: Int64) {
        guard qty > 0 else { return }
        guard let c else {
            unpriced += qty
            return
        }
        priced += qty
        addCost(time, c)
    }

    /// Zugang mit fortgeführtem Einstand (Herkunftsteile `from`).
    mutating func addCarried(_ qty: Decimal, from: [PortfolioCostPart]) {
        guard qty > 0 else { return }
        priced += qty
        for p in from { addCost(p.time, p.usdt) }
    }

    /// Einstand direkt zum Teil mit Kaufpreis.
    mutating func addCost(_ time: Int64, _ usdt: Decimal) {
        cost += usdt
        guard usdt != 0 else { return }
        guard let zone else {
            parts.append(PortfolioCostPart(time: time, usdt: usdt))
            return
        }
        let day = LocalDay(date: Date(timeIntervalSince1970: Double(time) / 1000), timeZone: zone)
        if let i = dayIndex[day] {
            parts[i] = PortfolioCostPart(time: parts[i].time, usdt: parts[i].usdt + usdt)
        } else {
            dayIndex[day] = parts.count
            parts.append(PortfolioCostPart(time: time, usdt: usdt))
        }
    }

    /// Alle Herkunftsteile entfernen (samt Tag-Index).
    private mutating func clearParts() {
        parts = []
        dayIndex = [:]
    }

    /// Gebühr einer Umbuchung: anteilig (Menge mit Kaufpreis / Bestand) zum Einstand, wie der Ø. Rückgabe = Anteil des
    /// Teils ohne Kaufpreis (ohne Teil mit Kaufpreis: die ganze Gebühr) — der Aufrufer vermerkt ihn als
    /// `PortfolioUnassignedFee`; 0 = alles zugeordnet bzw. kein Bestand. Wie Android.
    mutating func addFee(_ time: Int64, _ extra: Decimal) -> Decimal {
        let h = held
        guard extra > 0, h > 0 else { return 0 }
        guard priced > 0 else { return extra }
        let assigned = unpriced == 0 ? extra : PortfolioDecimal.divide(extra * priced, h)
        addCost(time, assigned)
        return extra - assigned
    }

    /// Übernimmt den ganzen Inhalt von `other` (danach leer).
    mutating func absorb(_ other: inout TaxPool) {
        priced += other.priced
        unpriced += other.unpriced
        // Einstand unverändert, Herkunft je Teil (mit `zone` nach Tag zusammengefasst)
        let c = cost
        for p in other.parts { addCost(p.time, p.usdt) }
        cost = c + other.cost
        other = TaxPool(zone: other.zone)
    }

    /// Gibt `requested` ab (gekappt auf den Bestand), beide Teile anteilig samt Einstand.
    mutating func take(_ requested: Decimal) -> TaxTaken {
        let h = held
        let removed = max(0, min(requested, h))
        guard removed > 0 else { return TaxTaken(removed: 0, removedPriced: 0, cost: 0, parts: []) }
        let allPriced = unpriced == 0
        let removedPriced: Decimal = allPriced ? removed
            : min(PortfolioDecimal.amount(PortfolioDecimal.divide(removed * priced, h)), priced)
        let costRemoved: Decimal
        if priced == 0 {
            costRemoved = 0
        } else if removedPriced >= priced {
            costRemoved = cost
        } else {
            costRemoved = PortfolioDecimal.divide(cost * removedPriced, priced)
        }
        let partsRemoved = takeParts(costRemoved)
        let rest = h - removed
        priced -= removedPriced
        cost -= costRemoved
        if PortfolioDecimal.isDust(priced) || priced < 0 { priced = 0; cost = 0; clearParts() }
        unpriced = max(0, rest - priced)
        if PortfolioDecimal.isDust(unpriced) { unpriced = 0 }
        return TaxTaken(removed: removed, removedPriced: removedPriced, cost: costRemoved, parts: partsRemoved)
    }

    /// Netzwerkgebühr: Menge sinkt anteilig, der Einstand bleibt im Rest. Bleibt kein Teil mit Kaufpreis, geht der
    /// Einstand nicht still verloren: Rückgabe = dieser Einstand (Aufrufer vermerkt `PortfolioLostFeeCost`), sonst 0.
    @discardableResult
    mutating func dropQty(_ qty: Decimal) -> Decimal {
        let h = held
        guard qty > 0, h > 0 else { return 0 }
        let removed = min(qty, h)
        let removedPriced: Decimal = unpriced == 0 ? removed
            : min(PortfolioDecimal.amount(PortfolioDecimal.divide(removed * priced, h)), priced)
        priced -= removedPriced
        var lost: Decimal = 0
        if PortfolioDecimal.isDust(priced) || priced < 0 {
            lost = cost
            priced = 0; cost = 0; clearParts()
        }
        unpriced = max(0, h - removed - priced)
        if PortfolioDecimal.isDust(unpriced) { unpriced = 0 }
        return lost
    }

    /// Nimmt `removed` Einstand anteilig aus allen Herkunftsteilen; Summe exakt `removed`.
    private mutating func takeParts(_ removed: Decimal) -> [PortfolioCostPart] {
        guard removed != 0, !parts.isEmpty else { return [] }
        if removed >= cost {
            let all = parts
            clearParts()
            return all
        }
        var out: [PortfolioCostPart] = []
        var left = removed
        for i in parts.indices {
            let p = parts[i]
            let r: Decimal = i == parts.count - 1 ? left : PortfolioDecimal.divide(p.usdt * removed, cost)
            left -= r
            out.append(PortfolioCostPart(time: p.time, usdt: r))
            parts[i] = PortfolioCostPart(time: p.time, usdt: p.usdt - r)
        }
        return out
    }
}

enum TaxEngines {

    /// Veräusserungen nach `method` — wie `TaxEngines.disposals` (Android); `timeZone` = Kalendertage (UK, CA, JP, AT),
    /// `priceAt` = USDT-Kurs eines Coins zu einem Zeitpunkt (`.FR_PTA`: Gesamtwert und Gebühren in Krypto; mit
    /// `TaxEventRules.networkFeeIsDisposal`: Netzwerkgebühren; alle: Gebühr-Zeilen zum Tageskurs); `fx` = USDT →
    /// Steuerwährung zu einem Zeitpunkt (`.CA_ACB`: Superficial Loss in CAD entschieden; nil bzw. ein Kurs fehlt = in USDT).
    /// `rules`: Schenkung und Netzwerkgebühr nach der Vorlage (`TaxTemplate.eventRules`, `applyRules`); nil = nach der
    /// Rechnung (`TaxEventRules.of`).
    static func disposals(_ trades: [PortfolioTx], method: TaxMethod, timeZone: TimeZone,
                          priceAt: ((String, Int64) -> Decimal?)? = nil,
                          fx: ((Int64) -> Decimal?)? = nil,
                          rules: TaxEventRules? = nil,
                          walletFrom: LocalDay? = nil) -> [PortfolioDisposal] {
        let applied = rules ?? .of(method)
        let input = applyRules(trades, rules: applied, priceAt: priceAt)
        let rows: [PortfolioDisposal]
        if let cm = method.costMethod {
            let valued = PortfolioLedger.valueAtDayClose(input, price: priceAt)
            // US-Übergang (`TaxTemplate.walletFrom`): universell bis vor diesen Tag
            if let walletFrom, cm == .FIFO_WALLET {
                rows = usTransition(valued, walletFrom: walletFrom, timeZone: timeZone)
            } else {
                rows = PortfolioCalculator.disposals(valued, method: cm)
            }
        } else {
            rows = run(input, method: method, timeZone: timeZone, priceAt: priceAt, fx: fx).disposals
        }
        guard applied.quoteFeeIsInfo else { return rows }
        // Gebühr USDT einer Umbuchung: nicht im Einstand (`applyRules`), aber als Zeile zur Information
        let fees = transferFeeRows(trades)
        if fees.isEmpty { return rows }
        // Stabil sortiert wie Android (`sortedWith`)
        return (rows + fees).enumerated().sorted { a, b in
            let x = a.element, y = b.element
            if x.sellTime != y.sellTime { return x.sellTime < y.sellTime }
            if x.sellTxId != y.sellTxId { return x.sellTxId < y.sellTxId }
            if x.coin != y.coin { return x.coin < y.coin }
            return a.offset < b.offset
        }.map(\.element)
    }

    /// `quoteFeeIsInfo`: je Umbuchung mit Gebühr USDT eine Zeile `.TRANSFER` (Menge 0, «Einstand» = Gebühr, Erlös 0) — der
    /// Export führt sie nur zur Information, nicht in den Summen. Wie Android.
    private static func transferFeeRows(_ trades: [PortfolioTx]) -> [PortfolioDisposal] {
        AddressBalance.split(trades).real
            .filter { $0.type == .TRANSFER && $0.amountExact > 0 && $0.quoteFeeExact > 0 }
            .map { t in
                PortfolioFeeExpenses.disposal(PortfolioCalculator.normalizeCoin(t.coin),
                                              PortfolioLedgerEvent(trade: t, feeLeg: false), .TRANSFER,
                                              PortfolioFeeExpenses.Expense(usdt: t.quoteFeeExact, feeValueMissing: false),
                                              method: .FIFO)
            }
    }

    /// US-Übergang 1.1.2025 (Rev. Proc. 2024-28, Safe Harbor) — wie Android `usTransition`: vor `walletFrom` (Ortszeit)
    /// FIFO über alle Wallets, dessen Restposten den Wallets zugeordnet (`allocate`), ab `walletFrom` FIFO je Wallet.
    static func usTransition(_ trades: [PortfolioTx], walletFrom: LocalDay, timeZone: TimeZone) -> [PortfolioDisposal] {
        let real = AddressBalance.split(trades).real
        let all = PortfolioLedger.eventsByCoin(real).flatMap { entry -> [PortfolioDisposal] in
            let before = entry.events.filter { day($0.trade.time, timeZone) < walletFrom }
            let after = entry.events.filter { !(day($0.trade.time, timeZone) < walletFrom) }
            let universal = PortfolioFifo.run(entry.coin, events: before)
            let held = PortfolioFifo.run(entry.coin, events: before, perWallet: true).lots
            let seed = allocate(entry.coin, universal: universal.lots, held: held)
            return universal.disposals + PortfolioFifo.run(entry.coin, events: after, perWallet: true, seed: seed).disposals
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

    /// Zuordnung der universellen Restposten zu den Wallets — Regel wie Android `allocate`: gehaltene Einheiten je Wallet
    /// (`held`) nach Erwerb, Id, Wallet sortiert; Restposten, älteste zuerst, füllen sie der Reihe nach (geteilt, Einstand
    /// anteilig); Überschuss in die Wallet der letzten Einheit (ohne: «ohne Angabe»).
    static func allocate(_ coin: String, universal: [PortfolioLot], held: [PortfolioLot]) -> [PortfolioLot] {
        let slots = held.filter { $0.amount > 0 }.enumerated().sorted { a, b in
            let x = a.element, y = b.element
            if x.acquiredTime != y.acquiredTime { return x.acquiredTime < y.acquiredTime }
            if x.txId != y.txId { return x.txId < y.txId }
            let kx = PortfolioWallets.key(x.wallet), ky = PortfolioWallets.key(y.wallet)
            if kx != ky { return kx < ky }
            return a.offset < b.offset
        }.map(\.element)
        let fallback = slots.last?.wallet ?? PortfolioWallets.unspecified
        let lots = universal.enumerated().sorted { a, b in
            let x = a.element, y = b.element
            if x.acquiredTime != y.acquiredTime { return x.acquiredTime < y.acquiredTime }
            if x.txId != y.txId { return x.txId < y.txId }
            return a.offset < b.offset
        }.map(\.element)
        var out: [PortfolioLot] = []
        var si = 0
        var slotLeft: Decimal = slots.first?.amount ?? 0
        for lot in lots {
            var left = lot.amount
            var costLeft = lot.costUsdt
            while left > 0 {
                let slot: PortfolioLot? = si < slots.count ? slots[si] : nil
                let qty = slot == nil ? left : min(left, slotLeft)
                let c: Decimal? = costLeft.map { qty == left ? $0 : PortfolioDecimal.divide($0 * qty, left) }
                out.append(PortfolioLot(coin: coin, txId: lot.txId, acquiredTime: lot.acquiredTime, kind: lot.kind,
                                        amount: qty, costUsdt: c, wallet: slot?.wallet ?? fallback))
                left -= qty
                if let c, let cl = costLeft { costLeft = cl - c }
                if slot != nil {
                    slotLeft -= qty
                    if slotLeft <= 0 {
                        si += 1
                        slotLeft = si < slots.count ? slots[si].amount : 0
                    }
                }
            }
        }
        return out
    }

    /// Art einer Belohnung ist ein Zins (Lending, Zinsen) — aus der Notiz bzw. der Art im CSV; AT rechnet ihn mit
    /// Anschaffungskosten = Wert beim Zufluss statt 0. Wie Android `interestReward` (von Hand: keine Unterart → 0).
    static func interestReward(_ text: String?) -> Bool {
        guard let t = text?.lowercased() else { return false }
        return ["interest", "lending", "zins", "interet", "intérêt"].contains { t.contains($0) }
    }

    /// Vorgänge ohne Verkauf nach den Regeln der Vorlage umschreiben (vor jeder Rechnung) — wie Android `applyRules`:
    ///  - Verschenkt an Ehe-/Lebenspartner mit `spouseGiftNeutral` (UK, CA): bleibt eine Schenkung mit Merker
    ///    (`PortfolioTx.isGiftToSpouse`) — die eigenen Rechnungen machen daraus eine Veräusserung ohne Gewinn/Verlust;
    ///  - sonst Verschenkt mit `giftIsDisposal` (UK, CA, AU): wie `.PAYMENT` mit `fromGift` (Veräusserung zum Marktwert);
    ///    ohne: wie eine Auszahlung. Der Partner-Merker fällt dabei weg — Vorlagen ohne die Regel ignorieren ihn;
    ///  - mit `networkFeeIsDisposal`: Netzwerkgebühr in Krypto einer Umbuchung, Auszahlung oder Schenkung (Coin und dritter
    ///    Coin) wird eine eigene Veräusserung der Gebühr-Coins zum Marktwert (`networkFeeSales`);
    ///  - mit `quoteFeeIsInfo` (Build 57): die Gebühr USDT einer Umbuchung erhöht den Einstand nicht (sie fällt weg;
    ///    `disposals` führt sie als Zeile zur Information).
    static func applyRules(_ trades: [PortfolioTx], rules: TaxEventRules,
                           priceAt: ((String, Int64) -> Decimal?)?) -> [PortfolioTx] {
        guard trades.contains(where: { $0.type == .GIFT }) || rules.networkFeeIsDisposal || rules.quoteFeeIsInfo else {
            return trades
        }
        var out: [PortfolioTx] = []
        out.reserveCapacity(trades.count)
        for t in trades {
            var g = t
            if rules.quoteFeeIsInfo && t.type == .TRANSFER && t.quoteFeeExact > 0 {
                g.feeExact = nil
                g.feeUnit = nil
            }
            if t.type == .GIFT && !(rules.spouseGiftNeutral && t.isGiftToSpouse) {
                if rules.giftIsDisposal {
                    g.type = .PAYMENT
                    g.fromGift = true
                }
                g.giftToSpouse = false
            }
            if rules.networkFeeIsDisposal { networkFeeSales(g, priceAt, &out) } else { out.append(g) }
        }
        return out
    }

    /// Netzwerkgebühr in Krypto einer Umbuchung (eigene Wallets), Auszahlung oder Schenkung als eigene Veräusserung — wie
    /// Android `networkFeeSales`: je Gebühr-Coin ein Verkauf der Gebühr-Menge zum Marktwert (erfasster Preis bzw.
    /// Gebühr-Preis, Stablecoin 1, sonst `priceAt` = Tagesschluss des Vortags; ohne Kurs Erlös unbekannt) mit Id und Zeit
    /// der Transaktion und ihrer Quell-Wallet (Umbuchung: «von», sonst die Börse) — VOR ihr in die Liste, damit die
    /// Gebühr-Coins (stabil sortiert) die Quelle zuerst verlassen. Die Transaktion verliert diese Gebühr: keine Mengen-
    /// oder Einstandsänderung der Umbuchung, kein Einstand-Zuschlag und kein Aufwand der Gebühr im dritten Coin (sonst
    /// zählte ihr Wert doppelt). Die Gebühr USDT bleibt.
    private static func networkFeeSales(_ t: PortfolioTx, _ priceAt: ((String, Int64) -> Decimal?)?,
                                        _ out: inout [PortfolioTx]) {
        guard t.type == .TRANSFER || t.type.isWithdrawalLike, t.amountExact > 0 else {
            out.append(t)
            return
        }
        let wallet = t.type == .TRANSFER ? t.transferFrom : t.exchange
        func sale(_ coin: String, _ qty: Decimal, _ recorded: Decimal?) -> PortfolioTx {
            PortfolioTx(id: t.id, coin: coin, type: .SELL, amountExact: qty,
                        priceExact: feePrice(coin, recorded, t.time, priceAt), time: t.time, exchange: wallet)
        }
        var rest = t
        if t.coinFeeExact > 0 {
            out.append(sale(PortfolioCalculator.normalizeCoin(t.coin), t.coinFeeExact, t.validPriceExact))
            rest.feeExact = nil
            rest.feeUnit = nil
        }
        if let c = t.thirdFeeCoin {
            out.append(sale(c, t.thirdFeeAmount, t.thirdFeePrice))
            rest.feeCoin = nil
            rest.feeCoinAmountExact = nil
            rest.feeCoinPriceExact = nil
        }
        out.append(rest)
    }

    /// Einstand von Netzwerkgebühren ohne Rest mit Kaufpreis der eigenen Rechnungen, zeitlich — wie Android;
    /// Einstandsmethoden des Portfolios: leer (dort `PortfolioCalculator.ledger`). Mit `networkFeeIsDisposal` ist die
    /// Gebühr eine Veräusserung — dann bleibt kein Einstand liegen.
    static func lostFeeCosts(_ trades: [PortfolioTx], method: TaxMethod, timeZone: TimeZone,
                             rules: TaxEventRules? = nil) -> [PortfolioLostFeeCost] {
        if method.costMethod != nil { return [] }
        let input = applyRules(trades, rules: rules ?? .of(method), priceAt: nil)
        return run(input, method: method, timeZone: timeZone, priceAt: nil).lostFeeCosts
    }

    /// Umbuchungsgebühren auf Bestand ohne Kaufpreis (`PortfolioUnassignedFee`) der eigenen Rechnungen, zeitlich — wie
    /// Android; Einstandsmethoden des Portfolios: leer (dort `PortfolioCalculator.ledger`). Nur Pool-Rechnungen mit Gebühr
    /// im Einstand (UK/CA ohne `quoteFeeIsInfo`); JP, AT und FR rechnen Umbuchungsgebühren nicht zum Einstand.
    static func unassignedFees(_ trades: [PortfolioTx], method: TaxMethod, timeZone: TimeZone,
                               rules: TaxEventRules? = nil) -> [PortfolioUnassignedFee] {
        if method.costMethod != nil { return [] }
        let input = applyRules(trades, rules: rules ?? .of(method), priceAt: nil)
        return run(input, method: method, timeZone: timeZone, priceAt: nil).unassignedFees
    }

    /// `.FR_PTA`: Abgänge ohne Verkauf mit Kürzung der Erwerbspreis-Summe, zeitlich; sonst leer.
    static func outflows(_ trades: [PortfolioTx], method: TaxMethod,
                         priceAt: ((String, Int64) -> Decimal?)? = nil) -> [TaxOutflow] {
        guard method == .FR_PTA else { return [] }
        let input = applyRules(trades, rules: .of(method), priceAt: priceAt)
        return run(input, method: method, timeZone: TimeZone(identifier: "UTC") ?? .current, priceAt: priceAt).outflows
    }

    /// Ergebnis einer Rechnung: Veräusserungen, vermerkter Gebühr-Einstand, nicht zugeordnete Gebühren, FR-Abgänge ohne Verkauf.
    private struct TaxRun {
        let disposals: [PortfolioDisposal]
        let lostFeeCosts: [PortfolioLostFeeCost]
        let unassignedFees: [PortfolioUnassignedFee]
        let outflows: [TaxOutflow]
    }

    private static func run(_ trades: [PortfolioTx], method: TaxMethod, timeZone: TimeZone,
                            priceAt: ((String, Int64) -> Decimal?)?, fx: ((Int64) -> Decimal?)? = nil) -> TaxRun {
        // Gebühr-Zeilen, Verschenkt/bezahlt ohne Marktwert und Tausch ohne Wert zum Tageskurs (ohne Kurs: unbekannt)
        let real = PortfolioLedger.valueAtDayClose(AddressBalance.split(trades).real, price: priceAt)
        var out: [PortfolioDisposal] = []
        var lost: [PortfolioLostFeeCost] = []
        var unassigned: [PortfolioUnassignedFee] = []
        var outflows: [TaxOutflow] = []
        switch method {
        case .UK_POOL:
            for entry in PortfolioLedger.eventsByCoin(real) {
                out += ukPool(entry.coin, steps(entry.events), timeZone, &lost, &unassigned)
            }
        case .CA_ACB:
            for entry in PortfolioLedger.eventsByCoin(real) {
                out += caAcb(entry.coin, steps(entry.events), timeZone, &lost, &unassigned, fx)
            }
        case .JP_TOTAL_AVERAGE:
            for entry in PortfolioLedger.eventsByCoin(real) {
                out += jpTotal(entry.coin, steps(entry.events), timeZone, &lost)
            }
        case .FR_PTA:
            // FR: Gesamtwert samt beobachteter Adressen (Adress-Einträge, `AddressBalance.isAddressTrade`)
            out = frPta(real, priceAt, &outflows, addressTrades: trades.filter { AddressBalance.isAddressTrade($0.id) })
        case .AT_MOVING_AVERAGE:
            out = atSplit(real, timeZone, &lost)
        case .FIFO, .FIFO_WALLET, .AVERAGE:
            out = []
        }
        // Gebühr als Aufwand wie bei den Einstandsmethoden (`PortfolioFeeExpenses`); FR: Abtretungspreis 0. FR und AT
        // (Neubestand) behandeln einen Tausch steuerneutral — dort kein Aufwand für den verbrauchten Zugang eines Tauschs
        let swaps = method != .FR_PTA && method != .AT_MOVING_AVERAGE
        var byId: [Int64: PortfolioTx] = [:]
        for t in real { byId[t.id] = t }
        let atNewFrom = startMillis(TaxTemplate.atNewFrom, timeZone)
        for entry in PortfolioLedger.eventsByCoin(real) {
            for var d in PortfolioFeeExpenses.of(entry.coin, entry.events, swaps: swaps) {
                // AT Neubestand: eine Gebühr, die den ganzen Zugang verbraucht, ist nur Anschaffungsnebenkosten — nicht
                // anzusetzen (§ 27a Abs. 4 Z 2 EStG), also kein abziehbarer Verlust
                if method == .AT_MOVING_AVERAGE, d.feeExpense == .CONSUMED,
                   (byId[d.sellTxId]?.acquisitionTime ?? d.sellTime) >= atNewFrom {
                    continue
                }
                if method == .FR_PTA {
                    // FR: Gebühr im dritten Coin einer Auszahlung ist schon eine eigene Abtretung (frPta) — als Aufwand
                    // bleibt nur die Gebühr USDT
                    if d.feeExpense == .WITHDRAWAL, let t = byId[d.sellTxId], t.thirdFeeCoin != nil {
                        let q = t.quoteFeeExact
                        if q <= 0 { continue }
                        var e = PortfolioDisposal(
                            coin: d.coin, sellTxId: d.sellTxId, sellTime: d.sellTime, lotTxId: d.lotTxId,
                            acquiredTime: d.acquiredTime, lotKind: d.lotKind, amount: d.amount, costUsdt: q,
                            proceedsUsdt: d.proceedsUsdt, gainUsdt: -q, holdingDays: d.holdingDays, feeLeg: d.feeLeg,
                            feeValueMissing: false, method: d.method, costParts: d.costParts, uncovered: d.uncovered,
                            wallet: d.wallet, swap: d.swap)
                        e.feeExpense = d.feeExpense
                        d = e
                    }
                    d.grossProceedsUsdt = 0
                }
                out.append(d)
            }
        }
        // Stabil sortiert wie Android (`sortedWith`)
        let sorted: [PortfolioDisposal] = out.enumerated().sorted { a, b in
            let x = a.element, y = b.element
            if x.sellTime != y.sellTime { return x.sellTime < y.sellTime }
            if x.sellTxId != y.sellTxId { return x.sellTxId < y.sellTxId }
            if x.coin != y.coin { return x.coin < y.coin }
            return a.offset < b.offset
        }.map(\.element)
        let lostSorted: [PortfolioLostFeeCost] = lost.enumerated().sorted { a, b in
            let x = a.element, y = b.element
            if x.time != y.time { return x.time < y.time }
            if x.txId != y.txId { return x.txId < y.txId }
            if x.coin != y.coin { return x.coin < y.coin }
            return a.offset < b.offset
        }.map(\.element)
        let unassignedSorted: [PortfolioUnassignedFee] = unassigned.enumerated().sorted { a, b in
            let x = a.element, y = b.element
            if x.time != y.time { return x.time < y.time }
            if x.txId != y.txId { return x.txId < y.txId }
            if x.coin != y.coin { return x.coin < y.coin }
            return a.offset < b.offset
        }.map(\.element)
        return TaxRun(disposals: sorted, lostFeeCosts: lostSorted, unassignedFees: unassignedSorted, outflows: outflows)
    }

    /// Vermerkt Einstand `cost` einer Netzwerkgebühr (`s`) ohne Rest mit Kaufpreis (0 = nichts).
    private static func note(_ lost: inout [PortfolioLostFeeCost], _ coin: String, _ s: TaxStep, _ cost: Decimal) {
        if cost != 0 { lost.append(PortfolioLostFeeCost(coin: coin, txId: s.id, time: s.time, costUsdt: cost)) }
    }

    /// Vermerkt den Anteil `usdt` einer Umbuchungsgebühr (`s`) auf Bestand ohne Kaufpreis (`TaxPool.addFee`; 0 = nichts).
    private static func noteUnassigned(_ unassigned: inout [PortfolioUnassignedFee], _ coin: String, _ s: TaxStep,
                                       _ usdt: Decimal) {
        if usdt > 0 { unassigned.append(PortfolioUnassignedFee(coin: coin, txId: s.id, time: s.time, usdt: usdt)) }
    }

    private static func day(_ millis: Int64, _ zone: TimeZone) -> LocalDay {
        LocalDay(date: Date(timeIntervalSince1970: Double(millis) / 1000), timeZone: zone)
    }

    /// Schritte eines Coins aus seinen Ereignissen — wie `steps` (Android).
    static func steps(_ events: [PortfolioLedgerEvent]) -> [TaxStep] {
        var out: [TaxStep] = []
        for e in events {
            let t = e.trade
            guard t.amountExact > 0 else { continue }
            if e.feeLeg {
                let fee = t.thirdFeeAmount
                let p: Decimal? = t.thirdFeePrice.map { fee * $0 }
                out.append(TaxStep(.dispose, t, qty: fee, proceeds: p, gross: p, feeLeg: true))
                continue
            }
            if e.swapIn {
                let r = t.swapReceived
                if r > 0 { out.append(TaxStep(.acquire, t, qty: r, cost: t.swapValue, swap: true)) }
                continue
            }
            let price = t.validPriceExact
            let feeValue = t.thirdFeeValue
            switch t.type {
            case .BUY, .REWARD, .DEPOSIT:
                let r = t.receivedExact
                guard r > 0 else { continue }
                let cost: Decimal? = price.map { t.amountExact * $0 + t.quoteFeeExact + (feeValue ?? 0) }
                out.append(TaxStep(.acquire, t, qty: r, cost: cost, acquired: t.acquisitionTime))
            case .SELL, .PAYMENT, .SWAP:
                // Damit bezahlt: Veräusserung zum Marktwert wie ein Verkauf (alle Vorlagen; Verschenkt nur nach
                // `applyRules` mit `giftIsDisposal`, sonst wie eine Auszahlung)
                let swap = t.type == .SWAP
                // Tausch ohne gültigen Zugang (erhaltener Coin fehlt): Erlös unbekannt → «realisiert unvollständig»
                let gross: Decimal? = swap ? (t.swapToCoin != nil ? t.swapValue : nil) : price.map { t.amountExact * $0 }
                let proceeds: Decimal? = gross.map { $0 - t.quoteFeeExact - (feeValue ?? 0) }
                out.append(TaxStep(.dispose, t, qty: t.amountExact + t.coinFeeExact, proceeds: proceeds, gross: gross,
                                   swap: swap, feeMissing: gross != nil && feeValue == nil,
                                   gift: t.fromGift, payment: t.type == .PAYMENT && !t.fromGift,
                                   estimated: t.valueEstimated))
            case .WITHDRAWAL, .GIFT:
                // Verschenkt (beim Schenkenden keine Veräusserung): wie eine Auszahlung; an Ehe-/Lebenspartner (Merker nur
                // nach `applyRules` mit UK/CA-Regeln): Veräusserung ohne Gewinn/Verlust zum zugeordneten Einstand
                if t.isGiftToSpouse {
                    out.append(TaxStep(.dispose, t, qty: t.amountExact + t.coinFeeExact, gift: true, spouseGift: true))
                } else {
                    out.append(TaxStep(.remove, t, qty: t.amountExact + t.coinFeeExact))
                }
            case .TRANSFER:
                let extra: Decimal = t.quoteFeeExact + (feeValue ?? 0)
                if extra > 0 { out.append(TaxStep(.addFee, t, qty: 0, cost: extra)) }
                if t.coinFeeExact > 0 { out.append(TaxStep(.networkFee, t, qty: t.coinFeeExact)) }
            }
        }
        return out
    }

    /// Ein Teil einer Veräusserung vor der Erlös-Verteilung.
    private struct Piece {
        let amount: Decimal
        let cost: Decimal?
        var parts: [PortfolioCostPart] = []
        var acquiredTime: Int64? = nil
        var lotTxId: Int64? = nil
        var lotKind: PortfolioTxType? = nil
        var match: TaxMatch? = nil
        var matchedTime: Int64? = nil
        var uncovered = false
        var oldHolding: Bool? = nil
    }

    /// Teile eines Abgangs aus `taken`: Teil mit Kaufpreis (Einstand, Herkunft) und Teil ohne.
    private static func piecesOf(_ taken: TaxTaken, match: TaxMatch? = nil, matchedTime: Int64? = nil,
                                 oldHolding: Bool? = nil) -> [Piece] {
        var out: [Piece] = []
        if taken.removedPriced > 0 {
            out.append(Piece(amount: taken.removedPriced, cost: taken.cost, parts: taken.parts, match: match,
                             matchedTime: matchedTime, oldHolding: oldHolding))
        }
        if taken.removedUnpriced > 0 {
            out.append(Piece(amount: taken.removedUnpriced, cost: nil, match: match, matchedTime: matchedTime,
                             oldHolding: oldHolding))
        }
        return out
    }

    /// Veräusserungen aus `pieces`: Erlös nach Menge verteilt (letzter Teil bekommt den Rest, Summe exakt). Schenkung an
    /// Ehe-/Lebenspartner (`TaxStep.spouseGift`): Erlös je Teil = sein Einstand (ohne Kaufpreis unbekannt).
    private static func emit(_ coin: String, _ s: TaxStep, _ pieces: [Piece], proceeds: Decimal?) -> [PortfolioDisposal] {
        let total = pieces.reduce(Decimal(0)) { $0 + $1.amount }
        guard total > 0 else { return [] }
        var left = proceeds
        var out: [PortfolioDisposal] = []
        for (i, p) in pieces.enumerated() {
            var share: Decimal? = nil
            if s.spouseGift {
                share = p.cost
            } else if let proceeds, let l = left {
                share = i == pieces.count - 1 ? l : PortfolioDecimal.divide(proceeds * p.amount, total)
                left = l - (share ?? 0)
            }
            var gain: Decimal? = nil
            if let share, let c = p.cost { gain = share - c }
            let holding: Int? = p.acquiredTime.map { PortfolioFifo.holdingDays(from: $0, to: s.time) }
            var d = PortfolioDisposal(
                coin: coin, sellTxId: s.id, sellTime: s.time, lotTxId: p.lotTxId, acquiredTime: p.acquiredTime,
                lotKind: p.lotKind, amount: p.amount, costUsdt: p.cost, proceedsUsdt: share, gainUsdt: gain,
                holdingDays: holding, feeLeg: s.feeLeg, feeValueMissing: s.feeMissing,
                method: p.acquiredTime != nil ? .FIFO : .AVERAGE,
                costParts: p.parts, uncovered: p.uncovered, swap: s.swap, gift: s.gift, payment: s.payment, valueEstimated: s.estimated)
            d.match = p.match
            d.matchedTime = p.matchedTime
            d.oldHolding = p.oldHolding
            d.spouseGift = s.spouseGift
            out.append(d)
        }
        return out
    }

    // MARK: - UK

    /// Kurs eines Gebühr-Coins: erfasster Preis `recorded`, Stablecoin 1, sonst `priceAt`; nil = unbekannt. Wie Android.
    private static func feePrice(_ coin: String, _ recorded: Decimal?, _ time: Int64,
                                 _ priceAt: ((String, Int64) -> Decimal?)?) -> Decimal? {
        if let p = recorded, p >= 0 { return p }
        if !PortfolioStables.needsQuote(coin) { return 1 }
        return priceAt?(coin, time).flatMap { $0 >= 0 ? $0 : nil }
    }

    private static func ukPool(_ coin: String, _ steps: [TaxStep], _ zone: TimeZone,
                               _ lost: inout [PortfolioLostFeeCost],
                               _ unassigned: inout [PortfolioUnassignedFee]) -> [PortfolioDisposal] {
        // Netzwerkgebühren sind hier schon eigene Verkäufe (`applyRules`, HMRC CRYPTO22280) — sie zählen so in der
        // Same-Day-Gruppe ihres Tags und in der 30-Tage-Regel wie jeder andere Abgang
        // Einzahlung mit früherem ursprünglichem Kaufdatum: erst ab dem Buchungstag bekannt → direkt in den Pool, nicht
        // «gleicher Tag»/«30 Tage». Einzahlung ohne Kaufdatum ebenso: eigene Coins von anderswo sind kein Erwerb an diesem
        // Tag (CRYPTO22200 ordnet nur Erwerbe zu) — sie dürfen keinen früheren Verkauf nach der 30-Tage-Regel umleiten
        func late(_ s: TaxStep) -> Bool {
            (s.trade.type == .DEPOSIT && s.trade.acquiredTime == nil) || day(s.acquired, zone) != day(s.time, zone)
        }
        // Zugänge je Tag (Erwerbsdatum) als ein Zugang zum Durchschnitt
        var dayPools: [LocalDay: TaxPool] = [:]
        var dayFirst: [LocalDay: Int64] = [:]
        for s in steps where s.kind == .acquire && !late(s) {
            let d = day(s.acquired, zone)
            var pool = dayPools[d] ?? TaxPool()
            pool.add(s.qty, cost: s.cost, time: s.acquired)
            dayPools[d] = pool
            dayFirst[d] = min(dayFirst[d] ?? Int64.max, s.acquired)
        }
        let poolDays = dayPools.keys.sorted()
        let disposeIdx: [Int] = steps.indices.filter { steps[$0].kind == .dispose }
        var remaining: [Int: Decimal] = [:]
        var pieces: [Int: [Piece]] = [:]
        for i in disposeIdx {
            remaining[i] = steps[i].qty
            pieces[i] = []
        }
        // 1. Gleicher Tag: alle Abgänge des Tags gemeinsam gegen die Zugänge des Tags, anteilig nach Menge
        var groups: [(day: LocalDay, idx: [Int])] = []
        for i in disposeIdx {
            let d = day(steps[i].time, zone)
            if let last = groups.indices.last, groups[last].day == d {
                groups[last].idx.append(i)
            } else if let k = groups.firstIndex(where: { $0.day == d }) {
                groups[k].idx.append(i)
            } else {
                groups.append((d, [i]))
            }
        }
        for g in groups {
            guard var pool = dayPools[g.day] else { continue }
            let total: Decimal = g.idx.reduce(Decimal(0)) { $0 + steps[$1].qty }
            let matched = min(total, pool.held)
            guard matched > 0 else { continue }
            var left = matched
            for (n, i) in g.idx.enumerated() {
                let want: Decimal = n == g.idx.count - 1 ? left : PortfolioDecimal.divide(matched * steps[i].qty, total)
                left -= want
                let taken = pool.take(want)
                remaining[i] = (remaining[i] ?? 0) - taken.removed
                pieces[i, default: []] += piecesOf(taken, match: .SAME_DAY, matchedTime: dayFirst[g.day])
            }
            dayPools[g.day] = pool
        }
        // 2. 30 Tage: früheste Veräusserung zuerst, Zugänge der 30 Tage danach früheste zuerst
        for i in disposeIdx {
            let d = day(steps[i].time, zone)
            let to = d.plusDays(TaxTemplate.ukBbDays)
            for ad in poolDays where d < ad && !(to < ad) {
                let r = remaining[i] ?? 0
                if r <= 0 { break }
                guard var pool = dayPools[ad], pool.held > 0 else { continue }
                let taken = pool.take(r)
                dayPools[ad] = pool
                remaining[i] = r - taken.removed
                pieces[i, default: []] += piecesOf(taken, match: .THIRTY_DAY, matchedTime: dayFirst[ad])
            }
        }
        // 3. Section-104-Pool
        var pool = TaxPool(zone: zone)
        var added = Set<LocalDay>()
        var out: [PortfolioDisposal] = []
        for (i, s) in steps.enumerated() {
            switch s.kind {
            case .acquire:
                if late(s) {
                    pool.add(s.qty, cost: s.cost, time: s.acquired)
                } else {
                    let d = day(s.acquired, zone)
                    if added.insert(d).inserted, var dp = dayPools[d] {
                        pool.absorb(&dp)
                        dayPools[d] = dp
                    }
                }
            case .dispose:
                var list = pieces[i] ?? []
                let r = remaining[i] ?? 0
                if r > 0 {
                    let taken = pool.take(r)
                    list += piecesOf(taken, match: .POOL)
                    let rest = r - taken.removed
                    if rest > 0 { list.append(Piece(amount: rest, cost: nil, uncovered: true)) }
                }
                out += emit(coin, s, list, proceeds: s.proceeds)
            case .remove:
                _ = pool.take(s.qty)
            case .networkFee:
                note(&lost, coin, s, pool.dropQty(s.qty))
            case .addFee:
                noteUnassigned(&unassigned, coin, s, pool.addFee(s.time, s.cost ?? 0))
            }
        }
        return out
    }

    // MARK: - CA

    /// CA: Verlust einer Zeile in der Steuerwährung (CAD) — Erlös zum Kurs des Verkaufs, Einstand je Herkunftsteil zum
    /// Kurs seines Datums (wie der Export); Rückgabe = Verlust als USDT-Betrag zum Kurs des Verkaufs, 0 = kein Verlust in
    /// CAD; nil = ein Kurs fehlt. Wie Android.
    private static func caLossAtSaleRate(_ row: PortfolioDisposal, _ fx: (Int64) -> Decimal?) -> Decimal? {
        guard let proceeds = row.proceedsUsdt, let cost = row.costUsdt,
              let rate = fx(row.sellTime), rate > 0 else { return nil }
        var costT: Decimal = 0
        if !row.costParts.isEmpty {
            for p in row.costParts {
                guard let r = fx(p.time) else { return nil }
                costT += PortfolioDecimal.multiply(p.usdt, r)
            }
        } else if cost != 0 {
            costT = PortfolioDecimal.multiply(cost, rate)
        }
        let gainT = PortfolioDecimal.multiply(proceeds, rate) - costT
        return gainT >= 0 ? 0 : PortfolioDecimal.divide(-gainT, rate)
    }

    private static func caAcb(_ coin: String, _ steps: [TaxStep], _ zone: TimeZone,
                              _ lost: inout [PortfolioLostFeeCost],
                              _ unassigned: inout [PortfolioUnassignedFee],
                              _ fx: ((Int64) -> Decimal?)? = nil) -> [PortfolioDisposal] {
        let days: [LocalDay] = steps.map { day($0.time, zone) }
        // Bestand nach jedem Schritt (nur Mengen) für «Bestand am Fensterende»
        var heldAfter: [Decimal] = []
        var h: Decimal = 0
        for s in steps {
            switch s.kind {
            case .acquire: h += s.qty
            case .dispose, .remove, .networkFee: h = max(0, h - s.qty)
            case .addFee: break
            }
            heldAfter.append(h)
        }
        var pool = TaxPool(zone: zone)
        var pending: [PortfolioCostPart] = []
        // Ersatz-Menge je Zugang: deckt nur einen nicht abziehbaren Verlust (wird verbraucht)
        var replaceLeft: [Decimal] = steps.map { $0.kind == .acquire ? $0.qty : 0 }
        // Nicht abziehbarer Verlust (Herkunft: Verkauf) für einen Ersatz NACH dem Verkauf: kommt bei diesem Zugang zum ACB
        var pendingAt: [Int: [PortfolioCostPart]] = [:]
        var out: [PortfolioDisposal] = []
        // Zugänge (Indizes, zeitlich): das Fenster per Binärsuche statt über alle Schritte je Verkauf
        let acquires: [Int] = steps.indices.filter { steps[$0].kind == .acquire }
        /// Erster Index in `days` mit Tag ≥ `d` (die Schritte sind zeitlich, also die Tage aufsteigend).
        func firstDayAtLeast(_ d: LocalDay) -> Int {
            var lo = 0
            var hi = days.count
            while lo < hi {
                let mid = (lo + hi) / 2
                if days[mid] < d { lo = mid + 1 } else { hi = mid }
            }
            return lo
        }
        /// Erste Position in `acquires` mit Index ≥ `i`.
        func firstAcquireAtLeast(_ i: Int) -> Int {
            var lo = 0
            var hi = acquires.count
            while lo < hi {
                let mid = (lo + hi) / 2
                if acquires[mid] < i { lo = mid + 1 } else { hi = mid }
            }
            return lo
        }

        /// Ersatzkauf (superficial loss) nur ein Erwerb im Fenster: Einzahlung ohne Kaufdatum = eigene Coins von anderswo, nie
        /// (wie UK `late`); sonst zählt das Erwerbsdatum (Einzahlung mit früherem Kaufdatum ausserhalb des Fensters: keiner).
        func replaces(_ s: TaxStep, _ from: LocalDay, _ to: LocalDay) -> Bool {
            if s.trade.type == .DEPOSIT && s.trade.acquiredTime == nil { return false }
            let a = day(s.acquired, zone)
            return !(a < from) && !(to < a)
        }

        /// Nicht abziehbarer Betrag zum ACB des Bestands (ohne Teil mit Kaufpreis: zum nächsten Zugang mit Kaufpreis).
        func toAcb(_ time: Int64, _ amount: Decimal) {
            guard amount > 0 else { return }
            if pool.priced > 0 {
                pool.addCost(time, amount)
            } else {
                pending.append(PortfolioCostPart(time: time, usdt: amount))
            }
        }
        for (i, s) in steps.enumerated() {
            switch s.kind {
            case .acquire:
                pool.add(s.qty, cost: s.cost, time: s.acquired)
                // Ersatz dieses Zugangs für einen früheren Verkauf: dessen nicht abziehbarer Verlust zum ACB
                if let parts = pendingAt.removeValue(forKey: i) {
                    for p in parts { toAcb(p.time, p.usdt) }
                }
                // Nicht abziehbarer Verlust ohne Bestand: zum nächsten Zugang mit Kaufpreis
                if s.cost != nil && !pending.isEmpty {
                    for p in pending { pool.addCost(p.time, p.usdt) }
                    pending = []
                }
            case .dispose:
                let taken = pool.take(s.qty)
                var list = piecesOf(taken)
                let rest = s.qty - taken.removed
                if rest > 0 { list.append(Piece(amount: rest, cost: nil, uncovered: true)) }
                let from = days[i].plusDays(-TaxTemplate.caWindowDays)
                let to = days[i].plusDays(TaxTemplate.caWindowDays)
                // Noch nicht verbrauchte Ersatz-Zugänge im Fenster und Bestand am Ende des Fensters (Binärsuche über die
                // aufsteigenden Tage: gleiche Indizes wie der frühere Durchlauf über alle Schritte)
                let lo = firstDayAtLeast(from)
                let afterTo = firstDayAtLeast(to.plusDays(1))
                let endIndex = max(i, afterTo - 1)
                var window: [Int] = []
                var k = firstAcquireAtLeast(lo)
                while k < acquires.count && acquires[k] < afterTo {
                    if replaces(steps[acquires[k]], from, to) { window.append(acquires[k]) }
                    k += 1
                }
                let bought: Decimal = window.reduce(Decimal(0)) { $0 + replaceLeft[$1] }
                let heldEnd = heldAfter[endIndex]
                let n = max(0, min(min(s.qty, bought), heldEnd))
                var denied: Decimal = 0
                var rows = emit(coin, s, list, proceeds: s.proceeds)
                for k in rows.indices {
                    // Verlust in der Steuerwährung entscheiden (CAD: gemeldet wird in CAD — ein USDT-Verlust kann in CAD
                    // ein Gewinn sein); ohne Kurse in USDT
                    // Schenkung an Ehe-/Lebenspartner: kein Verlust (ITA 73(1))
                    guard !s.spouseGift, let g = rows[k].gainUsdt else { continue }
                    let loss: Decimal = fx.flatMap { caLossAtSaleRate(rows[k], $0) } ?? max(0, -g)
                    guard loss > 0, rows[k].costUsdt != nil, s.qty > 0 else { continue }
                    let part: Decimal = n == s.qty ? loss : PortfolioDecimal.divide(loss * n, s.qty)
                    if part > 0 {
                        denied += part
                        rows[k].adjustmentUsdt = part
                    }
                }
                out += rows
                if denied > 0 && n > 0 {
                    // Ersatz verbrauchen (früheste zuerst); Betrag anteilig zum ACB dieses Ersatzes
                    var need = n
                    var matched: [(j: Int, q: Decimal)] = []
                    for j in window {
                        if need <= 0 { break }
                        let q = min(replaceLeft[j], need)
                        if q <= 0 { continue }
                        replaceLeft[j] -= q
                        need -= q
                        matched.append((j, q))
                    }
                    var left = denied
                    for (k, m) in matched.enumerated() {
                        let part: Decimal = k == matched.count - 1 ? left : PortfolioDecimal.divide(denied * m.q, n)
                        left -= part
                        if m.j > i {
                            pendingAt[m.j, default: []].append(PortfolioCostPart(time: s.time, usdt: part))
                        } else {
                            toAcb(s.time, part)
                        }
                    }
                }
            case .remove:
                _ = pool.take(s.qty)
            case .networkFee:
                note(&lost, coin, s, pool.dropQty(s.qty))
            case .addFee:
                noteUnassigned(&unassigned, coin, s, pool.addFee(s.time, s.cost ?? 0))
            }
        }
        return out
    }

    // MARK: - JP

    /// Herkunftsteile `parts` (Summe `total`) auf den Anteil `target` skaliert; Summe exakt `target`.
    private static func scaleParts(_ parts: [PortfolioCostPart], total: Decimal, target: Decimal) -> [PortfolioCostPart] {
        guard target != 0, total != 0, !parts.isEmpty else { return [] }
        if target == total { return parts }
        var left = target
        var out: [PortfolioCostPart] = []
        for (i, p) in parts.enumerated() {
            let v: Decimal = i == parts.count - 1 ? left : PortfolioDecimal.divide(p.usdt * target, total)
            left -= v
            out.append(PortfolioCostPart(time: p.time, usdt: v))
        }
        return out
    }

    private static func jpTotal(_ coin: String, _ steps: [TaxStep], _ zone: TimeZone,
                                _ lost: inout [PortfolioLostFeeCost]) -> [PortfolioDisposal] {
        var carryPriced: Decimal = 0
        var carryUnpriced: Decimal = 0
        var carryCost: Decimal = 0
        var carryParts: [PortfolioCostPart] = []
        var out: [PortfolioDisposal] = []
        var byYear: [Int: [TaxStep]] = [:]
        for s in steps { byYear[day(s.time, zone).year, default: []].append(s) }
        for year in byYear.keys.sorted() {
            let ys = byYear[year] ?? []
            // Jahressumme: Anfangsbestand + Zugänge des Jahres (Gebühren von Umbuchungen gehören nicht dazu)
            var totPriced = carryPriced
            var totUnpriced = carryUnpriced
            var totCost = carryCost
            var parts = carryParts
            for s in ys where s.kind == .acquire {
                if let c = s.cost {
                    totPriced += s.qty
                    totCost += c
                    if c != 0 { parts.append(PortfolioCostPart(time: s.acquired, usdt: c)) }
                } else {
                    totUnpriced += s.qty
                }
            }
            let totQty = totPriced + totUnpriced
            let share: Decimal = totQty > 0 ? PortfolioDecimal.divide(totPriced, totQty) : 1
            let unit: Decimal = totPriced > 0 ? PortfolioDecimal.divide(totCost, totPriced) : 0
            var held = carryPriced + carryUnpriced
            var costOut: Decimal = 0
            func remove(_ qty: Decimal) -> TaxTaken {
                let removed = max(0, min(qty, held))
                held -= removed
                let removedPriced: Decimal = share == 1 ? removed : PortfolioDecimal.multiply(removed, share)
                let c: Decimal = totPriced > 0 ? PortfolioDecimal.multiply(removedPriced, unit) : 0
                costOut += c
                return TaxTaken(removed: removed, removedPriced: removedPriced, cost: c,
                                parts: scaleParts(parts, total: totCost, target: c))
            }
            var lastFee: TaxStep? = nil
            for s in ys {
                switch s.kind {
                case .acquire:
                    held += s.qty
                case .dispose:
                    let taken = remove(s.qty)
                    var list = piecesOf(taken)
                    let rest = s.qty - taken.removed
                    if rest > 0 { list.append(Piece(amount: rest, cost: nil, uncovered: true)) }
                    out += emit(coin, s, list, proceeds: s.proceeds)
                case .remove:
                    _ = remove(s.qty)
                case .networkFee:
                    held = max(0, held - s.qty)
                    lastFee = s
                case .addFee:
                    break
                }
            }
            // Übertrag: Restmenge (Anteil mit Kaufpreis wie im Jahr), Rest-Einstand samt Herkunft
            carryPriced = share == 1 ? held : PortfolioDecimal.multiply(held, share)
            carryUnpriced = held - carryPriced
            let restRaw: Decimal = max(0, totCost - costOut)
            // Ohne Rest mit Kaufpreis: Einstand der Netzwerkgebühren nicht still verlieren, sondern vermerken
            if let fee = lastFee, carryPriced <= 0 || PortfolioDecimal.isDust(carryPriced), !PortfolioDecimal.isDust(restRaw) {
                note(&lost, coin, fee, restRaw)
            }
            let restCost: Decimal = carryPriced > 0 ? restRaw : 0
            carryParts = scaleParts(parts, total: totCost, target: restCost)
            carryCost = restCost
        }
        return out
    }

    // MARK: - AT

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

    /// Ereignisse aller Coins in Reihenfolge der Transaktionen: Haupt-Coin, Gebühr-Coin, erhaltener Coin.
    private static func mergedEvents(_ real: [PortfolioTx]) -> [(coin: String, event: PortfolioLedgerEvent)] {
        // Gleiche Reihenfolge wie das Buch (`PortfolioLedger.precedes`): Zeit, bei gleicher Zeit Zugänge vor Abgängen, dann Id
        let sorted = real.enumerated().sorted { a, b in
            PortfolioLedger.precedes(PortfolioLedgerEvent(trade: a.element, feeLeg: false),
                                     PortfolioLedgerEvent(trade: b.element, feeLeg: false)) ?? (a.offset < b.offset)
        }.map(\.element)
        var out: [(coin: String, event: PortfolioLedgerEvent)] = []
        for t in sorted {
            out.append((PortfolioCalculator.normalizeCoin(t.coin), PortfolioLedgerEvent(trade: t, feeLeg: false)))
            if let fc = t.thirdFeeCoin { out.append((fc, PortfolioLedgerEvent(trade: t, feeLeg: true))) }
            if let tc = t.swapToCoin { out.append((tc, PortfolioLedgerEvent(trade: t, feeLeg: false, swapIn: true))) }
        }
        return out
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
        if s.trade.type == .REWARD && !interestReward(s.trade.note) { return 0 }
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

    /// Mitternacht des Tags `day` in `zone` (Millisekunden).
    private static func startMillis(_ d: LocalDay, _ zone: TimeZone) -> Int64 {
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = zone
        let date = cal.date(from: DateComponents(year: d.year, month: d.month, day: d.day)) ?? Date(timeIntervalSince1970: 0)
        return Int64((date.timeIntervalSince1970 * 1000).rounded())
    }

    private static func atSplit(_ real: [PortfolioTx], _ zone: TimeZone,
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

    // MARK: - FR

    private static func frPta(_ real: [PortfolioTx], _ priceAt: ((String, Int64) -> Decimal?)?,
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

        /// Gesamtwert aller Bestände unmittelbar vor `time` — eigene Bestände und Guthaben beobachteter Adressen (zum selben
        /// Kurs); `coin` zu `price`; nil = ein Kurs fehlt. Wie Android.
        func portfolioValue(_ coin: String, _ price: Decimal?, _ time: Int64) -> Decimal? {
            var value: Decimal? = 0
            for c in bookOrder + addrOrder.filter({ books[$0] == nil }) {
                let q = (books[c]?.total ?? 0) + (addr[c] ?? 0)
                if q <= 0 || PortfolioDecimal.isDust(q) { continue }
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
                    let gross: Decimal? = p.map { PortfolioDecimal.multiply(qty, $0) }
                    let value: Decimal? = portfolioValue(feeCoin, p, t.time).map { $0 - spent }
                    let wasIncomplete = incomplete
                    var cost: Decimal? = nil
                    var parts: [PortfolioCostPart] = []
                    if let gross, let value, value > 0 {
                        let r = reduce(gross, value)
                        cost = r.cost
                        parts = r.parts
                    } else {
                        incomplete = true
                    }
                    if let gross { spent += gross }
                    var gain: Decimal? = nil
                    if let gross, let cost { gain = gross - cost }
                    var d = PortfolioDisposal(
                        coin: feeCoin, sellTxId: t.id, sellTime: t.time, lotTxId: nil, acquiredTime: nil, lotKind: nil,
                        amount: qty, costUsdt: cost, proceedsUsdt: gross, gainUsdt: gain, holdingDays: nil,
                        feeLeg: feeLeg, feeValueMissing: false, method: .AVERAGE, costParts: parts, uncovered: qty > held)
                    d.costIncomplete = wasIncomplete || cost == nil
                    d.portfolioValueUsdt = value
                    d.grossProceedsUsdt = gross
                    out.append(d)
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
                    if let v, let value, value > 0 {
                        reduction = reduce(v, value).cost
                    } else {
                        // Kurs fehlt: Kürzung unbekannt, die PTA danach nicht mehr gesichert
                        incomplete = true
                    }
                    outflows.append(TaxOutflow(coin: coin, txId: t.id, time: t.time, amount: qty, valueUsdt: v,
                                               ptaReductionUsdt: reduction))
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
