import SwiftUI

// MARK: Umgebung

private struct AppAccentKey: EnvironmentKey {
    static let defaultValue: AccentColor = .default
}

private struct CoinLogosEnabledKey: EnvironmentKey {
    static let defaultValue = false
}

private struct PortfolioCoinLogosEnabledKey: EnvironmentKey {
    static let defaultValue = false
}

private struct CoinNamesEnabledKey: EnvironmentKey {
    static let defaultValue = false
}

extension EnvironmentValues {
    /// Schalter «Coin-Logos in der App» (an der Wurzel gesetzt). Standard aus: Vorschauen und
    /// Tests laden nichts.
    var coinLogosEnabled: Bool {
        get { self[CoinLogosEnabledKey.self] }
        set { self[CoinLogosEnabledKey.self] = newValue }
    }

    /// Schalter «Im Portfolio» (an der Wurzel gesetzt).
    var portfolioCoinLogosEnabled: Bool {
        get { self[PortfolioCoinLogosEnabledKey.self] }
        set { self[PortfolioCoinLogosEnabledKey.self] = newValue }
    }

    /// Schalter «Namen anzeigen» der Merkliste (an der Wurzel gesetzt).
    var coinNamesEnabled: Bool {
        get { self[CoinNamesEnabledKey.self] }
        set { self[CoinNamesEnabledKey.self] = newValue }
    }
}

extension EnvironmentValues {
    /// Aktuelle Akzentfarbe der App.
    var appAccent: AccentColor {
        get { self[AppAccentKey.self] }
        set { self[AppAccentKey.self] = newValue }
    }
}

// MARK: Abschnitt

/// Abschnitt mit kleiner Überschrift und Inhalt auf abgerundeter Fläche — wie `SectionCard`.
/// Abschnittsüberschrift überall gleich: klein, fett, in Textfarbe, davor ein kurzer Strich in
/// der Themenfarbe — Einstellungen («Allgemein», «Darstellung» …), Portfolio («Gesamtwert»,
/// «Wertverlauf» …) und Markt («Jetzt», «Einordnung», «Daten» …); wie `SectionTitle` +
/// `sectionTitleMarker` (Android). Die Themenfarbe bleibt Bedienbarem vorbehalten.
struct SectionTitleStyle: ViewModifier {
    @Environment(\.appAccent) private var accent
    @ScaledMetric(relativeTo: .footnote) private var markerHeight: CGFloat = 13

    func body(content: Content) -> some View {
        content
            .font(.footnote.weight(.bold))
            .foregroundStyle(AppColors.onSurface)
            .textCase(nil)
            .padding(.leading, 10)
            .overlay(alignment: .leading) {
                Capsule()
                    .fill(accent.primary)
                    .frame(width: 3, height: markerHeight)
                    .accessibilityHidden(true)
            }
    }
}

extension View {
    /// Siehe `SectionTitleStyle`.
    func sectionTitleStyle() -> some View { modifier(SectionTitleStyle()) }
}

struct SectionCard<Content: View>: View {
    let title: String?
    @ViewBuilder var content: () -> Content

    init(_ title: String? = nil, @ViewBuilder content: @escaping () -> Content) {
        self.title = title
        self.content = content
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            if let title, !title.isEmpty {
                Text(title)
                    .sectionTitleStyle()
                    .padding(.leading, 4)
                    .accessibilityAddTraits(.isHeader)
            }
            VStack(alignment: .leading, spacing: 0, content: content)
                .padding(.horizontal, 16)
                .padding(.vertical, 8)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(AppColors.container, in: RoundedRectangle(cornerRadius: 20, style: .continuous))
        }
        .padding(.bottom, Spacing.lg)
    }
}

