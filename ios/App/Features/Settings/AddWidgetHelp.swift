import SwiftUI

/// Runde 13b: «Widgets» in den Einstellungen (Darstellung). Android legt Widgets direkt aus der
/// App an (`requestPinAppWidget`); iOS erlaubt das Apps nicht — deshalb ein Blatt mit drei
/// kurzen Schritten und den verfügbaren Widgets (Name wie in der Widget-Galerie und ein Satz).
/// Echte Widget-Vorschauen gehen hier nicht: Die Widget-Ansichten liegen nur im Widget-Ziel.
/// Runde 23f: Die Zeile «Widgets» steht jetzt in der Liste der Hauptseite (`SettingsScreen`).
///
/// Drei Schritte und die Widget-Arten.
@MainActor
struct AddWidgetHelpSheet: View {
    let portfolioEnabled: Bool
    @Environment(\.dismiss) private var dismiss

    /// Name (wie `configurationDisplayName`), Satz (wie `description`), Symbol.
    private struct Kind: Identifiable {
        let id: String
        let name: String
        let text: String
        let icon: String
    }

    private var kinds: [Kind] {
        var list = [
            Kind(id: "list", name: L("tab_watchlist"), text: L("widget_description"), icon: "list.bullet.rectangle"),
            Kind(id: "single", name: L("single_widget_label"), text: L("single_widget_description"), icon: "chart.xyaxis.line"),
        ]
        if portfolioEnabled {
            list.append(Kind(id: "portfolio", name: L("widget_portfolio_name"), text: L("widget_portfolio_description"),
                             icon: "chart.pie"))
        }
        list.append(Kind(id: "pulse", name: L("pulse_now_title"), text: L("widget_pulse_description"), icon: "waveform.path.ecg"))
        return list
    }

    private var steps: [String] { [L("widgets_ios_step1"), L("widgets_ios_step2"), L("widgets_ios_step3")] }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    Text(L("widgets_ios_intro"))
                        .font(.subheadline)
                        .foregroundStyle(AppColors.onSurfaceVariant)
                        .fixedSize(horizontal: false, vertical: true)

                    VStack(alignment: .leading, spacing: 12) {
                        ForEach(Array(steps.enumerated()), id: \.offset) { index, step in
                            stepRow(number: index + 1, text: step)
                        }
                    }

                    Text(L("widgets_ios_kinds"))
                        .font(.footnote.weight(.semibold))
                        .foregroundStyle(AppColors.onSurfaceVariant)
                        .accessibilityAddTraits(.isHeader)
                        .padding(.top, 4)

                    VStack(alignment: .leading, spacing: 12) {
                        ForEach(kinds) { kind in
                            kindRow(kind)
                        }
                    }
                }
                .padding(Spacing.lg)
                .readableContentWidth()
            }
            .background(AppColors.background.ignoresSafeArea())
            .navigationTitle(L("widgets_sheet_title"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button(L("action_close")) { dismiss() }
                }
            }
        }
    }

    /// «1» im Kreis, daneben der Schritt; VoiceOver liest «1. Halte …».
    private func stepRow(number: Int, text: String) -> some View {
        HStack(alignment: .firstTextBaseline, spacing: 12) {
            Text(verbatim: LocaleNumbers.integer(number))
                .font(.footnote.weight(.bold))
                .monospacedDigit()
                .foregroundStyle(AppColors.onSurface)
                .frame(width: 24, height: 24)
                // Ruhig: Akzentfarbe nur für Bedienbares
                .background(Circle().stroke(AppColors.outlineVariant, lineWidth: 1))
            Text(text)
                .font(.body)
                .foregroundStyle(AppColors.onSurface)
                .fixedSize(horizontal: false, vertical: true)
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Text(verbatim: "\(LocaleNumbers.integer(number)). \(text)"))
    }

    private func kindRow(_ kind: Kind) -> some View {
        HStack(alignment: .top, spacing: 12) {
            Image(systemName: kind.icon)
                .font(.body.weight(.medium))
                .foregroundStyle(AppColors.onSurfaceVariant)
                .frame(width: 26)
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 2) {
                Text(kind.name).font(.body.weight(.semibold)).foregroundStyle(AppColors.onSurface)
                Text(kind.text)
                    .font(.footnote)
                    .foregroundStyle(AppColors.onSurfaceVariant)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .accessibilityElement(children: .combine)
    }
}
