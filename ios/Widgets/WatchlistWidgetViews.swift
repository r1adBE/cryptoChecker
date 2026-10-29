import AppIntents
import SwiftUI
import WidgetKit

// Ansichten des Merklisten-Widgets (wie `ListWidgetRenderer.kt` / `ListWidgetRows.kt`): Liste mit Kopfzeile
// (mittel, gross), Zeile und kleines Widget. Daten, Zeitplan und Widget stehen in `WatchlistWidget.swift`.

/// Mittel und Gross: Kopfzeile und Liste.
struct WatchlistListView: View {
    let entry: WatchlistEntry
    let palette: WidgetPalette
    /// Zeilen in der Grundgrösse; mit grosser Schrift weniger (`WidgetRows.rowsFor`), damit sie Platz hat.
    let rows: Int
    @ScaledMetric(relativeTo: .body) private var textScale: CGFloat = 1

    /// Dünne Linie zwischen den Paaren.
    // 1 pt wie 1 dp auf Android – dünn, aber auf 3x-Displays noch sichtbar
    private var dividerHeight: CGFloat { 1 }

    var body: some View {
        VStack(spacing: 4) {
            header
            if entry.watches.isEmpty {
                empty
            } else {
                GeometryReader { geometry in
                    list(rows: WidgetRows.rowsFor(height: Double(geometry.size.height), scale: Double(textScale),
                                                  baseRows: rows))
                }
            }
        }
        .padding(.horizontal, 12)
        .padding(.top, 8)
        .padding(.bottom, 8)
        .widgetURL(entry.watches.isEmpty ? WidgetData.addURL : nil)
    }

    /// Logo · «Merkliste» links, rechts «Uhrzeit · Dauer» und der Aktualisieren-Knopf.
    private var header: some View {
        HStack(spacing: 4) {
            // Logo 18 wie Android (`widget_list.xml`), Titel 13 fett, Uhrzeit 12
            WidgetLogo(accent: entry.accent, dark: palette.dark, size: 18)
            WatchlistHeaderTitle(entry: entry, palette: palette,
                                 titleSizes: (normal: WidgetFont.body, small: WidgetFont.label), metaSize: 12)
            WidgetRefreshButton(palette: palette, size: 26, blockedUntil: entry.refreshBlockedUntil, date: entry.date)
        }
        .frame(height: 26)
    }

    private var empty: some View {
        VStack(spacing: 4) {
            Spacer(minLength: 0)
            Image(systemName: "list.bullet.rectangle")
                .font(.system(size: 22, weight: .regular))
                .foregroundStyle(palette.accent)
                .accessibilityHidden(true)
            Text(L("widget_empty"))
                .widgetSecondaryFont(12, weight: .medium)
                .foregroundStyle(palette.secondary)
                .multilineTextAlignment(.center)
                .minimumScaleFactor(0.8)
            Spacer(minLength: 0)
        }
        .frame(maxWidth: .infinity)
    }

    private func list(rows: Int) -> some View {
        let shown = Array(entry.watches.prefix(rows))
        return VStack(spacing: 0) {
            ForEach(0..<rows, id: \.self) { index in
                if index < shown.count {
                    let watch = shown[index]
                    // Trennlinie zwischen den Paaren (über jeder Zeile ausser der ersten, also nie
                    // unter der letzten): neutral (Haupttext mit geringer Deckkraft, nie Akzentfarbe —
                    // wie WidgetContrast.dividerColor auf Android). Eine Regel für beide —
                    // über die ganze Zeilenbreite (wie `item_divider` in widget_list_item.xml),
                    // nicht eingerückt (die Plakette fehlt ohne «Coin-Logos in Widgets»).
                    if index > 0 {
                        Rectangle()
                            .fill(palette.divider)
                            .frame(height: dividerHeight)
                            .accessibilityHidden(true)
                    }
                    if let url = WidgetData.watchURL(watch.id) {
                        Link(destination: url) {
                            WatchlistWidgetRow(watch: watch, palette: palette, date: entry.date,
                                               outdatedAfter: entry.outdatedAfter, changeView: entry.changeView)
                        }
                        .frame(maxHeight: .infinity)
                    } else {
                        WatchlistWidgetRow(watch: watch, palette: palette, date: entry.date,
                                           outdatedAfter: entry.outdatedAfter, changeView: entry.changeView)
                            .frame(maxHeight: .infinity)
                    }
                } else {
                    // Freie Plätze halten die Zeilenhöhe gleich.
                    Color.clear
                        .frame(maxHeight: .infinity)
                        .padding(.top, index > 0 ? dividerHeight : 0)
                }
            }
        }
        .frame(maxHeight: .infinity)
    }
}

