import SwiftUI
import UniformTypeIdentifiers

/// Steuer-Arbeitsunterlage als eigene Seite (Portfolio ⋯ › «Steuer-Arbeitsunterlage…») — wie `TaxWorksheetScreen.kt`:
/// Steuer-Vorlage wählen (dieselbe Einstellung wie Einstellungen → Portfolio; ein Vorschlag aus der Region wird nur
/// angeboten), Methode und Währung der Vorlage, Steuerjahr nach ihren Grenzen, Regelstand; dann realisierte
/// Veräusserungen, Einkünfte, Bestand per Ende des Steuerjahrs (CH: 31.12. in CHF mit ESTV-Hinweis) und Jahresübersicht
/// (CSV + Karte) (`TaxExport`). Ein laufender Export lässt sich abbrechen; Fehler nach Art mit «Erneut versuchen».
/// Ziel im `NavigationStack` des Portfolio-Tabs.
@MainActor
struct TaxWorksheetPage: View {
    @EnvironmentObject var data: AppData
    @Environment(\.appAccent) private var accent

    @StateObject var run = ExportRun()
    /// nil = Standard (letztes volles Jahr mit Transaktionen).
    @State private var chosenYear: Int?
    /// Hinweis zu den Länder-Vorlagen weggewischt (bis zur nächsten Vorlage).
    @State private var confirmDismissed = false
    /// Jahresübersicht (Orientierung) der Vorlage und des gewählten Jahrs für die Karte; nil = wird gerechnet.
    @State var summary: TaxYearSummary? = nil
    @State private var toast: ToastMessage? = nil

