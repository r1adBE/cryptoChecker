import SwiftUI
import UniformTypeIdentifiers

// Exporte im Export-Blatt (`PortfolioExportSheet`): Jahresübersicht, realisierte Gewinne und Einkünfte, Bestand am
// Ende des Steuerjahrs und Stichtag-CSV — Kopfzeilen bauen, Datei rechnen und zum Sichern anbieten.

extension PortfolioExportSheet {
    func startSummary(year: Int) {
        guard !running else { return }
        running = true
        let txs = transactions
        let template = taxTemplate
        let m = yearMethod(year)
        let walletFrom = template.walletFrom(costMethod, year)
        let code = workCurrency
        let known = matches(summary, year: year) ? summary : nil
        let texts = TaxSummaryTexts.localized(template: template, yearLabel: template.label(year), currency: code,
                                              year: year)
        // AT: Durchschnitt je Coin und Wallet (KryptowährungsVO § 2) — steht in der Kopfzeile
        let atNote: [String] = m == .AT_MOVING_AVERAGE ? [L("tax_export_at_wallet_note")] : []
        // Vorlage rechnet eine andere Methode als die App (wie Android: nach der Info-Zeile)
        let base: [String] = [texts.title, info(year: year)] + rulesLine + (methodNote.map { [$0] } ?? [])
        // USDT zum USDT/USD-Tagesschluss (ohne Schluss 1, je Zeile vermerkt) und die Tageskurs-Regel geschätzter
        // Marktwerte (`TaxDayClose`) — in jeder Kopfzeile, wie Android
        let header: [String] = TaxExport.withChWealthNote(
            template, header: base + atNote + [L("tax_export_usdt_usd_note"), L("tax_export_day_close_rule"),
                                               L("tax_export_workpaper_disclaimer")],
            chWealthNote: Self.chWealthNote(year: year))
        Task {
            let s: TaxYearSummary
            if let known {
                s = known
            } else {
                s = await PortfolioTaxExporter.summary(transactions: txs, template: template, method: m, year: year,
                                                       currency: code, walletFrom: walletFrom)
            }
            // Hinweiszeile zum CH-Vermögen kommt aus der Übersicht selbst (`TaxWealth.addressNote`), wie Android
            let csv = PortfolioTaxExporter.summaryCsv(s, texts: texts, header: header)
            running = false
            guard let data = csv.data(using: .utf8) else {
                UINotificationFeedbackGenerator().notificationOccurred(.error)
                toast = L("portfolio_export_failed")
                return
            }
            document = CutoffCsvDocument(data: data)
            fileName = TaxExport.summaryFileName(template, year: year)
            exporting = true
        }
    }

    func startTax(_ kind: TaxExportKind, year: Int) {
        guard !running else { return }
        running = true
        let txs = transactions
        let template = taxTemplate
        let m = yearMethod(year)
        let walletFrom = template.walletFrom(costMethod, year)
        let code = workCurrency
        let texts = TaxCsvTexts.localized(currency: code, zone: template.zone()).withTemplateTexts(currency: code, template: template)
        let infoLine = info(year: year)
        let yearLabel = template.label(year)
        let header: [String]
        switch kind {
        case .gains:
            var lines: [String] = [L("tax_export_gains_title", yearLabel), infoLine] + rulesLine
            // Vorlage rechnet eine andere Methode als die App (wie Android: vor der Regel)
            if let methodNote { lines.append(methodNote) }
            lines.append(L("tax_export_rule"))
            // AT: Durchschnitt je Coin und Wallet (KryptowährungsVO § 2)
            if m == .AT_MOVING_AVERAGE { lines.append(L("tax_export_at_wallet_note")) }
            lines.append(L("tax_export_usdt_usd_note"))
            lines.append(L("tax_export_day_close_rule"))
            lines.append(L("tax_export_workpaper_disclaimer"))
            header = lines
        case .income:
            header = [L("tax_export_income_title", yearLabel), infoLine] + rulesLine + [L("tax_export_usdt_usd_note"),
                      L("tax_export_day_close_rule"), L("tax_export_workpaper_disclaimer")]
        }
        // CH: «Vermögenssteuer nicht gerechnet – Kursliste ESTV» in den ersten Kopfzeilen (wie Android)
        let csvHeader = TaxExport.withChWealthNote(template, header: header, chWealthNote: Self.chWealthNote(year: year))
        Task {
            let csv = await PortfolioTaxExporter.build(kind: kind, transactions: txs, template: template, method: m,
                                                       year: year, currency: code, texts: texts, header: csvHeader,
                                                       walletFrom: walletFrom)
            running = false
            guard let data = csv.data(using: .utf8) else {
                UINotificationFeedbackGenerator().notificationOccurred(.error)
                toast = L("portfolio_export_failed")
                return
            }
            document = CutoffCsvDocument(data: data)
            switch kind {
            case .gains: fileName = TaxExport.gainsFileName(year)
            case .income: fileName = TaxExport.incomeFileName(year)
            }
            exporting = true
        }
    }

