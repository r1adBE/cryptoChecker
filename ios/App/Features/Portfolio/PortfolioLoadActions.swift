import SwiftUI

// Laden im Portfolio (Teil von `PortfolioModel`): Guthaben der beobachteten Adressen, Kurse, gemerktes «Heute»,
// Devisenkurs und Devisen-Reihe, Tagesschlüsse des Wertverlaufs.
// Eigene Datei, weil die Abrufe von den abgeleiteten Werten der Klasse getrennt lesbar sein sollen
// (wie `PortfolioLoadActions.kt`).

extension PortfolioModel {
    /// Guthaben der beobachteten Adressen: nur fällige (10 min, manuell 60 s); ohne Adressen keine Anfrage.
    /// Eine Runde zur Zeit (`AddressRefreshGate`): ein zweiter Aufruf im Vordergrund wartet und liest danach frisch.
    /// - Parameter budgetMillis: Hintergrund: höchstens so lange insgesamt (`WatchedAddressSource.refresh`); läuft
    ///   schon eine Runde, sofort zurück.
    func refreshWatchedAddresses(force: Bool, budgetMillis: Int64? = nil) async {
        await addressGate.run(background: budgetMillis != nil) { [weak self] in
            await self?.loadWatchedAddresses(force: force, budgetMillis: budgetMillis)
        }
    }

    /// Eine Runde: Liste frisch lesen, fällige laden, Ergebnis speichern.
    private func loadWatchedAddresses(force: Bool, budgetMillis: Int64?) async {
        // Server unbekannt (Einstellungen ersetzt): Bitcoin gar nicht fragen, bis bestätigt (nie still öffentlich)
        let unknown = StorageRecoveryNotice.addressServerUnknown()
        let list = data.watchedAddresses.filter { AddressServer.queryAllowed($0.chain, serverUnknown: unknown) }
        guard !list.isEmpty else { return }
        // Eigener Bitcoin-Server aus den Einstellungen: Bitcoin dann nur dort (`AddressServer`)
        guard let updates = try? await WatchedAddressSource.shared.refresh(list, force: force, budgetMillis: budgetMillis,
                                                                           btcServer: data.settings.addressServerBtc)
        else { return }
        data.applyWatchedAddressUpdates(updates)
    }

