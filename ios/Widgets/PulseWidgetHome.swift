import SwiftUI
import UIKit
import WidgetKit

// Ansicht des Widgets «Was gerade auffällt» (wie `PulseWidgetRenderer.kt`): Schlagzeile, Leitsatz, Coins,
// Fusszeile und «Kurz gesagt». Eigene Datei neben `PulseWidget.swift` (Einrichten, Daten, Zeitplan).

/// Klein: Schlagzeile, Leitsatz (2 Zeilen), BTC-Chip; mittel: alle drei Chips, der erste Satz von
/// «Kurz gesagt» statt des Leitsatzes (wenn er ganz in dessen 2 Zeilen passt); gross wie mittel mit dem
/// Leitsatz, darunter der Block «Kurz gesagt».
struct PulseHomeView: View {
    let entry: PulseEntry
    let palette: WidgetPalette
    let size: PulseWidgetSize

    /// Mittel und gross: drei Coins, Fear & Greed, Marktbreite (klein unverändert).
    private var wide: Bool { size != .small }

    /// Innenrand (pt, wie Android 12 dp) — auch für die Breite beim Messen des ersten Satzes.
    private var inset: CGFloat { 12 }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            header
            if let report = entry.report, let data = entry.data {
                content(report, data: data)
            } else {
                Spacer(minLength: 4)
                Text(L("pulse_unavailable"))
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(palette.secondary)
                    .fixedSize(horizontal: false, vertical: true)
                    .frame(maxWidth: .infinity, alignment: .leading)
                Spacer(minLength: 4)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .padding(inset)
    }

    private var header: some View {
        HStack(spacing: 4) {
            WidgetLogo(accent: entry.accent, dark: palette.dark, size: 16)
            // Titel ganz (notfalls kleiner) oder nur das Logo — nie «Was gerade a…»
            ViewThatFits(in: .horizontal) {
                titleText(size: 12.5)
                titleText(size: 11)
                Color.clear.frame(width: 0, height: 0)
            }
            Spacer(minLength: 0)
        }
        .accessibilityHidden(true)
    }

    private func titleText(size: CGFloat) -> some View {
        Text(L("pulse_now_title"))
            .font(.system(size: size, weight: .bold))
            .foregroundStyle(palette.text)
            .lineLimit(1)
            .fixedSize(horizontal: true, vertical: false)
    }

    /// Coins BTC, ETH, SOL als farbiger Text — nur so viele, wie ganz passen (klein: BTC), nie ein abgeschnittener.
    @ViewBuilder
    private func chips(_ coins: [PulseCoinLine]) -> some View {
        ViewThatFits(in: .horizontal) {
            ForEach(Array((1...max(1, coins.count)).reversed()), id: \.self) { count in
                HStack(spacing: 4) {
                    // «24h» klein und grau davor: die Veränderungen sind 24-h-Werte (wie Android)
                    Text(L("widget_range_short_24h"))
                        .widgetSecondaryFont(weight: .medium)
                        .foregroundStyle(palette.secondary)
                        .lineLimit(1)
                        .fixedSize()
                        .accessibilityHidden(true)
                    // Coins als Text ohne Pille: 8 pt Abstand (wie Android `PulseWidgetMath.CHIP_GAP_DP`)
                    HStack(spacing: 8) {
                        ForEach(coins.prefix(count), id: \.name) { coin in
                            PulseWidgetChip(coin: coin, palette: palette)
                        }
                    }
                }
            }
        }
    }

    /// Eine Fassung für `ViewThatFits`: zweite Nebenzeile erlaubt (`twoLines`) und so viele Sätze von
    /// «Kurz gesagt» (`plainCount`, nur gross).
    private struct Variant: Hashable {
        let twoLines: Bool
        let plainCount: Int
    }

    /// Leitsatz: mittel der erste Satz von «Kurz gesagt», wenn er ganz in die 2 Zeilen des Leitsatzes passt
    /// (gemessen wie Android `PulseWidgetPlain.sentenceAsLead`); sonst der bisherige Leitsatz.
    private func lead(_ report: PulseReport, sentences: [String]) -> String {
        if size == .medium, let first = sentences.first,
           PulseWidgetPlain.sentenceAsLead(
               sentenceLines: PulseWidgetMeasure.lines(first, size: PulseWidgetPlain.leadSize,
                                                       width: entry.displayWidth - 2 * inset - 2),
               leadLines: PulseWidgetPlain.leadLines) {
            return first
        }
        return PulseWidgetText.lead(report)
    }

