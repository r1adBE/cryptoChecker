import AppIntents
import SwiftUI
import WidgetKit

// «Was gerade auffällt» (Crypto Pulse) als Widget — wie `PulseWidgetProvider` /
// `PulseWidgetRenderer` in Android. Auswertung und Leitsatz unverändert aus
// `CryptoPulse` (Shared), Daten aus `CryptoPulseSource` (Shared) mit seinem
// Zwischenspeicher (5 Min., App Group — derselbe Stand wie in der App).

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

    /// Auswertung wie die Karte. Fear & Greed und Gas ändern Schlagzeile, Leitsatz und
    /// Chips nicht (nur die Zusatzsätze der Karte) — das Widget lädt sie deshalb nicht.
    var report: PulseReport? {
        guard let data else { return nil }
        return CryptoPulse.evaluate(PulseInput(btc: data.btc, eth: data.eth, sol: data.sol,
                                               volumeRatio: data.volumeRatio, fearGreed: nil,
                                               fundingPercent: data.fundingPercent, ethGasGwei: nil,
                                               topChanges: data.topChanges ?? []))
    }

    /// Beispiel für Galerie und Platzhalter: «Breite Stärke».
    static func sample(theme: WidgetThemeOption = .system) -> PulseEntry {
        let data = PulseMarketData(btc: 2.8, eth: 2.1, sol: 1.9, volumeRatio: 1.34, fundingPercent: 0.012,
                                   time: TimeUtils.nowMillis,
                                   topChanges: Array(repeating: 1.2, count: 22) + Array(repeating: -0.8, count: 8))
        return PulseEntry(date: Date(), data: data, theme: theme, accent: SharedStorage.loadSettings().accentColor,
                          fearGreed: 72)
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
        .sample()
    }

    func snapshot(for configuration: PulseWidgetIntent, in context: Context) async -> PulseEntry {
        // Galerie: ohne gespeicherte Daten das Beispiel statt «nicht verfügbar»
        if context.isPreview {
            guard let stored = PulseWidgetData.storedOnly() else { return .sample(theme: configuration.theme) }
            return Self.entry(stored, theme: configuration.theme)
        }
        let data = await PulseWidgetData.current()
        return Self.entry(data, theme: configuration.theme)
    }

    func timeline(for configuration: PulseWidgetIntent, in context: Context) async -> Timeline<PulseEntry> {
        let data = await PulseWidgetData.current()
        // Gleicher Takt wie die anderen Widgets
        return Timeline(entries: [Self.entry(data, theme: configuration.theme)], policy: .after(WidgetData.nextReload))
    }

    private static func entry(_ data: PulseMarketData?, theme: WidgetThemeOption) -> PulseEntry {
        PulseEntry(date: Date(), data: data, theme: theme, accent: SharedStorage.loadSettings().accentColor)
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
        .supportedFamilies([.systemSmall, .systemMedium])
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

    /// VoiceOver: Überzeile, Schlagzeile, Leitsatz, «Bitcoin, gestiegen um 2.80%» je gezeigtem Coin,
    /// Fear & Greed (mittel), Stand.
    static func spoken(_ report: PulseReport, coins: [PulseCoinLine], fearGreed: String? = nil, time: String?,
                       breadth: PulseBreadth? = nil) -> String {
        var parts: [String?] = [L("pulse_now_title"), headline(report.summary), lead(report)]
        for coin in coins {
            parts.append(A11y.join([coin.name, A11y.change(shown(coin.changePercent))]))
        }
        parts.append(fearGreed)
        parts.append(breadth.map { L("pulse_breadth_spoken", $0.total, $0.up, $0.down) })
        parts.append(time)
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
            PulseHomeView(entry: entry, palette: palette, medium: family == .systemMedium)
                .widgetURL(PulseWidgetData.appURL)
        }
    }
}

/// Klein: Schlagzeile, Leitsatz (2 Zeilen), BTC-Chip; mittel: alle drei Chips.
private struct PulseHomeView: View {
    let entry: PulseEntry
    let palette: WidgetPalette
    let medium: Bool

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
        .padding(medium ? 16 : 14)
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

