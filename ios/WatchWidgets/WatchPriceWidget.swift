import SwiftUI
import WidgetKit

/// Komplikation der Apple-Watch-App: der erste Coin der gewählten Liste — rund «BTC / +1.2%», rechteckig
/// mit Kurs, als Zeile «BTC +1.2%». Sind die gespeicherten Kurse älter als 10 Minuten, lädt sie neu (Zeitleiste alle
/// 15 Minuten); ohne Netz die letzten. Tippen öffnet die Uhr-App. Wie Android `PriceComplicationService`.
struct WatchPriceEntry: TimelineEntry {
    let date: Date
    let coin: String?
    let price: String
    let change: String
}

struct WatchPriceProvider: TimelineProvider {
    private static let maxAgeMs: Int64 = 10 * 60_000

    func placeholder(in context: Context) -> WatchPriceEntry {
        WatchPriceEntry(date: Date(), coin: "BTC", price: WatchText.price(67_420), change: WatchText.change(1.2))
    }

    func getSnapshot(in context: Context, completion: @escaping @Sendable (WatchPriceEntry) -> Void) {
        completion(context.isPreview ? placeholder(in: context) : Self.entry(WatchStore.quotes().quotes))
    }

    func getTimeline(in context: Context, completion: @escaping @Sendable (Timeline<WatchPriceEntry>) -> Void) {
        Task {
            let coins = WatchStore.settings.shown
            var (quotes, time) = WatchStore.quotes()
            if let first = coins.first, WatchStore.now() - time > Self.maxAgeMs || quotes[first] == nil {
                let fresh = await WatchPriceSource.fetch(coins)
                if !fresh.isEmpty {
                    time = WatchStore.now()
                    WatchStore.saveQuotes(fresh, time: time)
                    quotes = fresh
                }
            }
            let next = Date().addingTimeInterval(15 * 60)
            completion(Timeline(entries: [Self.entry(quotes)], policy: .after(next)))
        }
    }

    private static func entry(_ quotes: [String: WatchQuote]) -> WatchPriceEntry {
        guard let coin = WatchStore.settings.shown.first else {
            return WatchPriceEntry(date: Date(), coin: nil, price: "—", change: "—")
        }
        let q = quotes[coin]
        return WatchPriceEntry(date: Date(), coin: coin, price: q.map { WatchText.price($0.price) } ?? "—",
                               change: WatchText.change(q?.change24h))
    }
}

struct WatchPriceWidgetView: View {
    @Environment(\.widgetFamily) private var family
    let entry: WatchPriceEntry

    var body: some View {
        content
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(entry.coin.map { WatchText.t("a11y_watch_row", $0, entry.price, entry.change) }
                                ?? WatchText.t("watch_empty"))
            .containerBackground(.clear, for: .widget)
    }

    @ViewBuilder private var content: some View {
        let coin = entry.coin ?? "—"
        switch family {
        case .accessoryRectangular:
            VStack(alignment: .leading) {
                Text(coin).font(.headline)
                Text(entry.price).lineLimit(1).minimumScaleFactor(0.6)
                Text(entry.change).foregroundStyle(.secondary)
            }
        case .accessoryInline:
            Text("\(coin) \(entry.change)")
        case .accessoryCorner:
            Text(entry.change).widgetLabel(coin)
        default:
            VStack(spacing: 0) {
                Text(coin).font(.caption).lineLimit(1).minimumScaleFactor(0.5)
                Text(entry.change).font(.caption).lineLimit(1).minimumScaleFactor(0.5)
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
        .description(WatchText.t("watch_choose_hint", WatchCoins.max))
        .supportedFamilies([.accessoryCircular, .accessoryRectangular, .accessoryInline, .accessoryCorner])
    }
}