    private func content(_ report: PulseReport, data: PulseMarketData) -> some View {
        let coins = Array(report.coins.prefix(wide ? 3 : 1))
        let time: String? = data.time > 0 ? L("pulse_updated", PriceFormat.time(data.time)) : nil
        // Fear & Greed nur mittel/gross (klein unverändert), in der Zeile des Stands
        let fearGreed: String? = wide ? entry.fearGreed.map(FearGreedShared.line) : nil
        let fearGreedText: Text? = wide ? entry.fearGreed.map { PulseSideLine.fearGreed($0, palette: palette) } : nil
        // Marktbreite «Top 30 · 24h  ↗ 22  ↘ 8» (mittel/gross), neben Fear & Greed, wenn Platz ist
        let breadth: Text? = wide ? report.breadth.map { PulseSideLine.breadth($0, palette: palette) } : nil
        let sentences: [String] = size == .small ? [] : entry.plainSentences
        let leadText = lead(report, sentences: sentences)
        // Zuerst die bisherige Anordnung (zwei Nebenzeilen nur, wenn die Höhe reicht), darunter im grossen
        // Widget so viele ganze Sätze, wie noch passen — die Coins behalten ihren Platz, Sätze fallen von hinten weg
        let twoLineOptions = fearGreedText != nil && breadth != nil ? [true, false] : [false]
        let counts = size == .large ? PulseWidgetPlain.candidateCounts(sentences.count) : [0]
        let variants = twoLineOptions.flatMap { two in counts.map { Variant(twoLines: two, plainCount: $0) } }
        return ViewThatFits(in: .vertical) {
            ForEach(variants, id: \.self) { variant in
                let plain = Array(sentences.prefix(variant.plainCount))
                stack(report, coins: coins, time: time, fearGreed: fearGreedText, breadth: breadth,
                      twoLines: variant.twoLines, lead: leadText, plain: plain)
                    // Je Fassung ein eigener Satz für VoiceOver: genau das, was sie zeigt
                    .accessibilityElement(children: .ignore)
                    .accessibilityLabel(PulseWidgetText.spoken(report, coins: coins, fearGreed: fearGreed, time: time,
                                                               breadth: wide ? report.breadth : nil,
                                                               lead: leadText, plain: plain,
                                                               plainNote: entry.plainNote(shownCount: plain.count)))
            }
        }
    }

    /// Schlagzeile, Leitsatz, Coins und Fusszeile; `twoLines` erlaubt die Marktbreite in einer zweiten Zeile;
    /// darunter «Kurz gesagt» mit den Sätzen `plain` (leer = kein Block).
    private func stack(_ report: PulseReport, coins: [PulseCoinLine], time: String?, fearGreed fearGreedText: Text?,
                       breadth: Text?, twoLines: Bool, lead leadText: String, plain: [String]) -> some View {
        let glyph = PulseWidgetText.glyph(report.summary)
        let direction = PulseWidgetText.direction(report.summary)
        let headlineSize: CGFloat = wide ? 19 : 17
        return VStack(alignment: .leading, spacing: 0) {
            HStack(alignment: .firstTextBaseline, spacing: 4) {
                if let glyph {
                    Text(glyph)
                        .font(.system(size: 13, weight: .bold))
                        .foregroundStyle(direction > 0 ? palette.up : palette.down)
                }
                // Erst kleiner (bis 14 pt), dann zweizeilig — nie «Breite Stä…»
                Text(PulseWidgetText.headline(report.summary))
                    .font(.system(size: headlineSize, weight: .bold))
                    .foregroundStyle(palette.text)
                    .lineLimit(2)
                    .minimumScaleFactor(14 / headlineSize)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .padding(.top, 4)
            Text(leadText)
                .font(.system(size: PulseWidgetPlain.leadSize))
                .foregroundStyle(palette.text)
                .lineLimit(2)
                .fixedSize(horizontal: false, vertical: true)
                .padding(.top, 3)
            Spacer(minLength: 4)
            chips(coins)
            if time != nil || fearGreedText != nil || breadth != nil {
                // «Stand 14:05» links, rechts «Fear & Greed 72 · Gier · Top 30 ↗ 22 ↘ 8»; passt nicht
                // alles, die Marktbreite in einer zweiten Zeile darunter (`twoLines`), sonst zuerst
                // Fear & Greed, dann die Marktbreite, sonst nur der Stand (wie Android `PulseWidgetMath.sideLayout`)
                ViewThatFits(in: .horizontal) {
                    ForEach(PulseSideLayout.candidates(hasFearGreed: fearGreedText != nil, hasBreadth: breadth != nil,
                                                       twoLines: twoLines), id: \.self) { layout in
                        foot(layout, time: time, fearGreed: fearGreedText, breadth: breadth,
                             breadthDot: report.breadth.map { PulseSideLine.breadthDot($0, palette: palette) })
                    }
                    footRow(time, nil)
                }
                .padding(.top, 4)
            }
            if !plain.isEmpty {
                plainBlock(plain, note: entry.plainNote(shownCount: plain.count))
            }
        }
    }

    /// «Kurz gesagt» (gross): Titel, je Satz eine eigene Zeile — ganz, nie abgeschnitten (`ViewThatFits`
    /// lässt sonst Sätze von hinten weg) —, darunter die Quellen und der Hinweis. Abstände wie Android `PulseWidgetPlain`.
    private func plainBlock(_ sentences: [String], note: String) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            Text(L("market_plain_title"))
                .font(.system(size: PulseWidgetPlain.titleSize, weight: .bold))
                .foregroundStyle(palette.text)
                .lineLimit(1)
            ForEach(Array(sentences.enumerated()), id: \.offset) { _, sentence in
                Text(sentence)
                    .font(.system(size: PulseWidgetPlain.sentenceSize))
                    .foregroundStyle(palette.text)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.top, PulseWidgetPlain.sentenceGap)
            }
            // «Daten: CoinGecko, alternative.me · keine Prognose, keine Anlageberatung»
            Text(note)
                .font(.system(size: PulseWidgetPlain.footnoteSize))
                .foregroundStyle(palette.secondary)
                .fixedSize(horizontal: false, vertical: true)
                .padding(.top, PulseWidgetPlain.footnoteGap)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.top, PulseWidgetPlain.blockTop)
    }

    /// Fusszeile in der Anordnung `layout`; bei `.twoLines` die Marktbreite rechts in einer zweiten Zeile.
    @ViewBuilder
    private func foot(_ layout: PulseSideLayout, time: String?, fearGreed: Text?, breadth: Text?,
                      breadthDot: Text? = nil) -> some View {
        switch layout {
        case .oneLine:
            if let fearGreed, let breadth { footRow(time, fearGreed + Text(verbatim: " · ") + breadth) }
        case .twoLines:
            if let fearGreed, let breadth {
                VStack(spacing: 2) {
                    footRow(time, fearGreed)
                    // Eigener Punkt vor «Top 30», damit beide Zeilen gleich aussehen (wie Android)
                    footRow(nil, breadthDot.map { $0 + breadth } ?? breadth)
                }
            }
        case .fearGreed:
            if let fearGreed { footRow(time, fearGreed) }
        case .breadth:
            if let breadth { footRow(time, breadth) }
        }
    }

    /// «Stand 14:05» links, rechts die Nebenangabe (oder nichts).
    private func footRow(_ time: String?, _ right: Text?) -> some View {
        HStack(spacing: 8) {
            if let time { footnote(Text(time)) }
            Spacer(minLength: 0)
            if let right { footnote(right) }
        }
    }

    /// Grau, ausser Teilen mit eigener Farbe (Fear-&-Greed-Zahl, ↗/↘ der Marktbreite).
    private func footnote(_ text: Text) -> some View {
        text
            .widgetSecondaryFont(weight: .medium, monospacedDigit: true)
            .foregroundStyle(palette.secondary)
            .lineLimit(1)
            .fixedSize(horizontal: true, vertical: false)
    }
}

