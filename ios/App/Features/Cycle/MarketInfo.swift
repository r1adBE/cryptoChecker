import SwiftUI

// Info-Knopf (i) und Erklär-Blatt je Kennzahl im Markt-Tab (`MarketInfoTopic`).
// Eigene Datei, weil die Erklärungen zu allen Karten gehören (wie `MarketInfo.kt`).

// MARK: Info-Blätter

/// Thema eines Info-Blatts im Markt-Tab: Titel der Karte, «Was ist das?», «So liest du es»,
/// Quelle mit Rhythmus (Schlüssel `market_info_<rawValue>_what|how|source`) und ob der Hinweis
/// «Keine Anlageberatung» dazugehört. Wie `MarketInfoTopic` (MarketInfo.kt).
enum MarketInfoTopic: String, Identifiable {
    case pulse
    case unusual
    case macro
    case fearGreed = "fear_greed"
    case phase
    case dominance
    case altSeason = "alt_season"
    case halving
    case totals
    case gas
    case coin

    var id: String { rawValue }

    var titleKey: String {
        switch self {
        case .pulse: return "pulse_now_title"
        case .unusual: return "unusual_title"
        case .macro: return "macro_title"
        case .fearGreed: return "insights_fng_title"
        case .phase: return "neutral_btc_indicators_title"
        case .dominance: return "insights_dominance_title"
        case .altSeason: return "neutral_alt_title"
        case .halving: return "insights_halving_title"
        case .totals: return "market_cap_title"
        case .gas: return "gas_title"
        case .coin: return "coin_title"
        }
    }

    /// Runde 35: Bitcoin- und Coin-Kennzahlen, Altcoins und Halving mit neutralen Texten (wie Android).
    var whatKey: String {
        switch self {
        case .phase: return "neutral_info_btc_what"
        case .coin: return "neutral_info_coin_what"
        default: return "market_info_\(rawValue)_what"
        }
    }

    var howKey: String {
        switch self {
        case .phase: return "neutral_info_btc_how"
        case .coin: return "neutral_info_coin_how"
        case .altSeason: return "neutral_info_alt_season_how"
        case .halving: return "neutral_info_halving_how"
        default: return "market_info_\(rawValue)_how"
        }
    }
    var sourceKey: String { "market_info_\(rawValue)_source" }

    /// Zeilen «hoch heisst …, tief heisst …» zu Werten der Karte, die «So liest du es» noch
    /// nicht erklärt (Hebel, Funding, Altcoins) — wie `readingRes` (Android).
    var readingKeys: [String] {
        switch self {
        case .pulse: return ["ind_read_leverage"]
        case .unusual: return ["ind_read_funding"]
        case .altSeason: return ["ind_read_alt_season"]
        default: return []
        }
    }

    /// «Keine Anlageberatung» bei allem, was den Markt deutet (nicht bei reinen Messwerten).
    var advice: Bool { self != .totals && self != .gas }

    /// Zeigt CoinGecko-Daten → «Powered by CoinGecko» im Blatt (`CoinGeckoAttribution`). Wie Android.
    var usesCoinGecko: Bool { self == .pulse || self == .unusual || self == .dominance || self == .totals }
}

/// Lage des Platzhalters für das Info-Zeichen in der Titelzeile einer `MarketRow`.
struct MarketInfoAnchorKey: PreferenceKey {
    static let defaultValue: Anchor<CGRect>? = nil

    static func reduce(value: inout Anchor<CGRect>?, nextValue: () -> Anchor<CGRect>?) {
        value = value ?? nextValue()
    }
}

/// Kleines «ⓘ» hinter dem Titel einer Karte (18 pt, Nebenfarbe) mit 44 pt Tippfläche. Mit
/// `compact` belegt es in der Zeile nur `slotWidth` × `slotHeight` (die Titelzeile wird nicht
/// höher), die Tippfläche ragt darüber hinaus. Tippen öffnet das Info-Blatt und klappt die Karte
/// nicht auf. VoiceOver: Knopf «Info: <Titel>». Wie `MarketInfoButton` (MarketInfo.kt).
struct MarketInfoButton: View {
    static let slotWidth: CGFloat = 26
    static let slotHeight: CGFloat = 20
    static let touch: CGFloat = 44

    let topic: MarketInfoTopic
    var compact = false

    @State private var open = false

    var body: some View {
        Button {
            open = true
        } label: {
            Image(systemName: "info.circle")
                .font(.system(size: 18))
                .foregroundStyle(AppColors.onSurfaceVariant)
                .frame(width: Self.touch, height: Self.touch)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(L("market_info_a11y", L(topic.titleKey)))
        .frame(width: compact ? Self.slotWidth : nil, height: compact ? Self.slotHeight : nil)
        .sheet(isPresented: $open) {
            MarketInfoSheet(topic: topic)
        }
    }
}

/// Kleines Blatt zu einer Karte: Titel (Überschrift), «Was ist das?», «So liest du es» (mit den Zeilen
/// «hoch heisst …» des Themas), eine graue Zeile mit Quelle und Rhythmus, bei Deutungen «Keine Anlageberatung», unten «Schliessen».
/// Wie `MarketInfoSheet` (MarketInfo.kt).
struct MarketInfoSheet: View {
    let topic: MarketInfoTopic

    @Environment(\.dismiss) private var dismiss
    @Environment(\.appAccent) private var accent

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                Text(L(topic.titleKey))
                    .font(.title3.weight(.semibold))
                    .foregroundStyle(AppColors.onSurface)
                    .accessibilityAddTraits(.isHeader)
                section(L("market_info_what"), L(topic.whatKey))
                section(L("market_info_how"), L(topic.howKey))
                // «hoch heisst …, tief heisst …» direkt unter «So liest du es»
                ForEach(topic.readingKeys, id: \.self) { key in
                    Text(L(key))
                        .font(.body)
                        .foregroundStyle(AppColors.onSurface)
                        .fixedSize(horizontal: false, vertical: true)
                        .padding(.top, Spacing.xs)
                }
                footnotes
                closeButton
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(24)
        }
        .presentationDetents([.medium, .large])
        .presentationDragIndicator(.visible)
        .presentationBackground(AppColors.background)
    }

    /// Kleine Zwischenzeile (halbfett) und zwei bis drei kurze Sätze darunter.
    private func section(_ label: String, _ text: String) -> some View {
        VStack(alignment: .leading, spacing: Spacing.xs) {
            Text(label)
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(AppColors.onSurface)
            Text(text)
                .font(.body)
                .foregroundStyle(AppColors.onSurface)
                .fixedSize(horizontal: false, vertical: true)
        }
        .padding(.top, Spacing.lg)
    }

    /// Quelle und Rhythmus, bei Deutungen «Keine Anlageberatung» (grau).
    private var footnotes: some View {
        VStack(alignment: .leading, spacing: Spacing.xs) {
            Text(L(topic.sourceKey))
            if topic.usesCoinGecko {
                Text(verbatim: DataFreshness.poweredByCoinGecko)
            }
            if topic.advice {
                Text(L("market_info_no_advice"))
            }
        }
        .font(.footnote)
        .foregroundStyle(AppColors.onSurfaceVariant)
        .fixedSize(horizontal: false, vertical: true)
        .padding(.top, Spacing.lg)
    }

    private var closeButton: some View {
        Button(L("action_close")) { dismiss() }
            .font(.headline)
            .foregroundStyle(accent.primary)
            .frame(maxWidth: .infinity, alignment: .trailing)
            .padding(.top, Spacing.md)
    }
}
