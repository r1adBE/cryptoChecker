import SwiftUI

// Bedienelemente der Verlaufskarte: Zeitraum-Chips mit Schalter Kerzen | Linie, «Wert | Gewinn/Verlust»
// und «CHF | USDT | Vergleich».
// Eigene Datei, weil es reine Schalter ohne eigene Rechnung sind (wie `PortfolioHistoryControls.kt`).

extension PortfolioHistoryCard {
    /// Zeitraum und Kerzen/Linie unter dem Chart.
    var controls: some View {
        chips
            .padding(.top, Spacing.sm)
    }

    /// Zeitraum-Chips links, Kerzen/Linie rechts in derselben Zeile (wie im Aktionsblatt); reicht der
    /// Platz nicht (lange Übersetzungen, grosse Schrift), brechen die Chips um und der Schalter rutscht
    /// rechtsbündig darunter. Im «Vergleich» ohne Schalter (nur Linien).
    private var chips: some View {
        ViewThatFits(in: .horizontal) {
            HStack(spacing: Spacing.xs) {
                rangeChips
                if shownView != .compare {
                    Spacer(minLength: Spacing.xs)
                    typeToggle
                }
            }
            VStack(alignment: .trailing, spacing: 8) {
                FlowLayout(spacing: Spacing.xs) { rangeChips }
                    .frame(maxWidth: .infinity, alignment: .leading)
                if shownView != .compare {
                    typeToggle
                }
            }
        }
        .padding(.vertical, 2)
    }

    @ViewBuilder
    private var rangeChips: some View {
        ForEach(PortfolioHistoryRange.allCases) { option in
            rangeChip(option)
        }
    }

    private func rangeChip(_ option: PortfolioHistoryRange) -> some View {
        let selected = option == range
        return Button {
            range = option
        } label: {
            Text(option.chipLabel)
                .font(.subheadline.weight(selected ? .semibold : .regular))
                .lineLimit(1)
                .padding(.horizontal, 12)
                .padding(.vertical, Spacing.sm)
                .foregroundStyle(selected ? accent.onContainer : AppColors.onSurface)
                .background(selected ? accent.container : AppColors.containerHigh, in: Capsule())
                .contentShape(Capsule())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(option.chipAccessibility)
        .accessibilityAddTraits(selected ? .isSelected : [])
    }

    /// Kerzen | Linie als zwei Symbole in einer Pille — wie der Schalter im Aktionsblatt
    /// (je Hälfte 44 × 44 pt); VoiceOver: «Kerzen»/«Linie» mit «ausgewählt».
    private var typeToggle: some View {
        HStack(spacing: 0) {
            typeSegment(nil, label: L("widget_chart_candles"), selected: candles) {
                guard !candles else { return }
                Haptics.play(.selection)
                candles = true
            }
            Rectangle()
                .fill(AppColors.outlineVariant)
                .frame(width: 1, height: 44)
            typeSegment("chart.xyaxis.line", label: L("widget_chart_line"), selected: !candles) {
                guard candles else { return }
                Haptics.play(.selection)
                candles = false
            }
        }
        .background(AppColors.containerHigh, in: Capsule())
        .overlay(Capsule().strokeBorder(AppColors.outlineVariant, lineWidth: 1))
        .clipShape(Capsule())
        .fixedSize()
    }

    /// `symbol` nil = Kerzen-Symbol (wie im Aktionsblatt, `CandlesIcon`).
    private func typeSegment(_ symbol: String?, label: String, selected: Bool,
                             action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Group {
                if let symbol {
                    Image(systemName: symbol)
                        .scaledFont(size: 15, weight: .semibold, relativeTo: .subheadline)
                        .dynamicTypeSize(...DynamicTypeSize.xxxLarge)
                } else {
                    CandlesIcon().frame(width: 18, height: 18)
                }
            }
            .foregroundStyle(selected ? accent.onContainer : AppColors.onSurfaceVariant)
            // Trefferfläche 44 × 44 pt
            .frame(width: 44, height: 44)
            .background(selected ? accent.container : Color.clear)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(label)
        .accessibilityAddTraits(selected ? .isSelected : [])
    }

    /// Chips «Wert | Gewinn/Verlust» über dem Chart (wie die Zeitraum-Chips); VoiceOver mit «ausgewählt».
    var modeSwitch: some View {
        HStack(spacing: Spacing.xs) {
            modeChip(L("portfolio_value"), selected: !pnl) { pnl = false }
            modeChip(L("ledger_gain_title"), selected: pnl) { pnl = true }
        }
    }

    private func modeChip(_ label: String, selected: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(label)
                .font(.subheadline.weight(selected ? .semibold : .regular))
                .lineLimit(1)
                .padding(.horizontal, 12)
                .padding(.vertical, Spacing.sm)
                .foregroundStyle(selected ? accent.onContainer : AppColors.onSurface)
                .background(selected ? accent.container : AppColors.containerHigh, in: Capsule())
                .contentShape(Capsule())
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(selected ? .isSelected : [])
    }

    /// Umschalter «CHF | USDT | Vergleich» (ohne Ausgangswert ohne «Vergleich»); VoiceOver liest die
    /// Segmente mit Auswahl-Zustand («Wert in CHF», «Vergleich CHF und USDT in Prozent»).
    func viewSwitch(_ history: PortfolioHistoryUi) -> some View {
        let options: [PortfolioHistoryView] = history.compare != nil ? PortfolioHistoryView.allCases : [.currency, .usdt]
        let selection = Binding<PortfolioHistoryView>(get: { shownView }, set: { view = $0 })
        return Picker(L("portfolio_history_title"), selection: selection) {
            ForEach(options) { option in
                Text(Self.viewLabel(option, currency: history.unit))
                    .accessibilityLabel(Self.viewAccessibility(option, currency: history.unit))
                    .tag(option)
            }
        }
        .pickerStyle(.segmented)
    }

    static func viewLabel(_ option: PortfolioHistoryView, currency: String) -> String {
        switch option {
        case .currency: return currency
        case .usdt: return PortfolioFormat.usdt
        case .compare: return L("portfolio_compare")
        }
    }

    static func viewAccessibility(_ option: PortfolioHistoryView, currency: String) -> String {
        switch option {
        case .currency: return L("portfolio_history_in_a11y", currency)
        case .usdt: return L("portfolio_history_in_a11y", PortfolioFormat.usdt)
        case .compare: return L("portfolio_compare_a11y", currency)
        }
    }
}
