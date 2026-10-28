import SwiftUI

// Umrechnung in die Anzeigewährung im Portfolio-Modell: Devisenkurs, Faktor USDT → Währung, «Seit Kauf» zum Kurs der
// Erwerbstage und der Merkzettel fürs Widget. Eigene Datei neben `PortfolioModel.swift` (Kurse, Zeiträume, Verlauf).

extension PortfolioModel {
    /// Kurs für die Umrechnungszeile — nur, wenn er zur gewählten Währung gehört.
    func rate(for currency: String) -> Double? {
        fxCurrency == currency ? fxRate : nil
    }

    /// Faktor USDT → `currency` für Gesamtkarte und Zeilen: Devisenkurs × USDT/USD (`PortfolioStables.usdtTo`, USDT zum
    /// Marktkurs; ohne frische Messung × 1). nil = kein Devisenkurs. Wie Android `PortfolioViewModel.displayFxRate`.
    func displayRate(for currency: String) -> Double? {
        PortfolioStables.usdtTo(rate(for: currency), usdt: PortfolioStables.usdtUsdLive(DepegAlarmCheck.liveUsdtUsd()))
    }

    /// «Seit Kauf» und Einstand der Gesamtkarte in `currency` zum Kurs der Erwerbstage (`PortfolioCostFx`): Wert heute ×
    /// `displayRate` − Einstand je Teil zum Faktor seines Tags (Regel des Steuer-Exports). nil = nicht umgerechnet (USDT,
    /// kein Devisenkurs) oder die Kurse laden noch — die Karte zeigt dann in der Anzeigewährung «—» statt einer Zahl zum
    /// heutigen Kurs. Wie Android `PortfolioViewModel.costFx`.
    /// `summary`: in `body` die gezeigte Rechnung (`shownSummary`), sonst die genaue (`summary`).
    func costFx(for currency: String, summary shown: PortfolioSummary? = nil) -> PortfolioCostFxFigures? {
        let rate = displayRate(for: currency)
        guard PortfolioCardUnit(currency: currency, fxRate: rate).converted, let rate,
              let current = PortfolioDecimal.of(rate) else { return nil }
        let summary = shown ?? self.summary
        // Gesamtsicht noch ohne Herkunftsteile (`PortfolioCostFx.partsPending`): «—»
        if PortfolioCostFx.partsPending(summary) { return nil }
        let first = PortfolioCostFx.firstDay(summary)
        // Ohne Teile mit Datum keine Kurse nötig; sonst nur mit einer Ladung, die den frühesten Tag abdeckt
        let usable = costFxLoad.flatMap { load -> (rates: [Int: Decimal]?, usdtUsd: [Int: Decimal])? in
            guard load.currency == currency, first.map({ load.from.epochDay <= $0 }) ?? true else { return nil }
            return (load.rates, load.usdtUsd)
        }
        if first != nil && usable == nil { return nil }
        return PortfolioCostFx.figures(summary, currency: currency, currentRate: current,
                                       // Heute als UTC-Tag wie die Erwerbstage (`PortfolioCostFx.dayOf`)
                                       todayEpochDay: PortfolioHistory.utcToday(),
                                       rates: usable?.rates, usdtUsd: usable?.usdtUsd ?? [:])
    }

    /// Merkzettel fürs Portfolio-Widget (`PortfolioDisplayFx`): Einstand je Coin zum Kurs der Erwerbstage (`costFx`) und
    /// die Tagesfaktoren der letzten Tage wie «7 T» der Karte — damit jeder Stand, auch aus dem Hintergrund und dem Widget,
    /// «Seit Kauf» und «7 T» in der Anzeigewährung wie die Karte zeigt statt USDT × heutiger Kurs. nil in USDT bzw.
    /// solange die Kurse laden. Ohne den Live-Kurs USDT/USD (gilt erst ab heute, kommt aus dem Stand). Wie Android
    /// `PortfolioViewModel.widgetDisplayFx`.
    /// `summary`: die eben im Hintergrund gerechnete (`currentSummary`) — ohne sie rechnete `summary` synchron auf dem
    /// Hauptthread, falls der Zwischenspeicher inzwischen nicht mehr passt.
    func widgetDisplayFx(for currency: String, summary shown: PortfolioSummary? = nil) -> PortfolioDisplayFx? {
        let summary = shown ?? self.summary
        guard let figures = costFx(for: currency, summary: summary) else { return nil }
        let today = PortfolioHistory.utcToday()
        let from = today - PortfolioDisplayFigures.memoDays
        let fxNow = PortfolioHistoryFx.convertsUsdt(currency, usdtMarket: true)
            ? rate(for: currency).flatMap { $0 > 0 && $0.isFinite ? $0 : nil } : nil
        // Wie «7 T» der Karte: Devisen-Tageskurse, die bis zum ersten gemerkten Tag zurückreichen (USD: an jedem Tag 1)
        var rates: [Int: Double]?
        if !PortfolioHistoryFx.convertsUsdtAsUsd(currency) {
            rates = PortfolioHistoryFx.usdParity(fromEpochDay: from - PortfolioHistoryFx.leadDays, toEpochDay: today)
        } else if let known = fxSeries, known.currency == currency, known.from <= LocalDay(epochDay: from),
                  let r = known.rates, !r.isEmpty {
            rates = r
        }
        var dayFactor: ((Int) -> Double)?
        if let fxNow, let rates, let usdtDaily = historyCloses?.prices.usdtUsd {
            dayFactor = PortfolioHistoryFx.rateFunction(dailyRates: rates, currentRate: fxNow, todayEpochDay: today,
                                                        usdt: UsdtUsdRates(live: nil, daily: usdtDaily))
        }
        return PortfolioDisplayFigures.memo(currency: currency, coinCosts: figures.coinCosts, open: summary.open,
                                            dayFactor: dayFactor, todayEpochDay: today)
    }

    /// Merkzettel fürs Widget, sobald seine Kurse da sind: hat er sich gegenüber dem gespeicherten Stand geändert, den
    /// Stand mit denselben Kursen neu rechnen (wie Android: der Merkzettel gehört zur Eingabe der Aufnahme).
    func refreshWidgetDisplayFx() async {
        // Gesamtrechnung im Hintergrund abwarten und genau diese übergeben (nichts mehr synchron auf dem Hauptthread)
        let summary = await currentSummary()
        let currency = data.settings.portfolioCurrency
        guard let memo = widgetDisplayFx(for: currency, summary: summary), PortfolioWidgetStore.load()?.displayFx != memo,
              !calcPortfolio.isEmpty, prices.updatedAt > 0,
              PortfolioPriceFallback.mayRecord(transactions: calcPortfolio, prices: prices, now: EpochTime.nowMillis,
                                               staleAfterMillis: staleAfterMillis)
        else { return }
        await PortfolioWidgetStore.updateInBackground(transactions: calcPortfolio, prices: prices, currency: currency,
                                                      rate: rate(for: currency), displayFx: memo)
    }
}
