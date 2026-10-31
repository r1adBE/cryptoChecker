import Foundation

/// Lädt Devisen-Tageskurse (und für FR Tagesschlusskurse) und baut die Dateien der Arbeitsunterlage — wie
/// `PortfolioExportViewModel` (Android).
enum PortfolioTaxExporter {
    /// Devisen-Tageskurse USD → `currency` von `from` bis `to` (höchstens heute); USD = leer; Fehler = nil.
    static func rates(_ currency: String, from: LocalDay, to: LocalDay) async -> [Int: Decimal]? {
        if currency == "USD" { return [:] }
        let today = LocalDay.today()
        let end = to > today ? today : to
        guard let series = await HistoricPriceSource.usdToSeries(currency, from: from.plusDays(-PortfolioHistoryFx.leadDays),
                                                                  to: end) else { return nil }
        var exact: [Int: Decimal] = [:]
        for (day, rate) in series { if let r = PortfolioDecimal.of(rate) { exact[day] = r } }
        return exact
    }

    /// USDT/USD-Tagesschlüsse ab `from` für den Steuer-Export (`TaxExport.rateOn` `usdtUsd`, USDT zum Marktkurs): Tag →
    /// Schluss (Kraken, höchstens 720 Tage; Lücken füllt Coinbase). Ältere Steuerjahre werden nicht seitenweise
    /// nachgeladen (Begründung bei Android `usdtUsdCloses`); Tage ohne Schluss rechnen mit 1 und sind nie still: Vermerk
    /// je Zeile und eine Zeile in der Jahresübersicht (`TaxYearSummary.usdtApproximate`). nil für eine Währung ohne
    /// USDT-Umrechnung (USDT selbst). Wie Android `usdtUsdCloses`.
    static func usdtUsdCloses(_ currency: String, from: LocalDay) async -> [Int: Decimal]? {
        guard CutoffExport.convertsUsdt(currency) else { return nil }
        let days = max(1, LocalDay.today().epochDay - from.epochDay + 1 + PortfolioHistoryFx.leadDays)
        let closes = await PortfolioHistorySource.usdtUsdDaily(days: days)
        var exact: [Int: Decimal] = [:]
        for (day, close) in closes { if let c = PortfolioDecimal.of(close) { exact[day] = c } }
        return exact
    }

    /// Eigene Transaktionen + Eröffnungsposten beobachteter Adressen als Käufe (`AddressCostLots.exportTrades`); FR
    /// dazu der Gesamtwert samt beobachteter Adressen («im Portfolio zählen») — ihre Guthaben-Änderungen als Adress-Einträge
    /// (`AddressBalanceHistory.trades`, negative Id: keine Veräusserungen) ohne den belegten Teil. Wie `withAddressTrades`
    /// (Android).
    static func withAddressTrades(_ transactions: [PortfolioTx], method: TaxMethod) -> [PortfolioTx] {
        AddressCostLots.exportTrades(transactions, addresses: WatchedAddressStore.load().addresses, frTotalValue: method == .FR_PTA)
    }

    /// Tageskurse der Vorlage: FR `frPrices`, Netzwerkgebühren als Veräusserung (`TaxTemplate.networkFeeIsDisposal`: UK,
    /// US, CA, AU, JP) `networkFeePrices`; dazu für alle Vorlagen die Gebühr-Zeilen, Verschenkt/bezahlt ohne Marktwert und
    /// Tausch ohne Wert zum Tageskurs (`feeDisposalPrices`) — wie `taxPrices` (Android). `until`: letzter Tag mit Bedarf
    /// (`TaxExport.priceUntil`; UK/CA 30 Tage nach dem Steuerjahr — ein Zugang dort kann eine Veräusserung des Jahres decken).
    static func taxPrices(_ template: TaxTemplate, _ method: TaxMethod, _ transactions: [PortfolioTx],
                          period: TaxPeriod, until: LocalDay? = nil) async -> ((String, Int64) -> Decimal?)? {
        let zone = template.zone()
        let base: ((String, Int64) -> Decimal?)?
        if method == .FR_PTA {
            base = await frPrices(transactions, period: period, zone: zone, until: until)
        } else if template.networkFeeIsDisposal {
            base = await networkFeePrices(transactions, period: period, zone: zone, until: until)
        } else {
            base = nil
        }
        guard let fees = await feeDisposalPrices(transactions, period: period, zone: zone, until: until) else { return base }
        return { coin, time in fees(coin, time) ?? base?(coin, time) }
    }

