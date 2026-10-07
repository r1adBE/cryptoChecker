import AppIntents
import SwiftUI
import WidgetKit

// MARK: Daten

struct PortfolioEntry: TimelineEntry {
    var date: Date
    /// nil = noch nie berechnet (wie leer behandelt).
    let snapshot: PortfolioWidgetSnapshot?
    /// App-Sperre an: keine Werte zeigen.
    let locked: Bool
    let accent: AccentColor
    /// Anzeige je Widget: nur Umrechnung oder zusätzlich «≈ … USDT».
    var display: PortfolioDisplayOption = .conversionUsdt
    var theme: WidgetThemeOption = .system
    var priceColors: PriceColorScheme = .default
    var highContrast = false
    var priceColorsInverted = false
    /// Ab diesem Alter «veraltet» (`WidgetOutdated`), gemessen an `date`.
    var outdatedAfter: Int64 = WidgetOutdated.afterMillis

    /// Momentaufnahme am Datum des Eintrags veraltet? (Gesperrt oder leer: nie.)
    var outdated: Bool {
        guard !locked, let snapshot, !snapshot.empty else { return false }
        return WidgetOutdated.isOutdated(snapshot.updatedAt, at: date, afterMillis: outdatedAfter)
    }

    static func current(display: PortfolioDisplayOption = .conversionUsdt,
                        theme: WidgetThemeOption = .system) -> PortfolioEntry {
        let settings = SharedStorage.loadSettings()
        return PortfolioEntry(date: Date(), snapshot: PortfolioWidgetStore.load(), locked: settings.appLock,
                              accent: settings.accentColor, display: display, theme: theme,
                              priceColors: settings.priceColorScheme, highContrast: settings.highContrast,
                              priceColorsInverted: settings.priceColorsInverted)
    }

    static func sample(display: PortfolioDisplayOption = .conversionUsdt,
                       theme: WidgetThemeOption = .system) -> PortfolioEntry {
        let settings = SharedStorage.loadSettings()
        let now = TimeUtils.nowMillis
        let hour: Int64 = 3_600_000
        let values: [Double] = [11_820, 11_905, 11_870, 12_010, 11_960, 12_140, 12_080, 12_220, 12_190, 12_345.67]
        var history: [PortfolioWidgetPoint] = []
        for (i, value) in values.enumerated() {
            history.append(PortfolioWidgetPoint(at: now - Int64(values.count - 1 - i) * 5 * hour, value: value))
        }
        let positions = [
            PortfolioWidgetPosition(symbol: "BTC", value: 7_420.10, sharePercent: 60.1, change24hPercent: 1.8),
            PortfolioWidgetPosition(symbol: "ETH", value: 2_910.55, sharePercent: 23.6, change24hPercent: -0.9),
            PortfolioWidgetPosition(symbol: "SOL", value: 1_215.02, sharePercent: 9.8, change24hPercent: 3.2),
            PortfolioWidgetPosition(symbol: "ADA", value: 800.00, sharePercent: 6.5, change24hPercent: 0),
        ]
        let snapshot = PortfolioWidgetSnapshot(total: 12_345.67, changeAmount: 182.4, changePercent: 1.5,
                                               currency: settings.portfolioCurrency,
                                               updatedAt: now, empty: false, totalUsdt: 13_890.12,
                                               positions: positions, otherPositions: 0, history: history)
        return PortfolioEntry(date: Date(), snapshot: snapshot, locked: false, accent: settings.accentColor,
                              display: display, theme: theme,
                              priceColors: settings.priceColorScheme, highContrast: settings.highContrast,
                              priceColorsInverted: settings.priceColorsInverted)
    }
}

struct PortfolioProvider: AppIntentTimelineProvider {
    typealias Entry = PortfolioEntry
    typealias Intent = PortfolioWidgetIntent

    func placeholder(in context: Context) -> PortfolioEntry {
        .sample()
    }

    func snapshot(for configuration: PortfolioWidgetIntent, in context: Context) async -> PortfolioEntry {
        let entry = PortfolioEntry.current(display: configuration.display, theme: configuration.theme)
        let empty = entry.snapshot?.empty ?? true
        // Galerie: leer oder mit App-Sperre das Beispiel (keine echten Werte, also auch gesperrt unbedenklich)
        if context.isPreview && (empty || entry.locked) {
            return .sample(display: configuration.display, theme: configuration.theme)
        }
        return entry
    }

