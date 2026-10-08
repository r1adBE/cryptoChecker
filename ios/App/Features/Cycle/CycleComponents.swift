import SwiftUI
import UIKit

/// Farben der Marktzonen — die Stufen von `MarketScaleColors` (wie `ZoneColors` in `MarketPhaseCard.kt`).
enum CycleZonePalette {
    private static func index(_ zone: MarketZone) -> Int {
        MarketZone.allCases.firstIndex(of: zone) ?? 0
    }

    static func color(_ zone: MarketZone) -> Color { MarketScaleColors.steps[index(zone)] }

    /// Weiss nur auf den dunklen Randzonen, sonst dunkle Schrift (Kontrast).
    static func textColor(_ zone: MarketZone) -> Color { MarketScaleColors.onStep(index(zone)) }

    /// Verlauf von Extrem Bear (links) bis Extrem Bull (rechts); auch für Fear & Greed.
    static let gradient: [Color] = MarketScaleColors.steps
}

/// Datums- und Zahlenformate des Markt-Tabs.
enum CycleFormat {
    /// Wie `DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)`.
    static func mediumDate(_ day: LocalDay) -> String {
        let f = DateFormatter()
        f.dateStyle = .medium
        f.timeStyle = .none
        return f.string(from: day.date)
    }

    /// Kurz und lokalisiert mit Währungscode, höchstens 3 gültige Stellen:
    /// «3,45 Bio. CHF», "3.45T USD".
    static func compactMoney(_ value: Double, _ code: String) -> String {
        let number = value.formatted(
            .number
                .notation(.compactName)
                .precision(.significantDigits(1...3))
                .locale(Locale.current)
        )
        return "\(number) \(code)"
    }

    /// `"%.1f %%".format(value)`
    static func percent1(_ value: Double) -> String {
        String(format: "%.1f %%", locale: Locale.current, value)
    }
}

/// Einheitliche Karte für die Bereiche des Markt-Tabs (Android: `InsightCard`).
struct CycleInsightCard<Content: View>: View {
    let title: String
    @ViewBuilder let content: () -> Content

    init(_ title: String, @ViewBuilder content: @escaping () -> Content) {
        self.title = title
        self.content = content
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            CycleCardTitle(text: title)
            content()
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(Spacing.lg)
        .background(AppColors.container, in: RoundedRectangle(cornerRadius: CycleCardTitle.cornerRadius, style: .continuous))
    }
}

struct CycleCardTitle: View {
    static let cornerRadius: CGFloat = 20
    let text: String

    var body: some View {
        Text(text)
            .font(.subheadline.weight(.medium))
            .foregroundStyle(AppColors.onSurfaceVariant)
            .padding(.bottom, 12)
    }
}

struct CycleRetryButton: View {
    @Environment(\.appAccent) private var accent
    let action: () -> Void

    init(action: @escaping () -> Void) {
        self.action = action
    }

