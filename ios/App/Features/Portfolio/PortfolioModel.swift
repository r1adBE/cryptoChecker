import SwiftUI

/// Kurse, Devisenkurs und Coin-Liste für Portfolio-Tab, Detailansicht und
/// Erfassen-Blatt (auch aus der Merkliste) — wie `PortfolioViewModel.kt`.
/// Die Transaktionen selbst liegen in `AppData.portfolio`.
/// Kurse werden erst geladen, wenn eine Ansicht `refresh` aufruft — das Blatt
/// allein holt nur den Kurs des gewählten Coins.
@MainActor
final class PortfolioModel: ObservableObject {
    static let shared = PortfolioModel()

    @Published var prices = PortfolioPrices()
    /// Kurse werden gerade geladen (Kreisel oben rechts).
    @Published var refreshing = false
    /// USD → `fxCurrency`; nil = unbekannt (Umrechnungszeile ausblenden).
    @Published var fxRate: Double?
    @Published var fxCurrency = ""
    /// Wählbare Coins für die Suche (leer, solange nicht geladen).
    @Published private(set) var coins: [String] = []
    /// Kursveränderung je Coin (gleiche %-Basis wie «heute», aus dem letzten Portfolio-Stand) für
    /// «Grösste Bewegungen»; leer = noch keine Vergleichsbasis.
    @Published var coinChanges: [String: Double] = PortfolioModel.storedCoinChanges()
    /// MEIN Gewinn/Verlust je Coin in USDT über dieselbe %-Basis (`PortfolioWidgetSnapshot.coinPnl`,
    /// `PortfolioPeriodChange.todayByCoin`) für die Positionszeilen; die Summe ist «Heute» der Gesamtkarte.
    /// Leer = noch keine Vergleichsbasis — wie `PortfolioViewModel.coinPnl` (Android).
    @Published var coinPnl: [String: PortfolioWidgetChange] = PortfolioModel.storedCoinPnl()
    /// «Heute» im Gesamtwert-Kopf: Veränderung (Betrag in USDT, Prozent) über die %-Basis aus dem
    /// letzten Portfolio-Stand; nil = noch keine Vergleichsbasis. `amount` in USDT zurückgerechnet.
    @Published var today: (amount: Double?, percent: Double?)? = nil
    /// Coins, die in «Heute» fehlen (kein Vergleichskurs, `PortfolioWidgetSnapshot.todaySkipped`).
    @Published var todaySkipped: [String] = []
    /// «7 T» im Gesamtwert-Kopf (`PortfolioPeriodChange`): in einer Anzeigewährung mit denselben Tageskursen wie der
    /// Wertverlauf, als Gegenwert zum heutigen Kurs (`PortfolioHistoryFx.forCard`), sonst USDT; nil = unbekannt («—»).
    @Published private(set) var weekChange: PortfolioChange?
    /// «7 T» rein in USDT (ohne Devisenkurs) — eigene Zahl neben der Anzeige in der Währung. Wie Android.
    @Published private(set) var weekChangeUsdt: PortfolioChange?
    /// «1 J» für die Zeile unter dem Gesamtwert wie im Portfolio-Widget (gleiche Regel wie «7 T»);
    /// nil = keine Daten (z. B. Kerzen reichen nicht ein Jahr zurück) — dann entfällt die Zeile.
    @Published private(set) var yearChange: PortfolioChange?
    /// Hinweis an «7 T»/«1 J»: offene Coins, deren Verlauf im letzten Jahr Binance auf den Börsenkurs skaliert
    /// nutzt (`PortfolioExchangeRoute.scaledHint`); leer = keiner — wie `PortfolioViewModel.periodScaled`.
    @Published private(set) var periodScaled: [String] = []