    var transactions: [PortfolioTx] { data.portfolio }
    /// Anzeigewährung des Portfolios («Umrechnen in»).
    var currency: String { data.settings.portfolioCurrency }
    /// Einstandsmethode der App (Kopfzeile und Rechnung).
    var costMethod: CostMethod { data.settings.costMethod }
    /// Steuer-Vorlage der Arbeitsunterlage (Einstellung «tax_template»).
    var taxTemplate: TaxTemplate { data.settings.taxTemplate }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: Spacing.md) {
                templateSection
                yearSection
                // Arbeitsunterlage – keine Steuererklärung und keine Steuerberatung
                PortfolioHint(text: L("tax_export_workpaper_disclaimer"))
                    .padding(.horizontal, 4)
            }
            .padding(.horizontal, Spacing.lg)
            .padding(.top, 8)
            .padding(.bottom, 24)
        }
        .background(AppColors.background.ignoresSafeArea())
        .accessibilityIdentifier(UiTestTags.taxWorksheetScreen)
        .navigationTitle(L("tax_export_workpaper_title"))
        .navigationBarTitleDisplayMode(.inline)
        // Seite verlassen, während ein Export läuft: abbrechen (Ergebnis wird verworfen)
        .onDisappear { run.cancel() }
        .fileExporter(
            isPresented: $run.exporting,
            document: run.document,
            contentType: .commaSeparatedText,
            defaultFilename: run.fileName
        ) { result in
            // Seite bleibt offen (weitere Dateien der Arbeitsunterlage), Bestätigung als Meldung
            if run.finishExport(result) {
                Haptics.play(.success)
                toast = ToastMessage(L("portfolio_export_done"), kind: .success)
            }
        }
        .onChange(of: run.state) { _, state in
            if case .failed = state { Haptics.play(.error) }
        }
        .toast($toast)
        // Länder-Vorlage zum ersten Mal (gewählt oder Vorschlag übernommen): Hinweis einmal bestätigen — wie Android
        .alert(L("tax_template_confirm_title"), isPresented: Binding(
            get: { taxTemplate != .NEUTRAL && !data.settings.taxTemplateConfirmed && !confirmDismissed },
            set: { if !$0 { confirmDismissed = true } }
        )) {
            Button(L("ios_action_ok")) { data.settings.taxTemplateConfirmed = true }
        } message: {
            Text(L("tax_template_confirm_text"))
        }
        .onChange(of: taxTemplate) { _, _ in confirmDismissed = false }
    }

    // MARK: Vorlage und Jahr

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

    /// Vorlage erlaubt die Einstandsmethode der App nicht: offen sagen, womit der Export rechnet (Kopfzeile und
    /// Seite) — «Vorlage rechnet FIFO, nicht Ø» (wie Android).
    var methodNote: String? {
        guard taxTemplate.methodOverridden(costMethod, taxYear) else { return nil }
        return L("tax_export_method_overridden", method.label, TaxMethod.of(costMethod).label)
    }

    static func dayText(_ d: LocalDay) -> String {
        d.date.formatted(date: .abbreviated, time: .omitted)
    }

    /// Vorlage (Menü aller Vorlagen), Methode und Währung, «Beta» der Länder-Vorlagen, Hinweise und Vorschlag.
    @ViewBuilder
    private var templateSection: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(L("tax_template_title"))
                .font(.body)
                .foregroundStyle(AppColors.onSurfaceVariant)
                .accessibilityAddTraits(.isHeader)
            Menu {
                ForEach(TaxTemplate.allCases, id: \.self) { t in
                    Button(L(t.labelKey)) { data.settings.taxTemplate = t }
                        .accessibilityIdentifier(UiTestTags.taxTemplateOption(t.rawValue))
                }
            } label: {
                Text(L(taxTemplate.labelKey))
                    .font(.body.weight(.semibold))
                    .foregroundStyle(accent.primary)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            .disabled(run.running)
            .accessibilityIdentifier(UiTestTags.taxWorksheetTemplate)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 8)
        .background(AppColors.container, in: RoundedRectangle(cornerRadius: Radius.card, style: .continuous))
        // Länder-Vorlagen als «Beta» markiert (Neutral und Stichtag-Export nicht) — wie Android
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
    }

    /// Vorschlag aus der Region des Geräts: nur anbieten, nie still übernehmen.
    @ViewBuilder
    private var suggestionRow: some View {
        let suggestion = TaxTemplate.suggest(Locale.current.region?.identifier ?? "")
        if suggestion != .NEUTRAL && suggestion != taxTemplate {
            let name = L(suggestion.labelKey)
            PortfolioHint(text: L("tax_template_suggestion", name))
                .padding(.horizontal, 4)
            Button(L("tax_template_use", name)) { data.settings.taxTemplate = suggestion }
                .font(.footnote.weight(.semibold))
                .disabled(run.running)
                .padding(.horizontal, 4)
        }
    }

    /// Steuerjahr, Zeitraum, Regelstand, Hinweise der Vorlage, die vier Dateien samt Fortschritt bzw. Fehlermeldung und
    /// die Jahresübersicht; nur mit Transaktionen.
    @ViewBuilder
    private var yearSection: some View {
        if let year = taxYear {
            let period = taxTemplate.period(year)
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
                .disabled(run.running)
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
            taxButton(L("tax_export_gains"), systemImage: "chart.line.uptrend.xyaxis",
                      tag: UiTestTags.taxWorksheetGains) {
                startTax(.gains, year: year)
            }
            taxButton(L("tax_export_income"), systemImage: "gift", tag: UiTestTags.taxWorksheetIncome) {
                startTax(.income, year: year)
            }
            // Bestand am Ende des Steuerjahrs (Stichtag-Export mit Kopfzeilen der Arbeitsunterlage)
            taxButton(L("tax_export_holdings_cutoff", Self.dayText(period.end)), systemImage: "calendar",
                      tag: UiTestTags.taxWorksheetHoldings) {
                startHoldings(year: year, period: period)
            }
            // Jahresübersicht der Vorlage: Orientierung, keine Steuerberatung (CSV + Karte)
            taxButton(L("tax_export_summary", L(taxTemplate.labelKey)), systemImage: "list.bullet.rectangle",
                      tag: UiTestTags.taxWorksheetSummary) {
                startSummary(year: year)
            }
            runStatus
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

    /// Laufender Export mit «Abbrechen» bzw. die Fehlermeldung mit «Erneut versuchen».
    @ViewBuilder
    private var runStatus: some View {
        if run.running {
            HStack(spacing: Spacing.sm) {
                ProgressView().controlSize(.small)
                Text(L("portfolio_export_running"))
                    .font(.subheadline)
                    .foregroundStyle(AppColors.onSurfaceVariant)
                Spacer(minLength: 8)
                Button(L("action_cancel")) { run.cancel() }
                    .font(.subheadline.weight(.semibold))
                    .accessibilityIdentifier(UiTestTags.exportCancel)
            }
            .padding(.horizontal, 4)
        } else if case .failed(let kind) = run.state {
            PortfolioExportFailureRow(kind: kind) { run.retry() }
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

    private func taxButton(_ title: String, systemImage: String, tag: String,
                           action: @escaping () -> Void) -> some View {
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
        .disabled(run.running)
        .accessibilityIdentifier(tag)
    }
}
