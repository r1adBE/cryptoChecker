import SwiftUI

/// «Kurz gesagt» ganz oben im Markt-Tab: bis zu drei Sätze in Alltagssprache (`MarketPlainSummary`),
/// je mit Symbol, darunter der Hinweis «keine Prognose, keine Anlageberatung». Die Karten darunter
/// bleiben unverändert.
///
/// Erscheint erst, wenn Puls, «Heute auffällig» und Fear & Greed ihren ersten Stand haben (geladen,
/// gescheitert oder aus dem Zwischenspeicher) — so kommen nicht Satz für Satz neue Zeilen dazu.
/// Ohne einen einzigen gültigen Satz entfällt der Block ganz (kein Platzhalter, keine Vermutung).
/// Wie `MarketPlainSummaryCard.kt`.
struct MarketPlainSummaryCard: View {
    @ObservedObject var viewModel: CycleViewModel
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        // Uhr läuft mit (alle 30 s wie Android): ein Satz verschwindet, sobald seine Daten veraltet sind
        TimelineView(.periodic(from: .now, by: 30)) { context in
            let summary = plainSummary(now: Int64(context.date.timeIntervalSince1970 * 1000))
            let visible = ready && !summary.isEmpty
            // Kommt der Block erst nach den Karten (erster Start ohne Zwischenspeicher), schiebt er sie
            // weich nach unten statt mit einem Sprung
            VStack(spacing: 0) {
                if visible {
                    content(summary)
                        .transition(.opacity.combined(with: .move(edge: .top)))
                }
            }
            .animation(reduceMotion ? nil : .easeInOut(duration: 0.2), value: visible)
        }
    }

    private var ready: Bool {
        !viewModel.pulse.isLoading && !viewModel.unusual.isLoading && !viewModel.fearGreed.isLoading
    }

    private func plainSummary(now: Int64) -> PlainSummary {
        let fearGreed = viewModel.fearGreed.value
        return MarketPlainSummary.evaluate(PlainSummaryInput(
            now: now,
            pulseMarketChange24h: pulseMarketChange,
            pulseStamp: viewModel.pulseStamp,
            globalMarketChange24h: viewModel.globalMarket.value?.change24hPercent,
            globalStamp: viewModel.globalStamp,
            fearGreed: fearGreed?.value,
            fearGreedTime: fearGreed?.time,
            fearGreedStamp: viewModel.fearGreedStamp,
            unusual: viewModel.unusual.value,
            unusualStamp: viewModel.unusualStamp
        ))
    }

    /// Veränderung des Gesamtmarkts aus dem Puls — dieselbe Prüfung wie die Puls-Karte (ohne BTC,
    /// ETH, SOL oder ohne gültige Marktkapitalisierung kein Wert; Android: `PulseReport.marketCap24h`).
    private var pulseMarketChange: Double? {
        guard let data = viewModel.pulse.value else { return nil }
        return CryptoPulse.evaluate(PulseInput(
            btc: data.btc, eth: data.eth, sol: data.sol,
            volumeRatio: nil, fearGreed: nil, fundingPercent: nil, ethGasGwei: nil,
            marketCapUsd: data.marketCapUsd,
            marketCap24h: data.marketCap24h
        ))?.marketCap24h
    }

    private func content(_ summary: PlainSummary) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            Text(L("market_plain_title"))
                .sectionTitleStyle()
                .accessibilityAddTraits(.isHeader)
            if let direction = summary.direction {
                PlainLine(symbol: Self.directionSymbol(direction.kind), text: Self.directionText(direction))
            }
            if let mood = summary.mood {
                PlainLine(symbol: "gauge.with.dots.needle.50percent", text: Self.moodText(mood))
            }
            if let standout = summary.standout {
                PlainLine(symbol: "flag", text: Self.standoutText(standout))
            }
            Text(L("market_plain_footnote"))
                .font(.caption2)
                .foregroundStyle(AppColors.onSurfaceVariant)
                .fixedSize(horizontal: false, vertical: true)
                .padding(.top, Spacing.md)
            // Richtung (Marktkapitalisierung) und «Heute auffällig» (Umsatz-Anteil) nutzen CoinGecko-Daten
            if summary.direction != nil || summary.standout != nil {
                CoinGeckoAttribution()
            }
        }
        .appCard()
        // Den Abstand zur Karte darunter bringt dieser Block mit (die Puls-Karte hat oben keinen)
        .padding(.bottom, Spacing.md)
    }

    // MARK: Texte

    private static func directionSymbol(_ kind: PlainDirectionKind) -> String {
        switch kind {
        case .flat: "chart.line.flattrend.xyaxis"
        case .upSlight, .upClear: "chart.line.uptrend.xyaxis"
        case .downSlight, .downClear: "chart.line.downtrend.xyaxis"
        }
    }

    private static func directionText(_ direction: PlainDirection) -> String {
        let key = switch direction.kind {
        case .flat: "market_plain_direction_flat"
        case .upSlight: "market_plain_direction_up_slight"
        case .upClear: "market_plain_direction_up_clear"
        case .downSlight: "market_plain_direction_down_slight"
        case .downClear: "market_plain_direction_down_clear"
        }
        return L(key, PriceFormat.signedPercent(direction.changePercent, decimals: 1))
    }

    private static func moodText(_ mood: PlainMood) -> String {
        let key = switch mood.kind {
        case .extremeFear: "market_plain_mood_extreme_fear"
        case .fear: "market_plain_mood_fear"
        case .neutral: "market_plain_mood_neutral"
        case .greed: "market_plain_mood_greed"
        case .extremeGreed: "market_plain_mood_extreme_greed"
        }
        return L(key, mood.value)
    }

    private static func standoutText(_ standout: PlainStandout) -> String {
        let coin = standout.coin ?? ""
        func percent(_ value: Double?) -> String { PriceFormat.signedPercent(value ?? 0, decimals: 1) }
        switch standout.kind {
        case .aboveBtc:
            return L("market_plain_standout_above_btc", coin, percent(standout.change24h), percent(standout.btc24h))
        case .belowBtc:
            return L("market_plain_standout_below_btc", coin, percent(standout.change24h), percent(standout.btc24h))
        case .againstBtc:
            return L("market_plain_standout_against_btc", coin, percent(standout.change24h), percent(standout.btc24h))
        case .volume:
            return L("market_plain_standout_volume", coin, LocaleNumbers.decimal(standout.volumeRatio ?? 0, maxDecimals: 1))
        case .futuresUp:
            return L("market_plain_standout_futures_up", coin)
        case .futuresDown:
            return L("market_plain_standout_futures_down", coin)
        case .quiet:
            return L("market_plain_standout_quiet")
        }
    }
}

/// Eine Zeile: Symbol (nur Schmuck, Nebenfarbe — die Richtung steht im Satz, nie nur in der
/// Farbe) und der Satz in normaler Lesegrösse. VoiceOver liest nur den Satz.
private struct PlainLine: View {
    let symbol: String
    let text: String
    /// Symbol wächst mit der Schriftgrösse (Dynamic Type), wie der Text daneben.
    @ScaledMetric(relativeTo: .body) private var iconSize: CGFloat = 20

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: Spacing.md) {
            Image(systemName: symbol)
                .font(.system(size: iconSize * 0.85))
                .frame(width: iconSize)
                .foregroundStyle(AppColors.onSurfaceVariant)
                .accessibilityHidden(true)
            Text(text)
                .font(.body)
                .foregroundStyle(AppColors.onSurface)
                .fixedSize(horizontal: false, vertical: true)
        }
        .padding(.top, Spacing.md)
        .accessibilityElement(children: .combine)
    }
}
