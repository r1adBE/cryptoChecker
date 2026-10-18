import AppIntents
import SwiftUI
import UIKit
import WidgetKit

// «Was gerade auffällt» (Crypto Pulse) als Widget — wie `PulseWidgetProvider` /
// `PulseWidgetRenderer` in Android. Auswertung und Leitsatz unverändert aus
// `CryptoPulse` (Shared), Daten aus `CryptoPulseSource` (Shared) mit seinem
// Zwischenspeicher (5 Min., App Group — derselbe Stand wie in der App). Dazu «Kurz gesagt»
// (`PulseWidgetPlain`): mittel der erste Satz statt des Leitsatzes, gross ein eigener Block.

// MARK: Einrichten

struct PulseWidgetIntent: WidgetConfigurationIntent {
    static let title: LocalizedStringResource = "pulse_now_title"
    static let description = IntentDescription("widget_pulse_description")

    @Parameter(title: "widget_background", default: .system)
    var theme: WidgetThemeOption

    init() {}
}

// MARK: Daten

struct PulseEntry: TimelineEntry {
    let date: Date
    /// nil = keine (brauchbaren) Marktdaten → «nicht verfügbar».
    let data: PulseMarketData?
    let theme: WidgetThemeOption
    let accent: AccentColor
    var priceColors: PriceColorScheme = SharedStorage.loadSettings().priceColorScheme
    var highContrast: Bool = SharedStorage.loadSettings().highContrast
    var priceColorsInverted: Bool = SharedStorage.loadSettings().priceColorsInverted
    /// Fear & Greed aus dem App-Group-Speicher (≤ 24 h, kein eigener Abruf); nil = Zeile weg.
    var fearGreed: Int? = FearGreedShared.showable(FearGreedShared.stored(), now: TimeUtils.nowMillis)
    /// Fear & Greed samt Speicherzeit für «Kurz gesagt» (Frische-Regel wie in der App, nicht die 24 h der Zeile).
    var plainFearGreed: FearGreedShared.Stored? = FearGreedShared.stored()
    /// Breite des Widgets (pt, `context.displaySize`) zum Messen des ersten Satzes; 0 = unbekannt.
    var displayWidth: CGFloat = 0

    /// Auswertung wie die Karte. Fear & Greed und Gas ändern Schlagzeile, Leitsatz und
    /// Chips nicht (nur die Zusatzsätze der Karte) — das Widget lädt sie deshalb nicht.
    var report: PulseReport? {
        guard let data else { return nil }
        return CryptoPulse.evaluate(PulseInput(btc: data.btc, eth: data.eth, sol: data.sol,
                                               volumeRatio: data.volumeRatio, fearGreed: nil,
                                               fundingPercent: data.fundingPercent, ethGasGwei: nil,
                                               topChanges: data.topChanges ?? []))
    }

    /// «Kurz gesagt» aus denselben Eingaben wie im Markt-Tab, zum Zeitpunkt dieses Eintrags (`date`):
    /// Veränderung des Gesamtmarkts aus dem Puls (gleiche Prüfung wie die Karte) und Fear & Greed.
    var plainInput: PlainSummaryInput? {
        guard let data, report != nil else { return nil }
        let marketChange = CryptoPulse.evaluate(PulseInput(
            btc: data.btc, eth: data.eth, sol: data.sol,
            volumeRatio: nil, fearGreed: nil, fundingPercent: nil, ethGasGwei: nil,
            marketCapUsd: data.marketCapUsd,
            marketCap24h: data.marketCap24h
        ))?.marketCap24h
        return PulseWidgetPlain.input(now: Int64(date.timeIntervalSince1970 * 1000),
                                      pulseMarketChange24h: marketChange, pulseSavedAt: data.time,
                                      fearGreed: plainFearGreed?.value, fearGreedSavedAt: plainFearGreed?.time)
    }

    /// Die gültigen Sätze (höchstens drei, ohne «Heute auffällig» — siehe `PulseWidgetPlain`); leer = keiner.
    var plainSentences: [String] {
        guard let input = plainInput else { return [] }
        return MarketPlainSummaryText.sentences(MarketPlainSummary.evaluate(input))
    }

    /// Hinweis unter den ersten `shownCount` Sätzen: «Daten: CoinGecko, alternative.me · keine Prognose,
    /// keine Anlageberatung» (Quellen der gezeigten Sätze; ohne Quelle nur der Hinweis).
    func plainNote(shownCount: Int) -> String {
        let footnote = L("market_plain_footnote")
        guard let input = plainInput,
              let sources = PulseWidgetPlain.sources(MarketPlainSummary.evaluate(input), shownCount: shownCount)
        else { return footnote }
        return L("pulse_plain_sources", sources) + " · " + footnote
    }

