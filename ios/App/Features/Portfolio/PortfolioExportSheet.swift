import SwiftUI
import UniformTypeIdentifiers

/// Stichtag-Export — wie `PortfolioExportDialog.kt`: Datum wählen (Standard 31.12. des
/// Vorjahrs), Währung aus «Umrechnen in» (nur Anzeige), «CSV exportieren» lädt die
/// historischen Kurse, baut die Datei und bietet sie über `.fileExporter` zum Sichern an.
/// Darunter «Arbeitsunterlage»: gewählte Steuer-Vorlage (Einstellung; ein Vorschlag aus der Region wird nur angeboten),
/// Methode und Währung der Vorlage, Steuerjahr nach ihren Grenzen; dann realisierte Veräusserungen, Einkünfte, Bestand per
/// Ende des Steuerjahrs (CH: 31.12. in CHF mit ESTV-Hinweis) und Jahresübersicht (CSV + Karte) (`TaxExport`).
@MainActor
struct PortfolioExportSheet: View {
    let transactions: [PortfolioTx]
    let currency: String
    /// Einstandsmethode für den Steuer-Export (Kopfzeile und Rechnung).
    var costMethod: CostMethod = .defaultMethod
    /// Steuer-Vorlage der Arbeitsunterlage (Einstellung «tax_template»).
    var taxTemplate: TaxTemplate = .defaultTemplate
    /// Vorlage ausdrücklich wählen (Vorschlag aus der Region übernehmen); nil = kein Knopf.
    var onTaxTemplate: ((TaxTemplate) -> Void)? = nil
    /// Hinweis zu den Länder-Vorlagen (Beta) schon bestätigt; sonst Dialog mit OK (`onConfirmTemplate`).
    var taxTemplateConfirmed: Bool = true
    var onConfirmTemplate: (() -> Void)? = nil
    /// Datei gesichert — der Aufrufer schliesst das Blatt und zeigt die Meldung.
    let onExported: () -> Void

    @Environment(\.appAccent) private var accent
    @Environment(\.dismiss) private var dismiss

