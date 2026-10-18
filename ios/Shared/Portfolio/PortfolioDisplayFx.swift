import Foundation

/// Einstand eines Coins für «Seit Kauf» des Widgets: `usdt` = Einstand des Teils mit Kaufpreis in USDT (erkennt, ob der
/// Coin seit dem Merken gekauft/verkauft wurde), `target` = derselbe Einstand in der Anzeigewährung zum Kurs der
/// Erwerbstage (`PortfolioCostFx`). Wie `PortfolioDisplayCost` (Android).
struct PortfolioDisplayCost: Codable, Equatable, Sendable {
    var usdt: Double
    var target: Double
}

/// Von der App gemerkte Kurse, mit denen jeder Stand des Portfolio-Widgets (auch im Hintergrund und im Widget selbst,
/// ohne geladene Devisen-Tageskurse) «Seit Kauf» und «7 T» in der Anzeigewährung wie die Gesamtkarte rechnet — statt
/// USDT × heutiger Kurs. Wie `PortfolioDisplayFx` (Android).
struct PortfolioDisplayFx: Codable, Equatable, Sendable {
    /// Anzeigewährung, für die die Werte gelten.
    var currency: String
    /// Einstand je Coin (`PortfolioCostFxFigures.coinCosts`); Coins ohne Devisenkurs eines Erwerbstags fehlen.
    var costs: [String: PortfolioDisplayCost]
    /// Faktor USDT → `currency` je UTC-Tag (Devisenkurs × USDT/USD-Tagesschluss, `PortfolioHistoryFx.rateFunction`).
    var dayFactors: [Int: Double]
    /// UTC-Tag, an dem `dayFactors` gemerkt wurden (sie reichen bis zum Vortag).
    var day: Int
}

/// Reine Regeln für «Seit Kauf» und «7 T» des Widgets in der Anzeigewährung — wie `PortfolioDisplayFigures` (Android,
/// getestet in PortfolioDisplayFxTest; hier in PortfolioDisplayFxTests).
enum PortfolioDisplayFigures {
    /// So viele Tage vor dem Merk-Tag stehen in `dayFactors` — genug für «7 T» samt Vortag.
    static let memoDays = 14
    /// Tage von «7 T».
    static let weekDays = 7

    private static func same(_ a: String, _ b: String) -> Bool {
        a.trimmingCharacters(in: .whitespaces).uppercased() == b.trimmingCharacters(in: .whitespaces).uppercased()
    }

    /// Merkzettel aus den Zahlen der App: Einstand je Coin aus `coinCosts` (mit dem USDT-Einstand aus `open` zur
    /// Erkennung) und die Faktoren `dayFactor` der `memoDays` Tage vor `todayEpochDay`; ohne `dayFactor` keine Faktoren
    /// («7 T» dann in USDT). nil ohne `coinCosts`.
    static func memo(currency: String, coinCosts: [String: Decimal]?, open: [CoinPosition],
                     dayFactor: ((Int) -> Double)?, todayEpochDay: Int) -> PortfolioDisplayFx? {
        guard let coinCosts else { return nil }
        var costs: [String: PortfolioDisplayCost] = [:]
        for p in open {
            guard let target = coinCosts[p.coin], let usdt = p.pricedCostExact else { continue }
            costs[PortfolioCalculator.normalizeCoin(p.coin)] = PortfolioDisplayCost(usdt: PortfolioDecimal.double(usdt),
                                                                                    target: PortfolioDecimal.double(target))
        }
        var factors: [Int: Double] = [:]
        if let dayFactor {
            for d in (todayEpochDay - memoDays)..<todayEpochDay {
                let f = dayFactor(d)
                if f > 0 && f.isFinite { factors[d] = f }
            }
        }
        return PortfolioDisplayFx(currency: currency.trimmingCharacters(in: .whitespaces).uppercased(), costs: costs,
                                  dayFactors: factors, day: todayEpochDay)
    }

    /// «Seit Kauf» in `currency` wie `PortfolioCostFx.figures`: Σ (Wert des Teils mit Kaufpreis × `currentRate` −
    /// gemerkter Einstand) über die Coins mit Kaufpreis und Kurs; Prozent auf den Einstand. Ein Coin fehlt (`skipped` —
    /// «unvollständig»), wenn sein Einstand nicht gemerkt ist oder sich seither geändert hat (Kauf/Verkauf). nil ohne
    /// passenden Merkzettel oder wenn kein Coin übrig bleibt. Wie Android.
    static func unrealized(open: [CoinPosition], currency: String, currentRate: Double,
                           memo: PortfolioDisplayFx?) -> PortfolioWidgetChange? {
        guard let memo, same(memo.currency, currency), currentRate > 0, currentRate.isFinite else { return nil }
        var amount: Double?
        var cost: Double = 0
        var skipped = Set<String>()
        for p in open {
            guard let unrealized = p.unrealizedExact, let priced = p.pricedCostExact else { continue }
            let coin = PortfolioCalculator.normalizeCoin(p.coin)
            let pricedUsdt = PortfolioDecimal.double(priced)
            guard let m = memo.costs[coin], abs(m.usdt - pricedUsdt) <= 1e-9 * max(1, abs(pricedUsdt)) else {
                skipped.insert(coin)
                continue
            }
            let value = PortfolioDecimal.double(unrealized + priced)
            amount = (amount ?? 0) + value * currentRate - m.target
            cost += m.target
        }
        guard let a = amount, a.isFinite else { return nil }
        let list = skipped.sorted()
        return PortfolioWidgetChange(amount: a, percent: cost > 0 ? a / cost * 100 : nil, skipped: list.isEmpty ? nil : list)
    }

    /// Faktor je Tag für «7 T» in `currency` (`PortfolioPeriodChange.gain` `fx`): die gemerkten Faktoren, ab heute
    /// `currentRate` — nur, wenn der Merkzettel heute gemerkt wurde und jeden Tag von heute − `days` bis gestern hat;
    /// sonst nil («7 T» dann in USDT). Wie Android `periodFx`.
    static func periodFx(memo: PortfolioDisplayFx?, currency: String, currentRate: Double, todayEpochDay: Int,
                         days: Int = weekDays) -> ((Int) -> Double)? {
        guard let memo, same(memo.currency, currency), memo.day == todayEpochDay,
              currentRate > 0, currentRate.isFinite else { return nil }
        for d in (todayEpochDay - days)..<todayEpochDay where memo.dayFactors[d] == nil { return nil }
        let factors = memo.dayFactors
        return { day in day >= todayEpochDay ? currentRate : (factors[day] ?? currentRate) }
    }

    /// `change` aus `PortfolioPeriodChange.gain` mit `fx` (Betrag schon in der Anzeigewährung) für den Stand.
    static func periodChange(_ change: PortfolioChange?) -> PortfolioWidgetChange? {
        guard let change, change.amount.isFinite else { return nil }
        return PortfolioWidgetChange(amount: change.amount, percent: change.percent.flatMap { $0.isFinite ? $0 : nil },
                                     skipped: change.skipped.isEmpty ? nil : change.skipped)
    }
}