    /// Ab dann stünde ein gezeigter Satz auf veralteten Daten (Epoch-ms); nil = kein Satz.
    var plainExpiry: Int64? {
        guard let input = plainInput else { return nil }
        return PulseWidgetPlain.expiresAt(input, MarketPlainSummary.evaluate(input))
    }

    /// Derselbe Eintrag zu einem späteren Zeitpunkt (die Sätze werden dafür neu geprüft).
    func at(_ later: Date) -> PulseEntry {
        PulseEntry(date: later, data: data, theme: theme, accent: accent, priceColors: priceColors,
                   highContrast: highContrast, priceColorsInverted: priceColorsInverted, fearGreed: fearGreed,
                   plainFearGreed: plainFearGreed, displayWidth: displayWidth)
    }

    /// Beispiel für Galerie und Platzhalter: «Breite Stärke».
    static func sample(theme: WidgetThemeOption = .system, width: CGFloat = 0) -> PulseEntry {
        let now = TimeUtils.nowMillis
        let data = PulseMarketData(btc: 2.8, eth: 2.1, sol: 1.9, volumeRatio: 1.34, fundingPercent: 0.012,
                                   time: now,
                                   topChanges: Array(repeating: 1.2, count: 22) + Array(repeating: -0.8, count: 8),
                                   marketCapUsd: 2.9e12, marketCap24h: 1.8)
        return PulseEntry(date: Date(), data: data, theme: theme, accent: SharedStorage.loadSettings().accentColor,
                          fearGreed: 72, plainFearGreed: FearGreedShared.Stored(value: 72, time: now), displayWidth: width)
    }
}

enum PulseWidgetData {
    static let kind = "PulseWidget"
    /// Ältere Daten zeigt das Widget nicht mehr (dann «nicht verfügbar») — wie Android.
    static let maxAgeMillis: Int64 = 6 * 3_600_000
    /// Höchstens so lange auf frische Daten warten.
    static let fetchSeconds: Double = 12

    /// Frisch (oder aus dem Zwischenspeicher, falls jünger als 5 Min.); scheitert der Abruf,
    /// der letzte gespeicherte Stand, solange er nicht zu alt ist.
    static func current() async -> PulseMarketData? {
        if let data = await fetch(seconds: fetchSeconds), data.hasMarket { return data }
        return showable(CryptoPulseSource.stored())
    }

    /// Nur der gespeicherte Stand, ohne Netz (Galerie).
    static func storedOnly() -> PulseMarketData? {
        showable(CryptoPulseSource.stored())
    }

    static func showable(_ data: PulseMarketData?, now: Int64 = TimeUtils.nowMillis) -> PulseMarketData? {
        guard let data, data.hasMarket else { return nil }
        let age = now - data.time
        return age >= 0 && age <= maxAgeMillis ? data : nil
    }

    /// Abruf über `CryptoPulseSource` mit harter Zeitgrenze; nil bei Fehler oder Zeitüberschreitung.
    private static func fetch(seconds: Double) async -> PulseMarketData? {
        await withTaskGroup(of: PulseMarketData?.self) { group in
            group.addTask { try? await CryptoPulseSource.shared.fetch() }
            group.addTask {
                try? await Task.sleep(nanoseconds: UInt64(seconds * 1_000_000_000))
                return nil
            }
            let first = await group.next()
            group.cancelAll()
            return first ?? nil
        }
    }

    static var appURL: URL? { URL(string: "cryptochecker://cycle") }
}

struct PulseProvider: AppIntentTimelineProvider {
    typealias Entry = PulseEntry
    typealias Intent = PulseWidgetIntent

    func placeholder(in context: Context) -> PulseEntry {
        .sample(width: context.displaySize.width)
    }

    func snapshot(for configuration: PulseWidgetIntent, in context: Context) async -> PulseEntry {
        let width = context.displaySize.width
        // Galerie: ohne gespeicherte Daten das Beispiel statt «nicht verfügbar»
        if context.isPreview {
            guard let stored = PulseWidgetData.storedOnly() else { return .sample(theme: configuration.theme, width: width) }
            return Self.entry(stored, theme: configuration.theme, width: width)
        }
        let data = await PulseWidgetData.current()
        return Self.entry(data, theme: configuration.theme, width: width)
    }

    func timeline(for configuration: PulseWidgetIntent, in context: Context) async -> Timeline<PulseEntry> {
        let data = await PulseWidgetData.current()
        // Gleicher Takt wie die anderen Widgets
        let reload = WidgetData.nextReload
        return Timeline(entries: Self.entries(Self.entry(data, theme: configuration.theme, width: context.displaySize.width),
                                              until: reload),
                        policy: .after(reload))
    }