    func timeline(for configuration: PortfolioWidgetIntent, in context: Context) async -> Timeline<PortfolioEntry> {
        // Alter Stand: selbst nachrechnen (Kurse 60 s zwischengespeichert), damit
        // das Widget nicht vom Öffnen der App abhängt. Gesperrt: nichts holen.
        let settings = SharedStorage.loadSettings()
        let snapshot = PortfolioWidgetStore.load()
        let age = TimeUtils.nowMillis - (snapshot?.updatedAt ?? 0)
        if !settings.appLock && age >= WidgetRefresh.staleAfterMillis {
            await PortfolioWidgetStore.refresh(reload: false)
        }
        let entry = PortfolioEntry.current(display: configuration.display, theme: configuration.theme)
        // Zweiter Eintrag, sobald die Momentaufnahme veraltet («veraltet · 06:42»)
        var times: [Int64] = []
        if !entry.locked, let current = entry.snapshot, !current.empty { times = [current.updatedAt] }
        let entries = WidgetOutdated.entries(entry, date: entry.date, times: times, afterMillis: entry.outdatedAfter) { date in
            var later = entry
            later.date = date
            return later
        }
        return Timeline(entries: entries, policy: .after(WidgetData.nextReload))
    }
}

// MARK: Widget

struct PortfolioWidget: Widget {
    var body: some WidgetConfiguration {
        // Gleiche `kind` wie früher (StaticConfiguration): abgelegte Widgets bleiben und
        // erhalten die Standardwerte der Einrichtung.
        AppIntentConfiguration(kind: PortfolioWidgetStore.kind,
                               intent: PortfolioWidgetIntent.self,
                               provider: PortfolioProvider()) { entry in
            PortfolioWidgetView(entry: entry)
        }
        .configurationDisplayName(L("widget_portfolio_name"))
        .description(L("widget_portfolio_description"))
        .supportedFamilies([.systemSmall, .systemMedium, .systemLarge, .accessoryRectangular])
        .contentMarginsDisabled()
    }
}

// MARK: Texte

/// Gemeinsame Texte des Portfolio-Widgets.
enum PortfolioWidgetText {
    /// Bis zu dieser Spanne heisst der Wertverlauf «24h», darüber «48h».
    static let daySpanMillis: Int64 = 25 * 3_600_000

    /// «12’345.67 CHF».
    static func total(_ s: PortfolioWidgetSnapshot) -> String {
        PriceFormat.valueWithCurrency(s.total, s.currency)
    }

    /// «+182.40 CHF»; nil ohne Vergleichswert.
    static func amount(_ s: PortfolioWidgetSnapshot) -> String? {
        guard let value = s.changeAmount, value.isFinite else { return nil }
        let zero = abs(value) < 0.005
        let sign = zero ? "" : (value > 0 ? "+" : "−")
        return sign + PriceFormat.valueWithCurrency(abs(value), s.currency)
    }

    /// «+1.50%»; «0.00%» bei praktisch 0; nil ohne Vergleichswert.
    static func percent(_ s: PortfolioWidgetSnapshot) -> String? {
        guard let value = s.changePercent, value.isFinite else { return nil }
        return PriceFormat.changePercent(value) ?? WidgetChangeLabel.zeroText
    }

    /// «heute +182.40 CHF · +1.50%» bzw. «heute —».
    static func today(_ s: PortfolioWidgetSnapshot) -> String {
        let parts = [amount(s), percent(s)].compactMap { $0 }
        return L("widget_portfolio_today", parts.isEmpty ? "—" : parts.joined(separator: " · "))
    }

    /// «92’310.00 USDT», wenn gewünscht und die Anzeigewährung nicht schon USD ist; sonst nil.
    static func usdt(_ s: PortfolioWidgetSnapshot, display: PortfolioDisplayOption) -> String? {
        guard let value = s.totalUsdt, value.isFinite,
              PortfolioWidgetStore.showsUsdt(enabled: display.showsUsdt, currency: s.currency) else { return nil }
        return PriceFormat.valueWithCurrency(value, "USDT")
    }

