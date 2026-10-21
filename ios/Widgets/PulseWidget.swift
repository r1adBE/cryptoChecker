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
        // Fertig formatiert (Ziffern, Abstand und Zeichen der Sprache) statt eines festen «%» in der Übersetzung
        let second = detail.hasPercent
            ? L(detail.key, PercentText.unsigned(Double(lead.volumePercent), decimals: 0))
            : L(detail.key)
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