    /// Der Eintrag und je ein weiterer, sobald ein Satz von «Kurz gesagt» auf veralteten Daten stünde
    /// (bis zum nächsten Neuladen) — so steht kein Satz länger als in der App.
    static func entries(_ first: PulseEntry, until reload: Date) -> [PulseEntry] {
        var entries = [first]
        var last = first
        while entries.count <= PulseWidgetPlain.maxSentences, let expiry = last.plainExpiry {
            let at = Date(timeIntervalSince1970: Double(expiry) / 1000)
            guard at > last.date, at < reload else { break }
            last = first.at(at)
            entries.append(last)
        }
        return entries
    }

    private static func entry(_ data: PulseMarketData?, theme: WidgetThemeOption, width: CGFloat) -> PulseEntry {
        PulseEntry(date: Date(), data: data, theme: theme, accent: SharedStorage.loadSettings().accentColor,
                   displayWidth: width)
    }
}

// MARK: Widget

struct PulseWidget: Widget {
    var body: some WidgetConfiguration {
        AppIntentConfiguration(kind: PulseWidgetData.kind,
                               intent: PulseWidgetIntent.self,
                               provider: PulseProvider()) { entry in
            PulseWidgetView(entry: entry)
        }
        .configurationDisplayName(L("pulse_now_title"))
        .description(L("widget_pulse_description"))
        .supportedFamilies([.systemSmall, .systemMedium, .systemLarge])
        .contentMarginsDisabled()
    }
}

// MARK: Texte

enum PulseWidgetText {
    /// +1 breit steigend, −1 breit fallend, 0 gemischt oder ruhig — wie die Karte.
    static func direction(_ summary: PulseSummary) -> Int {
        switch summary {
        case .broadUp, .broadUpVolume: 1
        case .broadDown, .broadDownVolume: -1
        case .mixed, .calm: 0
        }
    }

    /// ↗ / ↘ nach der Richtung (schräg wie überall in der App, nie getauscht), gemischt/ruhig ohne Zeichen.
    static func glyph(_ summary: PulseSummary) -> String? {
        switch direction(summary) {
        case 1: PriceFormat.arrowUp
        case -1: PriceFormat.arrowDown
        default: nil
        }
    }

    static func headline(_ summary: PulseSummary) -> String {
        switch summary {
        case .broadUp, .broadUpVolume: L("pulse_headline_up")
        case .broadDown, .broadDownVolume: L("pulse_headline_down")
        case .mixed: L("pulse_headline_mixed")
        case .calm: L("pulse_headline_calm")
        }
    }

    /// Leitsatz aus `CryptoPulse.leadSentence`: ein oder zwei ganze Sätze.
    static func lead(_ report: PulseReport) -> String {
        let lead = CryptoPulse.leadSentence(report)
        let first = L(lead.kind.key)
        guard let detail = lead.detail else { return first }
        let second = detail.hasPercent ? L(detail.key, lead.volumePercent) : L(detail.key)
        return first + " " + second
    }

    /// Wie angezeigt gerundet: unter 0.05 % gilt als unverändert.
    static func shown(_ change: Double) -> Double { abs(change) < 0.05 ? 0 : change }

    static func ticker(_ name: String) -> String {
        switch name {
        case "Bitcoin": "BTC"
        case "Ethereum": "ETH"
        case "Solana": "SOL"
        default: name
        }
    }

    /// VoiceOver: Überzeile, Schlagzeile, Leitsatz (bzw. der erste Satz von «Kurz gesagt», `lead`),
    /// «Bitcoin, gestiegen um 2.80%» je gezeigtem Coin, Fear & Greed (mittel/gross), Stand, dann die
    /// gezeigten Sätze von «Kurz gesagt» samt Hinweis (gross, `plain`).
    static func spoken(_ report: PulseReport, coins: [PulseCoinLine], fearGreed: String? = nil, time: String?,
                       breadth: PulseBreadth? = nil, lead shownLead: String? = nil, plain: [String] = [],
                       plainNote: String? = nil) -> String {
        var parts: [String?] = [L("pulse_now_title"), headline(report.summary), shownLead ?? lead(report)]
        for coin in coins {
            parts.append(A11y.join([coin.name, A11y.change(shown(coin.changePercent))]))
        }
        parts.append(fearGreed)
        parts.append(breadth.map { L("pulse_breadth_spoken", $0.total, $0.up, $0.down) })
        parts.append(time)
        if !plain.isEmpty {
            parts.append(L("market_plain_title"))
            parts.append(contentsOf: plain.map { Optional($0) })
            parts.append(plainNote ?? L("market_plain_footnote"))
        }
        return A11y.join(parts)
    }
}

// MARK: Ansicht

struct PulseWidgetView: View {
    let entry: PulseEntry
    @Environment(\.widgetFamily) private var family