    /// Beim Öffnen (60 s Zwischenspeicher), bei neuen Coins bzw. per Ziehen oder Menü (`force`). Die Abrufe laufen
    /// gleichzeitig (Adressen, Verlauf, Devisenkurs, Kurse, «Seit Kauf») — Werte erscheinen, sobald ihr Teil da ist.
    /// Dauert es länger als 2 s, ein dünner Balken (`loadProgress`, `LoadProgressBar`); beim Ziehen kurz der Kreisel. Wie Android.
    func refresh(force: Bool) async {
        let owner = activeRefreshes == 0
        activeRefreshes += 1
        refreshing = true
        if owner {
            loadSteps = LoadProgress(total: Self.refreshSteps)
            loadProgress = loadSteps.current
        }
        defer {
            activeRefreshes -= 1
            refreshing = activeRefreshes > 0
            if !refreshing { loadProgress = nil }
        }
        // USDT/USD messen (höchstens alle 5 Minuten, parallel zu den Kursen): Live-Kurs der Bewertung; danach Verlauf und
        // «7 T»/«1 J» mit dem neuen Kurs (wie die Flows auf Android)
        let settings = data.settings
        let hasPortfolio = data.hasPortfolioData
        Task { [weak self] in
            await DepegAlarmCheck.runIfDue(settings: settings, hasPortfolioData: hasPortfolio, stables: { await StableDepegPlanner.plan() })
            self?.recomputeHistory()
        }
        let currency = data.settings.portfolioCurrency
        let symbols = Array(Set(calcPortfolio.flatMap(\.coins)))
        // Gleichzeitig, keiner wartet auf den anderen. Bringen die Adressen neue Coins, lädt `.task(id: calcCoins)`
        // deren Kurse nach. Verlauf: Tageskerzen je Coin 12 h zwischengespeichert, Fehlgeschlagenes neu.
        async let addresses: Void = stepAddresses(force: force)
        async let history: Void = stepHistory(load: !symbols.isEmpty)
        // Kein Kurs (auch kein höchstens 24 h alter): keine Umrechnung statt eines toten Kurses
        async let fetchedRate = stepRate(currency)
        if !symbols.isEmpty {
            // Erst Zwischenspeicher und gemerkter Stand (ohne Netz, nach einem Neustart), damit sofort
            // etwas dasteht (auch für neue Coins); dann frisch, wo nötig der gemerkte Stand je Coin
            prices = await PortfolioPriceSource.stored(symbols)
            // Eingetragene Börsen zu Paaren auflösen (App Group), dann frisch — mit dem Kurs dieser Börsen
            await PortfolioRouteResolver.resolve(data.portfolio)
            _ = await PortfolioPriceSource.prices(symbols, force: force)
            prices = await PortfolioPriceSource.stored(symbols)
        }
        markLoadStep()
        let rate = await fetchedRate
        if data.settings.portfolioCurrency == currency {
            fxRate = rate
            fxCurrency = currency
        }
        recomputeHistory()
        // «Seit Kauf»/Einstand in der Anzeigewährung: Kurse ab dem frühesten Erwerbstag (fehlten sie, erneut versuchen);
        // parallel, damit Widget und «Heute» nicht auf die Abfrage warten
        async let costFx: Void = stepCostFx()
        // Widget und «Heute» mit den frischen Adress-Guthaben
        await addresses
        // Portfolio-Widget: Stand mit denselben Kursen (zeichnet nur bei Änderung neu)
        // Gemerkter, veralteter Stand (ohne Netz, nach einem Neustart): nicht als «jetzt» aufnehmen
        // (Widget, Verlauf, Alarme blieben sonst mit alten Kursen stehen); «Heute» stattdessen aus dem
        // letzten Portfolio-Stand — zum Stand der Kurse. Wie Android `PortfolioViewModel`.
        // Frische je Coin: ein einzelner alter Coin hält die übrigen nicht an, solange mindestens 80 % des
        // Werts frisch sind (`PortfolioPriceFallback.mayRecordShare`)
        // Gesamtrechnung im Hintergrund (läuft schon für «7 T»); der Merkzettel des Widgets nimmt genau diese
        let summary = await currentSummary()
        let recordable = calcPortfolio.isEmpty
            || PortfolioPriceFallback.mayRecord(transactions: calcPortfolio, prices: prices,
                                                now: EpochTime.nowMillis, staleAfterMillis: staleAfterMillis)
        if data.settings.portfolioCurrency == currency && !recordable {
            if today == nil { today = storedToday(rate: self.rate(for: currency)) }
        } else if data.settings.portfolioCurrency == currency {
            // Mit dem Merkzettel der App: «Seit Kauf»/«7 T» des Widgets in der Anzeigewährung wie die Karte. Gerechnet
            // im Hintergrund, nacheinander mit den übrigen Aufnahmen — eine neuere gewinnt
            // Vergleichskurse für «Heute» und 24h: Mini-Charts und fehlende Stundenkerzen (je Coin höchstens stündlich)
            let hourly = await PortfolioWidgetStore.tabHourly(transactions: calcPortfolio, prices: prices)
            let widget = await PortfolioWidgetStore.updateInBackground(
                transactions: calcPortfolio, prices: prices, currency: currency, rate: self.rate(for: currency),
                extraHourly: hourly, displayFx: widgetDisplayFx(for: currency, summary: summary))
            // Währung inzwischen gewechselt: Zeilen nicht mit Zahlen der alten füllen
            if let widget, data.settings.portfolioCurrency == currency {
                coinChanges = widget.coinChanges ?? [:]
                coinPnl = widget.empty ? [:] : (widget.coinPnl ?? [:])
                // «Gewinn heute» kommt schon berichtigt aus dem Stand — dieselbe Zahl wie Widget und Alarm
                today = Self.todayChange(widget, rate: self.rate(for: currency))
                todaySkipped = widget.todaySkipped ?? []
            }
            // Portfolio-Alarme mit dem neuen Stand prüfen
            await data.evaluatePortfolioAlarms(widget)
        }
        await history
        await costFx
    }

