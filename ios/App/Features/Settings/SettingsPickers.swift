import SwiftUI

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
                        HStack(spacing: Spacing.xs) {
                            Image(systemName: option.icon).font(.footnote.weight(.semibold))
                            Text(L(option.key))
                                .font(.subheadline.weight(selected ? .semibold : .regular))
                                .lineLimit(1)
                                .minimumScaleFactor(0.8)
                        }
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, Spacing.md)
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
        VStack(alignment: .leading, spacing: Spacing.sm) {
            Text(L("settings_accent")).font(.body)
            HStack(spacing: 0) {
                ForEach(AccentColor.allCases) { accent in
                    let selected = accent == selection
                    let seed = accent.seedColor
                    Button {
                        withAnimation(.spring(duration: 0.3)) { onSelect(accent) }
                    } label: {
                        VStack(spacing: Spacing.xs) {
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
                                        .foregroundStyle(AppColors.onVivid)
                                        .transition(.scale.combined(with: .opacity))
                                }
                            }
                            .padding(4)
                            .overlay(Circle().strokeBorder(selected ? seed : .clear, lineWidth: 2.5))
                            .scaleEffect(selected ? 1.06 : 1)

                            // Fünf gleich breite Spalten: lange Namen («Marrs Green») brechen auf
                            // schmalen Geräten (iPhone SE) in die zweite Zeile um.
                            Text(L(accent.labelKey))
                                .font(.caption.weight(selected ? .semibold : .regular))
                                .foregroundStyle(selected ? AppColors.onSurface : AppColors.onSurfaceVariant)
                                .multilineTextAlignment(.center)
                                .lineLimit(2)
                                .minimumScaleFactor(0.85)
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

    /// Wie Android: Komma oder Punkt, 0 – 100; arabische/persische Ziffern gelten
    /// (`ThresholdParser.latinDigits`, auch für den vorbefüllten Wert aus `format`).
    static func parse(_ text: String) -> Double? {
        let cleaned = ThresholdParser.latinDigits(text).trimmingCharacters(in: .whitespaces)
            .replacingOccurrences(of: "%", with: "")
            .replacingOccurrences(of: ",", with: ".")
        guard let v = Double(cleaned), v >= 0, v <= 100 else { return nil }
        return v
    }
}

/// Umrechnungswährung als Menü — gilt für die umgerechneten Kurse der Merkliste,
/// Alarme in eigener Währung und das Portfolio (wie `ConversionCurrencyRow` in Android).
@MainActor
struct ConversionCurrencyRow: View {
    let selection: String
    let onSelect: (String) -> Void

    @Environment(\.appAccent) private var accent

    /// Eine früher gesetzte, nicht mehr gelistete Währung trotzdem anzeigen.
    private var codes: [String] {
        FxRateSource.currencies.contains(selection) ? FxRateSource.currencies : FxRateSource.currencies + [selection]
    }

    var body: some View {
        Menu {
            Picker(L("settings_conversion_currency"), selection: Binding(
                get: { selection },
                set: { code in if code != selection { onSelect(code) } }
            )) {
                ForEach(codes, id: \.self) { code in
                    Text(code).tag(code)
                }
            }
        } label: {
            HStack(spacing: 12) {
                Image(systemName: "dollarsign.arrow.circlepath")
                    .font(.body.weight(.medium))
                    .foregroundStyle(accent.primary)
                    .frame(width: 26)
                Text(L("settings_conversion_currency"))
                    .font(.body)
                    .foregroundStyle(AppColors.onSurface)
                Spacer(minLength: 8)
                Text(selection)
                    .font(.body.monospacedDigit())
                    .foregroundStyle(AppColors.onSurfaceVariant)
                Image(systemName: "chevron.up.chevron.down")
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(AppColors.outline)
            }
            .padding(.vertical, 12)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(L("settings_conversion_currency"))
        .accessibilityValue(selection)
    }
}
