import Foundation

/// «Seit Kauf» und Einstand der Gesamtkarte in der Anzeigewährung (`PortfolioCostFx.figures`). Beträge in der
/// Anzeigewährung, exakt; nil = unbekannt. Wie `PortfolioCostFxFigures` (Android).
struct PortfolioCostFxFigures: Equatable, Sendable {
    /// Gewinn/Verlust seit Kauf: Wert des Teils mit Kaufpreis × heutiger Faktor − dessen Einstand zu den Kursen der
    /// Erwerbstage, über dieselben Coins wie `PortfolioSummary.unrealized` — ohne die Coins in `fxMissingCoins`. nil,
    /// wenn kein solcher Coin umgerechnet werden kann.
    let unrealized: Decimal?
    /// `unrealized` ÷ Einstand derselben Coins in der Anzeigewährung × 100; nil ohne Einstand > 0.
    let unrealizedPercent: Double?
    /// Einstand wie `PortfolioSummary.invested` (Coins ganz mit Kaufpreis) zu den Kursen der Erwerbstage; nil = keiner.
    let invested: Decimal?
    /// In `unrealized` fehlt ein Coin, weil für einen Erwerbstag kein Devisenkurs da ist («unvollständig»).
    let unrealizedFxMissing: Bool
    /// In `invested` fehlt ein Coin aus demselben Grund.
    let investedFxMissing: Bool
    /// Coins ohne Devisenkurs eines Erwerbstags, alphabetisch (Hinweis).
    let fxMissingCoins: [String]
    /// An mindestens einem Erwerbstag ohne USDT/USD-Tagesschluss mit USDT = 1 gerechnet (`TaxExport.usdtApproximateOn`).
    let usdtApproximate: Bool
    /// Einstand je Coin in `unrealized` (Teil mit Kaufpreis) in der Anzeigewährung — für das Widget gemerkt
    /// (`PortfolioDisplayFx`), das «Seit Kauf» damit zum aktuellen Kurs nachrechnet. Ohne die Coins in `fxMissingCoins`.
    var coinCosts: [String: Decimal] = [:]
}

/// Einstand der offenen Bestände in der Anzeigewährung zum Kurs des Erwerbstags (Owner-Entscheid Okt. 2026) — wie
/// `PortfolioCostFx.kt` (Android):
///  - Bisher rechnete die Karte auch den Einstand mit dem heutigen Faktor um; «Seit Kauf (CHF)» zeigte so nur die
///    Bewegung in USDT. Jetzt: Wert heute × heutiger Faktor (`PortfolioStables.usdtTo`, wie der Gesamtwert) − Σ Teil
///    des Einstands × Faktor SEINES Tags. Der Faktor eines Tags folgt genau der Regel des Steuer-Exports
///    (`TaxExport.rateOn`): Devisenkurs des Tags bzw. des letzten Tags davor (höchstens `TaxExport.fxMaxGapDays` Tage)
///    × USDT/USD-Tagesschluss DES Tags (ohne Schluss × 1, `usdtApproximate`). Ab heute gilt der heutige Faktor der
///    Karte (wie der Wertverlauf, `PortfolioHistoryFx.rateFunction`).
///  - Tage in UTC wie Wertverlauf und «7 T»/«1 J»; die Steuer-CSV nimmt die Zeitzone der Vorlage.
///  - Die Teile (`CoinPosition.pricedCostParts`) kommen aus der gewählten Einstandsmethode: FIFO (auch je Wallet) die
///    offenen Posten mit Erwerb und zugeschlagenen Gebühren je zu ihrem Datum. Ø: jeder Zugang zum Kurs seines Tags,
///    jeder Abgang nimmt alle Teile anteilig mit — gleich, als würde man den Ø-Einstand von Anfang an in der
///    Anzeigewährung führen (dieselbe Rechnung wie der Ø-Einstand im Steuer-Export).
///  - Fehlt für einen Teil der Devisenkurs, fehlt der ganze Coin (nie still der heutige Kurs) und die Zahl ist
///    «unvollständig»; fehlt er bei allen, bleibt sie leer.
///  - Prozent auf den Einstand in der Anzeigewährung derselben Coins.
enum PortfolioCostFx {

    /// UTC-Tag (Tage seit 1970) eines Zeitpunkts.
    static func dayOf(_ time: Int64) -> Int { PortfolioHistory.epochDay(utcMillis: time) }

    /// Frühester Erwerbstag (UTC) der offenen Teile mit Kaufpreis — ab hier braucht `figures` Kurse; nil = keiner.
    static func firstDay(_ summary: PortfolioSummary) -> Int? {
        summary.open.flatMap(\.pricedCostParts).map { dayOf($0.time) }.min()
    }

