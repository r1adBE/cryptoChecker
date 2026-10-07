import SwiftUI

/// Abschnitt der Einstellungen: Überschrift mit kleinem Symbol in der
/// Akzentfarbe, darunter die Karte.
@MainActor
struct SettingsGroup<Content: View>: View {
    let title: String
    let icon: String
    @ViewBuilder var content: () -> Content
    @Environment(\.appAccent) private var accent

    init(_ title: String, icon: String, @ViewBuilder content: @escaping () -> Content) {
        self.title = title
        self.icon = icon
        self.content = content
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            SettingsGroupHeader(title: title, icon: icon)
            SectionCard(nil, content: content)
        }
    }
}

/// Überschrift einer Gruppe: kleines Symbol in der Akzentfläche und Titel,
/// für VoiceOver als Überschrift markiert.
@MainActor
struct SettingsGroupHeader: View {
    let title: String
    let icon: String
    @Environment(\.appAccent) private var accent

    var body: some View {
        HStack(spacing: 8) {
            Image(systemName: icon)
                .scaledFont(size: 12, weight: .semibold, relativeTo: .caption)
                .dynamicTypeSize(...DynamicTypeSize.xxxLarge)
                .foregroundStyle(accent.onContainer)
                .frame(width: 24, height: 24)
                .background(accent.container, in: RoundedRectangle(cornerRadius: 7, style: .continuous))
            Text(title)
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(AppColors.onSurfaceVariant)
        }
        .padding(.leading, 4)
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(.isHeader)
    }
}

/// Gruppe mit mehreren Karten unter einer Überschrift (z. B. «Alarme & Benachrichtigungen»).
/// Die Karten (`SettingsCard`) stehen enger beieinander als die Gruppen.
@MainActor
struct SettingsSection<Content: View>: View {
    let title: String
    let icon: String
    @ViewBuilder var content: () -> Content

    init(_ title: String, icon: String, @ViewBuilder content: @escaping () -> Content) {
        self.title = title
        self.icon = icon
        self.content = content
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            SettingsGroupHeader(title: title, icon: icon)
            content()
        }
        .padding(.bottom, 20)
    }
}

/// Karte innerhalb einer `SettingsSection` — gleiche Fläche wie `SectionCard`, ohne Abstand darunter.
@MainActor
struct SettingsCard<Content: View>: View {
    @ViewBuilder var content: () -> Content

    init(@ViewBuilder content: @escaping () -> Content) {
        self.content = content
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 0, content: content)
            .padding(.horizontal, 16)
            .padding(.vertical, 8)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(AppColors.container, in: RoundedRectangle(cornerRadius: 20, style: .continuous))
    }
}

/// Kleiner grauer Hinweistext unter einer Zeile.
@MainActor
struct SettingsHint: View {
    let text: String
    var top: CGFloat = 0

    var body: some View {
        Text(text)
            .font(.footnote)
            .foregroundStyle(AppColors.onSurfaceVariant)
            .fixedSize(horizontal: false, vertical: true)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.top, top)
            .padding(.bottom, 10)
    }
}

/// Wie das System, Hell oder Dunkel — gilt für App und Widgets.
/// Segmentleiste mit gleitender Markierung.
@MainActor
struct SettingsThemePicker: View {
    let selection: Bool?
    let onSelect: (Bool?) -> Void
    @Environment(\.appAccent) private var accent
    @Namespace private var namespace

