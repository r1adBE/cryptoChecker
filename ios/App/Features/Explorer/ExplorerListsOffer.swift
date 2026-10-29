import SwiftUI

/// Fehlende Paarlisten mit «Listen laden» (stumme Börsen mit Namen und «Erneut versuchen»), beim Laden der
/// Fortschritt. Geladen wird nur auf Tippen, je Börse die ganze öffentliche Liste. Wie `ListsOffer` in Android.
struct ExplorerListsOffer: View {
    let lists: ExplorerSearchLists
    let onLoad: () -> Void
    @Environment(\.appAccent) private var accent
    /// Alle Börsen ohne Antwort zeigen (Tipp auf «+N weitere»); neu eingeklappt, sobald sich die Liste ändert.
    @State private var expanded = false

    /// Eingeklappt so viele Börsennamen im Satz «… antworten zurzeit nicht» (wie Android `FAILED_NAMES_SHOWN`).
    static let namesShown = 3

    /// Gezeigte Namen (alphabetisch) und wie viele dahinter als «+N weitere» stehen. Wie Android `failedNamesShown`.
    static func shown(_ failed: [String], expanded: Bool) -> (names: [String], hidden: Int) {
        let sorted = failed.sorted { $0.caseInsensitiveCompare($1) == .orderedAscending }
        if expanded || sorted.count <= namesShown { return (sorted, 0) }
        return (Array(sorted.prefix(namesShown)), sorted.count - namesShown)
    }

    var body: some View {
        if let progress = lists.progress {
            VStack(alignment: .leading, spacing: Spacing.xs) {
                ProgressView(value: Double(progress.done), total: Double(max(progress.total, 1)))
                    .tint(accent.primary)
                Text(L("explorer_lists_loading", progress.done, progress.total))
                    .font(.footnote)
                    .foregroundStyle(AppColors.onSurfaceVariant)
                    .monospacedDigit()
            }
            .padding(.vertical, Spacing.sm)
        } else if lists.reading {
            VStack(alignment: .leading, spacing: Spacing.xs) {
                ProgressView().progressViewStyle(.linear).tint(accent.primary)
                Text(L("explorer_loading_pairs"))
                    .font(.footnote)
                    .foregroundStyle(AppColors.onSurfaceVariant)
            }
            .padding(.vertical, Spacing.sm)
        } else if lists.missing > 0 {
            VStack(alignment: .leading, spacing: Spacing.xs) {
                // Nach einem Fehlschlag zuerst die Börsen ohne Antwort, dann die übrigen fehlenden Listen
                let others = lists.missing - lists.failed.count
                if !lists.failed.isEmpty {
                    failedText
                }
                if lists.failed.isEmpty || others > 0 {
                    Text(L("explorer_lists_missing", count: others, others))
                        .font(.subheadline)
                        .foregroundStyle(AppColors.onSurface)
                }
                Text(L("explorer_lists_privacy"))
                    .font(.footnote)
                    .foregroundStyle(AppColors.onSurfaceVariant)
                HStack {
                    Spacer()
                    Button(L(lists.failed.isEmpty ? "explorer_lists_load" : "action_retry")) { onLoad() }
                        .accessibilityIdentifier(UiTestTags.explorerListsLoad)
                        .font(.subheadline.weight(.semibold))
                        .tint(accent.primary)
                        .buttonStyle(.borderless)
                }
            }
            .fixedSize(horizontal: false, vertical: true)
            .padding(Spacing.md)
            .background(AppColors.container, in: RoundedRectangle(cornerRadius: Radius.card, style: .continuous))
            .onChange(of: lists.failed) { expanded = false }
        }
    }

    /// «Binance, Bybit, Coinbase, +47 weitere antworten zurzeit nicht …»: ohne Netz wären es sonst rund 50 Namen;
    /// Tipp auf den Satz zeigt alle.
    @ViewBuilder
    private var failedText: some View {
        let shown = Self.shown(lists.failed, expanded: expanded)
        let more = shown.hidden > 0 ? L("explorer_lists_failed_more", count: shown.hidden, shown.hidden) : nil
        // Zwei Texte statt Plural: die Zahl steht nicht im Satz (Xcode verlangt sie in Plural-Varianten)
        let sentence = L(lists.failed.count == 1 ? "explorer_lists_failed_one" : "explorer_lists_failed_many",
                         (shown.names + [more].compactMap { $0 }).joined(separator: ", "))
        let label = Text(styled(sentence, more: more))
            .font(.subheadline)
            .foregroundStyle(AppColors.onSurface)
        if more != nil {
            Button { expanded = true } label: { label.multilineTextAlignment(.leading) }
                .buttonStyle(.plain)
                .accessibilityHint(L("watchlist_show_all"))
        } else {
            label
        }
    }

    /// «+N weitere» in der Akzentfarbe (antippbar), der Rest in der Textfarbe.
    private func styled(_ sentence: String, more: String?) -> AttributedString {
        var text = AttributedString(sentence)
        if let more, let range = text.range(of: more, options: .backwards) {
            text[range].foregroundColor = accent.primary
            text[range].font = Font.subheadline.weight(.semibold)
        }
        return text
    }
}