    /// Gebühr-Zeilen (`PortfolioLedger.isFeeDisposal`, CSV «Fee»), Verschenkt/bezahlt ohne Marktwert, Tausch ohne Wert,
    /// Belohnungen ohne Wert und Gebühren im dritten Coin ohne Preis (`PortfolioLedger.dayCloseNeeds`) bis Ende `until` (Standard: Ende `period`): zum Tageskurs — der Coin zum Tagesschluss SEINES UTC-Tags
    /// (`TaxDayClose`, gleicher Kurs wie Karte und Wertverlauf); nur für genau diese (Coin, Zeit), sonst nil. Ohne solche
    /// nil. Wie Android.
    static func feeDisposalPrices(_ transactions: [PortfolioTx], period: TaxPeriod,
                                  zone: TimeZone = .current, until: LocalDay? = nil) async -> ((String, Int64) -> Decimal?)? {
        let end = endMillis(period, until: until, zone: zone)
        var keys = Set<String>()
        var wanted: [String: Set<Int>] = [:]
        let own = transactions.filter { $0.time <= end && !AddressBalance.isAddressTrade($0.id) }
        for need in PortfolioLedger.dayCloseNeeds(own) {
            keys.insert("\(need.coin)|\(need.time)")
            if PortfolioStables.needsQuote(need.coin) {
                wanted[need.coin, default: []].insert(TaxDayClose.day(need.time))
            }
        }
        if keys.isEmpty { return nil }
        let closes = await dailyCloses(wanted)
        let found = keys
        return { coin, time in
            guard found.contains("\(coin)|\(time)") else { return nil }
            if !PortfolioStables.needsQuote(coin) { return 1 }
            return closes(coin, time)
        }
    }

    /// Marktwert der Netzwerkgebühren in Krypto (eigene Veräusserung, `TaxEngines.applyRules`) bis Ende `period`: Gebühr
    /// im Coin bzw. im dritten Coin ohne erfassten Gebühr-Preis einer Umbuchung, Auszahlung oder Schenkung
    /// (`TaxDayClose.networkFeeNeeds`) — der Coin zum Tagesschluss seines UTC-Tags wie jede Gebühr-Zeile derselben
    /// Unterlage. Bis Ende `until` wie `feeDisposalPrices`. Wie `networkFeePrices` (Android).
    static func networkFeePrices(_ transactions: [PortfolioTx], period: TaxPeriod,
                                 zone: TimeZone = .current, until: LocalDay? = nil) async -> (String, Int64) -> Decimal? {
        let end = endMillis(period, until: until, zone: zone)
        return await dailyCloses(TaxDayClose.networkFeeNeeds(transactions, end: end))
    }

    /// Ende (ms) des letzten Tags mit Kursbedarf: `until`, nie vor dem Ende von `period`.
    private static func endMillis(_ period: TaxPeriod, until: LocalDay?, zone: TimeZone) -> Int64 {
        let last = max(until ?? period.end, period.end)
        return CutoffDay(year: last.year, month: last.month, day: last.day).endOfDayMillis(in: zone)
    }

