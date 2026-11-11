import SwiftUI
import WidgetKit

/// Komplikation «Portfolio»: der Wert, den das iPhone schickt (nur mit «Portfolio-Wert auf der Uhr»), ohne eigene
/// Abfrage — rund kurz «12.3K» mit Währung, rechteckig «Portfolio / CHF 12’345.67 / +1.20 % heute», als Zeile «CHF
/// 12’345.67»; verborgen auf dem iPhone «•••». Ohne Wert (Schalter aus oder noch nie einer) nie leer: rund und Ecke
/// «Portfolio / aus», rechteckig «Portfolio / am Handy einschalten», als Zeile «Portfolio · am Handy einschalten»
/// (`WatchPortfolioOff`); Tippen öffnet die Uhr-App. Veraltet (`WatchFreshness`): grau, rechteckig mit Alter, rund, Ecke
/// und Zeile knapp «USD 47m» (`WatchFreshness.shortTitle`);
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
        guard let p = entry.portfolio else {
            return WatchPortfolioOff.spoken(title: WatchText.t("watch_portfolio"), hint: WatchText.t("watch_portfolio_turn_on"))
        }
        let row = WatchText.portfolioSpoken(p)
        return entry.outdated ? row + ", " + WatchText.spokenAge(p.time, now: Int64(entry.date.timeIntervalSince1970 * 1000)) : row
    }

    private var valueStyle: HierarchicalShapeStyle { entry.outdated ? .secondary : .primary }

    @ViewBuilder private var content: some View {
        if let p = entry.portfolio {
            value(p)
        } else {
            off
        }
    }

    /// Kein Wert vom iPhone (Schalter «Portfolio-Wert auf der Uhr» aus): sagt es, statt «—» zu zeigen.
    @ViewBuilder private var off: some View {
        let title = WatchText.t("watch_portfolio")
        let hint = WatchText.t("watch_portfolio_turn_on")
        let short = WatchText.t("watch_portfolio_off")
        switch family {
        case .accessoryRectangular:
            VStack(alignment: .leading) {
                Text(title).font(.headline)
                Text(hint).lineLimit(2).minimumScaleFactor(0.6).foregroundStyle(.secondary)
            }
        case .accessoryInline:
            Text(WatchPortfolioOff.line(title: title, hint: hint))
        case .accessoryCorner:
            Text(short).foregroundStyle(.secondary).widgetLabel(title)
        default:
            VStack(spacing: 0) {
                Text(title).font(.caption).lineLimit(1).minimumScaleFactor(0.5)
                Text(short).font(.caption).lineLimit(1).minimumScaleFactor(0.5).foregroundStyle(.secondary)
            }
        }
    }

    /// Betrag und Veränderung sind `privacySensitive` (wie das iPhone-Widget): watchOS verdeckt sie bei gesenktem
    /// Handgelenk, wenn «Sensible Komplikationen ausblenden» an ist — Titel und Währung bleiben lesbar.
    @ViewBuilder private func value(_ p: WatchPortfolio) -> some View {
        let total = WatchText.portfolioTotal(p)
        let now = Int64(entry.date.timeIntervalSince1970 * 1000)
        // Veraltet: Alter knapp auch in den kleinen Familien («USD 47m»), wie Android der Kurztitel
        let title = WatchFreshness.shortTitle(p.currency, time: p.time, now: now)
        switch family {
        case .accessoryRectangular:
            VStack(alignment: .leading) {
                Text(WatchText.t("watch_portfolio")).font(.headline)
                Text(total).lineLimit(1).minimumScaleFactor(0.6).foregroundStyle(valueStyle).privacySensitive()
                let age = entry.outdated ? "  ·  " + WatchText.ago(p.time, now: Int64(entry.date.timeIntervalSince1970 * 1000)) : ""
                Text(WatchText.portfolioToday(p) + age).lineLimit(1).minimumScaleFactor(0.6).foregroundStyle(.secondary)
                    .privacySensitive()
            }
        case .accessoryInline:
            Text(WatchFreshness.inlineLine(total, time: p.time, now: now)).privacySensitive()
        case .accessoryCorner:
            Text(WatchText.portfolioCompact(p)).foregroundStyle(valueStyle).privacySensitive().widgetLabel(title)
        default:
            VStack(spacing: 0) {
                Text(title).font(.caption).lineLimit(1).minimumScaleFactor(0.5)
                Text(WatchText.portfolioCompact(p)).font(.caption).lineLimit(1).minimumScaleFactor(0.5)
                    .foregroundStyle(valueStyle).privacySensitive()
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
