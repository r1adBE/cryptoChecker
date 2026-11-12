import SwiftUI
import WidgetKit

/// Komplikation der Apple-Watch-App: der erste Coin der gewählten Liste — rund, Ecke und als Zeile mit dem Kurs knapp
/// «BTC / 84’450» (`WatchPriceFormat.compactPrice`; die Änderung nennt VoiceOver), rechteckig «BTC • 84’450.00 USDT
/// +1.2%» mit der Gegenwährung des Paars (`WatchPriceFormat.line`). Zeitleiste im Intervall des iPhones (`WatchInterval`);
/// geladen wird nur, was nach `WatchPhoneQuotes.selfFetch` fällig ist — nichts, solange das iPhone frische Kurse liefert (die Erweiterung kennt die
/// Verbindung nicht: sie verlässt sich auf das Alter der Kurse vom iPhone), sonst ab dem Intervall weniger 5 Minuten;
/// ohne Netz oder ohne neuen Kurs für diesen Coin der letzte. Veraltet (`WatchFreshness`, 45 Min. ab dem
/// Stand seines Abrufs; eigener Zeitleisten-Eintrag ab dann): grau, rechteckig mit Alter «… +1.2%  ·  vor 47 Min.», rund,
/// Ecke und Zeile knapp «BTC 47m» (`WatchFreshness.shortTitle`, wie Android der Kurztitel); sonst rechteckig mit der Börse, die den Kurs geliefert hat («+1.2%  ·  Binance Futures», wie
/// die Zeile der Uhr-App); VoiceOver nennt Alter und Börse immer. Tippen öffnet die Uhr-App. Wie Android `PriceComplicationService`.
struct WatchPriceEntry: TimelineEntry {
    let date: Date
    let coin: String?
    let price: String
    let change: String
    /// Kurs knapp für kleine Familien («84’450»).
    var compact: String = "—"
    /// Zeile der rechteckigen Komplikation («BTC • 84’450.00 USDT  +1.20%  ·  Binance»).
    var line: String = "—"
    /// Börse, die den Kurs geliefert hat (wie die Zeile der Uhr-App); nil ohne Kurs.
    var source: String?
    /// Nur bei veraltetem Kurs: Alter kurz («vor 47 Min.») und für VoiceOver.
    var age: String?
    var spokenAge: String?
    /// Stand des Kurses (Epoch-ms, 0 = keiner): Alter knapp in den kleinen Familien (`WatchFreshness.shortTitle`).
    var time: Int64 = 0
    /// Keine Coins gewählt: was zu tun ist («Coins wählen» bzw. bei «Liste vom Handy» «Keine Favoriten auf dem Handy»);
    /// die Komplikation zeigt dann «Kurs / +» wie Android. nil = Coin gewählt.
    var empty: String?
    /// «—» ohne Kurs (Börse stillgelegt, noch kein Kurs): kurzer Grund sichtbar, ausführlich für VoiceOver
    /// (`WatchText.missingShort`/`missingReason`, wie die Zeile der Uhr-App und Android). nil = mit Kurs.
    var missing: String?
    var missingSpoken: String?

    var outdated: Bool { age != nil }
}

struct WatchPriceProvider: TimelineProvider {
    func placeholder(in context: Context) -> WatchPriceEntry {
        WatchPriceEntry(date: Date(), coin: "BTC", price: WatchText.price(84_450), change: WatchText.change(1.2),
                        compact: WatchText.compactPrice(84_450),
                        line: WatchPriceFormat.line(coin: "BTC", price: 84_450, quote: WatchPair.usdt, change: 1.2,
                                                    detail: "Binance"),
                        source: "Binance")
    }

    func getSnapshot(in context: Context, completion: @escaping @Sendable (WatchPriceEntry) -> Void) {
        let settings = WatchStore.settings
        let coin = settings.shown.first
        completion(context.isPreview ? placeholder(in: context)
                   : Self.entry(coin, coin.flatMap { WatchStore.quotes()[$0] }, at: Date(), phone: settings.mode == .phone))
    }

