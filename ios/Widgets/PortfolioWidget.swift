import AppIntents
import SwiftUI
import WidgetKit

// MARK: Daten

struct PortfolioEntry: TimelineEntry {
    var date: Date
    /// nil = noch nie berechnet (wie leer behandelt).
    let snapshot: PortfolioWidgetSnapshot?
    /// Portfolio-Sperre an: Titel, Schloss und «Gesperrt – in der App entsperren», keine Werte.
    let locked: Bool
    let accent: AccentColor
    /// Anzeige je Widget: nur Umrechnung oder zusätzlich «≈ … USDT».
    var display: PortfolioDisplayOption = .conversionUsdt
    var theme: WidgetThemeOption = .system
    var priceColors: PriceColorScheme = .default
    var highContrast = false
    var priceColorsInverted = false
    /// «Beträge verbergen» (Einstellung der App, App Group): Beträge als «•••», Prozente bleiben.
    var hideAmounts = false
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
        // %-Basis: Stand mit anderer Basis oder von einem früheren Tag → Veränderung «—»
        let snapshot = PortfolioWidgetStore.load()?.shown(basis: settings.changeBasis, now: TimeUtils.nowMillis)
        return PortfolioEntry(date: Date(), snapshot: snapshot,
                              locked: PortfolioLockPolicy.widgetLocked(lockSetting: settings.appLock),
                              accent: settings.accentColor, display: display, theme: theme,
                              priceColors: settings.priceColorScheme, highContrast: settings.highContrast,
                              priceColorsInverted: settings.priceColorsInverted,
                              hideAmounts: settings.hidePortfolioAmounts)
    }

    static func sample(display: PortfolioDisplayOption = .conversionUsdt,
                       theme: WidgetThemeOption = .system) -> PortfolioEntry {
        let settings = SharedStorage.loadSettings()
        let now = TimeUtils.nowMillis
        let hour: Int64 = 3_600_000
        // 24 Stundenwerte und der aktuelle (wie der Stundenverlauf)
        let values: [Double] = [12_163, 12_180, 12_150, 12_120, 12_135, 12_170, 12_166, 12_205, 12_230, 12_211,
                                12_240, 12_275, 12_260, 12_290, 12_315, 12_296, 12_324, 12_352, 12_333, 12_366,
                                12_395, 12_377, 12_410, 12_434, 12_345.67]
        var history: [PortfolioWidgetPoint] = []
        for (i, value) in values.enumerated() {
            history.append(PortfolioWidgetPoint(at: now - Int64(values.count - 1 - i) * hour, value: value))
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
        // Galerie: leer oder mit Portfolio-Sperre das Beispiel (keine echten Werte, also auch gesperrt unbedenklich)
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
        if !PortfolioLockPolicy.widgetLocked(lockSetting: settings.appLock) && age >= WidgetRefresh.staleAfterMillis {
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
    static func total(_ s: PortfolioWidgetSnapshot, hidden: Bool = false) -> String {
        PortfolioInsights.mask(PriceFormat.valueWithCurrency(s.total, s.currency), hidden: hidden)
    }

    /// Veränderung über 24 h in der Anzeigewährung; nil ohne Vergleichswert.
    static func changeAmount(_ s: PortfolioWidgetSnapshot) -> Double? {
        guard let value = s.changeAmount, value.isFinite else { return nil }
        return value
    }

    /// «+182.40 CHF» / «−1’968.40 CHF»; nil ohne Vergleichswert.
    static func amount(_ s: PortfolioWidgetSnapshot, hidden: Bool = false) -> String? {
        guard let value = changeAmount(s) else { return nil }
        if hidden { return PortfolioInsights.hidden }
        let zero = abs(value) < 0.005
        let sign = zero ? "" : (value > 0 ? "+" : "−")
        // RTL: als Insel, sonst stünde das Vorzeichen hinter der Zahl
        return BidiText.ltr(sign + PriceFormat.valueWithCurrency(abs(value), s.currency))
    }

    /// «2.31%» ohne Vorzeichen (Pille, Richtung zeigt der Pfeil); nil ohne Vergleichswert.
    static func pillPercent(_ s: PortfolioWidgetSnapshot) -> String? {
        guard changeAmount(s) != nil, let value = s.changePercent, value.isFinite else { return nil }
        return String(format: "%.2f%%", locale: Locale.current, abs(value))
    }

    /// Richtung für Farbe und Pfeil nach dem Vorzeichen des Betrags (nie nach dem Farbtausch):
    /// +1 / −1 / 0 als Prozentwert für `palette.change` und `ChangeArrowIcon`; nil ohne Vergleichswert.
    static func directionValue(_ s: PortfolioWidgetSnapshot) -> Double? {
        guard let value = changeAmount(s) else { return nil }
        return Double(PortfolioWidgetSeries.direction(value))
    }

    /// %-Basis, mit der der Stand gerechnet ist (älterer Stand: rollend).
    static func basis(_ s: PortfolioWidgetSnapshot) -> ChangeBasis { s.stamp?.basis ?? .ROLLING_24H }

    /// «−1’968.40 CHF · 24h» bzw. «… · heute» (Pfeil davor als Symbol); nil ohne Vergleichswert.
    static func changeLine(_ s: PortfolioWidgetSnapshot, withPeriod: Bool = true, hidden: Bool = false) -> String? {
        guard let amount = amount(s, hidden: hidden) else { return nil }
        return withPeriod ? amount + " · " + A11y.changeShortLabel(basis(s)) : amount
    }

    /// «92’310.00 USDT», wenn gewünscht und die Anzeigewährung nicht schon USD ist; sonst nil.
    static func usdt(_ s: PortfolioWidgetSnapshot, display: PortfolioDisplayOption, hidden: Bool = false) -> String? {
        guard !hidden, let value = s.totalUsdt, value.isFinite,
              PortfolioWidgetStore.showsUsdt(enabled: display.showsUsdt, currency: s.currency) else { return nil }
        return PriceFormat.valueWithCurrency(value, "USDT")
    }

    /// Wertverlauf mit genug Punkten für den Flächen-Chart, sonst nil («Verlauf folgt»).
    /// Tages-Basis: schon ab zwei Punkten, der Chart wächst über den Tag (`dayAxis`).
    static func chartPoints(_ s: PortfolioWidgetSnapshot) -> [PortfolioWidgetPoint]? {
        let points = s.valueHistory.filter { $0.value.isFinite }
        let enough = dayAxis(s) != nil ? PortfolioWidgetSeries.drawableDay(points) : PortfolioWidgetSeries.drawable(points)
        return enough ? points : nil
    }

    /// Tages-Basis: feste Zeitachse vom Tagesbeginn bis Tagesende; nil bei rollend.
    static func dayAxis(_ s: PortfolioWidgetSnapshot) -> (from: Int64, to: Int64)? {
        guard let stamp = s.stamp, stamp.basis.isDay,
              let end = ChangeBasisMath.dayEnd(stamp.basis, dayStart: stamp.dayStart) else { return nil }
        return (from: stamp.dayStart, to: end)
    }

    /// Zeitraum des Wertverlaufs: lang (VoiceOver) bzw. kurz (Fusszeile); seit Tagesbeginn
    /// «heute» / «Seit 00:00 …», sonst «24h» bzw. «48h».
    static func period(_ points: [PortfolioWidgetPoint], short: Bool, basis: ChangeBasis = .ROLLING_24H) -> String {
        if basis.isDay { return short ? A11y.changeShortLabel(basis) : A11y.changeLongLabel(basis) }
        let day = (points.last?.at ?? 0) - (points.first?.at ?? 0) <= daySpanMillis
        if short { return L(day ? "widget_range_short_24h" : "widget_portfolio_range_short_48h") }
        return L(day ? "widget_range_24h" : "widget_portfolio_range_48h")
    }

    /// VoiceOver-Satz zum Wertverlauf (Zeitraum, Start, Ende, Veränderung, Hoch, Tief).
    static func chartAccessibility(_ points: [PortfolioWidgetPoint], currency: String,
                                   basis: ChangeBasis = .ROLLING_24H, hidden: Bool = false) -> String {
        A11y.chart(period: period(points, short: false, basis: basis), values: points.map(\.value),
                   format: { hidden ? L("a11y_amount_hidden") : PriceFormat.valueWithCurrency($0, currency) })
    }

    /// Anteil mit einer Nachkommastelle, z. B. «62.3%».
    static func share(_ percent: Double) -> String {
        String(format: "%.1f%%", locale: Locale.current, Swift.min(100, Swift.max(0, percent)))
    }

    /// «Stand 15:19» bzw. «veraltet · 06:42» (ohne Sekunden).
    static func asOf(_ s: PortfolioWidgetSnapshot, outdated: Bool, at date: Date) -> String {
        outdated ? WidgetOutdated.label(s.updatedAt, at: date)
            : L("widget_portfolio_as_of", WidgetOutdated.stamp(s.updatedAt, at: date))
    }

    /// VoiceOver: «Grösste Positionen: BTC, 60.1% des Portfolios, gestiegen um 1.80% in 24 Stunden; …».
    static func positionsAccessibility(_ shown: [PortfolioWidgetPosition], basis: ChangeBasis = .ROLLING_24H) -> String? {
        guard !shown.isEmpty else { return nil }
        let parts = shown.map { p in
            L("a11y_portfolio_position_24h", p.symbol, share(p.sharePercent), A11y.change(p.change24hPercent, basis: basis))
        }
        return L("a11y_portfolio_positions", parts.joined(separator: "; "))
    }

    /// VoiceOver: «Portfolio 83’170.32 CHF, gesunken um 2.31% in 24 Stunden, Stand 15:19» — danach
    /// ≈ USDT, Verlauf und Positionen, soweit sichtbar. Veraltet: «veraltet, letzte Aktualisierung 06:42».
    static func accessibility(_ s: PortfolioWidgetSnapshot, outdated: Bool, at date: Date, usdt: String? = nil,
                              chart: String? = nil, positions: String? = nil, hidden: Bool = false) -> String {
        var change: String?
        let changeBasis = Self.basis(s)
        if let amount = changeAmount(s) {
            if let percent = s.changePercent, percent.isFinite {
                change = A11y.change(percent, basis: changeBasis)
            } else if PortfolioWidgetSeries.direction(amount) == 0 {
                change = A11y.changePhrase(L("a11y_change_flat"), basis: changeBasis)
            } else {
                let value = hidden ? L("a11y_amount_hidden") : PriceFormat.valueWithCurrency(abs(amount), s.currency)
                change = A11y.changePhrase(L(amount > 0 ? "a11y_change_up" : "a11y_change_down", value), basis: changeBasis)
            }
        }
        let time = outdated ? WidgetOutdated.spoken(s.updatedAt, at: date)
            : L("widget_portfolio_as_of", WidgetOutdated.stamp(s.updatedAt, at: date))
        let spokenTotal = hidden ? L("a11y_amount_hidden") : total(s)
        return A11y.join([L("widget_portfolio_name") + " " + spokenTotal, change, time,
                          usdt.map { L("a11y_converted", $0) }, chart, positions])
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

/// Startbildschirm (Stufe = Familie, wie `PortfolioWidgetSize` in Android): klein = Kopfzeile mit
/// Pille, Gesamtwert, Betrag über 24 h, ≈ USDT, Fusszeile; mittel = Werte links, Wertverlauf rechts;
/// gross = dazu die drei grössten Positionen unter dem Wertverlauf.
private struct PortfolioHomeView: View {
    let entry: PortfolioEntry
    let palette: WidgetPalette
    let family: WidgetFamily

    /// So viele Positionen zeigt die grosse Stufe.
    private static let largeRows = 3

    /// Gesperrtes Gerät (StandBy): Beträge sind `privacySensitive` und werden verdeckt.
    @Environment(\.redactionReasons) private var redactionReasons

    /// VoiceOver ohne Beträge — bei «Beträge verbergen» und solange sie verdeckt sind.
    private var spokenHidden: Bool { entry.hideAmounts || redactionReasons.contains(.privacy) }

    var body: some View {
        Group {
            if entry.locked {
                lockedBody
            } else if let s = entry.snapshot, !s.empty {
                switch family {
                case .systemMedium: mediumBody(s)
                case .systemLarge: largeBody(s)
                default: smallBody(s)
                }
            } else {
                VStack(alignment: .leading, spacing: 0) {
                    header(nil)
                    Spacer(minLength: 4)
                    message(L("widget_portfolio_empty"))
                }
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .padding(family == .systemSmall ? 14 : 16)
    }

    /// Logo, «Portfolio» und rechts die Pille «▼ 2.31%» (24 h). Wird es eng, fällt der Titel weg
    /// (lieber kein Titel als «Portf…»; VoiceOver nennt ihn trotzdem).
    private func header(_ s: PortfolioWidgetSnapshot?) -> some View {
        ViewThatFits(in: .horizontal) {
            headerRow(s, title: true)
            headerRow(s, title: false)
        }
    }

    private func headerRow(_ s: PortfolioWidgetSnapshot?, title: Bool) -> some View {
        HStack(spacing: 6) {
            WidgetLogo(accent: entry.accent, dark: palette.dark, size: 16)
            if title {
                Text(L("widget_portfolio_name"))
                    .font(.system(size: 12.5, weight: .bold))
                    .foregroundStyle(palette.text)
                    .lineLimit(1)
                    .fixedSize()
            }
            Spacer(minLength: 0)
            if let s { pill(s) }
        }
    }

    /// Portfolio-Sperre: normaler Rahmen mit Titel, Schloss und Hinweis — keine Beträge, kein
    /// Chart, keine Positionen. Tippen öffnet den Portfolio-Tab (`widgetURL`).
    private var lockedBody: some View {
        VStack(alignment: .leading, spacing: 0) {
            header(nil)
            Spacer(minLength: 4)
            Image(systemName: "lock.fill")
                .font(.system(size: family == .systemSmall ? 18 : 20, weight: .semibold))
                .foregroundStyle(palette.secondary)
                .accessibilityHidden(true)
            message(L("widget_portfolio_locked_hint"))
                .padding(.top, 6)
            Spacer(minLength: 0)
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(A11y.join([L("widget_portfolio_name"), L("widget_portfolio_locked_hint")]))
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
        let usdt = PortfolioWidgetText.usdt(s, display: entry.display, hidden: entry.hideAmounts)
        return VStack(alignment: .leading, spacing: 0) {
            header(s)
            Spacer(minLength: 4)
            totalText(s, size: 22)
            changeLine(s)
            usdtLine(usdt)
            Spacer(minLength: 4)
            footer(s, period: nil)
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(PortfolioWidgetText.accessibility(s, outdated: entry.outdated, at: entry.date,
                                                              usdt: spokenHidden ? nil : usdt, hidden: spokenHidden))
    }

    // MARK: Mittel

    private func mediumBody(_ s: PortfolioWidgetSnapshot) -> some View {
        let usdt = PortfolioWidgetText.usdt(s, display: entry.display, hidden: entry.hideAmounts)
        let points = PortfolioWidgetText.chartPoints(s)
        let chartText = points.map { PortfolioWidgetText.chartAccessibility($0, currency: s.currency,
                                                                                 basis: PortfolioWidgetText.basis(s),
                                                                                 hidden: spokenHidden) }
        return VStack(alignment: .leading, spacing: 0) {
            header(s)
            HStack(alignment: .top, spacing: 12) {
                VStack(alignment: .leading, spacing: 0) {
                    totalText(s, size: 24)
                    changeLine(s)
                    usdtLine(usdt)
                    Spacer(minLength: 4)
                    footer(s, period: points.map { PortfolioWidgetText.period($0, short: true, basis: PortfolioWidgetText.basis(s)) })
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
                chart(s, points: points)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
            .padding(.top, 6)
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(PortfolioWidgetText.accessibility(s, outdated: entry.outdated, at: entry.date,
                                                              usdt: spokenHidden ? nil : usdt, chart: chartText,
                                                              hidden: spokenHidden))
    }

    // MARK: Gross

    private func largeBody(_ s: PortfolioWidgetSnapshot) -> some View {
        let usdt = PortfolioWidgetText.usdt(s, display: entry.display, hidden: entry.hideAmounts)
        let points = PortfolioWidgetText.chartPoints(s)
        let chartText = points.map { PortfolioWidgetText.chartAccessibility($0, currency: s.currency,
                                                                                 basis: PortfolioWidgetText.basis(s),
                                                                                 hidden: spokenHidden) }
        let shown = Array(s.topPositions.prefix(Self.largeRows))
        return VStack(alignment: .leading, spacing: 0) {
            header(s)
            totalText(s, size: 30)
                .padding(.top, 8)
            changeLine(s)
            usdtLine(usdt)
            chart(s, points: points)
                .frame(maxWidth: .infinity, minHeight: 44, maxHeight: .infinity)
                .padding(.vertical, 10)
            if !shown.isEmpty {
                VStack(alignment: .leading, spacing: 6) {
                    ForEach(shown, id: \.symbol) { position in
                        positionRow(position, basis: PortfolioWidgetText.basis(s))
                    }
                }
                .padding(.bottom, 8)
            }
            footer(s, period: points.map { PortfolioWidgetText.period($0, short: true, basis: PortfolioWidgetText.basis(s)) })
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(PortfolioWidgetText.accessibility(
            s, outdated: entry.outdated, at: entry.date, usdt: spokenHidden ? nil : usdt, chart: chartText,
            positions: PortfolioWidgetText.positionsAccessibility(shown, basis: PortfolioWidgetText.basis(s)),
            hidden: spokenHidden))
    }

    /// Kürzel, Anteil als dünner Balken (neutral, nicht in der Akzentfarbe) und Text,
    /// Veränderung über 24 h mit Pfeil und Vorzeichen in der Kursfarbe (ohne Wert «—»).
    private func positionRow(_ p: PortfolioWidgetPosition, basis: ChangeBasis) -> some View {
        HStack(spacing: 8) {
            Text(p.symbol)
                .font(.system(size: 12.5, weight: .bold))
                .foregroundStyle(palette.text)
                .lineLimit(1)
                .minimumScaleFactor(0.7)
                .frame(minWidth: 38, alignment: .leading)
            GeometryReader { geo in
                ZStack(alignment: .leading) {
                    Capsule().fill(palette.secondary.opacity(0.18))
                    Capsule().fill(palette.secondary.opacity(0.65))
                        .frame(width: geo.size.width * CGFloat(Swift.min(100, Swift.max(0, p.sharePercent)) / 100))
                }
            }
            .frame(height: 3)
            Text(PortfolioWidgetText.share(p.sharePercent))
                .font(.system(size: 11, weight: .medium))
                .monospacedDigit()
                .foregroundStyle(palette.secondary)
                .lineLimit(1)
                .frame(minWidth: 40, alignment: .trailing)
            WidgetChangeLabel(change: p.change24hPercent, palette: palette, size: 11, showsArrow: true, day: true,
                              basis: basis)
                .frame(minWidth: 62, alignment: .trailing)
        }
    }

    // MARK: Bausteine

    private func totalText(_ s: PortfolioWidgetSnapshot, size: CGFloat) -> some View {
        HStack(alignment: .firstTextBaseline, spacing: 4) {
            Text(entry.hideAmounts ? PortfolioInsights.hidden
                 : PriceFormat.valueWithCurrency(s.total, "").trimmingCharacters(in: .whitespaces))
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
        // Gesperrtes Gerät (StandBy): Betrag verdeckt
        .privacySensitive()
    }

    /// Pille «▼ 2.31%» in der Kursfarbe (Pfeil nach dem Vorzeichen); ohne Wert keine.
    @ViewBuilder
    private func pill(_ s: PortfolioWidgetSnapshot) -> some View {
        if let percent = PortfolioWidgetText.pillPercent(s) {
            let direction = PortfolioWidgetText.directionValue(s)
            let color = palette.change(direction)
            HStack(spacing: 2) {
                ChangeArrowIcon(change: direction)
                    .font(.system(size: 8.5, weight: .bold))
                Text(percent)
                    .font(.system(size: 11, weight: .semibold))
                    .monospacedDigit()
                    .lineLimit(1)
            }
            .foregroundStyle(color)
            .padding(.horizontal, 7)
            .padding(.vertical, 2.5)
            .background(Capsule().fill(color.opacity(palette.dark ? 0.14 : 0.07)))
            .fixedSize()
        }
    }

    /// «▼ −1’968.40 CHF · 24h» in der Kursfarbe; passt «· 24h» nicht, ohne. Ohne Wert nichts.
    @ViewBuilder
    private func changeLine(_ s: PortfolioWidgetSnapshot) -> some View {
        if let full = PortfolioWidgetText.changeLine(s, hidden: entry.hideAmounts),
           let short = PortfolioWidgetText.changeLine(s, withPeriod: false, hidden: entry.hideAmounts) {
            let direction = PortfolioWidgetText.directionValue(s)
            ViewThatFits(in: .horizontal) {
                changeLabel(full, direction: direction)
                changeLabel(short, direction: direction)
            }
            // Betrag der Veränderung: bei gesperrtem Gerät verdeckt (die Pille mit % bleibt)
            .privacySensitive()
            .padding(.top, 2)
        }
    }

    private func changeLabel(_ text: String, direction: Double?) -> some View {
        HStack(spacing: 3) {
            ChangeArrowIcon(change: direction)
                .font(.system(size: 9, weight: .bold))
            Text(text)
                .font(.system(size: 12, weight: .semibold))
                .monospacedDigit()
                .lineLimit(1)
        }
        .foregroundStyle(palette.change(direction))
        .fixedSize()
    }

    /// «≈ 92’310.00 USDT» (je Widget wählbar), kleiner und schwächer; nil = keine Zeile.
    @ViewBuilder
    private func usdtLine(_ usdt: String?) -> some View {
        if let usdt {
            Text("≈ " + usdt)
                .font(.system(size: 10.5, weight: .medium))
                .monospacedDigit()
                .foregroundStyle(palette.secondary)
                .lineLimit(1)
                .minimumScaleFactor(0.7)
                .privacySensitive()
                .padding(.top, 1)
        }
    }

    /// Wertverlauf oder, solange es zu wenige Stundenwerte gibt, ruhig «Verlauf folgt».
    @ViewBuilder
    private func chart(_ s: PortfolioWidgetSnapshot, points: [PortfolioWidgetPoint]?) -> some View {
        if let points {
            let direction = PortfolioWidgetText.directionValue(s)
                ?? ((points.last?.value ?? 0) >= (points.first?.value ?? 0) ? 1 : -1)
            PortfolioValueChart(points: points, color: palette.change(direction), baseline: palette.secondary,
                                axis: PortfolioWidgetText.dayAxis(s))
                // Zeitachse immer von links nach rechts (auch bei Rechts-nach-links-Sprachen)
                .environment(\.layoutDirection, .leftToRight)
        } else {
            Text(L("widget_portfolio_chart_pending"))
                .font(.system(size: 11, weight: .medium))
                .foregroundStyle(palette.secondary)
                .multilineTextAlignment(.center)
                .lineLimit(2)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
    }

    /// «Stand 15:19 · 24h»; alter Stand ausgeschrieben: «veraltet · 06:42».
    private func footer(_ s: PortfolioWidgetSnapshot, period: String?) -> some View {
        let time = PortfolioWidgetText.asOf(s, outdated: entry.outdated, at: entry.date)
        return Text([time, period].compactMap { $0 }.joined(separator: " · "))
            .font(.system(size: 10, weight: .medium))
            .monospacedDigit()
            .foregroundStyle(palette.secondary)
            .lineLimit(1)
            .minimumScaleFactor(0.8)
    }
}

/// Wertverlauf (24 h): Linie 1.75 pt in der Kursfarbe, Fläche als Verlauf von 25 % an der Linie
/// bis 0 unten, gestrichelte schwache Linie beim Ausgangswert, Punkt am letzten Wert; keine Achsen —
/// wie `WidgetChartRenderer.drawPortfolioArea` (Android).
private struct PortfolioValueChart: View {
    let points: [PortfolioWidgetPoint]
    let color: Color
    let baseline: Color
    /// Feste Zeitachse (Tages-Basis); nil = erster bis letzter Punkt.
    var axis: (from: Int64, to: Int64)? = nil
    var lineWidth: CGFloat = 1.75
    var dot: CGFloat = 2.5

    var body: some View {
        GeometryReader { geo in
            let inset = dot + lineWidth / 2
            let xy = Self.positions(points, size: geo.size, inset: inset, axis: axis)
            if xy.count >= 2, let first = xy.first, let last = xy.last {
                let top = xy.map(\.y).min() ?? 0
                let baseY = Self.valueY(points, value: points[0].value, height: geo.size.height, inset: inset)
                ZStack {
                    Path { path in
                        path.move(to: CGPoint(x: first.x, y: geo.size.height))
                        for p in xy { path.addLine(to: p) }
                        path.addLine(to: CGPoint(x: last.x, y: geo.size.height))
                        path.closeSubpath()
                    }
                    .fill(LinearGradient(colors: [color.opacity(0.25), color.opacity(0)],
                                         startPoint: UnitPoint(x: 0.5, y: geo.size.height > 0 ? top / geo.size.height : 0),
                                         endPoint: .bottom))
                    Path { path in
                        path.move(to: CGPoint(x: first.x, y: baseY))
                        path.addLine(to: CGPoint(x: last.x, y: baseY))
                    }
                    .stroke(baseline.opacity(0.45), style: StrokeStyle(lineWidth: 0.75, dash: [3, 3]))
                    Path { path in
                        path.move(to: first)
                        for p in xy.dropFirst() { path.addLine(to: p) }
                    }
                    .stroke(color, style: StrokeStyle(lineWidth: lineWidth, lineCap: .round, lineJoin: .round))
                    Circle()
                        .fill(color)
                        .frame(width: dot * 2, height: dot * 2)
                        .position(last)
                }
            }
        }
        .accessibilityHidden(true)
    }

    /// x nach der Zeit über die Breite (Rand `inset` links und rechts), y zwischen `inset` und
    /// Höhe − `inset` (höchster Wert oben); ohne Spanne mittig — wie `PortfolioWidgetMath.linePoints`.
    /// `axis`: feste Zeitachse (Tages-Basis) — die Linie füllt nur den bisherigen Teil des Tages.
    static func positions(_ points: [PortfolioWidgetPoint], size: CGSize, inset: CGFloat,
                          axis: (from: Int64, to: Int64)? = nil) -> [CGPoint] {
        guard points.count >= 2, let first = points.first, let last = points.last else { return [] }
        let values = points.map(\.value)
        guard let low = values.min(), let high = values.max() else { return [] }
        let fixed = axis.flatMap { $0.to > $0.from ? $0 : nil }
        let t0 = fixed?.from ?? first.at
        let span = Double((fixed?.to ?? last.at) - t0)
        let width = Swift.max(0, size.width - 2 * inset)
        let top = inset
        let bottom = Swift.max(inset, size.height - inset)
        var out: [CGPoint] = []
        for (i, p) in points.enumerated() {
            let fx = Swift.min(1, Swift.max(0, span > 0 ? Double(p.at - t0) / span : Double(i) / Double(points.count - 1)))
            let x = inset + CGFloat(fx) * width
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

    /// y-Lage eines Werts in derselben Skala, auf die Fläche geklemmt — wie `PortfolioWidgetMath.valueY`.
    static func valueY(_ points: [PortfolioWidgetPoint], value: Double, height: CGFloat, inset: CGFloat) -> CGFloat {
        let values = points.map(\.value)
        let top = inset
        let bottom = Swift.max(inset, height - inset)
        guard let low = values.min(), let high = values.max(), high > low else { return (top + bottom) / 2 }
        let y = bottom - CGFloat((value - low) / (high - low)) * (bottom - top)
        return Swift.min(bottom, Swift.max(top, y))
    }
}

/// Sperrbildschirm: Titel, Gesamtwert, Veränderung über 24 h. Beide Beträge sind
/// `privacySensitive`: bei gesperrtem Gerät verdeckt, nach dem Entsperren sichtbar.
private struct PortfolioRectangularView: View {
    let entry: PortfolioEntry

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text(L("widget_portfolio_name"))
                .font(.system(size: 13, weight: .semibold))
                .lineLimit(1)
            if entry.locked {
                HStack(alignment: .firstTextBaseline, spacing: 4) {
                    Image(systemName: "lock.fill")
                        .font(.system(size: 10, weight: .semibold))
                        .accessibilityHidden(true)
                    Text(L("widget_portfolio_locked_hint"))
                        .font(.system(size: 12))
                        .lineLimit(2)
                }
                .foregroundStyle(.secondary)
            } else if let s = entry.snapshot, !s.empty {
                Text(PortfolioWidgetText.total(s, hidden: entry.hideAmounts))
                    .font(.system(size: 17, weight: .bold, design: .rounded))
                    .monospacedDigit()
                    .lineLimit(1)
                    .minimumScaleFactor(0.6)
                    .widgetAccentable()
                    .privacySensitive()
                if let line = PortfolioWidgetText.changeLine(s, hidden: entry.hideAmounts) {
                    HStack(spacing: 2) {
                        ChangeArrowIcon(change: PortfolioWidgetText.directionValue(s))
                            .font(.system(size: 8, weight: .bold))
                        Text(line)
                            .font(.system(size: 11))
                            .monospacedDigit()
                            .lineLimit(1)
                            .minimumScaleFactor(0.7)
                    }
                    .foregroundStyle(.secondary)
                    .privacySensitive()
                }
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
    /// Gesperrtes Gerät: Beträge verdeckt — dann auch VoiceOver ohne Beträge.
    @Environment(\.redactionReasons) private var redactionReasons

    @ViewBuilder
    func body(content: Content) -> some View {
        if !entry.locked, let s = entry.snapshot, !s.empty {
            content.accessibilityLabel(PortfolioWidgetText.accessibility(
                s, outdated: entry.outdated, at: entry.date,
                hidden: entry.hideAmounts || redactionReasons.contains(.privacy)))
        } else {
            content
        }
    }
}