    @State private var date: Date = CutoffDay.defaultDay(today: Date()).noonLocal
    @State private var running = false
    @State private var exporting = false
    @State private var document: CutoffCsvDocument?
    @State private var fileName = ""
    @State private var toast: String?
    /// nil = Standard (letztes volles Jahr mit Transaktionen).
    @State private var chosenYear: Int?
    /// Hinweis zu den Länder-Vorlagen weggewischt (bis zum nächsten Öffnen).
    @State private var confirmDismissed = false
    /// Jahresübersicht (Orientierung) der Vorlage und des gewählten Jahrs für die Karte; nil = wird gerechnet.
    @State private var summary: TaxYearSummary?

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: Spacing.md) {
                    DatePicker(selection: $date, in: Self.dateRange(), displayedComponents: .date) {
                        Text(L("portfolio_export_date"))
                            .font(.body)
                            .foregroundStyle(AppColors.onSurfaceVariant)
                    }
                    .disabled(running)
                    .padding(.horizontal, 16)
                    .padding(.vertical, 8)
                    .background(AppColors.container, in: RoundedRectangle(cornerRadius: Radius.card, style: .continuous))

                    // Heute (UTC) ist die Tageskerze noch offen: es gilt der aktuelle Kurs
                    if Self.isTodayUtc(date) {
                        PortfolioHint(text: L("portfolio_export_today_hint"))
                            .padding(.horizontal, 4)
                    }

                    Text(L("portfolio_export_currency", currency))
                        .font(.body)
                        .foregroundStyle(AppColors.onSurface)
                        .padding(.horizontal, 16)
                        .padding(.vertical, 12)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .background(AppColors.container, in: RoundedRectangle(cornerRadius: Radius.card, style: .continuous))

                    PortfolioHint(text: L("portfolio_export_hint"))
                        .padding(.horizontal, 4)

                    Button(action: { startExport(day: CutoffDay(date)) }) {
                        HStack(spacing: Spacing.sm) {
                            if running {
                                ProgressView().controlSize(.small)
                                Text(L("portfolio_export_running"))
                            } else {
                                Image(systemName: "square.and.arrow.up")
                                Text(L("portfolio_export_button"))
                            }
                        }
                        .font(.subheadline.weight(.semibold))
                        .lineLimit(1)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 12)
                    }
                    .buttonStyle(TonalButtonStyle())
                    .disabled(running)
                    .padding(.top, 4)

                    taxSection
                }
                .padding(.horizontal, Spacing.lg)
                .padding(.top, 8)
                .padding(.bottom, 24)
            }
            .background(AppColors.background.ignoresSafeArea())
            .navigationTitle(L("portfolio_export_title"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(L("action_cancel")) { dismiss() }
                        .disabled(running)
                }
            }
        }
        .tint(accent.primary)
        .interactiveDismissDisabled(running)
        .fileExporter(
            isPresented: $exporting,
            document: document,
            contentType: .commaSeparatedText,
            defaultFilename: fileName
        ) { result in
            document = nil
            switch result {
            case .success:
                UINotificationFeedbackGenerator().notificationOccurred(.success)
                onExported()
            case .failure(let error):
                if (error as? CocoaError)?.code != .userCancelled { toast = L("portfolio_export_failed") }
            }
        }
        .toast($toast)
        // Länder-Vorlage zum ersten Mal (z. B. Vorschlag übernommen): Hinweis einmal bestätigen — wie Android
        .alert(L("tax_template_confirm_title"), isPresented: Binding(
            get: { taxTemplate != .NEUTRAL && !taxTemplateConfirmed && !confirmDismissed && onConfirmTemplate != nil },
            set: { if !$0 { confirmDismissed = true } }
        )) {
            Button(L("ios_action_ok")) { onConfirmTemplate?() }
        } message: {
            Text(L("tax_template_confirm_text"))
        }
    }

    // MARK: Arbeitsunterlage

    /// Methode je Steuerjahr (US ab 2025 nur FIFO je Wallet) — wie Android.
    private var method: TaxMethod { taxTemplate.methodFor(costMethod, taxYear) }

    private func yearMethod(_ year: Int) -> TaxMethod { taxTemplate.methodFor(costMethod, year) }

    /// Währung der Arbeitsunterlage: die der Vorlage, Neutral die Anzeigewährung.
    private var workCurrency: String { taxTemplate.currency ?? currency }

    private var taxYears: [Int] { TaxExport.years(transactions, timeZone: .current, template: taxTemplate) }

    private var taxYear: Int? {
        let years = taxYears
        if let chosenYear, years.contains(chosenYear) { return chosenYear }
        return TaxExport.defaultYear(years, today: LocalDay.today(), template: taxTemplate)
    }

    private var version: String { Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "—" }

    /// Kopfzeile jeder Datei: Vorlage, Methode, Steuerjahr, Währung, Version.
    private func info(year: Int) -> String {
        L("tax_export_info_template", L(taxTemplate.labelKey), yearMethod(year).label, taxTemplate.label(year), workCurrency,
          version)
    }

    /// Länder-Vorlagen: Stand der hinterlegten Regeln in jeder Kopfzeile («Regelstand: 2026-10») — wie Android.
    private var rulesLine: [String] {
        taxTemplate == .NEUTRAL ? [] : [L("tax_export_rules_date", TaxTemplate.rulesDate)]
    }

    /// Vorlage erlaubt die Einstandsmethode der App nicht: offen sagen, womit der Export rechnet (Kopfzeile und Blatt) —
    /// «Vorlage rechnet FIFO, nicht Ø» (wie Android).
    private var methodNote: String? {
        guard taxTemplate.methodOverridden(costMethod, taxYear) else { return nil }
        return L("tax_export_method_overridden", method.label, TaxMethod.of(costMethod).label)
    }

    private static func dayText(_ d: LocalDay) -> String {
        d.date.formatted(date: .abbreviated, time: .omitted)
    }

    /// Vorlage, Steuerjahr, Hinweise und die vier Dateien; nur mit Transaktionen.
    @ViewBuilder
    private var taxSection: some View {
        if let year = taxYear {
            let period = taxTemplate.period(year)
            Text(L("tax_export_workpaper_title"))
                .font(.headline)
                .foregroundStyle(AppColors.onSurface)
                .padding(.top, 12)
                .padding(.horizontal, 4)
                .accessibilityAddTraits(.isHeader)
            // Gewählte Vorlage immer sichtbar (Einstellungen → Portfolio → Steuer-Vorlage); Länder-Vorlagen als «Beta»
            // markiert (Neutral und Stichtag-Export nicht) — wie Android
            if taxTemplate != .NEUTRAL {
                HStack(alignment: .firstTextBaseline, spacing: 6) {
                    Text(L("tax_template_current", L(taxTemplate.labelKey), method.label, workCurrency))
                        .font(.footnote)
                        .foregroundStyle(AppColors.onSurfaceVariant)
                        .fixedSize(horizontal: false, vertical: true)
                    Text(L("tax_template_beta"))
                        .font(.caption2.weight(.semibold))
                        .foregroundStyle(AppColors.onSurface)
                        .padding(.horizontal, 6)
                        .padding(.vertical, 1)
                        .background(AppColors.containerHighest, in: Capsule())
                    Spacer(minLength: 0)
                }
                .padding(.horizontal, 4)
                PortfolioHint(text: L("tax_template_beta_note"))
                    .padding(.horizontal, 4)
            } else {
                PortfolioHint(text: L("tax_template_current", L(taxTemplate.labelKey), method.label, workCurrency))
                    .padding(.horizontal, 4)
            }
            if let methodNote {
                PortfolioHint(text: methodNote)
                    .padding(.horizontal, 4)
            }
            // Methode/Vorlage gewechselt: abgegebene Jahre werden still neu gerechnet — fester Hinweis (wie Android)
            PortfolioHint(text: L("tax_export_method_change_warning"))
                .padding(.horizontal, 4)
            suggestionRow
            HStack {
                Text(L("tax_export_tax_year"))
                    .font(.body)
                    .foregroundStyle(AppColors.onSurfaceVariant)
                Spacer(minLength: 8)
                Picker(L("tax_export_tax_year"), selection: Binding(get: { year }, set: { chosenYear = $0 })) {
                    ForEach(taxYears, id: \.self) { y in
                        Text(taxTemplate.label(y)).tag(y)
                    }
                }
                .pickerStyle(.menu)
                .disabled(running)
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 8)
            .background(AppColors.container, in: RoundedRectangle(cornerRadius: Radius.card, style: .continuous))
            // Steuerjahr in Ortszeit (Verlauf/Heute in UTC) — Zone nennen (wie Android)
            PortfolioHint(text: L("tax_export_period", Self.dayText(period.start), Self.dayText(period.end)) + "\n"
                + L("tax_export_year_local_zone", TimeZone.current.identifier))
                .padding(.horizontal, 4)
            ForEach(templateNotes, id: \.self) { note in
                PortfolioHint(text: note)
                    .padding(.horizontal, 4)
            }
            // Steuer-CSV nach den Regeln des Landes: kann von «realisiert» im Portfolio abweichen (wie Android)
            PortfolioHint(text: L("tax_export_realized_differs"))
                .padding(.horizontal, 4)
            taxButton(L("tax_export_gains"), systemImage: "chart.line.uptrend.xyaxis") { startTax(.gains, year: year) }
            taxButton(L("tax_export_income"), systemImage: "gift") { startTax(.income, year: year) }
            // Bestand am Ende des Steuerjahrs (Stichtag-Export mit Kopfzeilen der Arbeitsunterlage)
            taxButton(L("tax_export_holdings_cutoff", Self.dayText(period.end)), systemImage: "calendar") {
                startHoldings(year: year, period: period)
            }
            // Jahresübersicht der Vorlage: Orientierung, keine Steuerberatung (CSV + Karte)
            taxButton(L("tax_export_summary", L(taxTemplate.labelKey)), systemImage: "list.bullet.rectangle") {
                startSummary(year: year)
            }
            PortfolioTaxSummaryCard(
                summary: matches(summary, year: year) ? summary : nil,
                texts: TaxSummaryTexts.localized(template: taxTemplate, yearLabel: taxTemplate.label(year),
                                                 currency: workCurrency, year: year),
                methodNote: methodNote
            )
            .task(id: "\(taxTemplate.rawValue)-\(year)-\(method.rawValue)-\(workCurrency)-\(taxTemplate.walletFrom(costMethod, year)?.description ?? "-")") {
                await loadSummary(year: year)
            }
        }
    }

    /// Vorschlag aus der Region des Geräts: nur anbieten, nie still übernehmen.
    @ViewBuilder
    private var suggestionRow: some View {
        let suggestion = TaxTemplate.suggest(Locale.current.region?.identifier ?? "")
        if suggestion != .NEUTRAL && suggestion != taxTemplate, let onTaxTemplate {
            let name = L(suggestion.labelKey)
            PortfolioHint(text: L("tax_template_suggestion", name))
                .padding(.horizontal, 4)
            Button(L("tax_template_use", name)) { onTaxTemplate(suggestion) }
                .font(.footnote.weight(.semibold))
                .disabled(running)
                .padding(.horizontal, 4)
        }
    }

    private func matches(_ s: TaxYearSummary?, year: Int) -> Bool {
        guard let s else { return false }
        return s.period.startYear == year && s.template == taxTemplate && s.method == yearMethod(year)
            && s.currency == workCurrency && s.walletFrom == taxTemplate.walletFrom(costMethod, year)
    }

    private func loadSummary(year: Int) async {
        if matches(summary, year: year) { return }
        summary = nil
        let s = await PortfolioTaxExporter.summary(transactions: transactions, template: taxTemplate,
                                                   method: yearMethod(year), year: year, currency: workCurrency,
                                                   walletFrom: taxTemplate.walletFrom(costMethod, year))
        guard !Task.isCancelled else { return }
        summary = s
    }

    private func startSummary(year: Int) {
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
        // USDT = 1 USD angenommen (bei Depeg falsch) — in jeder Kopfzeile, wie Android
        let header: [String] = TaxExport.withChWealthNote(
            template, header: base + atNote + [L("tax_export_usdt_usd_note"), L("tax_export_workpaper_disclaimer")],
            chWealthNote: Self.chWealthNote(year: year))
        Task {
            let s: TaxYearSummary
            if let known {
                s = known
            } else {
                s = await PortfolioTaxExporter.summary(transactions: txs, template: template, method: m, year: year,
                                                       currency: code, walletFrom: walletFrom)
            }
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

    private func taxButton(_ title: String, systemImage: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: Spacing.sm) {
                Image(systemName: systemImage)
                Text(title)
            }
            .font(.subheadline.weight(.semibold))
            .lineLimit(2)
            .frame(maxWidth: .infinity)
            .padding(.vertical, 12)
        }
        .buttonStyle(TonalButtonStyle())
        .disabled(running)
    }

    private func startTax(_ kind: TaxExportKind, year: Int) {
        guard !running else { return }
        running = true
        let txs = transactions
        let template = taxTemplate
        let m = yearMethod(year)
        let walletFrom = template.walletFrom(costMethod, year)
        let code = workCurrency
        let texts = TaxCsvTexts.localized(currency: code).withTemplateTexts(currency: code)
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
            lines.append(L("tax_export_workpaper_disclaimer"))
            header = lines
        case .income:
            header = [L("tax_export_income_title", yearLabel), infoLine] + rulesLine + [L("tax_export_usdt_usd_note"),
                      L("tax_export_workpaper_disclaimer")]
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

    /// Bestand am Ende des Steuerjahrs in der Währung der Vorlage, mit Kopfzeilen der Arbeitsunterlage.
    private func startHoldings(year: Int, period: TaxPeriod) {
        let end = CutoffDay(year: period.end.year, month: period.end.month, day: period.end.day)
        let header = [L("tax_export_holdings_title", Self.dayText(period.end)), info(year: year)] + rulesLine
            + [L("tax_export_usdt_usd_note"), L("tax_export_workpaper_disclaimer"),
               L("tax_export_year_local_zone", TimeZone.current.identifier)]
        startExport(day: end, currency: workCurrency,
                    header: TaxExport.withChWealthNote(taxTemplate, header: header, chWealthNote: Self.chWealthNote(year: year)))
    }

    /// «Vermögenssteuer nicht gerechnet – Kursliste ESTV: <Link>» für die Kopfzeilen der CH-Arbeitsunterlage — wie Android.
    static func chWealthNote(year: Int) -> String {
        L("tax_export_ch_wealth_not_calculated",
          EstvKursliste.listUrl(year: year, language: Bundle.main.preferredLocalizations.first ?? "en"))
    }

    private func startExport(day: CutoffDay, currency exportCurrency: String? = nil, header: [String] = []) {
        guard !running else { return }
        running = true
        let txs = transactions
        let code = exportCurrency ?? currency
        let texts = CutoffCsvTexts.localized(currency: code)
        let method = costMethod
        // Beobachtete Adressen (zählen im Portfolio) als eigene Zeilen — wie Android
        let addresses = AddressBalanceHistory.of(WatchedAddressStore.load().addresses)
        Task {
            let csv = await PortfolioCutoffExporter.build(transactions: txs, day: day, currency: code, texts: texts,
                                                          method: method, addresses: addresses, header: header)
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

    /// Gewählter Tag ist heute oder später nach UTC (laufende Tageskerze).
    static func isTodayUtc(_ date: Date, now: Date = Date()) -> Bool {
        var utc = Calendar(identifier: .gregorian)
        utc.timeZone = TimeZone(identifier: "UTC") ?? .gmt
        return CutoffDay(date).iso >= CutoffDay(now, calendar: utc).iso
    }

    /// Bis heute (ab 2009, wie beim Erfassen; gregorianisch, siehe `PortfolioTxSheet.gregorian`).
    private static func dateRange() -> ClosedRange<Date> {
        let start = PortfolioTxSheet.gregorian.date(from: DateComponents(year: 2009, month: 1, day: 1)) ?? Date(timeIntervalSince1970: 0)
        return start...max(start, Date())
    }
}