    /// Zeitraum des Wertverlaufs (Chips 7 T / 30 T / 1 J / Seit 1. Kauf) — zuletzt gewählt, nur auf
    /// diesem Gerät (`AppSettings.portfolioHistoryRange`, nicht in der Sicherung).
    @Published var historyRange: PortfolioHistoryRange = .month {
        didSet {
            guard historyRange != oldValue else { return }
            if data.settings.portfolioHistoryRange != historyRange { data.settings.portfolioHistoryRange = historyRange }
            recomputeHistory()
            // Nur der gewählte Zeitraum lädt; «Seit 1. Kauf» braucht evtl. mehr Tage
            Task { await loadHistory() }
        }
    }
    /// Wertverlauf des gewählten Zeitraums; nil, solange dessen Tageskurse noch laden (Platzhalter).
    /// Auch zugeklappt gerechnet — die Zeile zeigt die Änderung.
    @Published private(set) var history: PortfolioHistoryUi?

    /// Geladene Tagesschlusskurse (samt Eröffnung/Hoch/Tief für die Kerzen) und für welche Anfrage.
    var historyCloses: (request: PortfolioHistoryRequest, prices: PortfolioDailyPrices)? {
        didSet { historyClosesStamp &+= 1 }
    }
    /// Zählt jede neue Ladung von `historyCloses` (Schlüssel des `summary`-Zwischenspeichers).
    private var historyClosesStamp = 0
    private var historyTask: Task<Void, Never>?
    /// Devisen-Tageskurse USD → `currency` ab `from` (bis heute); `rates` nil = nicht zu haben.
    var fxSeries: (currency: String, from: LocalDay, rates: [Int: Double]?)?

    var activeRefreshes = 0
    private var loadingCoins = false

    private init() {
        historyRange = AppData.shared.settings.portfolioHistoryRange
    }

    var data: AppData { AppData.shared }

    /// Eingabe aller Berechnungen (Gesamtwert, Zeiträume, Verlauf, Widget, Alarme): echte Transaktionen +
    /// Adress-Guthaben als Einzahlung ohne Kaufpreis (`AddressBalance.withAddresses`) — wie
    /// `PortfolioViewModel.calcTransactions`. Listen, Blatt und Export nutzen `data.portfolio`.
    /// Zwischengespeichert: neu nur, wenn Transaktionen, Adressen oder der UTC-Tag sich ändern (wird in jedem
    /// `body` gelesen; Vergleich ist billiger als neu zusammenführen).
    var calcPortfolio: [PortfolioTx] {
        let portfolio = data.portfolio
        let addresses = data.watchedAddresses
        let day = Int64(PortfolioHistory.epochDay(utcMillis: TimeUtils.nowMillis))
        if let c = calcCache, c.day == day, c.addresses == addresses, c.portfolio == portfolio { return c.result }
        let result = AddressBalance.withAddresses(portfolio, addresses: addresses, todayUtcDay: day)
        calcCache = (portfolio, addresses, day, result)
        return result
    }
    private var calcCache: (portfolio: [PortfolioTx], addresses: [WatchedAddress], day: Int64, result: [PortfolioTx])?

    /// Gesamtrechnung für den Portfolio-Tab (`PortfolioCalculator.summarize`) — zwischengespeichert, neu nur bei
    /// geänderten Transaktionen/Adressen, Kursen oder Einstandsmethode (nicht bei jedem `body`), wie Android
    /// `PortfolioViewModel.summary`.
    var summary: PortfolioSummary {
        let trades = calcPortfolio
        let current = prices.prices
        let method = data.settings.costMethod
        let stamp = historyClosesStamp
        if let c = summaryCache, c.method == method, c.closes == stamp, c.prices == current, c.trades == trades {
            return c.result
        }
        // «Ausgebucht»: Auszahlung ohne Preis zum Tagesschluss ihres Tags (danach aktueller Kurs); ohne geladene
        // Schlusskurse unbekannt → «unvollständig» (`PortfolioSummary.withdrawnGainIncomplete`); Gebühr-Zeilen ebenso
        // — wie Android
        let result = PortfolioCalculator.summarize(trades, prices: current, method: method,
                                                   withdrawalPrice: feePrice)
        summaryCache = (trades, current, method, stamp, result)
        return result
    }
    private var summaryCache: (trades: [PortfolioTx], prices: [String: Double], method: CostMethod, closes: Int,
                               result: PortfolioSummary)?