    private func content(_ report: PulseReport, data: PulseMarketData) -> some View {
        let coins = Array(report.coins.prefix(medium ? 3 : 1))
        let time: String? = data.time > 0 ? L("pulse_updated", PriceFormat.time(data.time)) : nil
        // Fear & Greed nur mittel (klein unverändert), in der Zeile des Stands
        let fearGreed: String? = medium ? entry.fearGreed.map(FearGreedShared.line) : nil
        let fearGreedText: Text? = medium ? entry.fearGreed.map { PulseSideLine.fearGreed($0, palette: palette) } : nil
        // Marktbreite «Top 30 · 24h  ↗ 22  ↘ 8» (mittel), neben Fear & Greed, wenn Platz ist
        let breadth: Text? = medium ? report.breadth.map { PulseSideLine.breadth($0, palette: palette) } : nil
        return Group {
            if fearGreedText != nil && breadth != nil {
                // Zwei Nebenzeilen nur, wenn die Höhe reicht (erste Fassung passt senkrecht);
                // sonst die bisherige Anordnung mit höchstens einer Nebenzeile
                ViewThatFits(in: .vertical) {
                    stack(report, coins: coins, time: time, fearGreed: fearGreedText, breadth: breadth, twoLines: true)
                    stack(report, coins: coins, time: time, fearGreed: fearGreedText, breadth: breadth, twoLines: false)
                }
            } else {
                stack(report, coins: coins, time: time, fearGreed: fearGreedText, breadth: breadth, twoLines: false)
            }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(PulseWidgetText.spoken(report, coins: coins, fearGreed: fearGreed, time: time,
                                                   breadth: medium ? report.breadth : nil))
    }

    /// Schlagzeile, Leitsatz, Coins und Fusszeile; `twoLines` erlaubt die Marktbreite in einer zweiten Zeile.
    private func stack(_ report: PulseReport, coins: [PulseCoinLine], time: String?, fearGreed fearGreedText: Text?,
                       breadth: Text?, twoLines: Bool) -> some View {
        let glyph = PulseWidgetText.glyph(report.summary)
        let direction = PulseWidgetText.direction(report.summary)
        let headlineSize: CGFloat = medium ? 19 : 17
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
            Text(PulseWidgetText.lead(report))
                .font(.system(size: 12))
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
                        foot(layout, time: time, fearGreed: fearGreedText, breadth: breadth)
                    }
                    footRow(time, nil)
                }
                .padding(.top, 4)
            }
        }
    }

    /// Fusszeile in der Anordnung `layout`; bei `.twoLines` die Marktbreite rechts in einer zweiten Zeile.
    @ViewBuilder
    private func foot(_ layout: PulseSideLayout, time: String?, fearGreed: Text?, breadth: Text?) -> some View {
        switch layout {
        case .oneLine:
            if let fearGreed, let breadth { footRow(time, fearGreed + Text(verbatim: " · ") + breadth) }
        case .twoLines:
            if let fearGreed, let breadth {
                VStack(spacing: 2) {
                    footRow(time, fearGreed)
                    footRow(nil, breadth)
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

/// Nebenzeile mit Farben wie in der App (wie Android `PulseWidgetRenderer.sideLine`): die Fear-&-Greed-Zahl
/// in der Textfarbe (wie die Karte im Markt-Tab), davor ein Stufenpunkt in der festen Skala, «↗ 21» steigend
/// und «↘ 9» fallend in der Kursfarbe; der Rest grau. Nur Farben — die Breite bleibt gleich.
enum PulseSideLine {
    /// Stufenpunkt vor Fear & Greed (wie Android `PulseWidgetRenderer.FNG_DOT`).
    static let dot = "\u{25CF}"

    /// Farbe der Stufe aus der festen Skala (`ColorTokens.scaleSteps`, je Hell/Dunkel des Widgets, ≥ 3:1 als Grafik).
    static func stepColor(_ value: Int, dark: Bool) -> Color {
        Color(hex: ColorTokens.scaleSteps[MarketScalePalette.level(value)].hex(dark: dark))
    }

    /// «● Fear & Greed 72 · Gier»: Punkt in der Stufenfarbe, Zahl in der Textfarbe.
    static func fearGreed(_ value: Int, palette: WidgetPalette) -> Text {
        let line = FearGreedShared.line(value)
        let number = LocaleNumbers.integer(value)
        let dotText = Text(verbatim: dot + " ").foregroundStyle(stepColor(value, dark: palette.dark))
        guard let range = line.range(of: number) else { return dotText + Text(line) }
        let head = Text(verbatim: String(line[..<range.lowerBound]))
        let tail = Text(verbatim: String(line[range.upperBound...]))
        // Zahl in Textfarbe statt Stufenfarbe (AA, Build 43, wie die Karte); die Stufe zeigt der Punkt
        return dotText + head + Text(verbatim: number).foregroundStyle(palette.text) + tail
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