/// Titel «Merkliste» und «Uhrzeit · Dauer» der Kopfzeile, nie abgeschnitten (wie
/// ListWidgetHeader auf Android): `ViewThatFits` nimmt die erste Stufe, die ganz passt —
/// erst wird der Titel kleiner (die Dauer der Aktualisierung bleibt so sichtbar), dann fällt die Dauer
/// weg, dann die Uhrzeit. Der Titel bleibt immer. Veraltet: «veraltet · 06:42» ohne Dauer, eng nur «veraltet».
private struct WatchlistHeaderTitle: View {
    let entry: WatchlistEntry
    let palette: WidgetPalette
    /// Schriftgrössen des Titels: normal, eng.
    let titleSizes: (normal: CGFloat, small: CGFloat)
    let metaSize: CGFloat

    private var outdated: Bool {
        WidgetOutdated.isOutdated(entry.lastRefreshAt, at: entry.date, afterMillis: entry.outdatedAfter)
    }

    var body: some View {
        let stale = WidgetOutdated.label(entry.lastRefreshAt, at: entry.date)
        let full = outdated ? stale : WidgetData.refreshMeta(at: entry.lastRefreshAt, duration: entry.lastDuration)
        let timeOnly = outdated ? stale : WidgetData.refreshMeta(at: entry.lastRefreshAt, duration: 0)
        ViewThatFits(in: .horizontal) {
            row(titleSize: titleSizes.normal, meta: full)
            row(titleSize: titleSizes.small, meta: full)
            row(titleSize: titleSizes.normal, meta: timeOnly)
            row(titleSize: titleSizes.small, meta: timeOnly)
            if outdated {
                row(titleSize: titleSizes.small, meta: L("widget_outdated"))
            }
            HStack(spacing: 0) {
                // Letzte Stufe: nur der Titel, notfalls leicht verkleinert statt «Merkl…»
                title(size: titleSizes.small)
                    .minimumScaleFactor(0.85)
                Spacer(minLength: 0)
            }
        }
    }

    private func row(titleSize: CGFloat, meta: String) -> some View {
        HStack(spacing: 0) {
            title(size: titleSize)
                .fixedSize()
            Spacer(minLength: 4)
            if !meta.isEmpty {
                Text(meta)
                    .widgetSecondaryFont(metaSize, weight: .medium, monospacedDigit: true)
                    .foregroundStyle(palette.secondary)
                    .lineLimit(1)
                    .fixedSize()
                    .invalidatableContent()
                    // VoiceOver: «veraltet, letzte Aktualisierung 06:42» statt der Kurzform
                    .accessibilityLabel(outdated ? WidgetOutdated.spoken(entry.lastRefreshAt, at: entry.date) : meta)
            }
        }
    }

    private func title(size: CGFloat) -> some View {
        Text(entry.title)
            .font(.system(size: size, weight: .bold))
            .foregroundStyle(palette.text)
            .lineLimit(1)
    }
}

/// Eine Zeile, zweizeilig und eng: oben Paar und Kurs, unten Börse und
/// Veränderung mit Pfeil und Vorzeichen — die Richtung hängt nie allein an der Farbe.
/// VoiceOver liest die Zeile als einen Satz. Masse wie Android (`widget_list_item.xml`):
/// Plakette 24, Abstand 8, Paar und Kurs 14 fett, Börse und Veränderung 11; kein Favoriten-Stern.
private struct WatchlistWidgetRow: View {
    let watch: Watch
    let palette: WidgetPalette
    /// Datum des Eintrags und Grenze: eigener Stand des Paares veraltet → «Binance · veraltet».
    let date: Date
    let outdatedAfter: Int64
    /// %-Basis und Gültigkeit der gespeicherten Veränderung.
    var changeView = ChangeView()

