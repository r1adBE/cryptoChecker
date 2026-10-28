import Foundation

/// Eingaben für «7 T»/«1 J» der Gesamtkarte (`PortfolioModel.applyWeekChange`) als Sendable-Werte — auf dem
/// Hauptthread gelesen, im Hintergrund gerechnet (`PortfolioPeriodFigures.compute`).
struct PortfolioPeriodInput: Sendable {
    let trades: [PortfolioTx]
    /// Aktueller Kurs je offenem Coin (aus der Gesamtrechnung).
    let current: [String: Double]
    /// Tagesschlüsse je Coin (eigene Börse, sonst Binance skaliert).
    let closes: [String: [Int: Double]]
    let addresses: [AddressBalanceHistory]
    let method: CostMethod
    /// Heute als UTC-Tag.
    let today: Int
    /// Devisenkurs jetzt (USD → Währung), nil = in USDT rechnen.
    let fxNow: Double?
    /// Währung über Devisen-Tageskurse (`PortfolioHistoryFx.convertsUsdtAsUsd`).
    let viaFx: Bool
    /// Geladene Devisen-Tageskurse (`PortfolioModel.fxSeries`), nur wenn sie zur Währung gehören.
    let fxSeries: (from: LocalDay, rates: [Int: Double]?)?
    /// USDT/USD jetzt und je Tag.
    let usdt: UsdtUsdRates
}

/// Ergebnis: «7 T» in der Anzeigewährung (sonst USDT), «7 T» rein in USDT, «1 J».
struct PortfolioPeriodResult: Sendable {
    let week: PortfolioChange?
    let weekUsdt: PortfolioChange?
    let year: PortfolioChange?
}

/// «7 T»/«1 J» der Gesamtkarte — rein, ohne Hauptthread.
enum PortfolioPeriodFigures {
    static func compute(_ input: PortfolioPeriodInput) -> PortfolioPeriodResult {
        // Ohne Abbruch rechnet die abbrechbare Fassung immer zu Ende
        compute(input, cancelled: { false }) ?? PortfolioPeriodResult(week: nil, weekUsdt: nil, year: nil)
    }

    /// Wie oben, aber `cancelled` wird zwischen den Teilrechnungen gefragt (vor «7 T» rein in USDT und vor «1 J», die
    /// teuerste): true = abbrechen, nil zurück — eine veraltete Rechnung läuft nicht bis zum Ende weiter.
    static func compute(_ input: PortfolioPeriodInput, cancelled: () -> Bool) -> PortfolioPeriodResult? {
        let today = input.today
        func period(_ days: Int) -> PortfolioChange? {
            var fx: ((Int) -> Double)?
            if let fxNow = input.fxNow {
                var rates: [Int: Double]?
                if !input.viaFx {
                    rates = PortfolioHistoryFx.usdParity(fromEpochDay: today - days - PortfolioHistoryFx.leadDays,
                                                         toEpochDay: today)
                } else if let known = input.fxSeries, known.from <= LocalDay(epochDay: today - days) {
                    rates = known.rates
                }
                if let rates, !rates.isEmpty {
                    fx = PortfolioHistoryFx.rateFunction(dailyRates: rates, currentRate: fxNow, todayEpochDay: today,
                                                         usdt: input.usdt)
                }
            }
            let change = PortfolioPeriodChange.gain(trades: input.trades, current: input.current, closes: input.closes,
                                                    baseDay: today - days, addresses: input.addresses,
                                                    method: input.method, fx: fx)
            guard let change, fx != nil, let fxNow = input.fxNow else { return change }
            // Gegenwert zum Faktor der Karte (Devisenkurs × USDT/USD jetzt, `displayRate`)
            return PortfolioHistoryFx.forCard(change, currentRate: fxNow * input.usdt.current().rate)
        }
        if cancelled() { return nil }
        let week = period(PortfolioPeriodChange.days)
        if cancelled() { return nil }
        let weekUsdt = PortfolioPeriodChange.gain(trades: input.trades, current: input.current, closes: input.closes,
                                                  baseDay: today - PortfolioPeriodChange.days,
                                                  addresses: input.addresses, method: input.method)
        if cancelled() { return nil }
        let year = period(PortfolioPeriodChange.yearDays)
        return PortfolioPeriodResult(week: week, weekUsdt: weekUsdt, year: year)
    }
}