    func getTimeline(in context: Context, completion: @escaping @Sendable (Timeline<WatchPriceEntry>) -> Void) {
        Task {
            let settings = WatchStore.settings
            let coins = settings.shown
            let first = coins.first
            let stored = WatchStore.quotes()
            var cached = first.flatMap { stored[$0] }
            let need = WatchPhoneQuotes.selfFetch(coins, times: stored.mapValues(\.time), phoneCoins: WatchStore.phoneCoins,
                                                  usePhone: settings.source == .phone,
                                                  intervalMinutes: settings.interval, now: WatchStore.now(), force: false)
            if let first, !need.isEmpty {
                let startedAt = WatchStore.now()
                // Höchstens 20 s für alle Börsen zusammen (wie Android), danach der gespeicherte Kurs
                let fresh = await WatchQuoteFetch.within(seconds: WatchQuoteFetch.complicationTimeoutSeconds) {
                    await WatchPriceSource.fetch(need)
                }
                // Je Coin zusammengeführt: fehlt der erste Coin im neuen Ergebnis oder hat ein später gestarteter Abruf
                // (Uhr-App) schon geschrieben, bleibt der gespeicherte Kurs samt Zeit
                if !fresh.isEmpty {
                    cached = WatchStore.mergeQuotes(fresh, startedAt: startedAt)?[first] ?? cached
                }
            }
            let now = Date()
            let next = now.addingTimeInterval(TimeInterval(WatchInterval.clamp(settings.interval) * 60))
            let phone = settings.mode == .phone
            var entries = [Self.entry(first, cached, at: now, phone: phone)]
            // Zweiter Eintrag, sobald der Kurs veraltet (1 ms nach der Grenze, wie `OutdatedRule.nextChangeAt`)
            if let time = cached?.time {
                let staleAt = WatchText.date(time + WatchFreshness.outdatedAfterMillis + 1)
                if staleAt > now, staleAt < next {
                    entries.append(Self.entry(first, cached, at: staleAt, phone: phone))
                }
            }
            completion(Timeline(entries: entries, policy: .after(next)))
        }
    }

    /// `phone`: Liste vom iPhone («Wie Handy») — bestimmt den Hinweis ohne Coins.
    private static func entry(_ coin: String?, _ stored: WatchCached?, at date: Date, phone: Bool) -> WatchPriceEntry {
        guard let coin else {
            // Keine Coins: «Kurs / +» mit dem nächsten Schritt statt «— / —» — wie Android
            return WatchPriceEntry(date: date, coin: nil, price: "—", change: "—", compact: "+",
                                   empty: WatchText.t(phone ? "watch_phone_empty" : "watch_choose"))
        }
        let now = Int64(date.timeIntervalSince1970 * 1000)
        // Börse des Paars stillgelegt (`ExchangeRetirement`): kein alter Kurs — wie Android
        let retired = WatchPair.parse(coin).map { WatchPairQuotes.retired($0, now: now) } ?? false
        let cached = retired ? nil : stored
        let q = cached?.quote
        let time = cached?.time ?? 0
        let outdated = cached != nil && WatchFreshness.isOutdated(time, now: now)
        // Paar des iPhones wie dort angezeigt («BANK Perp»), Kürzel der eigenen Liste unverändert (gegen USDT)
        let label = WatchPair.label(of: coin)
        let source = q.map(\.source).flatMap { $0.isEmpty ? nil : $0 }
        let age = outdated ? WatchText.ago(time, now: now) : nil
        // Wie die Zeile der Uhr-App: veraltet das Alter, sonst die Börse
        let base = WatchPriceFormat.line(coin: label, price: q?.price, quote: WatchPair.parse(coin)?.quote ?? WatchPair.usdt,
                                         change: q?.change24h, detail: age ?? source)
        // Ohne Kurs: «BTC • —  ·  Bitpanda liefert keine Kurse mehr» bzw. «nicht erreichbar» — wie Android
        let missing = q == nil ? WatchText.missingShort(coin) : nil
        let line = missing.map { "\(base)  ·  \($0)" } ?? base
        return WatchPriceEntry(date: date, coin: label, price: q.map { WatchText.price($0.price) } ?? "—",
                               change: WatchText.change(q?.change24h),
                               compact: q.map { WatchText.compactPrice($0.price) } ?? "—", line: line,
                               source: source, age: age,
                               spokenAge: outdated ? WatchText.spokenAge(time, now: now) : nil, time: time,
                               missing: missing, missingSpoken: q == nil ? WatchText.missingReason(coin) : nil)
    }
}