/// Schalterzeile — die ganze Zeile ist antippbar; einzige Stelle, die einen Schalter zeichnet
/// (Akzentfarbe, Deaktiviert-Darstellung, Zeilenabstand) — wie `SwitchRow` in Android.
/// Mit Titel/Untertitel (`init(title:subtitle:isOn:enabled:)`) oder eigener Beschriftung.
struct SwitchRow<Label: View>: View {
    @Binding var isOn: Bool
    var enabled: Bool = true
    /// Abstand oben/unten; 0 z. B. in Formularen (die Zeile hat dort schon Abstand) oder Karten.
    var verticalPadding: CGFloat = Spacing.md
    /// Beschriftung nur für VoiceOver (z. B. Schalter am Rand einer Karte).
    var labelHidden: Bool = false
    @ViewBuilder let label: () -> Label
    @Environment(\.appAccent) private var accent

    var body: some View {
        toggle
            .tint(accent.primary)
            .disabled(!enabled)
            .opacity(enabled ? 1 : 0.5)
            .padding(.vertical, verticalPadding)
    }

    @ViewBuilder
    private var toggle: some View {
        if labelHidden {
            Toggle(isOn: $isOn, label: label).labelsHidden()
        } else {
            Toggle(isOn: $isOn, label: label)
        }
    }
}

extension SwitchRow where Label == SwitchRowText {
    /// - Parameter icon: SF Symbol vor dem Titel (optional), z. B. das Auge der Kurs-Mitteilung
    ///   wie in der Zeile der Merkliste.
    init(title: String, subtitle: String? = nil, isOn: Binding<Bool>, enabled: Bool = true,
         verticalPadding: CGFloat = Spacing.md, icon: String? = nil) {
        self.init(isOn: isOn, enabled: enabled, verticalPadding: verticalPadding) {
            SwitchRowText(title: title, subtitle: subtitle, icon: icon)
        }
    }
}

/// Titel und (optional) Untertitel einer `SwitchRow`.
struct SwitchRowText: View {
    let title: String
    let subtitle: String?
    var icon: String? = nil
    @Environment(\.appAccent) private var accent

    var body: some View {
        HStack(spacing: 12) {
            if let icon {
                Image(systemName: icon)
                    .foregroundStyle(AppColors.onSurfaceVariant)
                    .frame(width: 24)
                    .accessibilityHidden(true)
            }
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(.body)
                if let subtitle, !subtitle.isEmpty {
                    BoltHint.text(subtitle, accent: accent.primary)
                        .font(.footnote).foregroundStyle(AppColors.onSurfaceVariant)
                }
            }
        }
    }
}

/// Hinweistext, in dem «⚡» als Symbol in der Themenfarbe steht (wie der ⚡ in der Merkliste) statt
/// als farbiges Emoji. Ohne «⚡» ein normaler Text. Wie `BoltHintText` (Android).
enum BoltHint {
    static func text(_ string: String, accent: Color) -> Text {
        let parts = string.components(separatedBy: "⚡")
        guard parts.count > 1 else { return Text(verbatim: string) }
        var result = Text(verbatim: parts[0])
        for part in parts.dropFirst() {
            result = result + Text(Image(systemName: "bolt.fill")).foregroundColor(accent) + Text(verbatim: part)
        }
        return result
    }
}

/// Trennlinie zwischen Zeilen einer SectionCard.
struct RowDivider: View {
    var body: some View {
        Rectangle().fill(AppColors.outlineVariant.opacity(0.5)).frame(height: 0.5)
    }
}

/// Auswahl-Chips in einer Reihe (umbrechend).
struct ChoiceChips<T: Hashable>: View {
    let options: [T]
    let selection: T?
    let label: (T) -> String
    let onSelect: (T) -> Void
    @Environment(\.appAccent) private var accent

    var body: some View {
        FlowLayout(spacing: 8) {
            ForEach(options, id: \.self) { option in
                let selected = option == selection
                Button { onSelect(option) } label: {
                    Text(label(option))
                        .font(.subheadline.weight(selected ? .semibold : .regular))
                        .padding(.horizontal, Spacing.md)
                        .padding(.vertical, 8)
                        .foregroundStyle(selected ? accent.onContainer : AppColors.onSurface)
                        .background(selected ? accent.container : AppColors.containerHigh, in: Capsule())
                        .overlay(Capsule().strokeBorder(selected ? accent.primary.opacity(0.6) : .clear, lineWidth: 1))
                }
                .buttonStyle(.plain)
            }
        }
    }
}