    var body: some View {
        Button(action: action) {
            Text(L("action_retry"))
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(accent.primary)
                .padding(.vertical, Spacing.sm)
                .padding(.horizontal, 4)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}

struct CycleFailedRow: View {
    let onRetry: () -> Void

    var body: some View {
        HStack(spacing: 8) {
            Text(L("something_went_wrong"))
                .font(.footnote)
                .foregroundStyle(AppColors.error)
                .frame(maxWidth: .infinity, alignment: .leading)
            CycleRetryButton(action: onRetry)
        }
    }
}

/// Hülle für form-gleiche Platzhalter der Karten «Jetzt» (Android: `SkeletonPulse`):
/// pulsiert ruhig wie `WatchlistSkeletonBlock`, bei reduzierter Bewegung stehend.
/// Für VoiceOver ausgeblendet (die echten Titel daneben bleiben lesbar).
///
/// Die Deckkraft kommt aus der Zeit (`TimelineView`), nicht aus einer Animation: so
/// läuft keine animierte Transaktion durch den Platzhalter, und verschiebt sich die Karte
/// (Inhalt darüber ändert sich), gleitet der Platzhalter nicht hinterher.
struct CycleSkeleton<Content: View>: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    /// Für VoiceOver statt nichts eine Zeile (z. B. «Wird geladen …»); nil = ausgeblendet.
    let label: String?
    @ViewBuilder let content: () -> Content

    init(label: String? = nil, @ViewBuilder content: @escaping () -> Content) {
        self.label = label
        self.content = content
    }

    var body: some View {
        Group {
            if reduceMotion {
                content()
                    .opacity(0.7)
            } else {
                TimelineView(.animation(minimumInterval: 1.0 / 30, paused: false)) { context in
                    content()
                        .opacity(Self.pulse(at: context.date))
                }
            }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(label ?? "")
        .accessibilityHidden(label == nil)
    }

    /// 0,45 … 1 … 0,45 in 1,8 s (wie Android: 900 ms hin, 900 ms zurück, sanft).
    private static func pulse(at date: Date) -> Double {
        let period = 1.8
        let phase = date.timeIntervalSinceReferenceDate.truncatingRemainder(dividingBy: period) / period
        return 0.45 + 0.55 * (0.5 - 0.5 * cos(2 * Double.pi * phase))
    }
}

/// Ein Teil des Markt-Tabs, sobald `CycleReveal` ihn freigibt (Android: `RevealItem`):
/// Einblenden und 8 pt von unten nach oben, 220 ms — nur Deckkraft und Versatz, das Layout
/// steht sofort. Ohne Animation, wenn `animated` false ist oder Bewegung reduziert.
struct CycleRevealItem<Content: View>: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    let animated: Bool
    let content: Content
    @State private var shown = false

    init(animated: Bool, content: Content) {
        self.animated = animated
        self.content = content
    }

    var body: some View {
        let visible = shown || !animated || reduceMotion
        content
            .opacity(visible ? 1 : 0)
            .offset(y: visible ? 0 : 8)
            // Das Einfügen selbst ohne Übergang; das Einblenden macht `onAppear`
            .transition(.identity)
            .onAppear {
                guard !shown else { return }
                if animated && !reduceMotion {
                    withAnimation(.easeOut(duration: CycleReveal.enterSeconds)) { shown = true }
                } else {
                    shown = true
                }
            }
    }
}

extension View {
    /// Macht aus einer Platzhalter-Textzeile (z. B. `Text(verbatim: " ").font(.body)`)
    /// einen grauen Balken genau in deren Zeilenhöhe — folgt also Dynamic Type.
    /// `width` nil = ganze verfügbare Breite.
    func cycleSkeletonBar(width: CGFloat? = nil) -> some View {
        hidden()
            .frame(width: width)
            .frame(maxWidth: width == nil ? .infinity : nil, alignment: .leading)
            .background {
                // Etwas schmaler als die Zeile, mittig — wie Text ohne Ober-/Unterlängen
                GeometryReader { geo in
                    Capsule(style: .continuous)
                        .fill(AppColors.containerHighest)
                        .frame(width: geo.size.width, height: geo.size.height * 0.72)
                        .position(x: geo.size.width / 2, y: geo.size.height / 2)
                }
            }
    }
}

extension View {
    /// Platzhalter für mehrzeiligen Text (Android: `SkeletonText`): Auf einen unsichtbar
    /// gesetzten Text (der echte feste Text oder ein Beispiel mit typischen Zahlen) in der
    /// Schrift `style` angewendet, reserviert er genau dessen Höhe und zeichnet je Zeile
    /// einen grauen Balken (die letzte kürzer). Folgt Dynamic Type.
    func cycleSkeletonLines(_ style: UIFont.TextStyle) -> some View {
        hidden()
            .frame(maxWidth: .infinity, alignment: .leading)
            .background {
                GeometryReader { geo in
                    let lineHeight = UIFont.preferredFont(forTextStyle: style).lineHeight
                    let lines = max(1, Int((geo.size.height / lineHeight).rounded()))
                    let step = geo.size.height / CGFloat(lines)
                    ForEach(0..<lines, id: \.self) { line in
                        let width = line == lines - 1 && lines > 1 ? geo.size.width * 0.6 : geo.size.width
                        Capsule(style: .continuous)
                            .fill(AppColors.containerHighest)
                            .frame(width: width, height: step * 0.72)
                            .position(x: width / 2, y: step * (CGFloat(line) + 0.5))
                    }
                }
            }
    }
}

/// Graue Pille in Etiketten-Höhe — Text in `font` mit Innenabstand (Android: `SkeletonPill`).
/// `width` nil = ganze verfügbare Breite.
struct CycleSkeletonPill: View {
    let font: Font
    var width: CGFloat? = nil
    var horizontal: CGFloat = 10
    var vertical: CGFloat = 5