    private static let options: [(value: Bool?, key: String, icon: String)] = [
        (nil, "theme_system", "circle.lefthalf.filled"),
        (false, "theme_light", "sun.max.fill"),
        (true, "theme_dark", "moon.fill"),
    ]

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(L("settings_theme_mode")).font(.body)
            HStack(spacing: 4) {
                ForEach(Array(Self.options.enumerated()), id: \.offset) { _, option in
                    let selected = option.value == selection
                    Button {
                        withAnimation(.spring(duration: 0.3)) { onSelect(option.value) }
                    } label: {
                        HStack(spacing: 6) {
                            Image(systemName: option.icon).font(.footnote.weight(.semibold))
                            Text(L(option.key))
                                .font(.subheadline.weight(selected ? .semibold : .regular))
                                .lineLimit(1)
                                .minimumScaleFactor(0.8)
                        }
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 10)
                        .foregroundStyle(selected ? accent.onContainer : AppColors.onSurfaceVariant)
                        .background {
                            if selected {
                                RoundedRectangle(cornerRadius: 11, style: .continuous)
                                    .fill(accent.container)
                                    .overlay(
                                        RoundedRectangle(cornerRadius: 11, style: .continuous)
                                            .strokeBorder(accent.primary.opacity(0.5), lineWidth: 1)
                                    )
                                    .matchedGeometryEffect(id: "theme", in: namespace)
                            }
                        }
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityAddTraits(selected ? .isSelected : [])
                }
            }
            .padding(4)
            .background(AppColors.containerHigh, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
        }
        .padding(.top, 12)
        .padding(.bottom, 8)
        .sensoryFeedback(.selection, trigger: selection)
    }
}

/// Farbkreise zur Auswahl der Akzentfarbe; die gewählte hat Ring und Haken.
@MainActor
struct SettingsAccentPicker: View {
    let selection: AccentColor
    let onSelect: (AccentColor) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(L("settings_accent")).font(.body)
            HStack(spacing: 0) {
                ForEach(AccentColor.allCases) { accent in
                    let selected = accent == selection
                    let seed = Color(hex: accent.seed)
                    Button {
                        withAnimation(.spring(duration: 0.3)) { onSelect(accent) }
                    } label: {
                        VStack(spacing: 6) {
                            ZStack {
                                Circle()
                                    .fill(LinearGradient(colors: [seed.opacity(0.85), seed],
                                                         startPoint: .topLeading, endPoint: .bottomTrailing))
                                    .frame(width: 42, height: 42)
                                    .shadow(color: seed.opacity(selected ? 0.55 : 0), radius: 8, y: 3)
                                if selected {
                                    Image(systemName: "checkmark")
                                        .scaledFont(size: 16, weight: .bold, relativeTo: .callout)
                                        .dynamicTypeSize(...DynamicTypeSize.xxxLarge)
                                        .foregroundStyle(.white)
                                        .transition(.scale.combined(with: .opacity))
                                }
                            }
                            .padding(4)
                            .overlay(Circle().strokeBorder(selected ? seed : .clear, lineWidth: 2.5))
                            .scaleEffect(selected ? 1.06 : 1)

                            Text(L(accent.labelKey))
                                .font(.caption.weight(selected ? .semibold : .regular))
                                .foregroundStyle(selected ? AppColors.onSurface : AppColors.onSurfaceVariant)
                        }
                        .frame(maxWidth: .infinity)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(L(accent.labelKey))
                    .accessibilityAddTraits(selected ? .isSelected : [])
                }
            }
        }
        .padding(.vertical, 8)
        .sensoryFeedback(.selection, trigger: selection)
    }
}