/// Zeile mit Titel und darunter Auswahl-Chips (Einstellungen).
struct ChoiceRow<T: Hashable>: View {
    let title: String
    var subtitle: String? = nil
    let options: [T]
    let selection: T?
    let label: (T) -> String
    let onSelect: (T) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(title).font(.body)
            if let subtitle, !subtitle.isEmpty {
                Text(subtitle).font(.footnote).foregroundStyle(AppColors.onSurfaceVariant)
            }
            ChoiceChips(options: options, selection: selection, label: label, onSelect: onSelect)
        }
        .padding(.vertical, Spacing.md)
    }
}

/// Einfaches umbrechendes Layout (für Chips).
struct FlowLayout: Layout {
    var spacing: CGFloat = 8

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let maxWidth = proposal.width ?? .infinity
        var x: CGFloat = 0, y: CGFloat = 0, rowHeight: CGFloat = 0, width: CGFloat = 0
        for v in subviews {
            let s = v.sizeThatFits(.unspecified)
            if x > 0 && x + s.width > maxWidth {
                x = 0
                y += rowHeight + spacing
                rowHeight = 0
            }
            x += s.width + spacing
            rowHeight = max(rowHeight, s.height)
            width = max(width, x - spacing)
        }
        return CGSize(width: proposal.width ?? width, height: y + rowHeight)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        var x = bounds.minX, y = bounds.minY, rowHeight: CGFloat = 0
        for v in subviews {
            let s = v.sizeThatFits(.unspecified)
            if x > bounds.minX && x + s.width > bounds.maxX {
                x = bounds.minX
                y += rowHeight + spacing
                rowHeight = 0
            }
            v.place(at: CGPoint(x: x, y: y), proposal: ProposedViewSize(s))
            x += s.width + spacing
            rowHeight = max(rowHeight, s.height)
        }
    }
}

// MARK: Kursblock

/// Letzter Kurs gross, darunter Hoch/Tief, Bid/Ask und Volumen — wie `Ticker.kt`.
struct TickerView: View {
    let ticker: Ticker
    let base: String
    let quote: String

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text("\(PriceFormat.formatDouble(ticker.last)) \(quote)")
                .displayFont(.compact, design: .default)
            Text(L("ticker_timestamp") + " " + Self.sameDayTimeOrDate(ticker.timestamp))
                .font(.footnote)
                .foregroundStyle(AppColors.onSurfaceVariant)
                .padding(.bottom, 12)
            if ticker.high > Ticker.noData {
                statRow("ticker_high", "\(PriceFormat.formatDouble(ticker.high)) \(quote)",
                        "ticker_low", "\(PriceFormat.formatDouble(ticker.low)) \(quote)")
            }
            if ticker.ask > Ticker.noData {
                statRow("ticker_bid", "\(PriceFormat.formatDouble(ticker.bid)) \(quote)",
                        "ticker_ask", "\(PriceFormat.formatDouble(ticker.ask)) \(quote)")
            }
            if ticker.vol > Ticker.noData || ticker.volQuote > Ticker.noData {
                statRow("ticker_vol_base", ticker.vol > Ticker.noData ? "\(PriceFormat.formatDouble(ticker.vol)) \(base)" : "—",
                        "ticker_vol_quote", ticker.volQuote > Ticker.noData ? "\(PriceFormat.formatDouble(ticker.volQuote)) \(quote)" : "—")
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private func statRow(_ lt: String, _ lv: String, _ rt: String, _ rv: String) -> some View {
        HStack(alignment: .top, spacing: 12) {
            stat(lt, lv).frame(maxWidth: .infinity, alignment: .leading)
            stat(rt, rv).frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(.bottom, Spacing.sm)
    }

    private func stat(_ title: String, _ value: String) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(L(title)).font(.caption).foregroundStyle(AppColors.onSurfaceVariant)
            Text(value).font(.subheadline.weight(.medium).monospacedDigit()).lineLimit(1).minimumScaleFactor(0.7)
        }
    }