    /// Gleichzeitige Schritte in `refresh` (für den Balken).
    static let refreshSteps = 5

    /// Ein Schritt von `refresh` fertig: Balken vor (sichtbar erst nach 2 s, `LoadProgressBar`).
    func markLoadStep() {
        let shown = loadSteps.step()
        if loadProgress != nil { loadProgress = shown }
    }

    private func stepAddresses(force: Bool) async {
        await refreshWatchedAddresses(force: force)
        markLoadStep()
    }

    private func stepHistory(load: Bool) async {
        if load { await loadHistory() }
        markLoadStep()
    }

    private func stepRate(_ currency: String) async -> Double? {
        let rate = await FxRateSource.usdTo(currency)
        markLoadStep()
        return rate
    }

    private func stepCostFx() async {
        await loadCostFx()
        markLoadStep()
    }

    /// «Heute» aus dem zuletzt gespeicherten Portfolio-Stand, wenn er zur eingestellten %-Basis passt
    /// (Tagesbeginn: gerechnet für den Tag der Aufnahme — die Karte steht dann mit «Stand …» da).
    private func storedToday(rate: Double?) -> (amount: Double?, percent: Double?)? {
        guard let stored = PortfolioWidgetStore.load(), !stored.empty, stored.updatedAt > 0,
              ChangeBasisMath.isCurrent(stamp: stored.stamp, basis: data.settings.changeBasis.storage, now: stored.updatedAt)
        else { return nil }
        return Self.todayChange(stored, rate: rate)
    }

    /// Zielwährung gewechselt: zuerst der bekannte Kurs, dann frisch abfragen.
    func loadFx(_ currency: String) async {
        if fxCurrency != currency {
            fxRate = FxRateSource.cached(currency)
            fxCurrency = currency
        }
        let rate = await FxRateSource.usdTo(currency)
        if fxCurrency == currency { fxRate = rate }
        // Für das Portfolio-Widget merken, auch wenn gerade kein neuer Stand gelingt («Umrechnung» statt USDT)
        PortfolioWidgetStore.rememberFx(currency: currency, rate: rate)
        recomputeHistory()
        await loadFxSeries()
        await loadCostFx()
    }

    /// Beginn der Devisenkurs-Abfrage für den gewählten Zeitraum (`PortfolioHistoryFx.requestRange`), mindestens aber für
    /// «1 J» der Karte (`PortfolioPeriodChange.yearDays`) — sonst rechnete «1 J» in der Anzeigewährung je nach Zeitraum
    /// des Diagramms mit oder ohne Tageskurse (zwei Zahlen für dieselbe Zeile). Wie Android `fxSeriesFrom`.
    func fxSeriesFrom() -> LocalDay {
        let days = historyRange == .sinceFirst ? historyRequest().days : historyRange.days
        return PortfolioHistoryFx.requestRange(days: max(days, PortfolioPeriodChange.yearDays),
                                               today: LocalDay(epochDay: PortfolioHistory.utcToday())).from
    }

