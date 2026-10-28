import Foundation

// Bausteine der Steuer-Rechnung (`TaxEngines`) — wie `TaxEngineCore.kt` (Android).

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
    /// Erlös aus geschätztem Wert (`PortfolioTx.valueEstimated`) bzw. mit geschätztem Gebühr-Preis (`PortfolioTx.feePriceEstimated`).
    var estimated = false
    /// Zugang bzw. `.addFee`: `cost` enthält einen geschätzten Wert (`PortfolioTx.costEstimated`).
    var costEstimated = false

    init(_ kind: TaxStepKind, _ trade: PortfolioTx, qty: Decimal, cost: Decimal? = nil, acquired: Int64? = nil,
         proceeds: Decimal? = nil, gross: Decimal? = nil, feeLeg: Bool = false, swap: Bool = false, feeMissing: Bool = false,
         gift: Bool = false, spouseGift: Bool = false, payment: Bool = false, estimated: Bool = false,
         costEstimated: Bool = false) {
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
        self.costEstimated = costEstimated
    }

    var time: Int64 { trade.time }
    var id: Int64 { trade.id }
}

/// Ergebnis von `TaxPool.take` — wie `TaxTaken` (Android); `estimated` = der Einstand enthält einen geschätzten Wert.
struct TaxTaken: Sendable {
    let removed: Decimal
    let removedPriced: Decimal
    let cost: Decimal
    let parts: PortfolioCostPartList
    var estimated = false

    var removedUnpriced: Decimal { removed - removedPriced }
}

/// Durchschnitts-Topf (Menge mit/ohne Kaufpreis, Einstand mit Herkunft) — wie `TaxPool` (Android). Herkunftsteile in
/// `PortfolioCostPartPool` (je Abgang O(1)); mit `zone` die Teile desselben Kalendertags zusammengefasst (gleicher
/// Devisenkurs).
struct TaxPool: Sendable {
    private(set) var priced: Decimal = 0
    private(set) var unpriced: Decimal = 0
    private(set) var cost: Decimal = 0
    /// `cost` enthält einen geschätzten Wert (`PortfolioTx.costEstimated`) — bis kein Teil mit Kaufpreis mehr bleibt.
    private(set) var estimated = false
    private var costParts: PortfolioCostPartPool
    private let zone: TimeZone?

    init(zone: TimeZone? = nil) {
        self.zone = zone
        costParts = PortfolioCostPartPool(zone: zone)
    }

    /// Offene Herkunftsteile (Summe exakt `cost`).
    var parts: [PortfolioCostPart] { costParts.toArray(total: cost) }

    var held: Decimal { priced + unpriced }

    /// Zugang `qty` mit Einstand `cost` (nil = ohne Kaufpreis), Herkunft `time`; `est` = Einstand geschätzt.
    mutating func add(_ qty: Decimal, cost c: Decimal?, time: Int64, est: Bool = false) {
        guard qty > 0 else { return }
        guard let c else {
            unpriced += qty
            return
        }
        priced += qty
        addCost(time, c, est: est)
    }

    /// Zugang mit fortgeführtem Einstand (Herkunftsteile `from`); `est` = Einstand geschätzt.
    mutating func addCarried(_ qty: Decimal, from: PortfolioCostPartList, est: Bool = false) {
        guard qty > 0 else { return }
        priced += qty
        for p in from { addCost(p.time, p.usdt) }
        if est && !from.isEmpty { estimated = true }
    }

    /// Einstand direkt zum Teil mit Kaufpreis; `est` = Betrag geschätzt.
    mutating func addCost(_ time: Int64, _ usdt: Decimal, est: Bool = false) {
        cost += usdt
        costParts.add(time, usdt)
        if est && usdt != 0 { estimated = true }
    }

    /// Alle Herkunftsteile entfernen (samt Tag-Index) und den Merker `estimated`.
    private mutating func clearParts() {
        costParts.clear()
        estimated = false
    }