    static func sameDayTimeOrDate(_ millis: Int64) -> String {
        let d = Date(millis: millis)
        let f = DateFormatter()
        if Calendar.current.isDateInToday(d) { f.timeStyle = .short; f.dateStyle = .none } else { f.dateStyle = .short; f.timeStyle = .none }
        return f.string(from: d)
    }
}

// MARK: Coin-Logo

/// Runde Coin-Plakette, nur mit eingeschaltetem Schalter «Coin-Logos» (App bzw. `portfolio`):
/// echtes Logo (`CoinLogoStore`), sonst der Kreis mit den Initialen in der Akzentfarbe — nie ein
/// kaputtes Bild. Schalter aus: gar nichts (weder Logo noch Initialen, auch kein Platz). Feste
/// Grösse, nimmt also nie Breite von Kurs- oder Zahlenspalten. Dunkel: heller Grund hinter dem
/// Logo. Wie Android `CoinBadge`.
///
/// `logo: false` (DEX-Pools, deren Symbol jeder frei wählen kann): immer Initialen, damit nie das
/// Logo eines bekannten Coins neben einem gleichnamigen fremden Token steht. `favorite`: kleiner
/// Stern unten rechts (Ring in `ringColor`, der Farbe der Fläche darunter).
struct CoinBadge: View {
    let symbol: String
    var size: CGFloat = 36
    var logo: Bool = true
    /// Kennung des Paars (`Watch.logoPairKey`): Ist es TradFi, kommt das Logo aus dem
    /// TradFi-Namensraum, nie von einem gleichnamigen Krypto-Token.
    var pair: String? = nil
    /// Eigener Schalter «Im Portfolio».
    var portfolio: Bool = false
    var favorite: Bool = false
    var ringColor: Color = AppColors.container
    @Environment(\.coinLogosEnabled) private var appEnabled
    @Environment(\.appAccent) private var starAccent
    @Environment(\.portfolioCoinLogosEnabled) private var portfolioEnabled
    @Environment(\.appAccent) private var accent
    @Environment(\.colorScheme) private var colorScheme
    /// Neue Logos auf dem Gerät (Abgleich im Hintergrund): fehlende erneut versuchen.
    @ObservedObject private var revision = CoinLogoRevision.shared
    /// Geladenes Logo samt Symbol (Zeilen werden wiederverwendet: nie das Logo eines anderen Coins).
    @State private var loaded: (symbol: String, image: UIImage)?

    init(symbol: String, size: CGFloat = 36, logo: Bool = true, pair: String? = nil, portfolio: Bool = false,
         favorite: Bool = false, ringColor: Color = AppColors.container) {
        self.symbol = symbol
        self.size = size
        self.logo = logo
        self.pair = pair
        self.portfolio = portfolio
        self.favorite = favorite
        self.ringColor = ringColor
    }

    private var enabled: Bool { portfolio ? portfolioEnabled : appEnabled }

    /// Schlüssel des Logos (TradFi eigener Namensraum).
    private var key: String {
        CoinLogos.logoKey(symbol, tradFi: pair.map { CoinLogoStore.tradFiPairs.contains($0) } ?? false)
    }

    var body: some View {
        // Schalter aus: weder Logo noch Platzhalter
        if enabled {
            badge
                .overlay(alignment: .bottomTrailing) {
                    if favorite { star }
                }
                // Zierde: Das Kürzel steht daneben im Paar bzw. Namen
                .accessibilityHidden(true)
                .task(id: logo ? "\(key)|\(revision.value)" : "") {
                    guard logo else { loaded = nil; return }
                    let key = self.key
                    guard loaded?.symbol != key else { return }
                    if let image = await CoinLogoStore.shared.logo(key) { loaded = (key, image) }
                }
        }
    }