    /// Marktkurs eines Coins zu einem Zeitpunkt (Tagesschluss seines Tags, danach aktueller Kurs) wie in `summary` —
    /// auch für die Gebühr-Zeilen der Coin-Ansicht (`PortfolioCalculator.ledger` `feePrice`), damit sie dieselbe Zahl
    /// zeigt. Wie `PortfolioViewModel.feePrice` (Android).
    var feePrice: (String, Int64) -> Double? {
        let current = prices.prices
        let closes = historyCloses.map { routed($0.prices).closes } ?? [:]
        return { coin, time in
            PortfolioPeriodChange.marketPriceAt(coin, live: current[PortfolioCalculator.normalizeCoin(coin)],
                                                closes: closes, time: time)
        }
    }

    /// Beobachtete Adress-Guthaben (`AddressBalanceHistory.of`) für Heute / 7 T / 1 J und den Wertverlauf: zählen ab
    /// ihrer ersten Beobachtung, nie rückwirkend — wie `PortfolioViewModel.addressHistories`.
    var addressHistories: [AddressBalanceHistory] {
        AddressBalanceHistory.of(data.watchedAddresses)
    }

    /// Veränderungen je Coin aus dem gespeicherten Stand (nur, wenn er zur %-Basis und zum Tag passt).
    nonisolated private static func storedCoinChanges() -> [String: Double] {
        let basis = SharedStorage.loadSettings().changeBasis.storage
        return PortfolioWidgetStore.load()?.shown(basis: basis, now: TimeUtils.nowMillis).coinChanges ?? [:]
    }

    /// Gewinn/Verlust je Coin aus dem gespeicherten Stand (nur, wenn er zur %-Basis und zum Tag passt).
    nonisolated private static func storedCoinPnl() -> [String: PortfolioWidgetChange] {
        let basis = SharedStorage.loadSettings().changeBasis.storage
        return PortfolioWidgetStore.load()?.shown(basis: basis, now: TimeUtils.nowMillis).coinPnl ?? [:]
    }

    /// Ab diesem Alter gelten die Portfolio-Kurse als veraltet (`PortfolioPriceFallback.staleAfterMillis`).
    var staleAfterMillis: Int64 {
        PortfolioPriceFallback.staleAfterMillis(backgroundIntervalMinutes: data.settings.backgroundIntervalMinutes)
    }

    /// «Heute» aus dem Portfolio-Stand: Prozent wie gerechnet, Betrag aus der Anzeigewährung des
    /// Stands zurück in USDT (wie der Gesamtwert); leeres Portfolio → nil.
    static func todayChange(_ widget: PortfolioWidgetSnapshot, rate: Double?) -> (amount: Double?, percent: Double?)? {
        guard !widget.empty, let percent = widget.changePercent else { return nil }
        let factor: Double? = widget.currency == "USD" ? 1 : rate.flatMap { $0 > 0 ? $0 : nil }
        let amount = widget.changeAmount.flatMap { a in factor.map { a / $0 } }
        return (amount: amount, percent: percent)
    }