    /// Wertverlauf mit mindestens zwei Punkten, sonst nil (Chart ausgeblendet).
    static func chartPoints(_ s: PortfolioWidgetSnapshot) -> [PortfolioWidgetPoint]? {
        let points = s.valueHistory.filter { $0.value.isFinite }
        return points.count >= 2 ? points : nil
    }

    /// Zeitraum des Wertverlaufs: lang (VoiceOver) bzw. kurz (neben der Uhrzeit).
    static func period(_ points: [PortfolioWidgetPoint], short: Bool) -> String {
        let day = (points.last?.at ?? 0) - (points.first?.at ?? 0) <= daySpanMillis
        if short { return L(day ? "widget_range_short_24h" : "widget_portfolio_range_short_48h") }
        return L(day ? "widget_range_24h" : "widget_portfolio_range_48h")
    }

    /// VoiceOver-Satz zum Wertverlauf (Zeitraum, Start, Ende, Veränderung, Hoch, Tief).
    static func chartAccessibility(_ points: [PortfolioWidgetPoint], currency: String) -> String {
        A11y.chart(period: period(points, short: false), values: points.map(\.value),
                   format: { PriceFormat.valueWithCurrency($0, currency) })
    }

    /// Anteil mit einer Nachkommastelle, z. B. «62.3%».
    static func share(_ percent: Double) -> String {
        String(format: "%.1f%%", locale: Locale.current, Swift.min(100, Swift.max(0, percent)))
    }

    /// Wert einer Position ohne Währung (die steht beim Gesamtwert).
    static func positionValue(_ value: Double) -> String {
        PriceFormat.valueWithCurrency(value, "").trimmingCharacters(in: .whitespaces)
    }

    /// VoiceOver: «Grösste Positionen: BTC 7’420.10 CHF, 60.1% des Portfolios, heute gestiegen um 1.80%; …».
    static func positionsAccessibility(_ shown: [PortfolioWidgetPosition], more: Int, currency: String) -> String? {
        guard !shown.isEmpty else { return nil }
        var parts: [String] = shown.map { p in
            let value = PriceFormat.valueWithCurrency(p.value, currency)
            if let change = A11y.change(p.change24hPercent) {
                return L("a11y_portfolio_position_change", p.symbol, value, share(p.sharePercent), change)
            }
            return L("a11y_portfolio_position", p.symbol, value, share(p.sharePercent))
        }
        if more > 0 { parts.append(L("widget_portfolio_more", count: more)) }
        return L("a11y_portfolio_positions", parts.joined(separator: "; "))
    }

    /// VoiceOver: «Portfolio 12’345.67 CHF, heute gestiegen um 1.50%, 182.40 CHF» — danach
    /// ≈ USDT, Verlauf, Positionen und Uhrzeit (`withTime`), soweit sichtbar. `outdated`:
    /// «veraltet, letzte Aktualisierung 06:42» — steht immer statt der Uhrzeit.
    static func accessibility(_ s: PortfolioWidgetSnapshot, withToday: Bool = true, usdt: String? = nil,
                              chart: String? = nil, positions: String? = nil, withTime: Bool = false,
                              outdated: String? = nil) -> String {
        let head: String
        if withToday {
            var change = "—"
            if let value = s.changeAmount, value.isFinite {
                let direction = A11y.change(s.changePercent) ?? L("a11y_change_flat")
                change = A11y.join([direction, PriceFormat.valueWithCurrency(abs(value), s.currency)])
            }
            head = L("a11y_portfolio_widget", total(s), change)
        } else {
            head = A11y.join([L("widget_portfolio_name"), total(s)])
        }
        let time = withTime && s.updatedAt > 0 ? PriceFormat.time(s.updatedAt) : nil
        return A11y.join([head, usdt.map { L("a11y_converted", $0) }, chart, positions, outdated ?? time])
    }

    static var appURL: URL? { URL(string: "cryptochecker://portfolio") }
}

// MARK: Ansicht

struct PortfolioWidgetView: View {
    let entry: PortfolioEntry
    @Environment(\.widgetFamily) private var family