    /// Gesamtsicht noch ohne Herkunftsteile (z. B. gerade von USDT umgestellt, die Rechnung mit Teilen läuft noch): eine
    /// offene Position mit Einstand ≠ 0 hat keine Teile. Dann zeigt die Karte «—» statt kurz «Kurs fehlt». Wie Android
    /// `costFxFlow`.
    static func partsPending(_ summary: PortfolioSummary) -> Bool {
        summary.open.contains { ($0.pricedCostExact ?? 0) != 0 && $0.pricedCostParts.isEmpty }
    }

    /// Faktor USDT → `currency` am Tag `day`: ab `todayEpochDay` `currentRate`, sonst `TaxExport.rateOn` mit `rates`
    /// (Devisen-Tageskurse, Tag → Kurs) und `usdtUsd` (USDT/USD-Tagesschlüsse); nil = kein Devisenkurs.
    static func rateOn(day: Int, currency: String, currentRate: Decimal, todayEpochDay: Int,
                       rates: [Int: Decimal]?, usdtUsd: [Int: Decimal]?) -> Decimal? {
        day >= todayEpochDay ? currentRate
            : TaxExport.rateOn(rates, date: LocalDay(epochDay: day), currency: currency, usdtUsd: usdtUsd)
    }

    /// Kennzahlen der Karte in `currency` (`PortfolioCostFxFigures`); `currentRate` = heutiger Faktor USDT → Währung der
    /// Karte (`PortfolioStables.usdtTo`). Für USDT nicht gedacht (dort bleibt `PortfolioSummary` unverändert).
    static func figures(_ summary: PortfolioSummary, currency: String, currentRate: Decimal, todayEpochDay: Int,
                        rates: [Int: Decimal]?, usdtUsd: [Int: Decimal]?) -> PortfolioCostFxFigures {
        var usdtApproximate = false
        var missing = Set<String>()
        var targets: [String: Decimal?] = [:]

        /// Einstand des Teils mit Kaufpreis in der Währung; nil = ein Kurs fehlt.
        func costTarget(_ p: CoinPosition) -> Decimal? {
            guard let cost = p.pricedCostExact else { return nil }
            if cost == 0 { return 0 }
            // Einstand ohne Herkunft (sollte nicht vorkommen): Datum unbekannt — nicht mit heute raten
            if p.pricedCostParts.isEmpty { return nil }
            var sum: Decimal = 0
            var partsSum: Decimal = 0
            var approximate = false
            for part in p.pricedCostParts {
                let day = dayOf(part.time)
                guard let r = rateOn(day: day, currency: currency, currentRate: currentRate, todayEpochDay: todayEpochDay,
                                     rates: rates, usdtUsd: usdtUsd) else { return nil }
                if day < todayEpochDay, TaxExport.usdtApproximateOn(usdtUsd, date: LocalDay(epochDay: day)) { approximate = true }
                sum += PortfolioDecimal.multiply(part.usdt, r)
                partsSum += part.usdt
            }
            if approximate { usdtApproximate = true }
            // Teile und Einstand gehen exakt auf; ein Rest aus Teilungen zählt anteilig mit
            if partsSum != 0, partsSum != cost { return PortfolioDecimal.divide(PortfolioDecimal.multiply(sum, cost), partsSum) }
            return sum
        }
        func target(_ p: CoinPosition) -> Decimal? {
            if let known = targets[p.coin] { return known }
            let value = costTarget(p)
            if value == nil { missing.insert(p.coin) }
            targets[p.coin] = .some(value)
            return value
        }

        // «Seit Kauf»: dieselben Coins wie die Summe (Teil mit Kaufpreis und Kurs)
        var unrealized: Decimal?
        var includedCost: Decimal = 0
        var unrealizedFxMissing = false
        var coinCosts: [String: Decimal] = [:]
        for p in summary.open {
            guard let u = p.unrealizedExact, let pricedCost = p.pricedCostExact else { continue }
            guard let cost = target(p) else { unrealizedFxMissing = true; continue }
            coinCosts[p.coin] = cost
            unrealized = (unrealized ?? 0) + PortfolioDecimal.multiply(u + pricedCost, currentRate) - cost
            includedCost += cost
        }
        var percent: Double?
        if let unrealized, includedCost > 0 {
            percent = PortfolioDecimal.double(PortfolioDecimal.divide(unrealized, includedCost)) * 100
        }

        // Einstand: Coins ganz mit Kaufpreis, wie `PortfolioSummary.invested`
        var invested: Decimal?
        var investedFxMissing = false
        for p in summary.open where p.costBasis != nil {
            guard let cost = target(p) else { investedFxMissing = true; continue }
            invested = (invested ?? 0) + cost
        }
        return PortfolioCostFxFigures(unrealized: unrealized, unrealizedPercent: percent, invested: invested,
                                      unrealizedFxMissing: unrealizedFxMissing, investedFxMissing: investedFxMissing,
                                      fxMissingCoins: missing.sorted(), usdtApproximate: usdtApproximate,
                                      coinCosts: coinCosts)
    }
}
