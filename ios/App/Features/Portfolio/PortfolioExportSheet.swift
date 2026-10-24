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
    @State var running = false
    @State var exporting = false
    @State var document: CutoffCsvDocument? = nil
    @State var fileName = ""
    @State var toast: ToastMessage? = nil
    /// nil = Standard (letztes volles Jahr mit Transaktionen).
    @State private var chosenYear: Int?
    /// Hinweis zu den Länder-Vorlagen weggewischt (bis zum nächsten Öffnen).
    @State private var confirmDismissed = false
    /// Jahresübersicht (Orientierung) der Vorlage und des gewählten Jahrs für die Karte; nil = wird gerechnet.
    @State var summary: TaxYearSummary? = nil

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
                Haptics.play(.success)
                onExported()
            case .failure(let error):
                if (error as? CocoaError)?.code != .userCancelled {
                    Haptics.play(.error)
                    toast = ToastMessage(L("portfolio_export_failed"), kind: .error)
                }
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

    func yearMethod(_ year: Int) -> TaxMethod { taxTemplate.methodFor(costMethod, year) }

    /// Währung der Arbeitsunterlage: die der Vorlage, Neutral die Anzeigewährung.
    var workCurrency: String { taxTemplate.currency ?? currency }

    private var taxYears: [Int] { TaxExport.years(transactions, timeZone: taxTemplate.zone(), template: taxTemplate) }

    private var taxYear: Int? {
        let years = taxYears
        if let chosenYear, years.contains(chosenYear) { return chosenYear }
        return TaxExport.defaultYear(years, today: LocalDay.today(), template: taxTemplate)
    }

    private var version: String { Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "—" }

    /// Kopfzeile jeder Datei: Vorlage, Methode, Steuerjahr, Währung, Version.
    func info(year: Int) -> String {
        L("tax_export_info_template", L(taxTemplate.labelKey), yearMethod(year).label, taxTemplate.label(year), workCurrency,
          version)
    }

    /// Länder-Vorlagen: Stand der hinterlegten Regeln in jeder Kopfzeile («Regelstand: Okt. 2026», Monat und Jahr in
    /// Sprache und Ziffern der App) — wie Android.
    var rulesLine: [String] {
        taxTemplate == .NEUTRAL ? [] : [L("tax_export_rules_date", TaxRulesDate.label())]
    }

    /// Vorlage erlaubt die Einstandsmethode der App nicht: offen sagen, womit der Export rechnet (Kopfzeile und Blatt) —
    /// «Vorlage rechnet FIFO, nicht Ø» (wie Android).
    var methodNote: String? {
        guard taxTemplate.methodOverridden(costMethod, taxYear) else { return nil }
        return L("tax_export_method_overridden", method.label, TaxMethod.of(costMethod).label)
    }

    static func dayText(_ d: LocalDay) -> String {
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
                HStack(alignment: .firstTextBaseline, spacing: Spacing.xs) {
                    Text(L("tax_template_current", L(taxTemplate.labelKey), method.label, workCurrency))
                        .font(.footnote)
                        .foregroundStyle(AppColors.onSurfaceVariant)
                        .fixedSize(horizontal: false, vertical: true)
                    Text(L("tax_template_beta"))
                        .font(.caption2.weight(.semibold))
                        .foregroundStyle(AppColors.onSurface)
                        .padding(.horizontal, Spacing.xs)
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
                + L("tax_export_year_local_zone", taxTemplate.zone().identifier))
                .padding(.horizontal, 4)
            // Regelstand; Steuerjahr danach: Warnhinweis (blockiert nicht) — wie Android
            PortfolioTaxRulesNotice(template: taxTemplate, taxYear: year)
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

    func matches(_ s: TaxYearSummary?, year: Int) -> Bool {
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
