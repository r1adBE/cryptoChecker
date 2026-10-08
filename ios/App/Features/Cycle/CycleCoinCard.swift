import SwiftUI
import UIKit

/// Coin-Analyse als letzte Zeile unter «Daten»: rechts die Zone des gewählten Coins, darunter
/// Coin und Kurs. Tippen klappt Auswahl und Analyse auf — auch beim Laden und bei Fehler,
/// damit sich ein anderer Coin wählen lässt. Wie `CoinRow` in Android.
struct CycleCoinRow: View {
    @ObservedObject var viewModel: CycleViewModel
    var divider = true

    @Environment(\.appAccent) private var accent
    @State private var showPicker = false
    @State private var expanded = false

    init(viewModel: CycleViewModel, divider: Bool = true) {
        self.viewModel = viewModel
        self.divider = divider
    }

    var body: some View {
        let report = viewModel.coin.value
        MarketRow(
            title: L("coin_title"),
            secondary: report.map { "\($0.symbol) · \(PriceFormat.priceWithCurrency($0.price, "USDT"))" } ?? viewModel.selectedCoin,
            value: report.map { L($0.zone.labelKey) },
            state: CycleFearGreedRow.rowState(viewModel.coin, failure: L("coin_no_data")),
            divider: divider,
            stamp: viewModel.coinStamp,
            onRetry: { viewModel.loadCoin() },
            expanded: $expanded,
            details: AnyView(VStack(alignment: .leading, spacing: 0) {
                pickerField
                switch viewModel.coin {
                case .loading:
                    CycleCoinReportContent.skeleton
                case .failed:
                    // Meldung und «Erneut» stehen schon in der Zeile
                    EmptyView()
                case .loaded(let report):
                    CycleCoinReportContent(report: report)
                }
            })
        )
        .sheet(isPresented: $showPicker) {
            CycleCoinPickerSheet(viewModel: viewModel)
        }
    }

    /// Feld wie das Auswahlfeld in Android: Beschriftung, gewählter Coin, Pfeil.
    private var pickerField: some View {
        Button {
            showPicker = true
        } label: {
            HStack(spacing: 8) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(L("coin_choose"))
                        .font(.caption)
                        .foregroundStyle(accent.primary)
                    Text(viewModel.selectedCoin)
                        .font(.body.weight(.medium))
                        .foregroundStyle(AppColors.onSurface)
                }
                Spacer(minLength: 0)
                if viewModel.favoriteCoins.contains(viewModel.selectedCoin) {
                    Image(systemName: "star.fill")
                        .font(.footnote)
                        .foregroundStyle(accent.primary)
                }
                Image(systemName: "chevron.up.chevron.down")
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(AppColors.onSurfaceVariant)
            }
            .padding(.horizontal, Spacing.md)
            .padding(.vertical, Spacing.md)
            .background(AppColors.containerLow, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: 14, style: .continuous)
                    .strokeBorder(AppColors.outlineVariant, lineWidth: 1)
            )
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(Text(verbatim: "\(L("coin_choose")): \(viewModel.selectedCoin)"))
    }
}

/// Ergebnis des Coin-Modells.
struct CycleCoinReportContent: View {
    let report: CoinReport

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(spacing: 12) {
                CycleZonePill(zone: report.zone, font: .title3, horizontal: 16, vertical: 5)
                    .layoutPriority(1)
                Text(PriceFormat.priceWithCurrency(report.price, "USDT"))
                    .font(.body.weight(.medium))
                    .monospacedDigit()
                    .foregroundStyle(AppColors.onSurface)
                    .multilineTextAlignment(.trailing)
                    .frame(maxWidth: .infinity, alignment: .trailing)
            }
            .padding(.top, 16)

            CycleZoneGauge(index: report.index)
                .padding(.top, Spacing.md)

            Text(L("market_scores", report.topScore, report.bottomScore))
                .font(.subheadline)
                .monospacedDigit()
                .foregroundStyle(AppColors.onSurface)
                .padding(.top, Spacing.sm)