    /// Gebühr `extra` einer Umbuchung der Menge `amount` mit Netzwerkgebühr `coinFee` (vor dem Schritt `.networkFee`),
    /// wie der Ø des Portfolios: zuerst der Anteil der nicht gedeckten Menge (gedeckt = Bestand − Netzwerkgebühr, wie FIFO
    /// je Wallet, `PortfolioFeeExpenses.uncoveredShare`; leerer Pool: die ganze Gebühr), der Rest anteilig (Menge mit
    /// Kaufpreis / Bestand) zum Einstand. Rückgabe = nicht gedeckter Anteil + Anteil des Teils ohne Kaufpreis (ohne Teil
    /// mit Kaufpreis: der ganze Rest) — der Aufrufer vermerkt ihn als `PortfolioUnassignedFee`; 0 = alles zugeordnet.
    /// `est` = Gebühr enthält einen geschätzten Wert. Wie Android.
    mutating func addFee(_ time: Int64, _ extra: Decimal, amount: Decimal, coinFee: Decimal, est: Bool = false) -> Decimal {
        guard extra > 0 else { return 0 }
        let h = held
        let rest = extra - PortfolioFeeExpenses.uncoveredShare(extra, amount: amount, covered: max(0, h - coinFee))
        guard rest > 0, priced > 0 else { return extra }
        let assigned = unpriced == 0 ? rest : PortfolioDecimal.divide(rest, priced, by: h)
        addCost(time, assigned, est: est)
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
        if other.estimated && other.priced > 0 { estimated = true }
        other = TaxPool(zone: other.zone)
    }

    /// Gibt `requested` ab (gekappt auf den Bestand), beide Teile anteilig samt Einstand.
    mutating func take(_ requested: Decimal) -> TaxTaken {
        let h = held
        let removed = max(0, min(requested, h))
        guard removed > 0 else { return TaxTaken(removed: 0, removedPriced: 0, cost: 0, parts: []) }
        let allPriced = unpriced == 0
        let removedPriced: Decimal = allPriced ? removed
            : min(PortfolioDecimal.amount(PortfolioDecimal.divide(removed, priced, by: h)), priced)
        let costRemoved: Decimal
        if priced == 0 {
            costRemoved = 0
        } else if removedPriced >= priced {
            costRemoved = cost
        } else {
            costRemoved = PortfolioDecimal.divide(cost, removedPriced, by: priced)
        }
        let partsRemoved = takeParts(costRemoved)
        let est = estimated && removedPriced > 0
        let rest = h - removed
        priced -= removedPriced
        cost -= costRemoved
        if PortfolioDecimal.isDust(priced) || priced < 0 { priced = 0; cost = 0; clearParts() }
        unpriced = max(0, rest - priced)
        if PortfolioDecimal.isDust(unpriced) { unpriced = 0 }
        return TaxTaken(removed: removed, removedPriced: removedPriced, cost: costRemoved, parts: partsRemoved, estimated: est)
    }

    /// Netzwerkgebühr: Menge sinkt anteilig, der Einstand bleibt im Rest. Bleibt kein Teil mit Kaufpreis, geht der
    /// Einstand nicht still verloren: Rückgabe = dieser Einstand (Aufrufer vermerkt `PortfolioLostFeeCost`), sonst 0.
    @discardableResult
    mutating func dropQty(_ qty: Decimal) -> Decimal {
        let h = held
        guard qty > 0, h > 0 else { return 0 }
        let removed = min(qty, h)
        let removedPriced: Decimal = unpriced == 0 ? removed
            : min(PortfolioDecimal.amount(PortfolioDecimal.divide(removed, priced, by: h)), priced)
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

    /// Nimmt `removed` Einstand anteilig aus allen Herkunftsteilen; Summe exakt `removed` (Ansicht, O(1)).
    private mutating func takeParts(_ removed: Decimal) -> PortfolioCostPartList {
        costParts.take(removed, total: cost)
    }
}
