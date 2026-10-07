import SwiftUI

// MARK: Umgebung

private struct AppAccentKey: EnvironmentKey {
    static let defaultValue: AccentColor = .default
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
                    .font(.subheadline.weight(.medium))
                    .foregroundStyle(AppColors.onSurfaceVariant)
                    .padding(.leading, 4)
            }
            VStack(alignment: .leading, spacing: 0, content: content)
                .padding(.horizontal, 16)
                .padding(.vertical, 8)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(AppColors.container, in: RoundedRectangle(cornerRadius: 20, style: .continuous))
        }
        .padding(.bottom, 20)
    }
}

/// Schalterzeile — die ganze Zeile ist antippbar.
struct SwitchRow: View {
    let title: String
    var subtitle: String? = nil
    @Binding var isOn: Bool
    var enabled: Bool = true
    @Environment(\.appAccent) private var accent

    var body: some View {
        Toggle(isOn: $isOn) {
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(.body)
                if let subtitle, !subtitle.isEmpty {
                    Text(subtitle).font(.footnote).foregroundStyle(AppColors.onSurfaceVariant)
                }
            }
        }
        .tint(accent.primary)
        .disabled(!enabled)
        .opacity(enabled ? 1 : 0.5)
        .padding(.vertical, 10)
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
                        .padding(.horizontal, 14)
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
        .padding(.vertical, 10)
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
                .font(.title.weight(.semibold).monospacedDigit())
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
        .padding(.bottom, 10)
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

/// Kreis mit den ersten Buchstaben des Coins, Farbe aus dem Namen abgeleitet.
struct CoinBadge: View {
    let symbol: String
    var size: CGFloat = 36

    var body: some View {
        let hue = Double(abs(symbol.hashValueStable) % 360) / 360
        ZStack {
            Circle().fill(Color(hue: hue, saturation: 0.45, brightness: 0.85).opacity(0.25))
            Text(String(symbol.prefix(symbol.count > 4 ? 3 : 4)))
                .font(.system(size: size * (symbol.count > 3 ? 0.28 : 0.34), weight: .bold, design: .rounded))
                .foregroundStyle(Color(hue: hue, saturation: 0.7, brightness: 0.75))
                .lineLimit(1)
                .minimumScaleFactor(0.5)
                .padding(3)
        }
        .frame(width: size, height: size)
        // Zierde: Das Kürzel steht daneben im Paar bzw. Namen
        .accessibilityHidden(true)
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

struct EmptyStateView: View {
    let systemImage: String
    let title: String
    var message: String? = nil
    var actionTitle: String? = nil
    var action: (() -> Void)? = nil
    @Environment(\.appAccent) private var accent

    var body: some View {
        VStack(spacing: 14) {
            Image(systemName: systemImage)
                .scaledFont(size: 44, weight: .light, relativeTo: .largeTitle)
                .foregroundStyle(accent.primary)
                .padding(22)
                .background(accent.container.opacity(0.5), in: Circle())
            Text(title).font(.title3.weight(.semibold)).multilineTextAlignment(.center)
            if let message {
                Text(message).font(.subheadline).foregroundStyle(AppColors.onSurfaceVariant).multilineTextAlignment(.center)
            }
            if let actionTitle, let action {
                Button(action: action) {
                    Text(actionTitle).font(.headline).padding(.horizontal, 22).padding(.vertical, 12)
                }
                .buttonStyle(AccentButtonStyle())
                .padding(.top, 6)
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
                    .foregroundStyle(.white)
                    .padding(.horizontal, 18)
                    .padding(.vertical, 12)
                    .background(Color.black.opacity(0.82), in: Capsule())
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
                    .padding(.leading, 20)
            }
        }
        .padding(.vertical, 5)
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
        case .caution: WatchlistActivityColors.cautionText
        case .neutral: AppColors.onSurfaceVariant
        }
    }
}
