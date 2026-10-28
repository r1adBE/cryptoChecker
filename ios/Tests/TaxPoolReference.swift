import Foundation
@testable import CryptoChecker

/// Referenz für `PortfolioCostPartsEquivalenceTests`: `TaxPool` mit der einfachen Führung der Herkunftsteile — eine
/// Liste, die jeder Abgang Teil für Teil neu schreibt (Zugänge × Abgänge). Sonst Zeile für Zeile wie `TaxPool`; wie
/// `TaxPoolReference.kt` (Android).
struct TaxPoolReference {
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

    /// Gebühr `extra` einer Umbuchung der Menge `amount` mit Netzwerkgebühr `coinFee` (vor dem Schritt `.networkFee`),
    /// wie der Ø des Portfolios: zuerst der Anteil der nicht gedeckten Menge (gedeckt = Bestand − Netzwerkgebühr, wie FIFO
    /// je Wallet, `PortfolioFeeExpenses.uncoveredShare`; leerer Pool: die ganze Gebühr), der Rest anteilig (Menge mit
    /// Kaufpreis / Bestand) zum Einstand. Rückgabe = nicht gedeckter Anteil + Anteil des Teils ohne Kaufpreis (ohne Teil
    /// mit Kaufpreis: der ganze Rest) — der Aufrufer vermerkt ihn als `PortfolioUnassignedFee`; 0 = alles zugeordnet.
    /// Wie Android.
    mutating func addFee(_ time: Int64, _ extra: Decimal, amount: Decimal, coinFee: Decimal) -> Decimal {
        guard extra > 0 else { return 0 }
        let h = held
        let rest = extra - PortfolioFeeExpenses.uncoveredShare(extra, amount: amount, covered: max(0, h - coinFee))
        guard rest > 0, priced > 0 else { return extra }
        let assigned = unpriced == 0 ? rest : PortfolioDecimal.divide(rest * priced, h)
        addCost(time, assigned)
        return extra - assigned
    }

    /// Übernimmt den ganzen Inhalt von `other` (danach leer).
    mutating func absorb(_ other: inout TaxPoolReference) {
        priced += other.priced
        unpriced += other.unpriced
        // Einstand unverändert, Herkunft je Teil (mit `zone` nach Tag zusammengefasst)
        let c = cost
        for p in other.parts { addCost(p.time, p.usdt) }
        cost = c + other.cost
        other = TaxPoolReference(zone: other.zone)
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
        return TaxTaken(removed: removed, removedPriced: removedPriced, cost: costRemoved,
                        parts: PortfolioCostPartList(partsRemoved))
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