    var body: some View {
        Text(verbatim: " ")
            .font(font)
            .hidden()
            .frame(width: width)
            .frame(maxWidth: width == nil ? .infinity : nil)
            .padding(.horizontal, horizontal)
            .padding(.vertical, vertical)
            .background(AppColors.containerHighest, in: Capsule(style: .continuous))
    }
}

/// Platzhalter in der Form von `CycleZoneGauge`: Skala (18 pt), Zonen-Beschriftung, Zeile darunter.
struct CycleZoneGaugeSkeleton: View {
    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Capsule()
                .fill(AppColors.containerHighest)
                .frame(height: 8)
                .frame(maxWidth: .infinity, minHeight: 18, maxHeight: 18)
            Text(verbatim: " ")
                .font(.caption2)
                .cycleSkeletonBar()
                .padding(.top, 4)
            Text(verbatim: " ")
                .font(.caption2)
                .cycleSkeletonBar()
                .padding(.trailing, 80)
                .padding(.top, 2)
        }
        .frame(maxWidth: .infinity)
    }
}

struct CycleSourceText: View {
    let text: String

    var body: some View {
        Text(text)
            .font(.caption2)
            .foregroundStyle(AppColors.onSurfaceVariant)
            .padding(.top, Spacing.sm)
    }
}

/// Fortschrittsbalken (Android: `LinearProgressIndicator`).
struct CycleProgressBar: View {
    @Environment(\.appAccent) private var accent
    let fraction: Double
    /// Name für VoiceOver (z. B. «Bitcoin-Halving»); der Wert wird als Prozent gelesen.
    var label: String? = nil

    init(fraction: Double, label: String? = nil) {
        self.fraction = fraction
        self.label = label
    }

    var body: some View {
        GeometryReader { geo in
            ZStack(alignment: .leading) {
                Capsule().fill(accent.container)
                Capsule()
                    .fill(accent.primary)
                    .frame(width: geo.size.width * min(max(fraction, 0), 1))
            }
        }
        .frame(height: 6)
        .accessibilityElement()
        .accessibilityLabel(label ?? "")
        .accessibilityValue(Text(verbatim: LocaleNumbers.integer(Int((min(max(fraction, 0), 1) * 100).rounded())) + " %"))
    }
}

/// Farbskala mit runder Markierung (Zonen-Skala und Fear & Greed).
/// Folgt der Leserichtung wie die Beschriftung darunter: bei Rechts-nach-links-Sprachen
/// beginnt die Skala rechts. Gezeichnet wird ausdrücklich gespiegelt (Farben und Markierung
/// gemeinsam), damit Verlauf und Markierung nie auseinanderlaufen.
struct CycleScaleBar: View {
    let colors: [Color]
    /// 0…1
    let fraction: Double
    private let marker: CGFloat = 18
    @Environment(\.layoutDirection) private var layoutDirection

    init(colors: [Color], fraction: Double) {
        self.colors = colors
        self.fraction = fraction
    }

    var body: some View {
        let rtl = layoutDirection == .rightToLeft
        let clamped = min(max(fraction, 0), 1)
        GeometryReader { geo in
            ZStack(alignment: .leading) {
                Capsule()
                    .fill(LinearGradient(colors: rtl ? colors.reversed() : colors,
                                         startPoint: .leading, endPoint: .trailing))
                    .frame(height: 8)
                Circle()
                    .fill(AppColors.onSurface)
                    .frame(width: marker, height: marker)
                    .overlay(Circle().fill(AppColors.surface).padding(3))
                    .offset(x: max(geo.size.width - marker, 0) * (rtl ? 1 - clamped : clamped))
            }
            .frame(width: geo.size.width, height: marker)
            .environment(\.layoutDirection, .leftToRight)
        }
        .frame(height: marker)
    }
}

