import SwiftUI
import WidgetKit

// Ansichten «Gewinner und Verlierer 24 h» und «Bitcoin-Halving» des Widgets «Was gerade auffällt»
// (`PulseWidgetViewOption`) — wie Android `PulseWidgetViews.kt`. Der Impuls bleibt in `PulseWidgetHome.swift`.

/// Kopfzeile wie der Impuls: Logo und Titel (ganz, notfalls kleiner, sonst nur das Logo).
private struct PulseViewHeader: View {
    let title: String
    let entry: PulseEntry
    let palette: WidgetPalette
    /// Halving nach Kalender: ohne Stand (der Knopf bleibt, wie in allen Ansichten).
    var showsStatus = true

    var body: some View {
        HStack(spacing: 4) {
            WidgetLogo(accent: entry.accent, dark: palette.dark, size: 16)
                .accessibilityHidden(true)
            ViewThatFits(in: .horizontal) {
                titleText(WidgetFont.body)
                titleText(WidgetFont.label)
                Color.clear.frame(width: 0, height: 0)
            }
            .accessibilityHidden(true)
            Spacer(minLength: 0)
            // Stand und «Aktualisieren» wie im Merkliste-Widget
            WidgetHeaderTrailing(candidates: showsStatus ? entry.headerStatus : [], palette: palette,
                                 intent: RefreshPulseIntent(), blockedUntil: entry.refreshBlockedUntil, date: entry.date)
        }
    }

    private func titleText(_ size: CGFloat) -> some View {
        Text(title)
            .font(.system(size: size, weight: .bold))
            .foregroundStyle(palette.text)
            .lineLimit(1)
            .fixedSize(horizontal: true, vertical: false)
    }
}

/// Gewinner links, Verlierer rechts (je bis zu drei, aus der Rangliste des Puls), Veränderung in der Kursfarbe,
/// darunter der Stand; ohne Rangliste «nicht verfügbar». Wie Android `drawPulseMovers`.
struct PulseMoversView: View {
    let entry: PulseEntry
    let palette: WidgetPalette
    let size: PulseWidgetSize

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            PulseViewHeader(title: L("movers_title"), entry: entry, palette: palette)
            if let data = entry.data, let movers = data.movers, !movers.isEmpty {
                let gainers = movers.gainers.filter { ($0.change24h ?? 0) > 0 }
                let losers = movers.losers.filter { ($0.change24h ?? 0) < 0 }
                ViewThatFits(in: .vertical) {
                    columns(gainers, losers, rows: 3)
                    columns(gainers, losers, rows: 2)
                    columns(gainers, losers, rows: 1)
                }
                .padding(.top, Spacing.xs)
                Spacer(minLength: 2)
            } else {
                Spacer(minLength: 4)
                Text(L("pulse_unavailable"))
                    .widgetSecondaryFont(13, weight: .semibold)
                    .foregroundStyle(palette.secondary)
                Spacer(minLength: 4)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .padding(12)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(spokenLabel)
    }

    /// VoiceOver: Titel, Gewinner, Verlierer und Stand; ohne Rangliste «nicht verfügbar».
    private var spokenLabel: String {
        guard let data = entry.data, let movers = data.movers, !movers.isEmpty else {
            return A11y.join([L("movers_title"), L("pulse_unavailable")])
        }
        return spoken(movers.gainers.filter { ($0.change24h ?? 0) > 0 }, movers.losers.filter { ($0.change24h ?? 0) < 0 },
                      stand: PulseWidgetText.stand(data.time, offline: entry.offline, at: entry.date))
    }

    private func columns(_ gainers: [MoverCoin], _ losers: [MoverCoin], rows: Int) -> some View {
        HStack(alignment: .top, spacing: Spacing.md) {
            column(L("movers_gainers"), empty: L("movers_no_gainers"), coins: Array(gainers.prefix(rows)))
            column(L("movers_losers"), empty: L("movers_no_losers"), coins: Array(losers.prefix(rows)))
        }
    }

    private func column(_ title: String, empty: String, coins: [MoverCoin]) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            Text(coins.isEmpty ? empty : title)
                .widgetSecondaryFont()
                .foregroundStyle(palette.secondary)
                .lineLimit(1)
            ForEach(coins) { coin in
                HStack(spacing: 4) {
                    Text(coin.symbol)
                        .font(.system(size: 14, weight: .semibold))
                        .foregroundStyle(palette.text)
                        .lineLimit(1)
                    Spacer(minLength: 4)
                    Text(PercentText.signed(coin.change24h ?? 0, decimals: 1))
                        .font(.system(size: 14))
                        .monospacedDigit()
                        .foregroundStyle(palette.change(coin.change24h))
                        .lineLimit(1)
                }
                .frame(minHeight: 22)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private func spoken(_ gainers: [MoverCoin], _ losers: [MoverCoin], stand: String?) -> String {
        func list(_ coins: [MoverCoin]) -> String {
            coins.prefix(3).map { $0.symbol + " " + PercentText.signed($0.change24h ?? 0, decimals: 1) }
                .joined(separator: ", ")
        }
        return A11y.join([L("movers_title"), L("label_value", L("movers_gainers"), list(gainers)),
                          L("label_value", L("movers_losers"), list(losers)), stand])
    }
}

/// Nächstes Halving nach Kalender (`BitcoinHalving.info`, ohne Netz): Monat gross, «noch etwa 512 Tage», letztes
/// Halving und «Nach Kalender geschätzt». Keine Prognose. Wie Android `drawPulseHalving`.
struct PulseHalvingView: View {
    let entry: PulseEntry
    let palette: WidgetPalette
    let size: PulseWidgetSize

    var body: some View {
        let today = LocalDay(date: entry.date)
        let info = BitcoinHalving.info(today: today)
        let monthYear = DateFormatterCache.string(info.nextHalvingEstimate.date, purpose: "monthYear",
                                                  locale: LocaleNumbers.appLocale) { f in
            f.setLocalizedDateFormatFromTemplate("MMMyyyy")
        }
        let month = "~" + monthYear
        let days = max(0, today.days(until: info.nextHalvingEstimate))
        let left = L("pulse_widget_halving_left", L("alarm_near_window_days", count: days))
        let last = L("neutral_halving_last", DateFormatterCache.string(info.lastHalving.date, purpose: "mediumDate") { f in
            f.dateStyle = .medium
            f.timeStyle = .none
        })
        let note = L("pulse_widget_halving_note")
        VStack(alignment: .leading, spacing: 2) {
            PulseViewHeader(title: L("insights_halving_title"), entry: entry, palette: palette, showsStatus: false)
            Text(month)
                .font(.system(size: size == .small ? 20 : 22, weight: .bold))
                .foregroundStyle(palette.text)
                .lineLimit(1)
                .minimumScaleFactor(0.8)
                .padding(.top, Spacing.xs)
            Text(left)
                .font(.system(size: 14))
                .foregroundStyle(palette.text)
                .lineLimit(1)
            Spacer(minLength: 2)
            Text(last)
                .widgetSecondaryFont()
                .foregroundStyle(palette.secondary)
                .lineLimit(2)
            Text(note)
                .widgetSecondaryFont()
                .foregroundStyle(palette.secondary)
                .lineLimit(1)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .padding(12)
        .accessibilityElement(children: .ignore)
        // VoiceOver ohne «~» (würde als «Tilde» gelesen); «geschätzt» sagt der Hinweis — wie Android
        .accessibilityLabel(A11y.join([L("insights_halving_title"), monthYear, left, last, note]))
    }
}