            // Erklärung je Indikator nur einmal: RSI beim ersten RSI-Eintrag, Pi Cycle bei seinem
            let firstRsi = report.signals.firstIndex { $0.id == .RSI_WEEKLY || $0.id == .RSI_DAILY }
            ForEach(Array(report.signals.enumerated()), id: \.offset) { index, signal in
                CycleSignalRow(
                    name: Self.name(of: signal.id),
                    value: signal.value ?? L("ind_missing"),
                    topPoints: signal.topPoints,
                    bottomPoints: signal.bottomPoints,
                    explanation: Self.explanation(of: signal.id, isFirstRsi: index == firstRsi)
                )
                .padding(.top, 8)
            }

            if (1..<1400).contains(report.historyDays) {
                Text(L("coin_short_history", count: report.historyDays))
                    .font(.footnote)
                    .foregroundStyle(AppColors.onSurfaceVariant)
                    .padding(.top, 8)
            }
            Text(L("coin_price_only"))
                .font(.footnote)
                .foregroundStyle(AppColors.onSurfaceVariant)
                .padding(.top, 8)
        }
    }

    /// Platzhalter in der Form des Ergebnisses: Zonen-Etikett und Kurs, Skala, Scores, je
    /// Signal eine Zeile (Erklärtexte unsichtbar in voller Höhe); der feste Hinweis echt.
    static var skeleton: some View {
        VStack(alignment: .leading, spacing: 0) {
            CycleSkeleton(label: L("loading_hint")) {
                VStack(alignment: .leading, spacing: 0) {
                    HStack(spacing: 12) {
                        CycleSkeletonPill(font: .title3, width: 90, horizontal: 16, vertical: 5)
                        Spacer(minLength: 0)
                        Text(verbatim: " ")
                            .font(.body.weight(.medium))
                            .cycleSkeletonBar(width: 100)
                    }
                    .padding(.top, 16)
                    CycleZoneGaugeSkeleton()
                        .padding(.top, Spacing.md)
                    Text(L("market_scores", 0, 0))
                        .font(.subheadline)
                        .monospacedDigit()
                        .cycleSkeletonLines(.subheadline)
                        .padding(.top, Spacing.sm)
                    ForEach(0..<Self.skeletonExplanations.count, id: \.self) { index in
                        Self.skeletonSignalRow(explanation: Self.skeletonExplanations[index])
                            .padding(.top, 8)
                    }
                }
            }
            Text(L("coin_price_only"))
                .font(.footnote)
                .foregroundStyle(AppColors.onSurfaceVariant)
                .padding(.top, 8)
        }
    }

    /// Erklärtext je Signal wie im Ergebnis (RSI nur beim ersten RSI-Eintrag).
    private static var skeletonExplanations: [String?] {
        let ids: [CoinSignalId] = [.MAYER, .MA200W, .DRAWDOWN, .RSI_WEEKLY, .RSI_DAILY, .PI_CYCLE, .PARABOLIC, .CROSS, .VS_BTC]
        return ids.map { Self.explanation(of: $0, isFirstRsi: $0 == .RSI_WEEKLY) }
    }

    /// Wie `CycleSignalRow`, nur Balken; ein Erklärtext reserviert unsichtbar seine Höhe.
    private static func skeletonSignalRow(explanation: String?) -> some View {
        HStack(alignment: .center, spacing: 12) {
            VStack(alignment: .leading, spacing: 1) {
                Text(verbatim: " ").font(.subheadline).cycleSkeletonBar(width: 120)
                Text(verbatim: " ").font(.footnote).monospacedDigit().cycleSkeletonBar(width: 64)
                if let explanation {
                    Text(explanation)
                        .font(.caption2)
                        .fixedSize(horizontal: false, vertical: true)
                        .cycleSkeletonLines(.caption2)
                        .padding(.top, 1)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            Text(verbatim: " ").font(.caption.weight(.medium)).cycleSkeletonBar(width: 40)
        }
    }

    /// Erklärtext zu RSI und Pi Cycle; sonst nil.
    static func explanation(of id: CoinSignalId, isFirstRsi: Bool) -> String? {
        switch id {
        case .RSI_WEEKLY, .RSI_DAILY: return isFirstRsi ? L("explain_rsi") : nil
        case .PI_CYCLE: return L("explain_pi_cycle")
        default: return nil
        }
    }

    static func name(of id: CoinSignalId) -> String {
        switch id {
        case .MAYER: "Mayer Multiple"
        case .MA200W: L("ind_ma200w")
        case .DRAWDOWN: L("ind_drawdown")
        case .RSI_WEEKLY: L("ind_rsi_weekly")
        case .RSI_DAILY: L("ind_rsi_daily")
        case .PI_CYCLE: L("ind_pi_cycle")
        case .PARABOLIC: L("ind_parabolic")
        case .CROSS: L("ind_cross")
        case .VS_BTC: L("ind_vs_btc")
        }
    }
}

/// Auswahl des Coins mit Suche und Favoriten (Android: durchsuchbare `ComboBox`).
/// Favoriten stehen jeweils zuerst — ohne Suche und innerhalb der Treffer.
@MainActor
struct CycleCoinPickerSheet: View {
    @ObservedObject var viewModel: CycleViewModel

    @Environment(\.dismiss) private var dismiss
    @Environment(\.appAccent) private var accent
    @State private var query = ""

    init(viewModel: CycleViewModel) {
        self.viewModel = viewModel
    }

    private var visible: [String] {
        let coins = viewModel.coins
        let favorites = viewModel.favoriteCoins
        let q = query.trimmingCharacters(in: .whitespaces)
        let matches: [String]
        if q.isEmpty {
            matches = coins
        } else {
            let starts = coins.filter { $0.range(of: q, options: [.caseInsensitive, .anchored]) != nil }
            let contains = coins.filter {
                $0.range(of: q, options: [.caseInsensitive, .anchored]) == nil &&
                    $0.range(of: q, options: .caseInsensitive) != nil
            }
            matches = starts + contains
        }
        return matches.filter { favorites.contains($0) } + matches.filter { !favorites.contains($0) }
    }

    var body: some View {
        NavigationStack {
            ScrollViewReader { proxy in
                List {
                    ForEach(visible, id: \.self) { coin in
                        row(coin)
                    }
                }
                .listStyle(.plain)
                .overlay {
                    if visible.isEmpty {
                        Text(verbatim: "—")
                            .font(.title3)
                            .foregroundStyle(AppColors.onSurfaceVariant)
                    }
                }
                .onAppear {
                    // Ohne Suche zur aktuellen Auswahl springen.
                    let selected = viewModel.selectedCoin
                    DispatchQueue.main.async { proxy.scrollTo(selected, anchor: .center) }
                }
            }
            .searchable(text: $query, placement: .navigationBarDrawer(displayMode: .always))
            .textInputAutocapitalization(.characters)
            .autocorrectionDisabled()
            .onSubmit(of: .search) {
                if let first = visible.first { choose(first) }
            }
            .navigationTitle(L("coin_choose"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(L("action_close")) { dismiss() }
                }
            }
        }
        // Volle Höhe: das Suchfeld (Tastatur) würde ein halbes Blatt sonst auf «gross» springen lassen
        .presentationDetents([.large])
        .presentationDragIndicator(.visible)
    }

    private func row(_ coin: String) -> some View {
        let isFavorite = viewModel.favoriteCoins.contains(coin)
        let isSelected = coin == viewModel.selectedCoin
        return HStack(spacing: 8) {
            Text(coin)
                .font(.body)
                .foregroundStyle(AppColors.onSurface)
                .frame(maxWidth: .infinity, alignment: .leading)
            // Stern direkt in der Zeile; langes Drücken geht weiterhin.
            Button {
                viewModel.toggleFavoriteCoin(coin)
            } label: {
                Image(systemName: isFavorite ? "star.fill" : "star")
                    .font(.body)
                    .foregroundStyle(isFavorite ? accent.primary : AppColors.outline)
                    .frame(width: 36, height: 36)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.borderless)
            .accessibilityLabel(L(isFavorite ? "favorite_remove" : "favorite_add"))
        }
        .contentShape(Rectangle())
        .onTapGesture { choose(coin) }
        .onLongPressGesture {
            UIImpactFeedbackGenerator(style: .medium).impactOccurred()
            viewModel.toggleFavoriteCoin(coin)
        }
        .accessibilityAddTraits(isSelected ? [.isButton, .isSelected] : .isButton)
        .accessibilityAction(named: Text(L(isFavorite ? "favorite_remove" : "favorite_add"))) {
            viewModel.toggleFavoriteCoin(coin)
        }
        .listRowBackground(isSelected ? accent.primary.opacity(0.3) : Color.clear)
        .id(coin)
    }

    private func choose(_ coin: String) {
        if coin != viewModel.selectedCoin { viewModel.selectCoin(coin) }
        dismiss()
    }
}