    var body: some View {
        switch family {
        case .accessoryRectangular:
            PortfolioRectangularView(entry: entry)
                .containerBackground(for: .widget) { Color.clear }
                .widgetURL(PortfolioWidgetText.appURL)
        default:
            WidgetThemed(theme: entry.theme, accent: entry.accent, priceColors: entry.priceColors,
                         highContrast: entry.highContrast, inverted: entry.priceColorsInverted) { palette in
                PortfolioHomeView(entry: entry, palette: palette, family: family)
                    .widgetURL(PortfolioWidgetText.appURL)
            }
        }
    }
}

/// Startbildschirm: klein (Gesamtwert, ≈ USDT, Uhrzeit), mittel (dazu «heute» und
/// Wertverlauf rechts), gross (dazu die grössten Positionen).
private struct PortfolioHomeView: View {
    let entry: PortfolioEntry
    let palette: WidgetPalette
    let family: WidgetFamily

    private var medium: Bool { family == .systemMedium }
    private var large: Bool { family == .systemLarge }

    /// VoiceOver bei veraltetem Stand: «veraltet, letzte Aktualisierung 06:42».
    private var spokenOutdated: String? {
        guard entry.outdated, let s = entry.snapshot else { return nil }
        return WidgetOutdated.spoken(s.updatedAt, at: entry.date)
    }