    /// Devisen-Tageskurse für den Verlauf: eine Abfrage je Währung und Zeitraum (6 h zwischengespeichert);
    /// schon geladen (gleiche Währung, Zeitraum abgedeckt, bis zum heutigen UTC-Tag) → nichts zu tun. Ohne den letzten
    /// Tag blieben Kurse eines neuen Tags aus, bis die App neu startet.
    func loadFxSeries() async {
        let currency = data.settings.portfolioCurrency
        guard currency != "USD" else { return }
        let from = fxSeriesFrom()
        let utcToday = LocalDay.todayUTC()
        if let known = fxSeries, known.currency == currency, known.from <= from, known.rates != nil,
           !PortfolioHistoryFx.fxLoadOutdated(loadedTo: known.to, utcToday: utcToday) { return }
        let to = PortfolioHistoryFx.fxRequestEnd(localToday: LocalDay.today(), utcToday: utcToday)
        let rates = await HistoricPriceSource.usdToSeries(currency, from: from, to: to)
        guard data.settings.portfolioCurrency == currency, from <= fxSeriesFrom() else { return }
        fxSeries = (currency: currency, from: from, rates: rates.flatMap { $0.isEmpty ? nil : $0 }, to: to)
        recomputeHistory()
        await refreshWidgetDisplayFx()
    }

    /// Kurse für «Seit Kauf»/Einstand in der Anzeigewährung ab dem frühesten Erwerbstag der offenen Posten
    /// (`costFxLoad`): dieselben Quellen wie der Steuer-Export (Devisen-Tageskurse ab `PortfolioHistoryFx.leadDays` Tagen
    /// davor, USDT/USD-Tagesschlüsse). USDT braucht keine; schon geladen (gleiche Währung, Zeitraum abgedeckt, bis zum
    /// heutigen UTC-Tag) → nichts zu tun. Wie Android `loadCostFx`.
    func loadCostFx() async {
        let currency = data.settings.portfolioCurrency
        guard currency != PortfolioFormat.usdt, let first = PortfolioCostFx.firstDay(await currentSummary()) else { return }
        let from = LocalDay(epochDay: first)
        let utcToday = LocalDay.todayUTC()
        if let known = costFxLoad, known.currency == currency, known.from <= from, known.rates != nil,
           !PortfolioHistoryFx.fxLoadOutdated(loadedTo: known.to, utcToday: utcToday) { return }
        let to = PortfolioHistoryFx.fxRequestEnd(localToday: LocalDay.today(), utcToday: utcToday)
        var rates: [Int: Decimal]? = [:]
        if currency != "USD" {
            let series = await PortfolioTaxExporter.rates(currency, from: from, to: to)
            rates = series.flatMap { $0.isEmpty ? nil : $0 }
        }
        let usdt = await PortfolioTaxExporter.usdtUsdCloses(currency, from: from) ?? [:]
        // Inzwischen andere Währung gewählt: nicht überschreiben
        guard data.settings.portfolioCurrency == currency else { return }
        costFxLoad = (currency: currency, from: from, rates: rates, usdtUsd: usdt, to: to)
        // Widget: «Seit Kauf» in der Anzeigewährung mit den neuen Kursen
        await refreshWidgetDisplayFx()
    }

    // MARK: Wertverlauf

    /// Welche Tageskurse der gewählte Zeitraum braucht (Coins und Tage bis heute).
    func historyRequest() -> PortfolioHistoryRequest {
        PortfolioHistoryRequest.make(calcPortfolio, range: historyRange)
    }

    /// Tageskurse der aktuellen Coins für den gewählten Zeitraum laden; ein überholter Abruf
    /// (Coins oder Zeitraum inzwischen geändert) ersetzt den neueren nicht.
    func loadHistory() async {
        // Devisen-Tageskurse parallel (bei einer Umrechnungswährung)
        Task { await loadFxSeries() }
        let request = historyRequest()
        let entered = PortfolioRouteResolver.enteredCoins(data.portfolio)
        let prices = await PortfolioHistorySource.dailyPrices(Array(request.coins), days: request.days, entered: entered)
        guard request.covers(historyRequest()) else { return }
        historyCloses = (request: request, prices: prices)
        recomputeHistory()
        // Widget: «7 T» in der Anzeigewährung braucht die USDT/USD-Tagesschlüsse dieser Ladung
        await refreshWidgetDisplayFx()
    }
}
