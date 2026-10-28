import SwiftUI
import WidgetKit

/// Komplikation der Apple-Watch-App: der erste Coin der gewählten Liste — rund «BTC / +1.2%», rechteckig
/// mit Kurs, als Zeile «BTC +1.2%». Ist sein gespeicherter Kurs älter als 10 Minuten, lädt sie neu (Zeitleiste alle
/// 15 Minuten); ohne Netz oder ohne neuen Kurs für diesen Coin der letzte. Veraltet (`WatchFreshness`, 45 Min. ab dem
/// Stand seines Abrufs; eigener Zeitleisten-Eintrag ab dann): grau, rechteckig mit Alter «+1.2%  ·  vor 47 Min.», als
/// Zeile ohne Platz dafür; VoiceOver nennt das Alter immer. Tippen öffnet die Uhr-App. Wie Android `PriceComplicationService`.
struct WatchPriceEntry: TimelineEntry {
    let date: Date
    let coin: String?
    let price: String
    let change: String
    /// Nur bei veraltetem Kurs: Alter kurz («vor 47 Min.») und für VoiceOver.
    var age: String?
    var spokenAge: String?

    var outdated: Bool { age != nil }
}

struct WatchPriceProvider: TimelineProvider {
    private static let maxAgeMs: Int64 = 10 * 60_000

    func placeholder(in context: Context) -> WatchPriceEntry {
        WatchPriceEntry(date: Date(), coin: "BTC", price: WatchText.price(67_420), change: WatchText.change(1.2))
    }

    func getSnapshot(in context: Context, completion: @escaping @Sendable (WatchPriceEntry) -> Void) {
        let coin = WatchStore.settings.shown.first
        completion(context.isPreview ? placeholder(in: context)
                   : Self.entry(coin, coin.flatMap { WatchStore.quotes()[$0] }, at: Date()))
    }

    func getTimeline(in context: Context, completion: @escaping @Sendable (Timeline<WatchPriceEntry>) -> Void) {
        Task {
            let coins = WatchStore.settings.shown
            let first = coins.first
            var cached = first.flatMap { WatchStore.quotes()[$0] }
            if let first, cached.map({ WatchStore.now() - $0.time > Self.maxAgeMs }) ?? true {
                let startedAt = WatchStore.now()
                let fresh = await WatchPriceSource.fetch(coins)
                // Je Coin zusammengeführt: fehlt der erste Coin im neuen Ergebnis oder hat ein später gestarteter Abruf
                // (Uhr-App) schon geschrieben, bleibt der gespeicherte Kurs samt Zeit
                if !fresh.isEmpty {
                    cached = WatchStore.mergeQuotes(fresh, startedAt: startedAt)?[first] ?? cached
                }
            }
            let now = Date()
            let next = now.addingTimeInterval(TimeInterval(WatchFreshness.updateMinutes * 60))
            var entries = [Self.entry(first, cached, at: now)]
            // Zweiter Eintrag, sobald der Kurs veraltet (1 ms nach der Grenze, wie `OutdatedRule.nextChangeAt`)
            if let time = cached?.time {
                let staleAt = WatchText.date(time + WatchFreshness.outdatedAfterMillis + 1)
                if staleAt > now, staleAt < next {
                    entries.append(Self.entry(first, cached, at: staleAt))
                }
            }
            completion(Timeline(entries: entries, policy: .after(next)))
        }
    }

    private static func entry(_ coin: String?, _ cached: WatchCached?, at date: Date) -> WatchPriceEntry {
        guard let coin else {
            return WatchPriceEntry(date: date, coin: nil, price: "—", change: "—")
        }
        let q = cached?.quote
        let time = cached?.time ?? 0
        let now = Int64(date.timeIntervalSince1970 * 1000)
        let outdated = cached != nil && WatchFreshness.isOutdated(time, now: now)
        return WatchPriceEntry(date: date, coin: coin, price: q.map { WatchText.price($0.price) } ?? "—",
                               change: WatchText.change(q?.change24h),
                               age: outdated ? WatchText.ago(time, now: now) : nil,
                               spokenAge: outdated ? WatchText.spokenAge(time, now: now) : nil)
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

    /// «BTC 67’420.00, +1.20% in 24 Stunden» und bei veraltetem Kurs dessen Alter.
    private var spoken: String {
        guard let coin = entry.coin else { return WatchText.t("watch_empty") }
        let row = WatchText.t("a11y_watch_row", coin, entry.price, entry.change)
        return entry.spokenAge.map { row + ", " + $0 } ?? row
    }

    /// Veraltet grau (wie die Merkliste), sonst normal.
    private var valueStyle: HierarchicalShapeStyle { entry.outdated ? .secondary : .primary }

    @ViewBuilder private var content: some View {
        let coin = entry.coin ?? "—"
        switch family {
        case .accessoryRectangular:
            VStack(alignment: .leading) {
                Text(coin).font(.headline)
                Text(entry.price).lineLimit(1).minimumScaleFactor(0.6).foregroundStyle(valueStyle)
                Text(entry.change + (entry.age.map { "  ·  \($0)" } ?? "")).lineLimit(1).minimumScaleFactor(0.6)
                    .foregroundStyle(.secondary)
            }
        case .accessoryInline:
            // Eine Zeile ohne Platz für das Alter; das System färbt sie selbst
            Text("\(coin) \(entry.change)")
        case .accessoryCorner:
            Text(entry.change).foregroundStyle(valueStyle).widgetLabel(coin)
        default:
            VStack(spacing: 0) {
                Text(coin).font(.caption).lineLimit(1).minimumScaleFactor(0.5)
                Text(entry.change).font(.caption).lineLimit(1).minimumScaleFactor(0.5).foregroundStyle(valueStyle)
            }
        }
    }
}

@main
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
