import SwiftUI
import WidgetKit

/// Komplikation «Portfolio»: der Wert, den das iPhone schickt (nur mit «Portfolio-Wert auf der Uhr»), ohne eigene
/// Abfrage — rund kurz «12.3K» mit Währung, rechteckig «Portfolio / CHF 12’345.67 / +1.20 % heute», als Zeile «CHF
/// 12’345.67»; verborgen auf dem iPhone «•••». Ohne Wert «—». Veraltet (`WatchFreshness`): grau, rechteckig mit Alter;
/// VoiceOver nennt es immer. Neu gezeichnet bei jedem neuen Wert (`WatchModel`). Wie Android `PortfolioComplicationService`.
struct WatchPortfolioEntry: TimelineEntry {
    let date: Date
    let portfolio: WatchPortfolio?

    var outdated: Bool {
        guard let p = portfolio else { return false }
        return WatchFreshness.isOutdated(p.time, now: Int64(date.timeIntervalSince1970 * 1000))
    }
}

struct WatchPortfolioProvider: TimelineProvider {
    func placeholder(in context: Context) -> WatchPortfolioEntry {
        WatchPortfolioEntry(date: Date(), portfolio: WatchPortfolio(total: 12_345.67, changePercent: 1.2, currency: "USD",
                                                                    time: WatchStore.now(), hidden: false))
    }

    func getSnapshot(in context: Context, completion: @escaping @Sendable (WatchPortfolioEntry) -> Void) {
        completion(context.isPreview ? placeholder(in: context) : WatchPortfolioEntry(date: Date(), portfolio: WatchStore.portfolio()))
    }

    func getTimeline(in context: Context, completion: @escaping @Sendable (Timeline<WatchPortfolioEntry>) -> Void) {
        let now = Date()
        let portfolio = WatchStore.portfolio()
        var entries = [WatchPortfolioEntry(date: now, portfolio: portfolio)]
        // Zweiter Eintrag, sobald der Wert veraltet (1 ms nach der Grenze, wie `WatchPriceProvider`)
        if let time = portfolio?.time {
            let staleAt = WatchText.date(time + WatchFreshness.outdatedAfterMillis + 1)
            if staleAt > now { entries.append(WatchPortfolioEntry(date: staleAt, portfolio: portfolio)) }
        }
        // Neue Werte kommen vom iPhone (Neuzeichnen über `WatchComplications.reload`); sonst nur das Alter
        completion(Timeline(entries: entries, policy: .after(now.addingTimeInterval(TimeInterval(WatchFreshness.updateMinutes * 60)))))
    }
}

struct WatchPortfolioWidgetView: View {
    @Environment(\.widgetFamily) private var family
    let entry: WatchPortfolioEntry

    var body: some View {
        content
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(spoken)
            .containerBackground(.clear, for: .widget)
    }

    private var spoken: String {
        guard let p = entry.portfolio else { return WatchText.t("watch_portfolio") + ", —" }
        let row = WatchText.portfolioSpoken(p)
        return entry.outdated ? row + ", " + WatchText.spokenAge(p.time, now: Int64(entry.date.timeIntervalSince1970 * 1000)) : row
    }

    private var valueStyle: HierarchicalShapeStyle { entry.outdated ? .secondary : .primary }

    @ViewBuilder private var content: some View {
        let p = entry.portfolio
        let total = p.map(WatchText.portfolioTotal) ?? "—"
        switch family {
        case .accessoryRectangular:
            VStack(alignment: .leading) {
                Text(WatchText.t("watch_portfolio")).font(.headline)
                Text(total).lineLimit(1).minimumScaleFactor(0.6).foregroundStyle(valueStyle)
                if let p {
                    let age = entry.outdated ? "  ·  " + WatchText.ago(p.time, now: Int64(entry.date.timeIntervalSince1970 * 1000)) : ""
                    Text(WatchText.portfolioToday(p) + age).lineLimit(1).minimumScaleFactor(0.6).foregroundStyle(.secondary)
                }
            }
        case .accessoryInline:
            Text(total)
        case .accessoryCorner:
            Text(p.map(WatchText.portfolioCompact) ?? "—").foregroundStyle(valueStyle)
                .widgetLabel(p?.currency ?? WatchText.t("watch_portfolio"))
        default:
            VStack(spacing: 0) {
                Text(p?.currency ?? "—").font(.caption).lineLimit(1).minimumScaleFactor(0.5)
                Text(p.map(WatchText.portfolioCompact) ?? "—").font(.caption).lineLimit(1).minimumScaleFactor(0.5)
                    .foregroundStyle(valueStyle)
            }
        }
    }
}

struct WatchPortfolioWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: "WatchPortfolioWidget", provider: WatchPortfolioProvider()) { entry in
            WatchPortfolioWidgetView(entry: entry)
        }
        .configurationDisplayName(WatchText.t("watch_portfolio"))
        .description(WatchText.t("watch_portfolio"))
        .supportedFamilies([.accessoryCircular, .accessoryRectangular, .accessoryInline, .accessoryCorner])
    }
}