    /// FR: Kurse für den Gesamtwert des Portfolios vor jeder Abtretung bis Ende `period` — je Verkauf, Auszahlung und
    /// Gebühr in Krypto die gehaltenen Coins (ohne den verkauften, ohne Stablecoins) zum Tagesschluss des UTC-Tags der
    /// Abtretung (`TaxDayClose`, dieselbe Regel wie die Gebühr-Zeilen). Wie `frPrices` (Android).
    static func frPrices(_ transactions: [PortfolioTx], period: TaxPeriod,
                         zone: TimeZone = .current, until: LocalDay? = nil) async -> (String, Int64) -> Decimal? {
        let end = endMillis(period, until: until, zone: zone)
        var wanted: [String: Set<Int>] = [:]
        let own = transactions.filter { !AddressBalance.isAddressTrade($0.id) }
        // Beobachtete Adressen zählen im Gesamtwert mit (`TaxEngines` FR): ihre Coins brauchen ebenfalls Kurse
        let addressTrades = transactions.filter { AddressBalance.isAddressTrade($0.id) }
        // Verkäufe, Verschenkt/bezahlt, Auszahlungen (Abgang ohne Verkauf kürzt die PTA anteilig, `TaxEngines.outflows`)
        // und Gebühren in Krypto
        for t in own where t.time <= end {
            let feeInCrypto: Bool = t.thirdFeeCoin != nil || ((t.type == .TRANSFER || t.type == .SWAP) && t.coinFeeExact > 0)
            guard t.type.isSaleLike || t.type.isWithdrawalLike || feeInCrypto else { continue }
            let day = TaxDayClose.day(t.time)
            // Verkauf: Coin zum Verkaufspreis; sonst ohne erfassten Wert: auch der Coin selbst braucht einen Kurs
            let sold: String? = (t.type.isSaleLike || t.validPriceExact != nil) ? PortfolioCalculator.normalizeCoin(t.coin) : nil
            // Gehalten = in der Reihenfolge der Rechnung davor gebucht (auch dieselbe Millisekunde) — wie Android
            var held = TaxEngines.frHeldBefore(own, t)
            for a in addressTrades where a.time <= t.time { held.insert(PortfolioCalculator.normalizeCoin(a.coin)) }
            for coin in held where coin != sold && PortfolioStables.needsQuote(coin) {
                wanted[coin, default: []].insert(day)
            }
        }
        return await dailyCloses(wanted)
    }

    /// Tagesschlusskurse (USDT) je Coin und Tag (epochDay); Abfrage über (Coin, Zeit) → Schluss des UTC-Tags der Zeit
    /// (`TaxDayClose.lookup`); fehlt = nil. Je Coin alle Tage auf einmal (`HistoricPriceSource.dayClosesUsdt`: Kerzen je
    /// Quelle in Stücken, gleiche Kette und gleicher Schluss je Tag wie `dailyCloseUsdt`), mehrere Coins gleichzeitig.
    private static func dailyCloses(_ wanted: [String: Set<Int>]) async -> (String, Int64) -> Decimal? {
        if wanted.isEmpty { return { _, _ in nil } }
        let coins = wanted.keys.sorted()
        let live = await PortfolioPriceSource.prices(coins)
        let current = CutoffExport.referencePrices(live.prices, uncheckedCurrent: live.unchecked,
                                                   unverifiedCurrent: live.unverified)
        var closes: [String: Decimal] = [:]
        for (coin, byDay) in await HistoricPriceSource.dayClosesUsdt(wanted, current: current) {
            for (epochDay, r) in byDay {
                if let p = PortfolioDecimal.of(r.price) { closes[TaxDayClose.key(coin, epochDay)] = p }
            }
        }
        return TaxDayClose.lookup(closes)
    }