/// Zeilen eines umbrochenen Texts, gemessen wie ein `Text` in der Systemschrift (für den ersten Satz von
/// «Kurz gesagt» im mittleren Widget; wie Android `WidgetToolkit.textBlock`).
enum PulseWidgetMeasure {
    /// Zeilen von `text` in der Systemschrift `size` (pt) auf `width` (pt) umbrochen; nil = Breite unbekannt.
    static func lines(_ text: String, size: CGFloat, width: CGFloat) -> Int? {
        guard width > 0, !text.isEmpty else { return nil }
        let font = UIFont.systemFont(ofSize: size)
        let rect = (text as NSString).boundingRect(with: CGSize(width: width, height: .greatestFiniteMagnitude),
                                                   options: [.usesLineFragmentOrigin, .usesFontLeading],
                                                   attributes: [.font: font], context: nil)
        return max(1, Int((rect.height / font.lineHeight).rounded()))
    }
}

/// «BTC ↗ +2.8 %» als farbiger Text ohne Pille (wie Android): Kürzel in der Textfarbe, Veränderung in der
/// Kursfarbe mit Vorzeichen und Pfeil (Farbe nie allein); unverändert grau ohne Pfeil.
private struct PulseWidgetChip: View {
    let coin: PulseCoinLine
    let palette: WidgetPalette

    var body: some View {
        let change = PulseWidgetText.shown(coin.changePercent)
        let color = palette.change(change)
        HStack(spacing: 3) {
            Text(PulseWidgetText.ticker(coin.name))
                .font(.system(size: 12, weight: .semibold, design: .rounded))
                .foregroundStyle(palette.text)
            ChangeArrowIcon(change: change)
                .font(.system(size: 8, weight: .bold))
                .foregroundStyle(color)
            Text(ActivityTexts.percent(change, 1))
                .font(.system(size: 12, weight: .semibold, design: .rounded))
                .monospacedDigit()
                .foregroundStyle(color)
        }
        .lineLimit(1)
        .minimumScaleFactor(0.8)
        .fixedSize(horizontal: true, vertical: false)
    }
}