    private var outdated: Bool { WidgetOutdated.isOutdated(watch, at: date, afterMillis: outdatedAfter) }

    /// Börse ohne eigene Zeit, Kurs steht lange: «Kurs unverändert seit 14:05» (wie die Zeile der App).
    private var unchanged: String? {
        outdated ? nil : WidgetOutdated.unchanged(watch, at: date, afterMillis: outdatedAfter)
    }

    /// «Binance · deaktiviert» bzw. «Binance · veraltet» bzw. «Kraken · Kurs unverändert seit 14:05»
    /// bzw. nur die Börse.
    private var exchangeLine: String {
        if watch.disabled { return L("watchlist_row_disabled", BidiText.isolate(watch.exchangeName)) }
        if outdated { return L("watchlist_row_outdated", BidiText.isolate(watch.exchangeName)) }
        if let unchanged { return BidiText.isolate(watch.exchangeName) + " · " + unchanged }
        return watch.exchangeName
    }

    /// VoiceOver-Zusatz: «Deaktiviert» bzw. «veraltet, letzte Aktualisierung …» bzw. «Kurs unverändert seit …».
    private var spokenState: String? {
        if watch.disabled { return L("watch_disabled") }
        return outdated ? WidgetOutdated.spoken(watch, at: date, afterMillis: outdatedAfter) : unchanged
    }

    /// Coin-Name und Kurs wachsen mit Dynamic Type (`WidgetPrimaryFit`), solange die Zeile in der Höhe Platz hat;
    /// sonst Grundgrösse. Der Name schrumpft bei Platzmangel höchstens bis zur Grundgrösse.
    var body: some View {
        WidgetPrimaryFit(in: .vertical) { scale in
            content(scale: scale)
        }
        .contentShape(Rectangle())
        .accessibilityElement(children: .combine)
        .accessibilityLabel(A11y.watchRow(watch, stale: spokenState, changeView: changeView))
    }

    private func content(scale: CGFloat) -> some View {
        HStack(spacing: 8) {
            WidgetCoinBadge(symbol: CoinLogoStore.logoKey(for: watch), size: 24, dark: palette.dark, accent: palette.accent,
                            logo: CoinLogos.allowed(forExchange: watch.exchangeKey))
                // Deaktiviert: Logo gedämpft (wie Android `DISABLED_LOGO_ALPHA`), Texte grau statt blass
                .opacity(watch.disabled ? 0.45 : 1)
            VStack(alignment: .leading, spacing: 1) {
                Text(watch.displayName)
                    .font(.system(size: WidgetFont.body * scale, weight: .bold))
                    .foregroundStyle(watch.disabled ? palette.secondary : palette.text)
                    .lineLimit(1)
                    .minimumScaleFactor(WidgetTextSize.minimumFactor(1, primaryScale: scale))
                Text(exchangeLine)
                    .font(.system(size: 12 * scale))
                    .foregroundStyle(palette.secondary)
                    .lineLimit(1)
            }
            .layoutPriority(1)
            Spacer(minLength: 4)
            priceColumn(scale: scale)
                .layoutPriority(2)
        }
    }

    private func priceColumn(scale: CGFloat) -> some View {
        VStack(alignment: .trailing, spacing: 1) {
            // Deaktiviert: Kurs grau (palette.secondary) statt 60 % Deckkraft — wie Android
            WidgetPriceText(price: watch.lastPrice, quote: watch.quoteAsset, size: WidgetFont.body,
                            palette: palette, weight: .bold,
                            color: watch.disabled ? palette.secondary : nil, scale: scale)
            if watch.lastPrice == nil, watch.lastError != nil, !watch.disabled {
                Image(systemName: "exclamationmark.triangle.fill")
                    .font(.system(size: 9))
                    .foregroundStyle(palette.down)
            } else {
                // Änderung in der Listenzeile: 12 pt wie Android (widget_list_item, 12sp)
                WidgetChangeLabel(change: changeView.shown(watch), palette: palette, size: 12,
                                  showsArrow: true, day: true, basis: changeView.basis, neutral: watch.disabled)
            }
        }
    }
}

/// Klein: das oberste Paar.
struct WatchlistSmallView: View {
    let entry: WatchlistEntry
    let palette: WidgetPalette

