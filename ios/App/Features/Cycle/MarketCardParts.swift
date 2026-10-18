import SwiftUI
import UIKit

/// Stufen der gemeinsamen Farbskala `MarketScaleColors` (Fear & Greed).
enum CycleZonePalette {
    /// Verlauf der Skala von links nach rechts.
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

/// Einheitliche Karte für die Bereiche des Markt-Tabs (Android: `InsightCard`); mit `info` steht
/// das Info-Zeichen (`MarketInfoButton`) direkt hinter dem Titel.
struct CycleInsightCard<Content: View>: View {
    let title: String
    let info: MarketInfoTopic?
    @ViewBuilder let content: () -> Content

    init(_ title: String, info: MarketInfoTopic? = nil, @ViewBuilder content: @escaping () -> Content) {
        self.title = title
        self.info = info
        self.content = content
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            titleRow
            content()
        }
        .appCard()
    }

    @ViewBuilder
    private var titleRow: some View {
        if let info {
            HStack(spacing: 2) {
                Text(title)
                    .sectionTitleStyle()
                    .accessibilityAddTraits(.isHeader)
                MarketInfoButton(topic: info, compact: true)
            }
            .padding(.bottom, 12)
        } else {
            CycleCardTitle(text: title)
        }
    }
}

/// Titel in einer Karte des Markt-Tabs: Abschnittsüberschrift mit Strich in der Themenfarbe,
/// für VoiceOver eine Überschrift — wie `InsightCard` (Android).
struct CycleCardTitle: View {
    let text: String

    var body: some View {
        Text(text)
            .sectionTitleStyle()
            .accessibilityAddTraits(.isHeader)
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

/// Die eine Zeile für eine nicht erreichbare Datenquelle im Markt-Tab: Wolke durchgestrichen,
/// «Marktdaten gerade nicht verfügbar», «Erneut» — neutral grau statt Fehlerrot, denn eine
/// ausgefallene Quelle ist kein Fehler des Nutzers (Rot bleibt Eingabefehlern vorbehalten). Die
/// Karte bleibt stehen, damit darunter nichts springt. Wie Android `UnavailableRow`.
struct CycleUnavailableRow: View {
    let onRetry: () -> Void

    var body: some View {
        HStack(spacing: Spacing.sm) {
            CycleUnavailableIcon()
            Text(L("pulse_unavailable"))
                .font(.subheadline)
                .foregroundStyle(AppColors.onSurfaceVariant)
                .frame(maxWidth: .infinity, alignment: .leading)
            CycleRetryButton(action: onRetry)
        }
    }
}

/// Zeichen «Quelle nicht erreichbar» vor der Meldung (`CycleUnavailableRow`, Zeilen von `MarketRow`);
/// nur Schmuck. Wie Android `UnavailableIcon` (18).
struct CycleUnavailableIcon: View {
    @ScaledMetric(relativeTo: .subheadline) private var size: CGFloat = 18

    var body: some View {
        Image(systemName: "icloud.slash")
            .resizable()
            .scaledToFit()
            .frame(width: size, height: size)
            .foregroundStyle(AppColors.onSurfaceVariant)
            .accessibilityHidden(true)
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
    var horizontal: CGFloat = Spacing.sm
    var vertical: CGFloat = Spacing.sm

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

/// «Powered by CoinGecko» (`DataFreshness.poweredByCoinGecko`) unter Werten aus CoinGecko-Daten;
/// caption2 = 11 pt (Bedingung: mindestens 10). Wie Android `CoinGeckoAttribution`.
struct CoinGeckoAttribution: View {
    var body: some View {
        Text(verbatim: DataFreshness.poweredByCoinGecko)
            .font(.caption2)
            .foregroundStyle(AppColors.onSurfaceVariant)
            .padding(.top, 2)
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