    /// Jahresübersicht der Vorlage für das Steuerjahr ab `year` (ohne Devisenkurse: Werte in der Währung leer).
    static func summary(transactions: [PortfolioTx], template: TaxTemplate, method: TaxMethod, year: Int,
                        currency: String, walletFrom: LocalDay? = nil) async -> TaxYearSummary {
        // Daten in der Zeitzone der Vorlage (Neutral: Gerät), wie in der CSV genannt
        let zone = template.zone()
        let period = template.period(year)
        let transactions = withAddressTrades(transactions, method: method)
        // UK/CA: Kurse auch für Zugänge bis 30 Tage nach dem Steuerjahr (`TaxExport.priceUntil`)
        let today = TaxExport.localDay(Int64(Date().timeIntervalSince1970 * 1000), timeZone: zone)
        let until = TaxExport.priceUntil(template, period: period, today: today)
        let priceAt: ((String, Int64) -> Decimal?)? = await taxPrices(template, method, transactions, period: period,
                                                                      until: until)
        // Veräusserungen einmal gerechnet: Devisenspanne und Zeilen der Übersicht aus derselben Rechnung
        let run = TaxExport.GainsRun(transactions, method: method, period: period, timeZone: zone, priceAt: priceAt,
                                     rules: template.eventRules, walletFrom: walletFrom)
        let span = run.span(until: until, today: today)
        let from = span.from
        let fx = await rates(currency, from: from, to: span.until)
        let usdtUsd = await usdtUsdCloses(currency, from: from)
        // CH: Vermögenswert per 31.12. (Orientierung) — Tagesschlüsse und Adress-Guthaben wie der Stichtag-Export
        var yearEnd: (closes: CutoffCloses, addresses: AddressCutoffView)? = nil
        if template == .CH { yearEnd = await chYearEnd(transactions, method: method, end: period.end, zone: zone) }
        // Länder-Schlüsse erst nach Fachprüfung (`TaxCountryFigures`)
        return TaxCountryFigures.shown(TaxExport.yearSummary(
            transactions, template: template, method: method, startYear: year, timeZone: zone,
            currency: currency, rates: fx, priceAt: priceAt, walletFrom: walletFrom,
            usdtUsd: usdtUsd, yearEndCloses: yearEnd?.closes, yearEndAddresses: yearEnd?.addresses,
            run: run))
    }

    /// CH: Tagesschlüsse des 31.12. (`end`) für den Bestand an dessen Ende (Zeitzone der Vorlage) samt beobachteter
    /// Adressen in der Sicht der Karte (`AddressCutoff.at`: Posten, Adress-Teil ohne Einstand) — dieselben Quellen wie der Stichtag-Export (`PortfolioCutoffExporter.build`:
    /// `HistoricPriceSource.dailyClosesUsdt` mit Identitätsprüfung gegen die aktuellen Kurse). Erst wenn die UTC-Tageskerze
    /// des 31.12. geschlossen ist (UTC-Datum danach), sonst nil: kein laufender oder heutiger Kurs, der Posten bleibt
    /// «nicht gerechnet». Ebenso nil, wenn die beobachteten Adressen nicht lesbar sind (`intact` false) — sonst stünde
    /// still eine zu tiefe Summe da. Wie Android `chYearEnd`.
    private static func chYearEnd(_ transactions: [PortfolioTx], method: TaxMethod, end: LocalDay,
                                  zone: TimeZone) async -> (closes: CutoffCloses, addresses: AddressCutoffView)? {
        let utc = TimeZone(identifier: "UTC") ?? .gmt
        guard end < TaxExport.localDay(Int64(Date().timeIntervalSince1970 * 1000), timeZone: utc) else { return nil }
        let day = CutoffDay(year: end.year, month: end.month, day: end.day)
        let cutoff = day.endOfDayMillis(in: zone)
        let watched = WatchedAddressStore.loadChecked()
        guard watched.intact else { return nil }
        let view = AddressCutoff.at(watched.file.addresses, real: transactions, cutoffMillis: cutoff)
        let holdings = AddressCutoff.holdingsAt(transactions, view: view, cutoffMillis: cutoff,
                                                method: method.costMethod ?? .defaultMethod)
        var seen: Set<String> = []
        let coins = (holdings.map(\.coin) + view.part.balances.keys.sorted())
            .filter { seen.insert($0).inserted && PortfolioStables.needsQuote($0) }
        var current: [String: Double] = [:]
        if !coins.isEmpty {
            let live = await PortfolioPriceSource.prices(coins)
            current = CutoffExport.referencePrices(live.prices, uncheckedCurrent: live.unchecked,
                                                   unverifiedCurrent: live.unverified)
        }
        let closes = await HistoricPriceSource.dailyClosesUsdt(coins, day: day, current: current)
        return (closes, view)
    }