/// Kursfarben (Grün/Rot oder Blau/Orange) als Segmentleiste — wie
/// `PriceColorSchemeRow` in Android. Jede Option zeigt ihre beiden Farben als Punkte.
@MainActor
struct SettingsPriceColorPicker: View {
    let selection: PriceColorScheme
    let onSelect: (PriceColorScheme) -> Void
    @Environment(\.appAccent) private var accent
    @Environment(\.priceHighContrast) private var highContrast
    @Environment(\.priceColorsInverted) private var inverted
    @Namespace private var namespace

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(L("settings_price_colors")).font(.body)
            HStack(spacing: 4) {
                ForEach(PriceColorScheme.allCases) { scheme in
                    let selected = scheme == selection
                    Button {
                        withAnimation(.spring(duration: 0.3)) { onSelect(scheme) }
                    } label: {
                        HStack(spacing: 6) {
                            HStack(spacing: 3) {
                                Circle().fill(scheme.up(highContrast: highContrast, inverted: inverted)).frame(width: 8, height: 8)
                                Circle().fill(scheme.down(highContrast: highContrast, inverted: inverted)).frame(width: 8, height: 8)
                            }
                            .accessibilityHidden(true)
                            Text(L(scheme.labelKey))
                                .font(.subheadline.weight(selected ? .semibold : .regular))
                                .lineLimit(1)
                                .minimumScaleFactor(0.8)
                        }
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 10)
                        .foregroundStyle(selected ? accent.onContainer : AppColors.onSurfaceVariant)
                        .background {
                            if selected {
                                RoundedRectangle(cornerRadius: 11, style: .continuous)
                                    .fill(accent.container)
                                    .overlay(
                                        RoundedRectangle(cornerRadius: 11, style: .continuous)
                                            .strokeBorder(accent.primary.opacity(0.5), lineWidth: 1)
                                    )
                                    .matchedGeometryEffect(id: "priceColors", in: namespace)
                            }
                        }
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityAddTraits(selected ? .isSelected : [])
                }
            }
            .padding(4)
            .background(AppColors.containerHigh, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
        }
        .padding(.top, 8)
        .padding(.bottom, 6)
        .sensoryFeedback(.selection, trigger: selection)
    }
}

/// Zeile, die zu einer anderen Stelle führt (z. B. iOS-Einstellungen).
@MainActor
struct SettingsLinkRow: View {
    let icon: String
    let title: String
    var subtitle: String? = nil
    /// Aktueller Wert rechts (z. B. die Sprache), optional.
    var value: String? = nil
    var trailingIcon: String = "arrow.up.forward"
    let action: () -> Void
    @Environment(\.appAccent) private var accent

    var body: some View {
        Button(action: action) {
            HStack(spacing: 12) {
                Image(systemName: icon)
                    .font(.body.weight(.medium))
                    .foregroundStyle(accent.primary)
                    .frame(width: 26)
                VStack(alignment: .leading, spacing: 2) {
                    Text(title).font(.body).foregroundStyle(AppColors.onSurface)
                    if let subtitle, !subtitle.isEmpty {
                        Text(subtitle)
                            .font(.footnote)
                            .foregroundStyle(AppColors.onSurfaceVariant)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }
                Spacer(minLength: 8)
                if let value, !value.isEmpty {
                    Text(value)
                        .font(.body)
                        .foregroundStyle(AppColors.onSurfaceVariant)
                        .lineLimit(1)
                }
                Image(systemName: trailingIcon)
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(AppColors.onSurfaceVariant)
            }
            .padding(.vertical, 12)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}

/// Melde-Schwelle: 0 / 3 / 5 / 7 % oder ein eigener Wert.
enum SettingsPercentOption: Hashable {
    case value(Double)
    case custom

    /// «5» statt «5.0», «2,5» mit dem Dezimaltrenner der Sprache.
    static func format(_ value: Double) -> String {
        if value.rounded() == value, abs(value) < 1e9 { return String(Int(value)) }
        let f = NumberFormatter()
        f.locale = Locale.current
        f.minimumFractionDigits = 0
        f.maximumFractionDigits = 4
        return f.string(from: NSNumber(value: value)) ?? String(value)
    }

    /// Wie Android: Komma oder Punkt, 0 – 100.
    static func parse(_ text: String) -> Double? {
        let cleaned = text.trimmingCharacters(in: .whitespaces)
            .replacingOccurrences(of: "%", with: "")
            .replacingOccurrences(of: ",", with: ".")
        guard let v = Double(cleaned), v >= 0, v <= 100 else { return nil }
        return v
    }
}