    private var badge: some View {
        let key = self.key
        return ZStack {
            if logo, let image = (loaded?.symbol == key ? loaded?.image : nil) ?? CoinLogoStore.cached(key) {
                Circle().fill(colorScheme == .dark ? AppColors.onSurface.opacity(0.92) : AppColors.containerHigh)
                Image(uiImage: image)
                    .resizable()
                    .interpolation(.high)
                    .scaledToFit()
                    .clipShape(Circle())
            } else {
                let label = CoinLogos.initials(symbol)
                Circle().fill(accent.tint(0.14))
                Text(label)
                    .font(.system(size: size * (label.count <= 3 ? 0.33 : 0.275), weight: .bold))
                    .foregroundStyle(accent.primary)
                    .lineLimit(1)
                    .minimumScaleFactor(0.5)
                    .padding(2)
            }
        }
        .frame(width: size, height: size)
        .clipShape(Circle())
    }

    /// Favorit: kleiner Stern in der Themenfarbe mit Ring in der Farbe der Fläche darunter.
    private var star: some View {
        let starSize = max(12, size * 0.45)
        return Image(systemName: "star.fill")
            .font(.system(size: starSize * 0.62, weight: .bold))
            .foregroundStyle(starAccent.primary)
            .frame(width: starSize, height: starSize)
            .background(Circle().fill(ringColor))
            .offset(x: 3, y: 3)
    }
}

extension String {
    /// Stabiler Hash (anders als `hashValue` gleich bei jedem Start).
    var hashValueStable: Int {
        var h = 5381
        for b in utf8 { h = (h &* 33) &+ Int(b) }
        return h
    }
}

// MARK: Leere Ansicht

/// Eigene Symbole neben den SF Symbols: «Portfolio» ist überall der Münzstapel (Asset
/// «PortfolioIcon», gleiche Form wie Android `ic_portfolio`). Andere Namen = SF Symbol.
enum AppSymbol {
    static let portfolio = "app.portfolio"

    static func isCustom(_ name: String) -> Bool { name == portfolio }

    /// Bild zum Namen; eigene Symbole als Vorlage (färben sich wie SF Symbols).
    static func image(_ name: String) -> Image {
        isCustom(name) ? Image("PortfolioIcon").renderingMode(.template) : Image(systemName: name)
    }

    /// Wie `image`, eigene Symbole auf `size` Punkte skaliert (SF Symbols folgen der Schrift).
    @ViewBuilder
    static func view(_ name: String, size: CGFloat) -> some View {
        if isCustom(name) {
            image(name).resizable().scaledToFit().frame(width: size, height: size)
        } else {
            image(name)
        }
    }
}

struct EmptyStateView: View {
    let systemImage: String
    let title: String
    var message: String? = nil
    var actionTitle: String? = nil
    var action: (() -> Void)? = nil
    @Environment(\.appAccent) private var accent

    var body: some View {
        VStack(spacing: Spacing.md) {
            AppSymbol.view(systemImage, size: 44)
                .scaledFont(size: 44, weight: .light, relativeTo: .largeTitle)
                .foregroundStyle(accent.primary)
                .padding(Spacing.xl)
                .background(accent.container.opacity(0.5), in: Circle())
            Text(title).font(.title3.weight(.semibold)).multilineTextAlignment(.center)
            if let message {
                Text(message).font(.subheadline).foregroundStyle(AppColors.onSurfaceVariant).multilineTextAlignment(.center)
            }
            if let actionTitle, let action {
                Button(action: action) {
                    Text(actionTitle).font(.headline).padding(.horizontal, Spacing.xl).padding(.vertical, 12)
                }
                .buttonStyle(AccentButtonStyle())
                .padding(.top, Spacing.xs)
            }
        }
        .padding(32)
        .frame(maxWidth: .infinity)
    }
}

/// Gefüllter Knopf in der Akzentfarbe.
struct AccentButtonStyle: ButtonStyle {
    @Environment(\.appAccent) private var accent
    @Environment(\.isEnabled) private var isEnabled

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .foregroundStyle(accent.onPrimary)
            .background(accent.primary.opacity(isEnabled ? 1 : 0.4), in: Capsule())
            .scaleEffect(configuration.isPressed ? 0.97 : 1)
            .animation(.easeOut(duration: 0.15), value: configuration.isPressed)
    }
}