    /// «7 T» und «1 J» aus den ohnehin geladenen Tagesschlusskursen — jede Ladung reicht mindestens
    /// `PortfolioHistory.maxDays` Tage zurück, egal welcher Zeitraum gewählt ist. Ladung ohne alle
    /// heutigen Coins → nil («—»). Wie `PortfolioViewModel.weekChange`/`yearChange` (Android).
    private func recomputeWeekChange() {
        // Bestände nach derselben Einstandsmethode wie die Karte (FIFO je Wallet: je Wallet gekappt)
        let method = data.settings.costMethod
        let summary = PortfolioCalculator.summarize(calcPortfolio, prices: prices.prices, method: method)
        var holdings: [String: Double] = [:]
        var current: [String: Double] = [:]
        for p in summary.open {
            holdings[p.coin] = p.holdings
            if let price = p.currentPrice { current[p.coin] = price }
        }
        let today = LocalDay.today().epochDay
        if let loaded = historyCloses {
            periodScaled = PortfolioExchangeRoute.scaledHint(scaled: routed(loaded.prices).scaled,
                                                             binanceBefore: loaded.prices.binanceBefore,
                                                             fromDay: today - PortfolioPeriodChange.yearDays,
                                                             coins: Array(holdings.keys))
        } else {
            periodScaled = []
        }
        guard let loaded = historyCloses, !holdings.isEmpty,
              loaded.request.coins.isSuperset(of: holdings.keys.map { PortfolioCalculator.normalizeCoin($0) })
        else {
            weekChange = nil
            weekChangeUsdt = nil
            yearChange = nil
            return
        }
        // «Gewinn 7 T» / «1 J»: Käufe und Verkäufe im Zeitraum herausgerechnet (`PortfolioPeriodChange.gain`)
        let trades = calcPortfolio
        // Tageskerzen der eingetragenen Börse, sonst Binance skaliert (PortfolioExchangeRoute.historyCloses)
        let scaled = routed(loaded.prices).closes
        let addresses = addressHistories
        // Anzeigewährung: dieselben Tageskurse wie der Wertverlauf, sofern sie bis zum Stichtag zurückreichen — Kopf
        // und Kurve zeigen dieselbe Zahl; sonst wie bisher USDT (× heutiger Kurs in der Karte). Wie Android.
        let currency = data.settings.portfolioCurrency
        let fxNow = PortfolioHistoryFx.convertsUsdtAsUsd(currency) ? rate(for: currency).flatMap { $0 > 0 && $0.isFinite ? $0 : nil } : nil
        func period(_ days: Int) -> PortfolioChange? {
            var fx: ((Int) -> Double)?
            if let fxNow, let known = fxSeries, known.currency == currency,
               known.from <= LocalDay(epochDay: today - days), let rates = known.rates, !rates.isEmpty {
                fx = PortfolioHistoryFx.rateFunction(dailyRates: rates, currentRate: fxNow, todayEpochDay: today)
            }
            let change = PortfolioPeriodChange.gain(trades: trades, current: current, closes: scaled,
                                                    baseDay: today - days, addresses: addresses, method: method, fx: fx)
            guard let change, fx != nil, let fxNow else { return change }
            return PortfolioHistoryFx.forCard(change, currentRate: fxNow)
        }
        weekChange = period(PortfolioPeriodChange.days)
        weekChangeUsdt = PortfolioPeriodChange.gain(trades: trades, current: current, closes: scaled,
                                                    baseDay: today - PortfolioPeriodChange.days,
                                                    addresses: addresses, method: method)
        yearChange = period(PortfolioPeriodChange.yearDays)
    }

    /// Reihen für Verlauf, «7 T» und «1 J» zu den aktuellen Kursen (`PortfolioExchangeRoute.historyCloses`):
    /// eigene Börse, sonst Binance skaliert — ohne Binance-Bezugskurs jetzt fehlt der Coin («ohne …»).
    private func routed(_ daily: PortfolioDailyPrices) -> RoutedHistory {
        PortfolioExchangeRoute.historyCloses(daily.closes, ranges: daily.ranges, own: daily.own,
                                             routed: daily.routed.union(prices.sources.keys),
                                             failed: Set(prices.exchangeFailed.keys),
                                             prices: prices.prices, binance: prices.binance)
    }

