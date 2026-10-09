import SwiftUI

// MARK: Beträge verbergen

private struct HidePortfolioAmountsKey: EnvironmentKey {
    static let defaultValue = false
}

extension EnvironmentValues {
    /// «Beträge verbergen»: Portfolio-Beträge und -Werte als «•••», Prozente bleiben — gesetzt von
    /// Portfolio-Tab und Detailansicht (Einstellung bzw. Auge im Kopf), wie `LocalHidePortfolioAmounts`.
    var hidePortfolioAmounts: Bool {
        get { self[HidePortfolioAmountsKey.self] }
        set { self[HidePortfolioAmountsKey.self] = newValue }
    }
}

/// Für VoiceOver: `text` oder «Betrag verborgen» statt «•••».
func portfolioSpokenAmount(_ text: String, hidden: Bool) -> String {
    hidden ? L("a11y_amount_hidden") : text
}

/// «62.3%» (eine Nachkommastelle, Dezimalzeichen der Sprache).
private func shareText(_ percent: Double) -> String {
    String(format: "%.1f%%", locale: Locale.current, min(100, max(0, percent)))
}

// MARK: Aufteilung

/// «Aufteilung»: gestapelter Balken und Liste der vier grössten Coins nach Wert (Rest «Andere»)
/// mit Anteil und Wert — wie `AllocationCard` (Android). Werte mit «Beträge verbergen» als «•••».
struct PortfolioAllocationCard: View {
    let slices: [AllocationSlice]
    @Environment(\.hidePortfolioAmounts) private var hidden
    @Environment(\.appAccent) private var accent
    @Environment(\.colorScheme) private var colorScheme

    private static let alphas: [Double] = [1, 0.72, 0.5, 0.32]

    /// Dunkel zur hellen Schrift hin gemischt statt durchsichtig (wie Android `sliceColor`):
    /// durchsichtiges Orange wirkt auf dunklem Grund braun.
    private func color(_ index: Int, _ slice: AllocationSlice) -> Color {
        if slice.isOther { return AppColors.onSurfaceVariant.opacity(0.35) }
        let strength = Self.alphas[min(index, Self.alphas.count - 1)]
        guard colorScheme == .dark else { return accent.primary.opacity(strength) }
        let t = CGFloat((1 - strength) * 0.85)
        var r: CGFloat = 0, g: CGFloat = 0, b: CGFloat = 0, a: CGFloat = 0
        accent.primaryUI.resolvedColor(with: UITraitCollection(userInterfaceStyle: .dark))
            .getRed(&r, green: &g, blue: &b, alpha: &a)
        let light: CGFloat = 0xE2 / 255.0
        return Color(red: r + (light - r) * t, green: g + (light - g) * t, blue: b + (light - b) * t)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text(L("portfolio_allocation_title"))
                .sectionTitleStyle()
                .accessibilityAddTraits(.isHeader)

            // Gestapelter Balken (2 pt Abstand); für VoiceOver zählt die Liste darunter
            GeometryReader { proxy in
                let gaps = CGFloat(max(0, slices.count - 1)) * 2
                let usable = max(0, proxy.size.width - gaps)
                HStack(spacing: 2) {
                    ForEach(Array(slices.enumerated()), id: \.offset) { index, slice in
                        Rectangle()
                            .fill(color(index, slice))
                            .frame(width: max(2, usable * CGFloat(slice.sharePercent) / 100))
                    }
                }
            }
            .frame(height: 10)
            .clipShape(Capsule())
            .padding(.top, Spacing.sm)
            .accessibilityHidden(true)

            VStack(spacing: Spacing.xs) {
                ForEach(Array(slices.enumerated()), id: \.offset) { index, slice in
                    let name = slice.coin ?? L("portfolio_allocation_other")
                    let share = shareText(slice.sharePercent)
                    let value = PortfolioFormat.usdtValue(slice.valueUsd)
                    HStack(spacing: Spacing.sm) {
                        Circle()
                            .fill(color(index, slice))
                            .frame(width: 10, height: 10)
                        Text(name)
                            .font(.subheadline)
                            .foregroundStyle(AppColors.onSurface)
                            .lineLimit(1)
                        Spacer(minLength: 8)
                        Text(share)
                            .font(.caption.monospacedDigit())
                            .foregroundStyle(AppColors.onSurfaceVariant)
                        Text(PortfolioInsights.mask(value, hidden: hidden))
                            .font(AppFont.amount(.subheadline, weight: .medium))
                            .foregroundStyle(AppColors.onSurface)
                            .lineLimit(1)
                            .minimumScaleFactor(0.75)
                    }
                    .accessibilityElement(children: .ignore)
                    .accessibilityLabel(A11y.join([name, share, portfolioSpokenAmount(value, hidden: hidden)]))
                }
            }
            .padding(.top, Spacing.sm)
        }
        .portfolioSurface()
    }
}

// MARK: Alarm «Portfolio-Wert»

/// Neuer Alarm «Portfolio-Wert»: über/unter Betrag (in `currency`) oder steigt/fällt um x %
/// (Basis wie «heute»); wiederholend oder einmalig — wie `PortfolioAlarmDialog` (Android).
struct PortfolioAlarmSheet: View {
    let currency: String
    let basis: ChangeBasis
    let onSave: (PortfolioAlarmKind, Double, Bool) -> Void
    @Environment(\.dismiss) private var dismiss
    @Environment(\.appAccent) private var accent

    @State private var kind: PortfolioAlarmKind = .CHANGE_DOWN
    @State private var text = ""
    @State private var repeating = true
    @FocusState private var focused: Bool

    private var threshold: Double? {
        ThresholdParser.parse(text, decimalSeparator: ThresholdParser.localeDecimalSeparator)
    }

    private var valid: Bool { PortfolioAlarmLogic.isValidThreshold(kind, threshold) }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Picker(selection: $kind) {
                        ForEach(PortfolioAlarmKind.allCases, id: \.self) { option in
                            Text(label(option)).tag(option)
                        }
                    } label: {
                        EmptyView()
                    }
                    .pickerStyle(.inline)
                    .labelsHidden()
                }
                Section {
                    HStack {
                        TextField(kind.isValue ? L("portfolio_alarm_amount", currency) : L("alarm_field_percent"), text: $text)
                            .keyboardType(.decimalPad)
                            .focused($focused)
                            .font(.body.monospacedDigit())
                        Text(kind.isValue ? currency : "%")
                            .foregroundStyle(AppColors.onSurfaceVariant)
                    }
                    SwitchRow(title: L("alarm_repeating"), isOn: $repeating, verticalPadding: 0)
                } footer: {
                    Text(L("portfolio_alarm_hint"))
                }
            }
            .navigationTitle(L("portfolio_alarm_title"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(L("action_cancel")) { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button(L("action_save")) {
                        guard let value = threshold, valid else { return }
                        onSave(kind, value, repeating)
                        dismiss()
                    }
                    .disabled(!valid)
                }
            }
            .onAppear { focused = true }
        }
    }

    private func label(_ option: PortfolioAlarmKind) -> String {
        let name = PortfolioAlarmTexts.kindLabel(option)
        return option.isValue ? name : name + " (" + A11y.changeShortLabel(basis) + ")"
    }
}