struct WatchPriceWidgetView: View {
    @Environment(\.widgetFamily) private var family
    let entry: WatchPriceEntry

    var body: some View {
        content
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(spoken)
            .containerBackground(.clear, for: .widget)
    }

    /// «BTC 67’420.00, +1.20% in 24 Stunden», bei veraltetem Kurs dessen Alter, dazu die Börse.
    private var spoken: String {
        guard let coin = entry.coin else { return entry.empty ?? WatchText.t("watch_empty") }
        if let reason = entry.missingSpoken { return "\(coin), \(reason)" }
        let row = WatchText.t("a11y_watch_row", coin, entry.price, entry.change)
        return ([row] + [entry.spokenAge, entry.source].compactMap { $0 }).joined(separator: ", ")
    }

    /// Veraltet grau (wie die Merkliste), sonst normal.
    private var valueStyle: HierarchicalShapeStyle { entry.outdated ? .secondary : .primary }

    @ViewBuilder private var content: some View {
        // Keine Coins: Titel «Kurs», Wert «+», rechteckig der Hinweis — wie Android («Kurs / +»)
        let coin = entry.coin ?? WatchText.t("watch_complication_label")
        let now = Int64(entry.date.timeIntervalSince1970 * 1000)
        // Veraltet: Alter knapp auch in den kleinen Familien («BTC 47m»), wie Android der Kurztitel
        let title = WatchFreshness.shortTitle(coin, time: entry.time, now: now)
        switch family {
        case .accessoryRectangular:
            VStack(alignment: .leading) {
                Text(coin).font(.headline)
                // «BTC • 84’450.00 USDT  +1.20%  ·  Binance» (wie Android LONG_TEXT); ohne Coins der Hinweis
                Text(entry.empty ?? entry.line).lineLimit(2).minimumScaleFactor(0.6).foregroundStyle(valueStyle)
            }
        case .accessoryInline:
            // Eine Zeile, veraltet mit dem Alter «BTC 84’450 · 47m»; das System färbt sie selbst
            Text(WatchFreshness.inlineLine("\(coin) \(entry.compact)", time: entry.time, now: now))
        case .accessoryCorner:
            Text(entry.compact).foregroundStyle(valueStyle).widgetLabel(title)
        default:
            VStack(spacing: 0) {
                Text(title).font(.caption).lineLimit(1).minimumScaleFactor(0.5)
                Text(entry.compact).font(.caption).lineLimit(1).minimumScaleFactor(0.5).foregroundStyle(valueStyle)
            }
        }
    }
}

/// Komplikationen der Apple-Watch-App: Kurs des ersten Coins und Portfolio-Wert vom iPhone.
@main
struct WatchComplicationBundle: WidgetBundle {
    var body: some Widget {
        WatchPriceWidget()
        WatchPortfolioWidget()
    }
}

struct WatchPriceWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: "WatchPriceWidget", provider: WatchPriceProvider()) { entry in
            WatchPriceWidgetView(entry: entry)
        }
        .configurationDisplayName(WatchText.t("watch_complication_label"))
        .description(WatchText.t("watch_complication_label"))
        .supportedFamilies([.accessoryCircular, .accessoryRectangular, .accessoryInline, .accessoryCorner])
    }
}
