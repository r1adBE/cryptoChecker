import SwiftUI
import UIKit

/// Coin-Kennzahlen als letzte Zeile unter «Daten»: rechts Coin und Kurs, darunter die ersten
/// zwei Kennzahlen (reine Messwerte, keine Zone). Tippen klappt Auswahl und Analyse auf — auch beim Laden und bei Fehler,
/// damit sich ein anderer Coin wählen lässt. Wie `CoinRow` in Android.
struct CycleCoinRow: View {
    @ObservedObject var viewModel: CycleViewModel
    var first = false

    @Environment(\.appAccent) private var accent
    @State private var showPicker = false
    @State private var expanded = false

    init(viewModel: CycleViewModel, first: Bool = false) {
        self.viewModel = viewModel
        self.first = first
    }

    var body: some View {
        let report = viewModel.coin.value
        MarketRow(
            title: L("coin_title"),
            secondary: report.map { IndicatorText.summary($0.indicators, currency: "USDT") }.flatMap { $0.isEmpty ? nil : $0 }
                ?? viewModel.selectedCoin,
            value: report.map { "\($0.symbol) · \(PriceFormat.priceWithCurrency($0.price, "USDT"))" },
            state: CycleFearGreedRow.rowState(viewModel.coin, failure: L("coin_no_data")),
            first: first,
            stamp: viewModel.coinStamp,
            onRetry: { viewModel.loadCoin() },
            expanded: $expanded,
            info: .coin,
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
            .background(AppColors.containerLow, in: RoundedRectangle(cornerRadius: Radius.field, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: Radius.field, style: .continuous)
                    .strokeBorder(AppColors.outlineVariant, lineWidth: 1)
            )
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(Text(verbatim: L("label_value", L("coin_choose"), viewModel.selectedCoin)))
    }
}

/// Kennzahlen des gewählten Coins (reine Messwerte, keine Zone) — wie `CoinReportContent` (Android).
struct CycleCoinReportContent: View {
    let report: CoinReport

    /// So viele Kennzahlen liefert `CoinIndicatorModel`.
    static let indicatorCount = 8

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            // Gegenwährung klein und grau wie der Börsenname der Merkliste
            PriceWithQuote(price: PriceFormat.price(report.price), quote: "USDT",
                           font: .body.weight(.medium).monospacedDigit())
                .frame(maxWidth: .infinity, alignment: .trailing)
                .padding(.top, 16)

            IndicatorListView(indicators: report.indicators, currency: "USDT")

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

    /// Platzhalter in der Form des Ergebnisses: Kurs, je Kennzahl eine Zeile; der feste Hinweis echt.
    static var skeleton: some View {
        VStack(alignment: .leading, spacing: 0) {
            SkeletonPulse(label: L("loading_hint")) {
                VStack(alignment: .leading, spacing: 0) {
                    HStack(spacing: 12) {
                        Spacer(minLength: 0)
                        Text(verbatim: " ")
                            .font(.body.weight(.medium))
                            .cycleSkeletonBar(width: 100)
                    }
                    .padding(.top, 16)
                    IndicatorListSkeleton(count: indicatorCount)
                }
            }
            Text(L("coin_price_only"))
                .font(.footnote)
                .foregroundStyle(AppColors.onSurfaceVariant)
                .padding(.top, 8)
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
        // Leerer Stern auf der getönten gewählten Zeile in onSurfaceVariant: outline hielt dort < 3:1
        let emptyStar: Color = isSelected ? AppColors.onSurfaceVariant : AppColors.outline
        let starColor: Color = isFavorite ? accent.primary : emptyStar
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
                    .foregroundStyle(starColor)
                    .frame(width: 36, height: 36)
                    // Trefferfläche 44 × 44 pt (Bedienungshilfen), die Zeile bleibt gleich hoch
                    .frame(minWidth: 44, minHeight: 44)
                    .contentShape(Rectangle())
                    .padding(-4)
            }
            .buttonStyle(.borderless)
            .accessibilityLabel(L(isFavorite ? "favorite_remove" : "favorite_add"))
        }
        .contentShape(Rectangle())
        .onTapGesture { choose(coin) }
        .onLongPressGesture {
            Haptics.play(.longPress)
            viewModel.toggleFavoriteCoin(coin)
        }
        .accessibilityAddTraits(isSelected ? [.isButton, .isSelected] : .isButton)
        .accessibilityAction(named: Text(L(isFavorite ? "favorite_remove" : "favorite_add"))) {
            viewModel.toggleFavoriteCoin(coin)
        }
        .listRowBackground(isSelected ? accent.tint(0.3) : Color.clear)
        .id(coin)
    }

    private func choose(_ coin: String) {
        if coin != viewModel.selectedCoin { viewModel.selectCoin(coin) }
        dismiss()
    }
}