    var body: some View {
        Group {
            if !entry.locked, let s = entry.snapshot, !s.empty {
                switch family {
                case .systemMedium: mediumBody(s)
                case .systemLarge: largeBody(s)
                default: smallBody(s)
                }
            } else {
                VStack(alignment: .leading, spacing: 0) {
                    header
                    Spacer(minLength: 4)
                    message(entry.locked ? L("widget_portfolio_locked") : L("widget_portfolio_empty"))
                }
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .padding(family == .systemSmall ? 14 : 16)
    }

    private var header: some View {
        HStack(spacing: 6) {
            WidgetLogo(accent: entry.accent, dark: palette.dark, size: 16)
            Text(L("widget_portfolio_name"))
                .font(.system(size: 12.5, weight: .bold))
                .foregroundStyle(palette.text)
                .lineLimit(1)
            Spacer(minLength: 0)
            if entry.locked {
                Image(systemName: "lock.fill")
                    .font(.system(size: 11, weight: .semibold))
                    .foregroundStyle(palette.secondary)
                    .accessibilityHidden(true)
            }
        }
    }

    private func message(_ text: String) -> some View {
        Text(text)
            .font(.system(size: 13, weight: .semibold))
            .foregroundStyle(palette.secondary)
            .fixedSize(horizontal: false, vertical: true)
            .frame(maxWidth: .infinity, alignment: .leading)
    }

    // MARK: Klein

    private func smallBody(_ s: PortfolioWidgetSnapshot) -> some View {
        let usdt = PortfolioWidgetText.usdt(s, display: entry.display)
        return VStack(alignment: .leading, spacing: 0) {
            header
            Spacer(minLength: 4)
            totalText(s, size: 22)
            usdtLine(usdt)
            Spacer(minLength: 4)
            timeLine(s, period: nil)
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(PortfolioWidgetText.accessibility(s, withToday: false, usdt: usdt, withTime: true,
                                                              outdated: spokenOutdated))
    }

    // MARK: Mittel

    private func mediumBody(_ s: PortfolioWidgetSnapshot) -> some View {
        let usdt = PortfolioWidgetText.usdt(s, display: entry.display)
        let points = PortfolioWidgetText.chartPoints(s)
        let chartText = points.map { PortfolioWidgetText.chartAccessibility($0, currency: s.currency) }
        return HStack(spacing: 14) {
            VStack(alignment: .leading, spacing: 0) {
                header
                Spacer(minLength: 4)
                totalText(s, size: 26)
                usdtLine(usdt)
                todayPill(s)
                Spacer(minLength: 4)
                timeLine(s, period: points.map { PortfolioWidgetText.period($0, short: true) })
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            if let points {
                PortfolioValueChart(points: points, palette: palette, lineWidth: 2)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(PortfolioWidgetText.accessibility(s, usdt: usdt, chart: chartText, withTime: true,
                                                              outdated: spokenOutdated))
    }

    // MARK: Gross

    /// Gross: so viele Listenzeilen (inklusive «+ n weitere»), wie in die Höhe passen —
    /// `ViewThatFits` nimmt die erste Variante, die ganz passt (nichts wird abgeschnitten).
    private func largeBody(_ s: PortfolioWidgetSnapshot) -> some View {
        // Bis 5 Positionen und «+ n weitere» (6 Zeilen), zuletzt ohne Liste
        ViewThatFits(in: .vertical) {
            largeContent(s, maxLines: 6)
            largeContent(s, maxLines: 5)
            largeContent(s, maxLines: 4)
            largeContent(s, maxLines: 3)
            largeContent(s, maxLines: 2)
            largeContent(s, maxLines: 1)
            largeContent(s, maxLines: 0)
        }
    }

    private func largeContent(_ s: PortfolioWidgetSnapshot, maxLines: Int) -> some View {
        let usdt = PortfolioWidgetText.usdt(s, display: entry.display)
        let points = PortfolioWidgetText.chartPoints(s)
        let chartText = points.map { PortfolioWidgetText.chartAccessibility($0, currency: s.currency) }
        let rows = PortfolioWidgetStore.rows(s.topPositions, others: s.otherPositions ?? 0, maxLines: maxLines)
        let positionsText = PortfolioWidgetText.positionsAccessibility(rows.shown, more: rows.more, currency: s.currency)
        return VStack(alignment: .leading, spacing: 0) {
            header
            totalText(s, size: 30)
                .padding(.top, 8)
            usdtLine(usdt)
            todayPill(s)
            if let points {
                PortfolioValueChart(points: points, palette: palette, lineWidth: 2)
                    .frame(maxWidth: .infinity, minHeight: 32, maxHeight: .infinity)
                    .padding(.vertical, 8)
            } else {
                Spacer(minLength: 8)
            }
            if !rows.shown.isEmpty {
                VStack(alignment: .leading, spacing: 3) {
                    ForEach(rows.shown, id: \.symbol) { position in
                        positionRow(position)
                    }
                    if rows.more > 0 {
                        Text(L("widget_portfolio_more", count: rows.more))
                            .font(.system(size: 11, weight: .medium))
                            .foregroundStyle(palette.secondary)
                            .lineLimit(1)
                    }
                }
                .padding(.bottom, 6)
            }
            timeLine(s, period: points.map { PortfolioWidgetText.period($0, short: true) })
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(PortfolioWidgetText.accessibility(s, usdt: usdt, chart: chartText,
                                                              positions: positionsText, withTime: true,
                                                              outdated: spokenOutdated))
    }

    private func positionRow(_ p: PortfolioWidgetPosition) -> some View {
        HStack(spacing: 8) {
            WidgetCoinBadge(symbol: p.symbol, size: 18, dark: palette.dark)
            Text(p.symbol)
                .font(.system(size: 12.5, weight: .bold))
                .foregroundStyle(palette.text)
                .lineLimit(1)
                .minimumScaleFactor(0.7)
            Spacer(minLength: 4)
            Text(PortfolioWidgetText.positionValue(p.value))
                .font(.system(size: 12.5, weight: .medium))
                .monospacedDigit()
                .foregroundStyle(palette.text)
                .lineLimit(1)
                .minimumScaleFactor(0.7)
            Text(PortfolioWidgetText.share(p.sharePercent))
                .font(.system(size: 11, weight: .medium))
                .monospacedDigit()
                .foregroundStyle(palette.secondary)
                .lineLimit(1)
                .frame(minWidth: 38, alignment: .trailing)
            if let change = p.change24hPercent {
                WidgetChangeLabel(change: change, palette: palette, size: 11, showsArrow: true)
                    .padding(.horizontal, 6)
                    .padding(.vertical, 2)
                    .background(Capsule().fill(palette.change(change).opacity(palette.dark ? 0.14 : 0.06)))
                    .frame(minWidth: 64, alignment: .trailing)
            }
        }
    }

    // MARK: Bausteine

    private func totalText(_ s: PortfolioWidgetSnapshot, size: CGFloat) -> some View {
        HStack(alignment: .firstTextBaseline, spacing: 4) {
            Text(PriceFormat.valueWithCurrency(s.total, "").trimmingCharacters(in: .whitespaces))
                .font(.system(size: size, weight: .bold, design: .rounded))
                .monospacedDigit()
                .foregroundStyle(palette.text)
                .lineLimit(1)
                .minimumScaleFactor(0.45)
            Text(s.currency)
                .font(.system(size: size >= 26 ? 12 : 10, weight: .semibold))
                .foregroundStyle(palette.secondary)
                .lineLimit(1)
                .fixedSize()
        }
    }

    /// «≈ 92’310.00 USDT» (je Widget wählbar); nil = keine Zeile.
    @ViewBuilder
    private func usdtLine(_ usdt: String?) -> some View {
        if let usdt {
            Text("≈ " + usdt)
                .font(.system(size: 11, weight: .medium))
                .monospacedDigit()
                .foregroundStyle(palette.secondary)
                .lineLimit(1)
                .minimumScaleFactor(0.7)
                .padding(.top, 1)
        }
    }

    /// Veränderung heute: Betrag und Prozent-Pille in der Kursfarbe, mit + / −;
    /// ohne Vergleichswert nichts. Passt «heute +997.62 CHF» nicht neben die Pille, nur
    /// «heute» und die Pille (Prozent) — lieber kürzer als abgeschnitten.
    @ViewBuilder
    private func todayPill(_ s: PortfolioWidgetSnapshot) -> some View {
        if s.changeAmount != nil {
            let color = palette.change(s.changePercent)
            let percent = PortfolioWidgetText.percent(s)
            ViewThatFits(in: .horizontal) {
                HStack(spacing: 6) {
                    todayLabel(L("widget_portfolio_today", PortfolioWidgetText.amount(s) ?? "—"), color: color)
                        .fixedSize(horizontal: true, vertical: false)
                    percentPill(s, percent: percent, color: color)
                }
                if let percent {
                    HStack(spacing: 6) {
                        todayLabel(L("widget_portfolio_today_label"), color: color)
                            .fixedSize(horizontal: true, vertical: false)
                        percentPill(s, percent: percent, color: color)
                    }
                }
                // Letzter Ausweg (ohne Prozentwert): Betrag etwas kleiner
                todayLabel(L("widget_portfolio_today", PortfolioWidgetText.amount(s) ?? "—"), color: color)
                    .minimumScaleFactor(0.7)
            }
            .padding(.top, 4)
        }
    }

    private func todayLabel(_ text: String, color: Color) -> some View {
        Text(text)
            .font(.system(size: 13, weight: .semibold))
            .monospacedDigit()
            .foregroundStyle(color)
            .lineLimit(1)
    }

    /// Prozent-Pille mit Pfeil (folgt dem Vorzeichen); nil = keine.
    @ViewBuilder
    private func percentPill(_ s: PortfolioWidgetSnapshot, percent: String?, color: Color) -> some View {
        if let percent {
            HStack(spacing: 2) {
                ChangeArrowIcon(change: s.changePercent)
                    .font(.system(size: 9, weight: .bold))
                Text(percent)
                    .font(.system(size: 12, weight: .semibold))
                    .monospacedDigit()
                    .lineLimit(1)
            }
            .foregroundStyle(color)
            .padding(.horizontal, 7)
            .padding(.vertical, 3)
            .background(Capsule().fill(color.opacity(palette.dark ? 0.14 : 0.06)))
            .fixedSize(horizontal: true, vertical: false)
        }
    }

    /// Uhrzeit; mit Wertverlauf dessen Zeitraum daneben, z. B. «14:05 · 48h»;
    /// alter Stand ausgeschrieben: «veraltet · 06:42 · 48h».
    private func timeLine(_ s: PortfolioWidgetSnapshot, period: String?) -> some View {
        let time = WidgetOutdated.timeText(s.updatedAt, outdated: entry.outdated, at: entry.date)
        return Text([time, period].compactMap { $0 }.joined(separator: " · "))
            .font(.system(size: medium || large ? 10 : 9.5, weight: .medium))
            .monospacedDigit()
            .foregroundStyle(palette.secondary)
            .lineLimit(1)
    }
}

/// Wertverlauf: dünne Linie mit schwacher Fläche darunter, ohne Achsen — wie der
/// Linien-Modus des Einzel-Widgets. Farbe nach der Gesamtrichtung (letzter gegen ersten Wert).
private struct PortfolioValueChart: View {
    let points: [PortfolioWidgetPoint]
    let palette: WidgetPalette
    var lineWidth: CGFloat = 2

    var body: some View {
        GeometryReader { geo in
            let xy = Self.positions(points, size: geo.size, inset: lineWidth)
            let up = (points.last?.value ?? 0) >= (points.first?.value ?? 0)
            let color = up ? palette.up : palette.down
            if xy.count >= 2 {
                ZStack {
                    Path { path in
                        path.move(to: CGPoint(x: xy[0].x, y: geo.size.height))
                        for p in xy { path.addLine(to: p) }
                        path.addLine(to: CGPoint(x: xy[xy.count - 1].x, y: geo.size.height))
                        path.closeSubpath()
                    }
                    .fill(color.opacity(0.16))
                    Path { path in
                        path.move(to: xy[0])
                        for p in xy.dropFirst() { path.addLine(to: p) }
                    }
                    .stroke(color, style: StrokeStyle(lineWidth: lineWidth, lineCap: .round, lineJoin: .round))
                }
            }
        }
        .accessibilityHidden(true)
    }

    /// x nach der Zeit über die Breite, y zwischen `inset` und Höhe − `inset` (höchster Wert oben);
    /// ohne Spanne mittig — gleiche Regel wie `PortfolioWidgetMath.linePoints` (Android).
    static func positions(_ points: [PortfolioWidgetPoint], size: CGSize, inset: CGFloat) -> [CGPoint] {
        guard points.count >= 2, let first = points.first, let last = points.last else { return [] }
        let values = points.map(\.value)
        guard let low = values.min(), let high = values.max() else { return [] }
        let span = Double(last.at - first.at)
        let width = Swift.max(0, size.width - inset)
        let top = inset
        let bottom = Swift.max(inset, size.height - inset)
        var out: [CGPoint] = []
        for (i, p) in points.enumerated() {
            let fx = span > 0 ? Double(p.at - first.at) / span : Double(i) / Double(points.count - 1)
            let x = inset / 2 + CGFloat(fx) * width
            let y: CGFloat
            if high > low {
                y = bottom - CGFloat((p.value - low) / (high - low)) * (bottom - top)
            } else {
                y = (top + bottom) / 2
            }
            out.append(CGPoint(x: x, y: y))
        }
        return out
    }
}

/// Sperrbildschirm: Titel, Gesamtwert, Veränderung heute.
private struct PortfolioRectangularView: View {
    let entry: PortfolioEntry

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text(L("widget_portfolio_name"))
                .font(.system(size: 13, weight: .semibold))
                .lineLimit(1)
            if entry.locked {
                Text(L("widget_portfolio_locked"))
                    .font(.system(size: 12))
                    .foregroundStyle(.secondary)
                    .lineLimit(2)
            } else if let s = entry.snapshot, !s.empty {
                Text(PortfolioWidgetText.total(s))
                    .font(.system(size: 17, weight: .bold, design: .rounded))
                    .monospacedDigit()
                    .lineLimit(1)
                    .minimumScaleFactor(0.6)
                    .widgetAccentable()
                HStack(spacing: 2) {
                    ChangeArrowIcon(change: s.changePercent)
                        .font(.system(size: 8, weight: .bold))
                    Text(PortfolioWidgetText.today(s))
                        .font(.system(size: 11))
                        .monospacedDigit()
                        .lineLimit(1)
                        .minimumScaleFactor(0.7)
                }
                .foregroundStyle(.secondary)
            } else {
                Text(L("widget_portfolio_empty"))
                    .font(.system(size: 12))
                    .foregroundStyle(.secondary)
                    .lineLimit(2)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .accessibilityElement(children: .combine)
        .modifier(PortfolioRectangularA11y(entry: entry))
    }
}

private struct PortfolioRectangularA11y: ViewModifier {
    let entry: PortfolioEntry

    @ViewBuilder
    func body(content: Content) -> some View {
        if !entry.locked, let s = entry.snapshot, !s.empty {
            content.accessibilityLabel(PortfolioWidgetText.accessibility(
                s, outdated: entry.outdated ? WidgetOutdated.spoken(s.updatedAt, at: entry.date) : nil))
        } else {
            content
        }
    }
}
