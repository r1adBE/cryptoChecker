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

    /// Schrift und Symbole auf gefüllter Akzentfläche (Knöpfe, «+»-Kreis, gewählte gefüllte
    /// Chips, Marken in Verläufen): die kontrastsichere `AccentColor.onPrimary` je Akzentfarbe.
    /// Wie Android `AppColors.onAccent`.
    var onAccent: Color { appAccent.onPrimary }
}

// MARK: Abschnitt

/// Abschnitt mit Überschrift und Inhalt auf abgerundeter Fläche — wie `SectionCard`.
/// Abschnittsüberschrift überall gleich: Titelstufe (`.headline`, halbfett), in Textfarbe, davor ein
/// kurzer Strich in der Themenfarbe — Einstellungen («Allgemein», «Darstellung» …), Portfolio
/// («Gesamtwert», «Wertverlauf» …) und Markt («Jetzt», «Einordnung», «Daten» …); wie `SectionTitle` +
/// `sectionTitleMarker` (Android). Die Themenfarbe bleibt Bedienbarem vorbehalten. Grösser als der
/// Fliesstext, damit eine Karte drei sichtbare Stufen hat: Abschnitt, Wert (Display), Text.
struct SectionTitleStyle: ViewModifier {
    @Environment(\.appAccent) private var accent
    /// So hoch wie die Schriftgrösse der Überschrift (wächst mit Dynamic Type).
    @ScaledMetric(relativeTo: .headline) private var markerHeight: CGFloat = 17

    func body(content: Content) -> some View {
        content
            .font(AppFont.title)
            .foregroundStyle(AppColors.onSurface)
            .textCase(nil)
            .padding(.leading, Spacing.sm)
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
                .appCard(padding: .list)
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

/// Hinweistext, in dem «⚡» als weisses Symbol (Textfarbe, wie der ⚡ in der Merkliste) statt als
/// gelbes Emoji steht. Ohne «⚡» ein normaler Text. Wie `BoltHintText` (Android).
enum BoltHint {
    static func text(_ string: String, accent: Color) -> Text {
        let parts = string.components(separatedBy: "⚡")
        guard parts.count > 1 else { return Text(verbatim: string) }
        var result = Text(verbatim: parts[0])
        for part in parts.dropFirst() {
            result = result + Text(Image(systemName: "bolt.fill")).foregroundColor(AppColors.onSurface) + Text(verbatim: part)
        }
        return result
    }
}

/// Trennlinie zwischen Zeilen einer SectionCard: volle Token-Farbe, nie halbtransparent (Build 46).
struct RowDivider: View {
    var body: some View {
        Rectangle().fill(AppColors.outlineVariant).frame(height: 0.5)
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
