import SwiftUI
import UniformTypeIdentifiers

/// Stichtag-Export — wie `PortfolioExportDialog.kt`: Datum wählen (Standard 31.12. des
/// Vorjahrs), Währung aus «Umrechnen in» (nur Anzeige), «CSV exportieren» lädt die
/// historischen Kurse, baut die Datei und bietet sie über `.fileExporter` zum Sichern an.
/// «Abbrechen» während des Laufs bricht den Export ab; Fehler stehen nach ihrer Art im Blatt, mit «Erneut versuchen».
/// Die Steuer-Arbeitsunterlage hat eine eigene Seite (`TaxWorksheetPage`).
@MainActor
struct PortfolioExportSheet: View {
    let transactions: [PortfolioTx]
    let currency: String
    /// Einstandsmethode der App (Bestand des Stichtags).
    var costMethod: CostMethod = .defaultMethod
    /// Datei gesichert — der Aufrufer schliesst das Blatt und zeigt die Meldung.
    let onExported: () -> Void

    @Environment(\.appAccent) private var accent
    @Environment(\.dismiss) private var dismiss

    @State private var date: Date = CutoffDay.defaultDay(today: Date()).noonLocal
    @StateObject private var run = ExportRun()

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: Spacing.md) {
                    DatePicker(selection: $date, in: Self.dateRange(), displayedComponents: .date) {
                        Text(L("portfolio_export_date"))
                            .font(.body)
                            .foregroundStyle(AppColors.onSurfaceVariant)
                    }
                    .disabled(run.running)
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

                    Button(action: startExport) {
                        HStack(spacing: Spacing.sm) {
                            if run.running {
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
                    .disabled(run.running)
                    .padding(.top, 4)

                    if case .failed(let kind) = run.state {
                        PortfolioExportFailureRow(kind: kind) { run.retry() }
                    }
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
                    // Während des Exports: abbrechen (Ergebnis wird verworfen), sonst schliessen
                    Button(L("action_cancel")) {
                        if run.running { run.cancel() } else { dismiss() }
                    }
                    .accessibilityIdentifier(UiTestTags.exportCancel)
                }
            }
        }
        .tint(accent.primary)
        // Wischen schliesst nicht mitten im Export — dafür «Abbrechen»
        .interactiveDismissDisabled(run.running)
        .fileExporter(
            isPresented: $run.exporting,
            document: run.document,
            contentType: .commaSeparatedText,
            defaultFilename: run.fileName
        ) { result in
            if run.finishExport(result) {
                Haptics.play(.success)
                onExported()
            }
        }
        .onChange(of: run.state) { _, state in
            if case .failed = state { Haptics.play(.error) }
        }
    }

    private func startExport() {
        let day = CutoffDay(date)
        run.start(fileName: CutoffExport.fileName(day),
                  build: CutoffExportJob.build(transactions: transactions, day: day, currency: currency,
                                               method: costMethod))
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