    var body: some View {
        let watch = entry.watches.first
        let outdated = watch.map { WidgetOutdated.isOutdated($0, at: entry.date, afterMillis: entry.outdatedAfter) } ?? false
        VStack(alignment: .leading, spacing: 0) {
            HStack(spacing: 4) {
                WidgetLogo(accent: entry.accent, dark: palette.dark, size: 14)
                // «Merkliste» · «Uhrzeit · Dauer»; eng: kleinerer Titel, ohne Dauer, ohne Uhrzeit
                WatchlistHeaderTitle(entry: entry, palette: palette,
                                     titleSizes: (normal: 12, small: 11), metaSize: 12)
                WidgetRefreshButton(palette: palette, size: 24, blockedUntil: entry.refreshBlockedUntil, date: entry.date)
            }
            Spacer(minLength: 4)
            if let watch {
                // Coin-Name und Kurs wachsen mit Dynamic Type, solange das Widget in der Höhe Platz hat
                WidgetPrimaryFit(in: .vertical) { scale in
                    smallPair(watch, outdated: outdated, scale: scale)
                }
                // VoiceOver: das Paar als ein Satz (veraltet mit «veraltet, letzte Aktualisierung …»)
                .accessibilityElement(children: .combine)
                .accessibilityLabel(A11y.watchRow(watch, stale: watch.disabled ? L("watch_disabled")
                                                    : (outdated ? WidgetOutdated.spoken(watch, at: entry.date, afterMillis: entry.outdatedAfter)
                                                       : WidgetOutdated.unchanged(watch, at: entry.date,
                                                                                  afterMillis: entry.outdatedAfter)),
                                                  changeView: entry.changeView))
            } else {
                Text(L("widget_empty"))
                    .widgetSecondaryFont(12, weight: .medium)
                    .foregroundStyle(palette.secondary)
                    .minimumScaleFactor(0.8)
                Spacer(minLength: 0)
            }
        }
        .padding(12)
        .widgetURL(watch.flatMap { WidgetData.watchURL($0.id) } ?? WidgetData.addURL)
    }

    /// Paar, Börse, Kurs und Veränderung; `scale` = Faktor der Haupttexte (`WidgetPrimaryFit`).
    private func smallPair(_ watch: Watch, outdated: Bool, scale: CGFloat) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(spacing: 4) {
                WidgetCoinBadge(symbol: CoinLogoStore.logoKey(for: watch), size: 20, dark: palette.dark, accent: palette.accent,
                                logo: CoinLogos.allowed(forExchange: watch.exchangeKey))
                Text(watch.displayName)
                    .font(.system(size: 13 * scale, weight: .semibold))
                    .foregroundStyle(palette.text)
                    .lineLimit(1)
                    .minimumScaleFactor(WidgetTextSize.minimumFactor(0.75, primaryScale: scale))
            }
            // Deaktiviert: «Binance · deaktiviert», Kurs und Veränderung grau
            Text(watch.disabled ? L("watchlist_row_disabled", BidiText.isolate(watch.exchangeName)) : watch.exchangeName)
                .widgetSecondaryFont()
                .foregroundStyle(palette.secondary)
                .lineLimit(1)
                .padding(.top, 2)
            WidgetPriceText(price: watch.lastPrice, quote: watch.quoteAsset, size: 24, palette: palette,
                            color: watch.disabled ? palette.secondary : nil, scale: scale)
                .padding(.top, 2)
            HStack {
                WidgetChangeLabel(change: entry.changeView.shown(watch), palette: palette, size: 12,
                                  showsArrow: true, day: true, basis: entry.changeBasis, neutral: watch.disabled)
                Spacer(minLength: 4)
                // Kein Platz für «Kurs unverändert seit …»: dann die Zeit der letzten Änderung
                Text(WidgetOutdated.timeText(watch, outdated: outdated, at: entry.date,
                                             afterMillis: entry.outdatedAfter, allowsHint: false))
                    .widgetSecondaryFont(weight: .medium, monospacedDigit: true)
                    .foregroundStyle(palette.secondary)
                    .lineLimit(1)
                    .invalidatableContent()
            }
            .padding(.top, 1)
        }
    }
}