/// Getönter Knopf (Akzent-Container).
struct TonalButtonStyle: ButtonStyle {
    @Environment(\.appAccent) private var accent
    @Environment(\.isEnabled) private var isEnabled

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .foregroundStyle(accent.onContainer)
            .background(accent.container.opacity(isEnabled ? 1 : 0.4), in: Capsule())
            .scaleEffect(configuration.isPressed ? 0.97 : 1)
            .animation(.easeOut(duration: 0.15), value: configuration.isPressed)
    }
}

/// Kleines Hinweis-Toast unten am Bildschirm.
struct ToastModifier: ViewModifier {
    @Binding var message: String?

    func body(content: Content) -> some View {
        content.overlay(alignment: .bottom) {
            if let message {
                Text(message)
                    .font(.subheadline.weight(.medium))
                    .foregroundStyle(AppColors.onToast)
                    .padding(.horizontal, Spacing.lg)
                    .padding(.vertical, 12)
                    .background(AppColors.toastBackground, in: Capsule())
                    .padding(.bottom, 24)
                    .padding(.horizontal, 16)
                    .transition(.move(edge: .bottom).combined(with: .opacity))
                    .task(id: message) {
                        try? await Task.sleep(nanoseconds: 2_500_000_000)
                        withAnimation { self.message = nil }
                    }
            }
        }
        .animation(.spring(duration: 0.3), value: message)
    }
}

extension View {
    func toast(_ message: Binding<String?>) -> some View { modifier(ToastModifier(message: message)) }
}

// MARK: Faktor-Checkliste

/// Eine Zeile der Faktor-Checkliste (Crypto Pulse «Warum?» und «Warum bewegt
/// sich das?»): Markierung ✓ / – / !, kurzer Titel, Wert rechtsbündig, optional
/// eine Erklärung darunter. VoiceOver: ein Element «Volumen, stützt die
/// Bewegung, 1,34-mal so viel wie üblich». Wie `FactorRow.kt`.
struct FactorRow: View {
    let mark: WhySummary.Mark
    let title: String
    let value: String
    var spokenValue: String? = nil
    var detail: String? = nil

    @Environment(\.priceColorScheme) private var priceColors
    @Environment(\.priceHighContrast) private var highContrast

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            HStack(alignment: .firstTextBaseline, spacing: 0) {
                Text(mark.glyph)
                    .font(.subheadline.weight(.heavy))
                    .foregroundStyle(markColor)
                    .frame(width: 20, alignment: .leading)
                Text(title)
                    .font(.subheadline)
                    .foregroundStyle(AppColors.onSurface)
                    .fixedSize(horizontal: false, vertical: true)
                    .frame(maxWidth: .infinity, alignment: .leading)
                if !value.isEmpty {
                    Text(value)
                        .font(.subheadline.weight(.semibold).monospacedDigit())
                        .foregroundStyle(AppColors.onSurface)
                        .multilineTextAlignment(.trailing)
                        .padding(.leading, 12)
                }
            }
            if let detail {
                Text(detail)
                    .font(.footnote.monospacedDigit())
                    .foregroundStyle(AppColors.onSurfaceVariant)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.leading, Spacing.lg)
            }
        }
        .padding(.vertical, Spacing.xs)
        .frame(maxWidth: .infinity, alignment: .leading)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(spoken)
    }

    private var spoken: String {
        let row = A11y.join([title, L(mark.a11yKey), spokenValue ?? value])
        guard let detail else { return row }
        return row + ". " + detail
    }

    /// ✓ in der «OK»-Farbe (Steigend-Farbe des Schemas, nie getauscht), ! in Bernstein, – grau.
    private var markColor: Color {
        switch mark {
        case .supports: priceColors.up(highContrast: highContrast)
        case .caution: AppColors.warningText
        case .neutral: AppColors.onSurfaceVariant
        }
    }
}
