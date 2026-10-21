import SwiftUI

/// Kurs-Chart im Aktionsblatt (unter Kopf, Kurs und Alarm · «Was bewegt sich mit?» · Favorit) — wie
/// `SheetPriceChart` (Android): bei 7T/30T/1J die Veränderung über den Zeitraum, Chart wie das Einzel-Widget
/// (`PriceChartRenderer`: Kerzen oder Linie, Preisstufen, Gitter, Kurs-Etikett), darunter
/// 24h · 7T · 30T · 1J und Kerzen/Linie. Kurz drücken und ziehen zeigt Kurs, Zeit und Veränderung
/// der Kerze unter dem Finger. DEX- und andere Paare ohne Kerzenquelle: ganz ausgeblendet.
@MainActor
struct WatchSheetChartView: View {
    let watch: Watch

    @EnvironmentObject private var data: AppData
    @Environment(\.appAccent) var accent
    @Environment(\.onAccent) var onAccent
    @Environment(\.priceColorScheme) var priceColors
    @Environment(\.priceHighContrast) var highContrast
    @Environment(\.priceColorsInverted) var inverted
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.displayScale) var displayScale
    /// %-Basis: bei «seit 00:00» heisst der erste Zeitraum «Heute» und beginnt beim Tagesbeginn.
    @Environment(\.changeView) var changeView
    /// Beschriftung im Chart wächst mit der Textgrösse, höchstens bis `ChartLabelMetrics.maxSize`.
    @ScaledMetric(relativeTo: .caption2) var labelSize: CGFloat = 10

    @State var range: PriceChartRange = .day
    @State private var result: SheetChartResult? = nil
    /// Zuletzt geladenes Paar/Zeitraum: Neu laden mit neuem Kurs lässt den alten Verlauf stehen.
    @State private var loadedKey: LoadKey? = nil
    /// Fertig vorbereitete Kerzen (abseits des Main-Threads); gezeichnet wird nur diese.
    @State var prepared: SheetChartPrepared? = nil
    @State var layoutBox = LayoutBox()
    /// x des Fingers beim Ziehen (nach kurzem Drücken); nil = kein Ziehen.
    @GestureState var pressX: CGFloat? = nil

    /// Lage der zuletzt gezeichneten Fläche (kein beobachteter Zustand).
    final class LayoutBox {
        var layout: PriceChartLayout?
    }

    /// Mit dem Paar: wurde es bearbeitet (gleiche Id, anderes Paar/Börse), neu laden.
    private struct LoadKey: Hashable {
        let watchId: Int64
        let pair: WatchEdit.Key
        let range: PriceChartRange
    }

    /// Dazu der Stand des Kurses: neuer Kurs (auch nach «Aktualisieren») prüft den Zwischenspeicher
    /// (von Hand verworfen, Kurssprung, abgelaufen) und lädt dann still neu.
    private struct RefreshKey: Hashable {
        let load: LoadKey
        let stamp: Int64
    }

    /// Eingabe der Vorbereitung (`SheetChart.prepare`).
    private struct PrepareInput: Equatable, Sendable {
        let candles: [MarketCandle]
        let type: PriceChartType
        let range: PriceChartRange
        let stable: Bool
    }

    static let chartHeight: CGFloat = 180

    /// Über so vielen Kerzen (1 Jahr) beim Ziehen kein Ticken je Kerze — es wäre ein Dauersurren.
    static let maxTickCandles = 100

    /// Zeitraum, Art oder Kerzen gewechselt: alter und neuer Verlauf blenden kurz ineinander.
    private struct FadeKey: Hashable {
        let range: PriceChartRange
        let type: PriceChartType
        let first: Int64?
        let count: Int
    }

    var chartType: PriceChartType { data.settings.sheetChartLine ? .line : .candles }

    var body: some View {
        let current = result ?? SheetChartStore.shared.cached(watch, range: range)
        if current != .unsupported {
            VStack(alignment: .leading, spacing: 8) {
                switch current {
                case .some(.ready(let loaded, _, let info)):
                    let candles = shownCandles(loaded)
                    // Erster Zeitraum: keine eigene Zeile — dieselbe Zahl steht als farbige Schrift neben dem
                    // Kurs; 7T/30T/1J: Veränderung aus den Kerzen über dem Chart (wie Android)
                    if range != .day {
                        header(candles)
                    }
                    preparedChart(candles)
                    SheetChartCaption(info: info, candles: loaded, range: range,
                                      timeZone: ChangeBasisMath.chartTimeZone(changeView.basis),
                                      markets: [watch.marketKey, watch.marketName], quote: watch.quoteAsset)
                case .some(.noData):
                    Text(L("sheet_chart_no_data"))
                        .font(.footnote)
                        .foregroundStyle(AppColors.onSurfaceVariant)
                        .padding(.vertical, 8)
                default:
                    skeleton
                }
                chips
            }
            .animation(reduceMotion ? nil : .easeInOut(duration: 0.2), value: currentFadeKey(current))
            .task(id: RefreshKey(load: loadKey, stamp: watch.lastUpdate)) { await load(loadKey) }
        }
    }

    private var loadKey: LoadKey { LoadKey(watchId: watch.id, pair: WatchEdit.Key(watch), range: range) }

    private func fadeKey(_ candles: [MarketCandle]) -> FadeKey {
        FadeKey(range: range, type: chartType, first: candles.first?.openTime, count: candles.count)
    }

    /// Schlüssel der Überblendung; nil ohne fertige Kerzen (Platzhalter, keine Daten).
    private func currentFadeKey(_ current: SheetChartResult?) -> FadeKey? {
        guard case .some(.ready(let loaded, _, _)) = current else { return nil }
        return fadeKey(shownCandles(loaded))
    }

    private func load(_ key: LoadKey) async {
        let wanted = range
        if let hit = SheetChartStore.shared.cached(watch, range: wanted) {
            result = hit
            loadedKey = key
            return
        }
        // Gleiches Paar und gleicher Zeitraum: alter Verlauf bleibt bis zum neuen stehen
        let same = loadedKey == key
        if !same { result = nil }
        let loaded = await SheetChartStore.shared.load(watch, range: wanted)
        guard wanted == range, !Task.isCancelled else { return }
        // Neu laden fehlgeschlagen: stehender Verlauf bleibt (statt «Keine Kursdaten»)
        if same, case .some(.ready) = result, loaded == .noData { return }
        result = loaded
        loadedKey = key
    }

    /// Stablecoin: Achse mindestens ±0.5 % hoch (`SheetChart.axisLevels`).
    private var stable: Bool { PortfolioStables.isStable(watch.baseAsset) }

    /// Chart erst, wenn Kerzen, Achse und Volumen abseits des Main-Threads vorbereitet sind;
    /// bis dahin bleibt der vorige Verlauf stehen (bzw. eine leere Fläche).
    @ViewBuilder
    private func preparedChart(_ candles: [MarketCandle]) -> some View {
        let input = PrepareInput(candles: candles, type: chartType, range: range, stable: stable)
        Group {
            if let p = prepared {
                chart(p)
                    .id(FadeKey(range: p.range, type: p.type, first: p.candles.first?.openTime, count: p.candles.count))
                    .transition(.opacity)
            } else {
                Color.clear.frame(maxWidth: .infinity).frame(height: Self.chartHeight)
            }
        }
        .task(id: input) {
            let done = await Task.detached(priority: .userInitiated) {
                SheetChart.prepare(input.candles, type: input.type, range: input.range, stable: input.stable)
            }.value
            guard !Task.isCancelled else { return }
            // Zeitraum/Art gewechselt: alter und neuer Verlauf blenden kurz ineinander
            withAnimation(reduceMotion ? nil : .easeInOut(duration: 0.2)) { prepared = done }
        }
    }

    /// Erster Zeitraum bei einer Tages-Basis der %-Änderung: «Heute» statt «24h».
    private var today: Bool { range == .day && changeView.basis.isDay }

    /// Zeitraum ausgeschrieben (VoiceOver): «24h» / «Seit 00:00 UTC» …
    var periodLong: String { today ? A11y.changeLongLabel(changeView.basis) : L(range.labelKey) }

    /// «Heute»: Kerzen erst ab Tagesbeginn (mindestens zwei); sonst alle geladenen.
    private func shownCandles(_ candles: [MarketCandle]) -> [MarketCandle] {
        guard today, let start = ChangeBasisMath.dayStart(changeView.basis, now: TimeUtils.nowMillis) else { return candles }
        return ChangeBasisMath.sinceDayStart(candles, dayStart: start) { $0.openTime }
    }

    // MARK: Kopf

    /// «[+2.31%] in 24h» bzw. «[+2.31%] heute»: die Veränderung als gefüllte `ChangePill` (mittel),
    /// der Rest des übersetzten Satzes grau davor/dahinter (wie Android `RangeChangeHeader`);
    /// VoiceOver: «gestiegen um 2.31%, 24h».
    /// Erster Zeitraum: dieselbe Zahl wie die Änderung neben dem Kurs; 7T/30T/1J aus den Kerzen.
    private func header(_ candles: [MarketCandle]) -> some View {
        let change = SheetChart.headerChange(range: range, candles, type: chartType,
                                             dayChange: changeView.shown(watch.shownChange24h))
        let spoken = (A11y.change(change) ?? L("a11y_chart_empty")) + ", " + periodLong
        // Satz mit Platzhalter für die Änderung, damit die Wortstellung der Übersetzung bleibt
        let sentence = today
            ? L("sheet_chart_change_since", Self.pillMark, A11y.changeShortLabel(changeView.basis))
            : L("sheet_chart_change", Self.pillMark, L(range.shortLabelKey))
        return SheetChangeHeaderLine(sentence: sentence, mark: Self.pillMark, change: change)
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(spoken)
    }

    /// Platzhalter im Satz «%1$@ in %2$@» an der Stelle der Änderung.
    static let pillMark = "\u{E000}"

    // MARK: Platzhalter

    /// Grauer Block in Form von Kopf und Chart; pulsiert nur ohne «Bewegung reduzieren».
    @ViewBuilder
    private var skeleton: some View {
        let shape = VStack(alignment: .leading, spacing: 8) {
            // Kopfzeile nur bei 7T/30T/1J (wie oben)
            if range != .day {
                Capsule().fill(AppColors.containerHighest).frame(width: 130, height: 14)
            }
            RoundedRectangle(cornerRadius: 12, style: .continuous)
                .fill(AppColors.containerHighest)
                .frame(maxWidth: .infinity)
                .frame(height: Self.chartHeight)
        }
        .accessibilityHidden(true)
        SkeletonPulse { shape }
    }

    // MARK: Zeitraum und Chart-Art

    /// Zeitraum links, Kerzen/Linie als kompakter Zwei-Symbol-Schalter rechts in derselben
    /// Zeile; reicht der Platz nicht (grosse Schrift), rutscht der Schalter rechtsbündig darunter.
    private var chips: some View {
        ViewThatFits(in: .horizontal) {
            HStack(spacing: Spacing.xs) {
                rangeChips
                Spacer(minLength: Spacing.xs)
                typeToggle
            }
            VStack(alignment: .trailing, spacing: 8) {
                FlowLayout(spacing: Spacing.xs) { rangeChips }
                    .frame(maxWidth: .infinity, alignment: .leading)
                typeToggle
            }
        }
    }

    @ViewBuilder
    private var rangeChips: some View {
        ForEach(PriceChartRange.allCases, id: \.self) { option in
            // Tages-Basis: «Heute» statt «24h» (VoiceOver: «Seit 00:00 UTC» bzw. «… Ortszeit»)
            let optionToday = option == .day && changeView.basis.isDay
            chip(optionToday ? L("sheet_chart_today") : L(option.shortLabelKey),
                 spoken: optionToday ? A11y.changeLongLabel(changeView.basis) : L(option.labelKey),
                 selected: option == range) {
                guard option != range else { return }
                Haptics.play(.selection)
                result = SheetChartStore.shared.cached(watch, range: option)
                range = option
            }
        }
    }

    /// Kerzen | Linie als zwei Symbole in einer Pille (je Hälfte 44 × 44 pt Trefferfläche,
    /// wie `ChartTypeSwitch` in Android mit 48 dp); VoiceOver: «Kerzen»/«Linie» mit «ausgewählt».
    private var typeToggle: some View {
        HStack(spacing: 0) {
            typeSegment(nil, label: L("widget_chart_candles"), selected: chartType == .candles) {
                guard data.settings.sheetChartLine else { return }
                Haptics.play(.selection)
                data.settings.sheetChartLine = false
            }
            Rectangle()
                .fill(AppColors.outlineVariant)
                .frame(width: 1, height: 44)
            typeSegment("chart.xyaxis.line", label: L("widget_chart_line"), selected: chartType == .line) {
                guard !data.settings.sheetChartLine else { return }
                Haptics.play(.selection)
                data.settings.sheetChartLine = true
            }
        }
        .background(AppColors.containerHigh, in: Capsule())
        .overlay(Capsule().strokeBorder(AppColors.outlineVariant, lineWidth: 1))
        .clipShape(Capsule())
        .fixedSize()
    }

    /// `symbol` nil = Kerzen-Symbol (SF Symbols hat keins; wie `ic_chart_candles` in Android).
    private func typeSegment(_ symbol: String?, label: String, selected: Bool,
                             action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Group {
                if let symbol {
                    Image(systemName: symbol)
                        .scaledFont(size: 15, weight: .semibold, relativeTo: .subheadline)
                        .dynamicTypeSize(...DynamicTypeSize.xxxLarge)
                } else {
                    CandlesIcon().frame(width: 18, height: 18)
                }
            }
            .foregroundStyle(selected ? accent.onContainer : AppColors.onSurfaceVariant)
            // Trefferfläche 44 × 44 pt
            .frame(width: 44, height: 44)
            .background(selected ? accent.container : Color.clear)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(label)
        .accessibilityAddTraits(selected ? .isSelected : [])
    }

    /// Kleiner Auswahl-Chip wie `ChoiceChips`; VoiceOver: Knopf mit «ausgewählt».
    private func chip(_ title: String, spoken: String? = nil, selected: Bool,
                      action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(title)
                .font(.subheadline.weight(selected ? .semibold : .regular))
                .lineLimit(1)
                .padding(.horizontal, Spacing.md)
                .padding(.vertical, Spacing.sm)
                .foregroundStyle(selected ? accent.onContainer : AppColors.onSurface)
                .background(selected ? accent.container : AppColors.containerHigh, in: Capsule())
                .overlay(Capsule().strokeBorder(selected ? accent.primary.opacity(0.6) : .clear, lineWidth: 1))
        }
        .buttonStyle(.plain)
        .accessibilityLabel(spoken ?? title)
        .accessibilityAddTraits(selected ? .isSelected : [])
    }
}
