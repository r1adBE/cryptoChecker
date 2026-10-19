import SwiftUI

/// «Kurz gesagt»: bis zu drei Sätze in Alltagssprache (`MarketPlainSummary`) als Körper der Heldenkarte
/// (`CryptoPulseCard`, direkt unter der Schlagzeile). Gibt es erst, wenn Puls, «Heute auffällig» und Fear & Greed
/// ihren ersten Stand haben (geladen, gescheitert oder aus dem Zwischenspeicher) — so kommen nicht Satz für Satz
/// neue Zeilen dazu. nil = noch nicht bereit oder kein einziger gültiger Satz (dann entfällt der Block ganz: kein
/// Platzhalter, keine Vermutung). Der Aufrufer rechnet alle 30 s neu (wie Android): ein Satz verschwindet, sobald
/// seine Daten veraltet sind. Wie `rememberPlainSummary` (MarketPlainSummaryCard.kt).
@MainActor
enum MarketPlainSummaryBlock {
    static func summary(_ viewModel: CycleViewModel, now: Int64) -> PlainSummary? {
        let ready = !viewModel.pulse.isLoading && !viewModel.unusual.isLoading && !viewModel.fearGreed.isLoading
        guard ready else { return nil }
        let fearGreed = viewModel.fearGreed.value
        let summary = MarketPlainSummary.evaluate(PlainSummaryInput(
            now: now,
            pulseMarketChange24h: pulseMarketChange(viewModel),
            pulseStamp: viewModel.pulseStamp,
            globalMarketChange24h: viewModel.globalMarket.value?.change24hPercent,
            globalStamp: viewModel.globalStamp,
            fearGreed: fearGreed?.value,
            fearGreedTime: fearGreed?.time,
            fearGreedStamp: viewModel.fearGreedStamp,
            unusual: viewModel.unusual.value,
            unusualStamp: viewModel.unusualStamp
        ))
        return summary.isEmpty ? nil : summary
    }

    /// Veränderung des Gesamtmarkts aus dem Puls — dieselbe Prüfung wie die Puls-Karte (ohne BTC,
    /// ETH, SOL oder ohne gültige Marktkapitalisierung kein Wert; Android: `PulseReport.marketCap24h`).
    private static func pulseMarketChange(_ viewModel: CycleViewModel) -> Double? {
        guard let data = viewModel.pulse.value else { return nil }
        return CryptoPulse.evaluate(PulseInput(
            btc: data.btc, eth: data.eth, sol: data.sol,
            volumeRatio: nil, fearGreed: nil, fundingPercent: nil, ethGasGwei: nil,
            marketCapUsd: data.marketCapUsd,
            marketCap24h: data.marketCap24h
        ))?.marketCap24h
    }
}

extension PlainSummary {
    /// Richtung (Marktkapitalisierung) und «Heute auffällig» (Umsatz-Anteil) nutzen CoinGecko-Daten.
    var usesCoinGecko: Bool { direction != nil || standout != nil }
}

/// Die Sätze von «Kurz gesagt» in der Heldenkarte: kleine Unterüberschrift (für VoiceOver eine Überschrift),
/// darunter je Satz eine Zeile mit Symbol. Kommt der Block erst nach der Schlagzeile (erster Start ohne
/// Zwischenspeicher), schiebt er die Belege weich nach unten statt mit einem Sprung; ohne Animation bei
/// reduzierter Bewegung. Hinweis und Quelle stehen am Fuss der Karte. Wie `PlainSummaryBlock` (Android).
struct MarketPlainLines: View {
    let summary: PlainSummary?
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            if let summary {
                content(summary)
                    .transition(.opacity.combined(with: .move(edge: .top)))
            }
        }
        .animation(reduceMotion ? nil : .easeInOut(duration: 0.2), value: summary != nil)
    }

    private func content(_ summary: PlainSummary) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            Text(L("market_plain_title"))
                .font(.subheadline.weight(.medium))
                .foregroundStyle(AppColors.onSurfaceVariant)
                .accessibilityAddTraits(.isHeader)
            if let direction = summary.direction {
                PlainLine(symbol: Self.directionSymbol(direction.kind), text: MarketPlainSummaryText.direction(direction))
            }
            if let mood = summary.mood {
                PlainLine(symbol: "gauge.with.dots.needle.50percent", text: MarketPlainSummaryText.mood(mood))
            }
            if let standout = summary.standout {
                PlainLine(symbol: "flag", text: MarketPlainSummaryText.standout(standout))
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.top, Spacing.md)
    }

    // MARK: Texte

    private static func directionSymbol(_ kind: PlainDirectionKind) -> String {
        switch kind {
        case .flat: "chart.line.flattrend.xyaxis"
        case .upSlight, .upClear: "chart.line.uptrend.xyaxis"
        case .downSlight, .downClear: "chart.line.downtrend.xyaxis"
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
        .padding(.top, Spacing.sm)
        .accessibilityElement(children: .combine)
    }
}