    /// Verlauf neu rechnen (Transaktionen, Kurse, Währung oder Zeitraum geändert) —
    /// abseits des Hauptthreads; der bisherige Verlauf bleibt stehen, bis der neue fertig ist.
    func recomputeHistory() {
        recomputeWeekChange()
        let txs = calcPortfolio
        let addresses = addressHistories
        guard let loaded = historyCloses, loaded.request.covers(historyRequest()) else {
            historyTask?.cancel()
            history = nil
            return
        }
        let currency = data.settings.portfolioCurrency
        let fx = rate(for: currency)
        let converted = currency != "USD" && fx != nil
        // Devisen-Tageskurse dieses Zeitraums noch nicht da: Platzhalter statt still falscher Kurve
        let dailyRates: [Int: Double]?
        if converted {
            guard let known = fxSeries, known.currency == currency, known.from <= fxSeriesFrom() else {
                historyTask?.cancel()
                history = nil
                return
            }
            dailyRates = known.rates
        } else {
            dailyRates = nil
        }
        let fxFactor: Double = fx ?? 1
        let unit = converted ? currency : PortfolioFormat.usdt
        let range = historyRange
        let live = prices.prices
        // Eigene Börse, sonst Binance skaliert — Dochte und Eröffnung mit demselben Faktor wie die Schlüsse
        let routedHistory = routed(loaded.prices)
        let closes = routedHistory.closes
        let ranges = routedHistory.ranges
        let scaledCoins = routedHistory.scaled
        let binanceBefore = loaded.prices.binanceBefore
        let method = data.settings.costMethod
        historyTask?.cancel()
        historyTask = Task { [weak self] in
            let ui = await Task.detached(priority: .userInitiated) { () -> PortfolioHistoryUi in
                let today = LocalDay.today().epochDay
                let series = PortfolioHistory.build(trades: txs, closes: closes, livePrices: live, range: range,
                                                    todayEpochDay: today,
                                                    dayEndMillis: { PortfolioHistory.dayEndMillis($0) },
                                                    ranges: ranges, addresses: addresses, method: method)
                let scaled = series.points.first.map { first in
                    PortfolioExchangeRoute.scaledHint(scaled: scaledCoins, binanceBefore: binanceBefore,
                                                      fromDay: first.epochDay,
                                                      coins: txs.flatMap(\.coins).map { PortfolioCalculator.normalizeCoin($0) }
                                                          .filter { !series.skipped.contains($0) })
                } ?? []
                guard converted else {
                    return PortfolioHistoryUi(range: range, series: series, unit: unit, converted: false, scaled: scaled)
                }
                // Je Punkt der Kurs seines Tags, heute der aktuelle (`PortfolioHistoryFx`)
                let result = PortfolioHistoryFx.convert(series, dailyRates: dailyRates, currentRate: fxFactor,
                                                        todayEpochDay: today)
                // Vergleich mit USDT nur mit Tageskursen — mit dem heutigen Kurs wäre er bedeutungslos
                guard !result.approximate else {
                    return PortfolioHistoryUi(range: range, series: result.series, unit: unit, converted: true,
                                              approximateFx: true, scaled: scaled)
                }
                return PortfolioHistoryUi(range: range, series: result.series, unit: unit, converted: true,
                                          approximateFx: false, usdtSeries: series,
                                          compare: PortfolioCompare.build(currency: result.series.points,
                                                                          usdt: series.points),
                                          scaled: scaled)
            }.value
            guard !Task.isCancelled else { return }
            self?.history = ui
        }
    }

    /// Kurs für die Umrechnungszeile — nur, wenn er zur gewählten Währung gehört.
    func rate(for currency: String) -> Double? {
        fxCurrency == currency ? fxRate : nil
    }

    func loadCoins() async {
        guard coins.isEmpty, !loadingCoins else { return }
        loadingCoins = true
        defer { loadingCoins = false }
        coins = await PortfolioPriceSource.coins(fallback: { await portfolioPairCacheCoins() })
    }

    /// Aktueller USDT-Kurs zum Vorbelegen; nil, wenn keiner zu haben ist.
    func currentPrice(_ coin: String) async -> Double? {
        await PortfolioPriceSource.price(coin)
    }
}

/// Paarliste der Börse «Binance» aus der App (gespeichert oder mitgeliefert):
/// Spot-Paare gegen USDT — Ausweichquelle der Coin-Liste.
private func portfolioPairCacheCoins() async -> [String]? {
    let info = await PairCache.shared.pairs(for: "Binance")
    let list = info.pairs
        .filter { $0.quote == PortfolioPriceSource.stable && $0.contractType == .none }
        .map { $0.base.uppercased() }
        .filter { PortfolioPriceSource.isSymbol($0) }
    return list.isEmpty ? nil : list
}