/// Skala von Extrem Bear (links) bis Extrem Bull (rechts) mit Markierung.
struct CycleZoneGauge: View {
    let index: Int

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            CycleScaleBar(colors: CycleZonePalette.gradient, fraction: Double(index) / 100)
                // «Skala von Extrem Bear bis Extrem Bull: 63 von 100» — wie Android
                .accessibilityElement(children: .ignore)
                .accessibilityLabel(L("a11y_gauge", L("zone_extreme_bear"), L("zone_extreme_bull"), index))
            // Zwischenmarken: Bear · Neutral · Bull, an den Enden die Extremzonen
            HStack(spacing: 2) {
                ForEach(Array(MarketZone.allCases.enumerated()), id: \.offset) { i, zone in
                    Text(L(zone.labelKey))
                        .font(.caption2)
                        .foregroundStyle(AppColors.onSurfaceVariant)
                        .lineLimit(1)
                        .minimumScaleFactor(0.7)
                        .frame(maxWidth: .infinity, alignment: i == 0 ? .leading : (i == 4 ? .trailing : .center))
                }
            }
            .padding(.top, 4)
            // Rechts heisst nicht «gut»: dort liegt das Top-Risiko.
            HStack(spacing: 8) {
                Text(L("zone_bottom_chance"))
                    .font(.caption2)
                    .foregroundStyle(AppColors.onSurfaceVariant.opacity(0.8))
                    .frame(maxWidth: .infinity, alignment: .leading)
                Text("▲ " + L("zone_top_risk"))
                    .font(.caption2)
                    .foregroundStyle(AppColors.error)
            }
            .padding(.top, 2)
        }
        .frame(maxWidth: .infinity)
    }
}

/// Etikett der Zone (farbige Pille).
struct CycleZonePill: View {
    let zone: MarketZone
    var font: Font = .title2
    var horizontal: CGFloat = 18
    var vertical: CGFloat = 6

    var body: some View {
        Text(L(zone.labelKey))
            .font(font)
            .foregroundStyle(CycleZonePalette.textColor(zone))
            .lineLimit(1)
            .minimumScaleFactor(0.7)
            .padding(.horizontal, horizontal)
            .padding(.vertical, vertical)
            .background(CycleZonePalette.color(zone), in: Capsule())
    }
}

/// Eine Zeile eines Indikators mit Messwert und Punkten.
struct CycleSignalRow: View {
    let name: String
    let value: String?
    let topPoints: Int
    let bottomPoints: Int
    /// Kurze Erklärung des Indikators (kleiner Nebentext); nil = keine.
    var explanation: String? = nil

    private var points: (String, Color) {
        if topPoints > 0 { return (L("ind_points_top", topPoints), CycleZonePalette.color(.BULL)) }
        if bottomPoints > 0 { return (L("ind_points_bottom", bottomPoints), CycleZonePalette.color(.BEAR)) }
        return ("–", AppColors.outline)
    }

    var body: some View {
        HStack(alignment: .center, spacing: 12) {
            VStack(alignment: .leading, spacing: 1) {
                Text(name)
                    .font(.subheadline)
                    .foregroundStyle(AppColors.onSurface)
                if let value {
                    Text(value)
                        .font(.footnote)
                        .monospacedDigit()
                        .foregroundStyle(AppColors.onSurfaceVariant)
                }
                if let explanation {
                    Text(explanation)
                        .font(.caption2)
                        .foregroundStyle(AppColors.onSurfaceVariant)
                        .fixedSize(horizontal: false, vertical: true)
                        .padding(.top, 1)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            Text(points.0)
                .font(.caption.weight(.medium))
                .foregroundStyle(points.1)
        }
        .accessibilityElement(children: .combine)
    }
}
