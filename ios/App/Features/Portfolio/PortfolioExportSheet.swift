import SwiftUI
import UniformTypeIdentifiers

/// Stichtag-Export — wie `PortfolioExportDialog.kt`: Datum wählen (Standard 31.12. des
/// Vorjahrs), Währung aus «Umrechnen in» (nur Anzeige), «CSV exportieren» lädt die
/// historischen Kurse, baut die Datei und bietet sie über `.fileExporter` zum Sichern an.
@MainActor
struct PortfolioExportSheet: View {
    let transactions: [PortfolioTx]
    let currency: String
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

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 14) {
                    DatePicker(selection: $date, in: Self.dateRange(), displayedComponents: .date) {
                        Text(L("portfolio_export_date"))
                            .font(.body)
                            .foregroundStyle(AppColors.onSurfaceVariant)
                    }
                    .disabled(running)
                    .padding(.horizontal, 16)
                    .padding(.vertical, 8)
                    .background(AppColors.container, in: RoundedRectangle(cornerRadius: 16, style: .continuous))

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
                        .background(AppColors.container, in: RoundedRectangle(cornerRadius: 16, style: .continuous))

                    PortfolioHint(text: L("portfolio_export_hint"))
                        .padding(.horizontal, 4)

                    Button(action: startExport) {
                        HStack(spacing: 10) {
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
                }
                .padding(.horizontal, 20)
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
    }

    private func startExport() {
        guard !running else { return }
        running = true
        let day = CutoffDay(date)
        let txs = transactions
        let code = currency
        let texts = CutoffCsvTexts.localized(currency: code)
        Task {
            let csv = await PortfolioCutoffExporter.build(transactions: txs, day: day, currency: code, texts: texts)
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

    /// Bis heute (ab 2009, wie beim Erfassen).
    private static func dateRange() -> ClosedRange<Date> {
        let start = Calendar.current.date(from: DateComponents(year: 2009, month: 1, day: 1)) ?? Date(timeIntervalSince1970: 0)
        return start...max(start, Date())
    }
}
