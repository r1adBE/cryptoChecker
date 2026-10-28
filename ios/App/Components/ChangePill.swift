import SwiftUI

/// Grösse einer Änderungsangabe: `small` für Listenzeilen (12 pt), `medium` für Karten,
/// Kopfzeilen und Blätter (14 pt). Wie `ChangePillSize` (Android). Der Name «Pill» ist geblieben,
/// gezeichnet wird seit dem Verzicht auf Pillen nur noch farbige Schrift ohne Fläche.
enum ChangePillSize {
    case small, medium

    var iconSize: CGFloat { self == .small ? 9 : 11 }
}

/// Bedeutung einer Änderungsangabe und damit ihre Schriftfarbe — einzige Stelle, die das festlegt:
/// `up`/`down` in der Kursfarbe (Schema, hoher Kontrast, Tausch); `neutral` grau (0, unbekannt,
/// deaktiviert); `average` in der normalen Textfarbe für neutrale Kennzahlen wie den
/// Durchschnitt «Ø +0.27%» — nicht Bernstein, das bleibt «Achtung» vorbehalten. Wie `PillTone` (Android).
enum PillTone {
    case up, down, neutral, average

    /// Prozent-Änderung: Richtung erst ab sichtbarer Änderung (≥ 0.005 %, wie `PriceFormat.changePercent`).
    static func forPercent(_ change: Double?) -> PillTone {
        guard let change, change.isFinite, PriceFormat.changePercent(change) != nil else { return .neutral }
        return change > 0 ? .up : .down
    }

    /// Betrag oder Prozent mit eigener Null-Regel (`isZero`, z. B. halber Cent im Portfolio).
    static func forValue(_ value: Double?, isZero: (Double) -> Bool) -> PillTone {
        guard let value, value.isFinite, !isZero(value) else { return .neutral }
        return value > 0 ? .up : .down
    }

    /// Schrift- und Pfeilfarbe direkt auf der Fläche (Karte, Zeile, Blatt) — ohne Pille:
    /// Kursfarbe steigend/fallend (≥ 4.5:1 bzw. 7:1 auf allen Flächen, siehe PriceColorSchemeTests),
    /// grau für 0/unbekannt/deaktiviert, normale Textfarbe für den Durchschnitt (eine Kennzahl ohne
    /// Richtung; grau hiesse «0/deaktiviert», Bernstein hiesse «Achtung»). Wie `pillContent` (Android).
    func content(_ scheme: PriceColorScheme, highContrast: Bool, inverted: Bool) -> Color {
        switch self {
        case .up: scheme.up(highContrast: highContrast, inverted: inverted)
        case .down: scheme.down(highContrast: highContrast, inverted: inverted)
        case .neutral: AppColors.onSurfaceVariant
        case .average: AppColors.onSurface
        }
    }

    /// Schräger Pfeil einer Prozent-Änderung (SF Symbol): steigend/fallend, sonst keiner.
    /// Wie `pillArrow` (Android).
    var arrowSymbol: String? {
        switch self {
        case .up: "arrow.up.right"
        case .down: "arrow.down.right"
        case .neutral, .average: nil
        }
    }
}

/// Schrift der Änderungsangabe: gerundete Betragsschrift mit gleich breiten Ziffern, halbfett.
private struct PillFont: ViewModifier {
    let size: ChangePillSize

    @ViewBuilder
    func body(content: Content) -> some View {
        switch size {
        case .small:
            content.font(AppFont.amount(.caption, weight: .semibold))
        case .medium:
            content.scaledFont(size: 14, weight: .semibold, design: .rounded, relativeTo: .subheadline,
                               monospacedDigit: true)
        }
    }
}

/// Beliebiger Text als farbige Schrift in der Farbe von `tone` (keine Fläche, kein Innenabstand) —
/// für Beträge («+12.40 USDT»), Zähler («↗ 404 steigen», `icon` = SF Symbol davor) und den
/// Durchschnitt. `numericValue` gesetzt: Ziffern rollen bei Änderungen.
/// `spoken` ersetzt den Text für VoiceOver. Wie `TonePill` (Android).
struct TonePill: View {
    let text: String
    let tone: PillTone
    var size: ChangePillSize = .small
    var icon: String? = nil
    var spoken: String? = nil
    var numericValue: Double? = nil
    @Environment(\.priceColorScheme) private var priceColors
    @Environment(\.priceHighContrast) private var highContrast
    @Environment(\.priceColorsInverted) private var inverted

    var body: some View {
        let content: Color = tone.content(priceColors, highContrast: highContrast, inverted: inverted)
        HStack(spacing: 3) {
            if let icon {
                Image(systemName: icon)
                    .scaledFont(size: size.iconSize, weight: .bold, relativeTo: .caption)
                    .accessibilityHidden(true)
            }
            label
        }
        .foregroundStyle(content)
        .lineLimit(1)
        // Wächst mit der Textgrösse, aber nicht über AX2 (sonst sprengt die Angabe die Zeile)
        .dynamicTypeSize(...DynamicTypeSize.accessibility2)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(spoken ?? text)
    }