    /// Bestand am Ende des Steuerjahrs in der Währung der Vorlage, mit Kopfzeilen der Arbeitsunterlage; gerechnet nach
    /// der Methode der Vorlage (im Kopf genannt, samt «Vorlage ersetzt die Methode») — wie Android.
    func startHoldings(year: Int, period: TaxPeriod) {
        let end = CutoffDay(year: period.end.year, month: period.end.month, day: period.end.day)
        let header = [L("tax_export_holdings_title", Self.dayText(period.end)), info(year: year)] + rulesLine
            + (methodNote.map { [$0] } ?? [])
            + [L("tax_export_usdt_usd_note"), L("tax_export_workpaper_disclaimer"),
               L("tax_export_year_local_zone", taxTemplate.zone().identifier)]
        startExport(day: end, currency: workCurrency,
                    header: TaxExport.withChWealthNote(taxTemplate, header: header, chWealthNote: Self.chWealthNote(year: year)),
                    zone: taxTemplate.zone(), method: yearMethod(year).holdingsCostMethod)
    }

    /// «Vermögenssteuer nicht gerechnet – Kursliste ESTV: <Link>» für die Kopfzeilen der CH-Arbeitsunterlage — wie Android.
    static func chWealthNote(year: Int) -> String {
        L("tax_export_ch_wealth_not_calculated",
          EstvKursliste.listUrl(year: year, language: Bundle.main.preferredLocalizations.first ?? "en"))
    }

    /// Stichtag-CSV; `method` = Einstandsmethode des Bestands: nil = die der App (allgemeiner Stichtag-Export).
    func startExport(day: CutoffDay, currency exportCurrency: String? = nil, header: [String] = [],
                             zone: TimeZone = .current, method holdingsMethod: CostMethod? = nil) {
        guard !running else { return }
        running = true
        let txs = transactions
        let code = exportCurrency ?? currency
        let texts = CutoffCsvTexts.localized(currency: code)
        let method = holdingsMethod ?? costMethod
        // Beobachtete Adressen (zählen im Portfolio) als eigene Zeilen — wie Android. Nicht lesbar: ohne Adressen, aber
        // in der Datei vermerkt und das Total «unvollständig» — nie still eine zu tiefe Summe
        let watched = WatchedAddressStore.loadChecked()
        let addresses = watched.file.addresses
        let addressesUnreadable: Bool = !watched.intact
        Task {
            let csv = await PortfolioCutoffExporter.build(transactions: txs, day: day, currency: code, texts: texts,
                                                          method: method, addresses: addresses,
                                                          addressesUnreadable: addressesUnreadable,
                                                          header: header, zone: zone)
            running = false
            guard let data = csv.data(using: .utf8) else {
                UINotificationFeedbackGenerator().notificationOccurred(.error)
                toast = L("portfolio_export_failed")
                return
            }
            document = CutoffCsvDocument(data: data)
            fileName = CutoffExport.fileName(day)
            exporting = true
        }
    }
}