    var body: some View {
        WidgetThemed(theme: entry.theme, accent: entry.accent, priceColors: entry.priceColors,
                     highContrast: entry.highContrast, inverted: entry.priceColorsInverted) { palette in
            PulseHomeView(entry: entry, palette: palette, size: size)
                .widgetURL(PulseWidgetData.appURL)
        }
    }

    private var size: PulseWidgetSize {
        switch family {
        case .systemMedium: .medium
        case .systemLarge: .large
        default: .small
        }
    }
}

/// Klein: Schlagzeile, Leitsatz (2 Zeilen), BTC-Chip; mittel: alle drei Chips, der erste Satz von
/// «Kurz gesagt» statt des Leitsatzes (wenn er ganz in dessen 2 Zeilen passt); gross wie mittel mit dem
/// Leitsatz, darunter der Block «Kurz gesagt».
private struct PulseHomeView: View {
    let entry: PulseEntry
    let palette: WidgetPalette
    let size: PulseWidgetSize

    /// Mittel und gross: drei Coins, Fear & Greed, Marktbreite (klein unverändert).
    private var wide: Bool { size != .small }

    /// Innenrand (pt) — auch für die Breite beim Messen des ersten Satzes.
    private var inset: CGFloat { wide ? 16 : 14 }

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
        HStack(spacing: 6) {
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
                HStack(spacing: 6) {
                    // «24h» klein und grau davor: die Veränderungen sind 24-h-Werte (wie Android)
                    Text(L("widget_range_short_24h"))
                        .widgetSecondaryFont(weight: .medium)
                        .foregroundStyle(palette.secondary)
                        .lineLimit(1)
                        .fixedSize()
                        .accessibilityHidden(true)
                    // Coins als Text ohne Pille: 10 pt Abstand (wie Android `PulseWidgetMath.CHIP_GAP_DP`)
                    HStack(spacing: 10) {
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
            HStack(alignment: .firstTextBaseline, spacing: 6) {
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
            .padding(.top, 8)
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

/// Nebenzeile mit Farben wie in der App (wie Android `PulseWidgetRenderer.sideLine`): die Fear-&-Greed-Zahl
/// in der Textfarbe (wie die Karte im Markt-Tab), davor ein Stufenpunkt in der Skala aus den Kursfarben, «↗ 21» steigend
/// und «↘ 9» fallend in der Kursfarbe; der Rest grau. Nur Farben — die Breite bleibt gleich.
enum PulseSideLine {
    /// Stufenpunkt vor Fear & Greed (wie Android `PulseWidgetRenderer.FNG_DOT`).
    static let dot = "\u{25CF}"

    /// Farbe der Stufe aus den Kursfarben des Widgets (`WidgetPalette.scaleSteps`, ≥ 3:1 als Grafik) — passt so
    /// genau zu den Pfeilen.
    static func stepColor(_ value: Int, palette: WidgetPalette) -> Color {
        Color(hex: palette.scaleSteps[MarketScalePalette.level(value)])
    }

    /// «● Fear & Greed 72 · Gier»: Punkt in der Stufenfarbe, Zahl in der Textfarbe.
    static func fearGreed(_ value: Int, palette: WidgetPalette) -> Text {
        let line = FearGreedShared.line(value)
        let number = LocaleNumbers.integer(value)
        let dotText = Text(verbatim: dot + " ").foregroundStyle(stepColor(value, palette: palette))
        guard let range = line.range(of: number) else { return dotText + Text(line) }
        let head = Text(verbatim: String(line[..<range.lowerBound]))
        let tail = Text(verbatim: String(line[range.upperBound...]))
        // Zahl in Textfarbe statt Stufenfarbe (AA, Build 43, wie die Karte); die Stufe zeigt der Punkt
        return dotText + head + Text(verbatim: number).foregroundStyle(palette.text) + tail
    }

    /// Punkt vor «Top 30» in der zweiten Zeile: Kursfarbe der Mehrheit (steigend/fallend), gleich viele → grau.
    static func breadthDot(_ b: PulseBreadth, palette: WidgetPalette) -> Text {
        let color = b.up > b.down ? palette.up : (b.down > b.up ? palette.down : palette.secondary)
        return Text(verbatim: dot + " ").foregroundStyle(color)
    }

    /// «Top 30 · 24h  ↗ 21  ↘ 9»: Marktbreite über 24 Stunden.
    static func breadth(_ b: PulseBreadth, palette: WidgetPalette) -> Text {
        let label = L("pulse_breadth_label", b.total) + " · " + L("widget_range_short_24h") + "  "
        let up = Text(verbatim: "\(PriceFormat.arrowUp) \(LocaleNumbers.integer(b.up))").foregroundStyle(palette.up)
        let down = Text(verbatim: "\(PriceFormat.arrowDown) \(LocaleNumbers.integer(b.down))").foregroundStyle(palette.down)
        return Text(verbatim: label) + up + Text(verbatim: "  ") + down
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
