import SwiftUI

// Blatt «Importe»: frühere CSV-Importläufe (Format, Datum/Zeit, Anzahl), je Lauf «Rückgängig» mit Rückfrage.
// Eigene Datei wie `PortfolioImports.kt`.

/// ⋯ › «Importe»: CSV-Importläufe (Format, Datum/Zeit, Anzahl), je Lauf «Rückgängig» mit Rückfrage — wie
/// `PortfolioImportsDialog` (Android). `onUndo` löscht den Lauf (der Aufrufer zeigt das Banner mit «Rückgängig»).
@MainActor
struct PortfolioImportsSheet: View {
    let batches: [PortfolioImportBatchInfo]
    let onUndo: (PortfolioImportBatchInfo) -> Void

    @Environment(\.appAccent) private var accent
    @Environment(\.dismiss) private var dismiss
    @State private var confirm: PortfolioImportBatchInfo?

    private static let timeFormatter: DateFormatter = {
        let f = DateFormatter()
        f.dateStyle = .medium
        f.timeStyle = .short
        return f
    }()

    var body: some View {
        NavigationStack {
            List {
                if batches.isEmpty {
                    Text(L("portfolio_imports_empty"))
                        .foregroundStyle(AppColors.onSurfaceVariant)
                }
                ForEach(batches, id: \.id) { batch in
                    HStack(spacing: Spacing.sm) {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(verbatim: title(batch))
                                .font(.body.monospacedDigit())
                            Text(L("portfolio_imports_count", count: batch.count))
                                .font(.caption)
                                .foregroundStyle(AppColors.onSurfaceVariant)
                        }
                        Spacer(minLength: 8)
                        Button(L("portfolio_import_undo"), role: .destructive) { confirm = batch }
                            .buttonStyle(.borderless)
                            .accessibilityIdentifier(UiTestTags.importUndo)
                    }
                }
            }
            .navigationTitle(L("portfolio_imports_title"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button(L("action_close")) { dismiss() }
                }
            }
        }
        .tint(accent.primary)
        .confirmationDialog(L("portfolio_import_undo_title"),
                            isPresented: Binding(get: { confirm != nil }, set: { if !$0 { confirm = nil } }),
                            titleVisibility: .visible,
                            presenting: confirm) { batch in
            Button(L("portfolio_import_undo"), role: .destructive) {
                confirm = nil
                onUndo(batch)
                dismiss()
            }
            .accessibilityIdentifier(UiTestTags.importUndoConfirm)
            Button(L("action_cancel"), role: .cancel) { confirm = nil }
        } message: { batch in
            Text(L("portfolio_import_undo_confirm", count: batch.count))
        }
    }

    /// «Relai · 12.06.2024, 14:05».
    private func title(_ batch: PortfolioImportBatchInfo) -> String {
        let format = batch.format.flatMap(CsvImportFormat.init(rawValue:)).map(PortfolioCsvImporter.formatName) ?? batch.format ?? "CSV"
        guard let time = batch.time else { return format }
        return format + " · " + Self.timeFormatter.string(from: Date(millis: time))
    }
}
