import Foundation

/// Kennzahlen eines Coins. Beträge in USDT — wie `CoinPosition` (Android).
struct CoinPosition: Sendable, Equatable, Identifiable {
    let coin: String
    /// Gehaltene Menge nach allen Transaktionen (nie negativ).
    let holdings: Double
    /// Ø Kaufpreis über die Käufe mit bekanntem Preis; nil ohne solche.
    let avgCost: Double?
    /// Einstand des Bestands («investiert»); nil, wenn bei einem Teil der Kaufpreis fehlt.
    let costBasis: Double?
    let currentPrice: Double?
    /// Bestand × aktueller Kurs; nil ohne Kurs.
    let value: Double?
    /// Unrealisierter Gewinn/Verlust; nur mit vollständigem Einstand und Kurs.
    let unrealized: Double?
    let unrealizedPercent: Double?
    /// Realisierter Gewinn/Verlust aus Verkäufen mit bekanntem Preis und Einstand.
    let realized: Double
    /// Ein Teil des Bestands hat keinen Kaufpreis («Preis fehlt»).
    let priceMissing: Bool
    /// Mindestens ein Verkauf war grösser als der Bestand und wurde gekappt.
    let oversold: Bool
    let tradeCount: Int

    var id: String { coin }
    var isOpen: Bool { holdings > PortfolioCalculator.eps }
}

/// Gesamtsicht über alle Coins — wie `PortfolioSummary` (Android).
struct PortfolioSummary: Sendable, Equatable {
    /// Coins mit Bestand, nach Wert absteigend.
    let open: [CoinPosition]
    /// Coins ohne Bestand, aber mit realisiertem Gewinn/Verlust.
    let closed: [CoinPosition]
    /// Summe der Werte (Coins ohne Kurs zählen nicht mit).
    let totalValue: Double
    /// Einstand aller offenen Positionen; nil, wenn irgendwo der Kaufpreis fehlt.
    let invested: Double?
    /// Unrealisiert gesamt; nil, wenn Einstand oder ein aktueller Kurs fehlt.
    let unrealized: Double?
    let unrealizedPercent: Double?
    let realized: Double
    /// Bei mindestens einem offenen Coin fehlt ein Kaufpreis.
    let costMissing: Bool
    /// Offene Coins ohne aktuellen Kurs.
    let missingCurrentPrices: [String]

    var isEmpty: Bool { open.isEmpty && closed.isEmpty }
}

/// Durchschnittskosten-Methode — Regeln, Schwellen und Toleranzen exakt wie
/// `PortfolioCalculator.kt`:
///  - Transaktionen je Coin zeitlich (bei Gleichstand nach Id) abarbeiten.
///  - Kauf mit Preis: Ø = (Ø × Menge_vorher + Menge × Preis) / Menge_nachher — nur über
///    Käufe mit Preis. Kauf ohne Preis erhöht nur den Bestand (Teil «ohne Preis»).
///  - Verkauf: Bestand sinkt (nie unter 0; zu grosse Verkäufe werden gekappt und markiert).
///    Beide Teile (mit/ohne Preis) sinken anteilig, der Ø bleibt unverändert.
///    Realisiert += (Verkaufspreis − Ø) × Menge, wenn der Verkaufspreis bekannt ist und
///    der ganze Bestand einen Kaufpreis hat.
///  - Gewinn/Verlust nur, wenn der ganze Restbestand einen Kaufpreis hat.
///  - USDT selbst hat immer den Kurs 1.
enum PortfolioCalculator {
    /// Darunter gilt eine Menge als null.
    static let eps = 1e-12

    /// Relative Toleranz, damit «alles verkaufen» bei Rundung nicht als Überverkauf gilt.
    private static let sellTolerance = 1e-9

    private static let oneDollar: Set<String> = ["USDT"]

    static func normalizeCoin(_ coin: String) -> String {
        coin.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
    }