    private var label: some View {
        let transition: ContentTransition = numericValue.map { ContentTransition.numericText(value: $0) }
            ?? .numericText()
        return Text(text)
            .modifier(PillFont(size: size))
            .contentTransition(transition)
    }
}

/// Prozent-Änderung als farbige Schrift ohne Pille, wie in den Widgets: steigend «↗ +2.35%» in
/// der Steigend-Farbe, fallend «↘ −1.20%» (echtes Minus U+2212) in der Fallend-Farbe — Pfeil und
/// Vorzeichen, die Bedeutung hängt so nie allein an der Farbe. Praktisch keine Änderung: grau
/// «0.00%» ohne Pfeil. Ohne Wert: mit `dashWhenMissing` ein graues «—», sonst nichts — wie
/// `ChangePill` in Android. VoiceOver: Richtungswort («gestiegen um 2.35%»).
struct ChangePill: View {
    let change: Double?
    var size: ChangePillSize = .small
    var dashWhenMissing = false
    /// Grau ohne Kursfarbe (deaktivierte Paare zeigen den letzten Wert nur noch) — der Pfeil
    /// bleibt, sonst ginge die Richtung verloren.
    var neutral = false

    var body: some View {
        let value = change.flatMap { $0.isFinite ? $0 : nil }
        if value != nil || dashWhenMissing {
            let text = value.map { PriceFormat.changePercent($0) ?? PriceFormat.zeroPercent() } ?? "—"
            let direction = PillTone.forPercent(value)
            TonePill(text: text,
                     tone: neutral ? .neutral : direction,
                     size: size,
                     icon: direction.arrowSymbol,
                     spoken: value.flatMap { A11y.change($0) } ?? "—")
        }
    }
}

/// Kurs mit Gegenwährung, überall gleich: die Zahl in `font` (gleich breite Ziffern), dahinter die
/// Währung («USDT») klein und grau wie der Börsenname der Merkliste (`.footnote`, `onSurfaceVariant`).
/// Immer «Kurs Währung» von links nach rechts, auch bei Schrift von rechts nach links. Ohne Kurs
/// («—») keine Währung. Wie `PriceWithQuote` (Android).
struct PriceWithQuote: View {
    let price: String
    let quote: String?
    var font: Font = AppFont.amount(.body, weight: .semibold)
    /// Gesetzt: Zahl in der Display-Stufe (`displayFont`) statt in `font`.
    var display: AppFont.Display? = nil
    var color: Color = AppColors.onSurface

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: 4) {
            priceLabel
                .foregroundStyle(color)
            if let quote, price != "—" {
                Text(quote)
                    .font(.footnote)
                    .foregroundStyle(AppColors.onSurfaceVariant)
            }
        }
        .lineLimit(1)
        .environment(\.layoutDirection, .leftToRight)
    }

    @ViewBuilder
    private var priceLabel: some View {
        if let display {
            Text(price).displayFont(display, design: .default)
        } else {
            Text(price).font(font)
        }
    }
}

extension PriceWithQuote {
    /// Wie `PriceWithQuote`, aber als ein Text für «Zahl Währung» (`value`, z. B. «1’234.00 USDT»):
    /// die Währung am Ende (2–10 Grossbuchstaben/Ziffern, auch vor Bidi-Steuerzeichen) klein und grau wie dort (footnote,
    /// onSurfaceVariant), die Zahl bleibt in Schrift und Farbe der Ansicht. Als ein Text bricht der
    /// Betrag bei grosser Schrift um. Ohne Währung («—», «•••») unverändert.
    /// Wie `withSmallQuote` (Android).
    static func text(_ value: String) -> Text {
        // Bidi-Steuerzeichen am Ende (Isolation) bleiben hinter der Währung
        var end = value.endIndex
        while end > value.startIndex,
              value[value.index(before: end)].unicodeScalars.allSatisfy({ $0.properties.generalCategory == .format }) {
            end = value.index(before: end)
        }
        guard let space = value[..<end].lastIndex(of: " ") else { return Text(verbatim: value) }
        let quote = value[value.index(after: space)..<end]
        let isQuote = (2...10).contains(quote.count)
            && quote.first.map { $0.isASCII && $0.isUppercase } == true
            && quote.allSatisfy { $0.isASCII && ($0.isUppercase || $0.isNumber) }
        guard isQuote else { return Text(verbatim: value) }
        let head = Text(verbatim: String(value[..<quote.startIndex]))
        let tail = Text(verbatim: String(value[end...]))
        let small = Text(verbatim: String(quote))
            .font(.footnote.weight(.regular))
            .foregroundStyle(AppColors.onSurfaceVariant)
        return head + small + tail
    }
}
