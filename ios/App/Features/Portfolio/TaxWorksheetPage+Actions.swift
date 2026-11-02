import SwiftUI

// Exporte der Seite «Steuer-Arbeitsunterlage» (`TaxWorksheetPage`): Jahresübersicht, realisierte Gewinne und Einkünfte
// und Bestand am Ende des Steuerjahrs — Kopfzeilen bauen und den Lauf starten (`ExportRun`: rechnen, dann zum Sichern
// anbieten). Eigene Datei, weil die Seite sonst zu lang würde.

extension TaxWorksheetPage {
    private var version: String { Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "—" }

    /// Kopfzeile jeder Datei: Vorlage, Methode, Steuerjahr, Währung, Version.
    func info(year: Int) -> String {
        L("tax_export_info_template", L(taxTemplate.labelKey), yearMethod(year).label, taxTemplate.label(year),
          workCurrency, version)
    }

    /// Länder-Vorlagen: Stand der hinterlegten Regeln in jeder Kopfzeile («Regelstand: Okt. 2026», Monat und Jahr in
    /// Sprache und Ziffern der App) — wie Android.
    var rulesLine: [String] {
        taxTemplate == .NEUTRAL ? [] : [L("tax_export_rules_date", TaxRulesDate.label())]
    }

    /// «Vermögenssteuer nicht gerechnet – Kursliste ESTV: <Link>» für die Kopfzeilen der CH-Arbeitsunterlage — wie
    /// Android.
    static func chWealthNote(year: Int) -> String {
        L("tax_export_ch_wealth_not_calculated",
          EstvKursliste.listUrl(year: year, language: Bundle.main.preferredLocalizations.first ?? "en"))
    }

    func startSummary(year: Int) {
        guard !run.running else { return }
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
            chWealthNote: TaxWorksheetPage.chWealthNote(year: year))
        run.start(fileName: TaxExport.summaryFileName(template, year: year)) {
            let s: TaxYearSummary
            if let known {
                s = known
            } else {
                s = await PortfolioTaxExporter.summary(transactions: txs, template: template, method: m, year: year,
                                                       currency: code, walletFrom: walletFrom)
            }
            // Hinweiszeile zum CH-Vermögen kommt aus der Übersicht selbst (`TaxWealth.addressNote`), wie Android
            return PortfolioTaxExporter.summaryCsv(s, texts: texts, header: header)
        }
    }

    func startTax(_ kind: TaxExportKind, year: Int) {
        guard !run.running else { return }
        let txs = transactions
        let template = taxTemplate
        let m = yearMethod(year)
        let walletFrom = template.walletFrom(costMethod, year)
        let code = workCurrency
        let texts = TaxCsvTexts.localized(currency: code, zone: template.zone())
            .withTemplateTexts(currency: code, template: template)
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
        let csvHeader = TaxExport.withChWealthNote(template, header: header,
                                                   chWealthNote: TaxWorksheetPage.chWealthNote(year: year))
        let name: String
        switch kind {
        case .gains: name = TaxExport.gainsFileName(year)
        case .income: name = TaxExport.incomeFileName(year)
        }
        run.start(fileName: name) {
            await PortfolioTaxExporter.build(kind: kind, transactions: txs, template: template, method: m,
                                             year: year, currency: code, texts: texts, header: csvHeader,
                                             walletFrom: walletFrom)
        }
    }

    /// Bestand am Ende des Steuerjahrs in der Währung der Vorlage, mit Kopfzeilen der Arbeitsunterlage; gerechnet nach
    /// der Methode der Vorlage (im Kopf genannt, samt «Vorlage ersetzt die Methode») — wie Android.
    func startHoldings(year: Int, period: TaxPeriod) {
        guard !run.running else { return }
        let end = CutoffDay(year: period.end.year, month: period.end.month, day: period.end.day)
        let title = L("tax_export_holdings_title", TaxWorksheetPage.dayText(period.end))
        let header = [title, info(year: year)] + rulesLine
            + (methodNote.map { [$0] } ?? [])
            + [L("tax_export_usdt_usd_note"), L("tax_export_workpaper_disclaimer"),
               L("tax_export_year_local_zone", taxTemplate.zone().identifier)]
        let csvHeader = TaxExport.withChWealthNote(taxTemplate, header: header,
                                                   chWealthNote: TaxWorksheetPage.chWealthNote(year: year))
        run.start(fileName: CutoffExport.fileName(end),
                  build: CutoffExportJob.build(transactions: transactions, day: end, currency: workCurrency,
                                               method: yearMethod(year).holdingsCostMethod, header: csvHeader,
                                               zone: taxTemplate.zone()))
    }
}