    /// CSV «Jahresübersicht» (Orientierung, keine Steuerberatung).
    static func summaryCsv(_ summary: TaxYearSummary, texts: TaxSummaryTexts, header: [String],
                           addressLots: Bool = false) -> String {
        TaxExport.summaryCsv(summary, texts: texts, header: header,
                             zoneLine: L("tax_export_year_local_zone", summary.template.zone().identifier),
                             format: CutoffCsvFormat.current, addressLots: addressLots)
    }

    static func build(kind: TaxExportKind, transactions: [PortfolioTx], template: TaxTemplate, method: TaxMethod, year: Int,
                      currency: String, texts: TaxCsvTexts, header: [String], walletFrom: LocalDay? = nil) async -> String {
        // Daten in der Zeitzone der Vorlage (Neutral: Gerät), wie in der CSV genannt
        let zone = template.zone()
        let period = template.period(year)
        // Trenn-/Dezimalzeichen wie der Stichtag-Export (Excel der Region)
        let format = CutoffCsvFormat.current
        switch kind {
        case .gains:
            let transactions = withAddressTrades(transactions, method: method)
            // UK/CA: Kurse auch für Zugänge bis 30 Tage nach dem Steuerjahr (`TaxExport.priceUntil`)
            let today = TaxExport.localDay(Int64(Date().timeIntervalSince1970 * 1000), timeZone: zone)
            let until = TaxExport.priceUntil(template, period: period, today: today)
            let priceAt: ((String, Int64) -> Decimal?)? = await taxPrices(template, method, transactions, period: period,
                                                                          until: until)
            // Devisen-Tageskurse ab dem frühesten Kauf-/Herkunftsdatum (Einstand zum Kurs seines Datums) bis zum
            // spätesten genutzten Zugang (UK: 30-Tage-Regel über das Ende des Steuerjahrs hinaus) — Spanne und Zeilen
            // aus EINER Rechnung der Veräusserungen (`TaxExport.GainsRun`; CA: zweite mit den Kursen)
            let run = TaxExport.GainsRun(transactions, method: method, period: period, timeZone: zone, priceAt: priceAt,
                                         rules: template.eventRules, walletFrom: walletFrom)
            let span = run.span(until: until, today: today)
            let from = span.from
            let fx = await rates(currency, from: from, to: span.until)
            // USDT zum Marktkurs: USDT/USD-Tagesschluss je Datum (ohne Schluss 1, je Zeile vermerkt)
            let usdtUsd = await usdtUsdCloses(currency, from: from)
            let rows = run.gainRows(currency: currency, rates: fx, usdtUsd: usdtUsd)
            // Gebühr-Vermerke beide aus einer Rechnung
            let notes = TaxExport.feeNotes(in: transactions, method: method, period: period, timeZone: zone,
                                           rules: template.eventRules)
            return TaxExport.gainsCsv(rows, texts: texts, header: header, format: format,
                                      lostFeeCosts: notes.lostFeeCosts, timeZone: zone,
                                      unassignedFees: notes.unassignedFees)
        case .income:
            let fx = await rates(currency, from: period.start, to: period.end)
            let usdtUsd = await usdtUsdCloses(currency, from: period.start)
            // Belohnungen ohne Wert (Kraken/Binance) zum Tagesschluss geschätzt, wie in der Jahresübersicht
            let priceAt = await feeDisposalPrices(transactions, period: period, zone: zone)
            let rows = TaxExport.incomeRows(transactions, period: period, timeZone: zone, currency: currency, rates: fx,
                                            priceAt: priceAt, usdtUsd: usdtUsd)
            return TaxExport.incomeCsv(rows, texts: texts, header: header, format: format)
        }
    }
}