    /// Kennzahlen eines Coins aus seinen Transaktionen (fremde Coins werden ignoriert).
    static func position(_ coin: String, trades: [PortfolioTx], currentPrice: Double?) -> CoinPosition {
        let symbol = normalizeCoin(coin)
        let own = trades.filter { normalizeCoin($0.coin) == symbol }
            .sorted { $0.time != $1.time ? $0.time < $1.time : $0.id < $1.id }

        var priced = 0.0      // Menge mit Kaufpreis
        var unpriced = 0.0    // Menge ohne Kaufpreis
        var avg = 0.0         // Ø Kaufpreis des Teils mit Preis
        var realized = 0.0
        var oversold = false

        for t in own {
            let amount = t.amount
            guard amount > 0, !amount.isInfinite else { continue }
            let price: Double? = t.priceUsdt.flatMap { $0 >= 0 && !$0.isInfinite && !$0.isNaN ? $0 : nil }
            switch t.type {
            case .BUY:
                if let price {
                    avg = (avg * priced + amount * price) / (priced + amount)
                    priced += amount
                } else {
                    unpriced += amount
                }
            case .SELL:
                let holdings = priced + unpriced
                var sold = amount
                if sold > holdings + max(eps, holdings * sellTolerance) { oversold = true }
                if sold > holdings { sold = holdings }
                if holdings <= eps || sold <= 0 { continue }

                if let price, unpriced <= eps, priced > eps {
                    realized += (price - avg) * sold
                }
                let keep = (holdings - sold) / holdings
                priced *= keep
                unpriced *= keep
                if priced <= eps { priced = 0; avg = 0 }
                if unpriced <= eps { unpriced = 0 }
            }
        }

        let total = priced + unpriced
        let holdings = total <= eps ? 0 : total
        let price: Double?
        if let p = currentPrice, p > 0, !p.isInfinite {
            price = p
        } else {
            price = oneDollar.contains(symbol) ? 1 : nil
        }
        let priceMissing = holdings > 0 && unpriced > eps
        let avgCost: Double? = priced > eps ? avg : nil
        let costBasis: Double? = holdings > 0 && !priceMissing ? priced * avg : nil
        let value: Double? = price.map { holdings * $0 }
        var unrealized: Double?
        if let costBasis, let value { unrealized = value - costBasis }
        var unrealizedPercent: Double?
        if let unrealized, let costBasis, costBasis > 0 { unrealizedPercent = unrealized / costBasis * 100 }

        return CoinPosition(
            coin: symbol,
            holdings: holdings,
            avgCost: avgCost,
            costBasis: costBasis,
            currentPrice: price,
            value: value,
            unrealized: unrealized,
            unrealizedPercent: unrealizedPercent,
            realized: realized,
            priceMissing: priceMissing,
            oversold: oversold,
            tradeCount: own.count
        )
    }

    /// Gesamtsicht; `prices` = aktueller USDT-Kurs je Coin (Grossschreibung).
    static func summarize(_ trades: [PortfolioTx], prices: [String: Double]) -> PortfolioSummary {
        var seen = Set<String>()
        var coins: [String] = []
        for t in trades {
            let c = normalizeCoin(t.coin)
            if seen.insert(c).inserted { coins.append(c) }
        }
        let positions = coins.map { position($0, trades: trades, currentPrice: prices[$0]) }

        let open = positions.filter(\.isOpen).sorted { a, b in
            let va = a.value ?? -1, vb = b.value ?? -1
            return va != vb ? va > vb : a.coin < b.coin
        }
        let closed = positions.filter { !$0.isOpen && abs($0.realized) > 1e-9 }
            .sorted { $0.coin < $1.coin }

        let totalValue = open.reduce(0.0) { $0 + ($1.value ?? 0) }
        let missingCurrent = open.filter { $0.value == nil }.map(\.coin)
        let costMissing = open.contains { $0.costBasis == nil }
        let invested: Double? = open.isEmpty || costMissing ? nil : open.reduce(0.0) { $0 + ($1.costBasis ?? 0) }
        var unrealized: Double?
        if let invested, missingCurrent.isEmpty { unrealized = totalValue - invested }
        var unrealizedPercent: Double?
        if let unrealized, let invested, invested > 0 { unrealizedPercent = unrealized / invested * 100 }

        return PortfolioSummary(
            open: open,
            closed: closed,
            totalValue: totalValue,
            invested: invested,
            unrealized: unrealized,
            unrealizedPercent: unrealizedPercent,
            realized: positions.reduce(0.0) { $0 + $1.realized },
            costMissing: costMissing,
            missingCurrentPrices: missingCurrent
        )
    }
}
