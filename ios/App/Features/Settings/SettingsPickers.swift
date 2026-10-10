import SwiftUI

/// Auswahlzeile (Einfach-Auswahl), auf allen Auswahlseiten gleich — wie `RadioRow` (Android): ganze
/// Zeile tippbar, die gewählte dezent in der Akzentfarbe hinterlegt (wie der gewählte Gruppen-Chip),
/// Text kräftiger, Häkchen rechts. VoiceOver: «ausgewählt». `leading`: Farbpunkt, Pfeile, Kürzel.
@MainActor
struct SettingsChoiceRow<Leading: View>: View {
    let title: String
    var subtitle: String? = nil
    let selected: Bool
    let leading: Leading
    let action: () -> Void

    @Environment(\.appAccent) private var accent

    init(title: String, subtitle: String? = nil, selected: Bool,
         @ViewBuilder leading: () -> Leading, action: @escaping () -> Void) {
        self.title = title
        self.subtitle = subtitle
        self.selected = selected
        self.leading = leading()
        self.action = action
    }

    var body: some View {
        Button(action: action) {
            HStack(spacing: 12) {
                leading
                VStack(alignment: .leading, spacing: 2) {
                    Text(title)
                        .font(.body.weight(selected ? .semibold : .regular))
                        .foregroundStyle(AppColors.onSurface)
                        .fixedSize(horizontal: false, vertical: true)
                    if let subtitle, !subtitle.isEmpty {
                        Text(subtitle)
                            .font(.footnote)
                            .foregroundStyle(AppColors.onSurfaceVariant)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }
                Spacer(minLength: 8)
                if selected {
                    Image(systemName: "checkmark")
                        .font(.body.weight(.semibold))
                        .foregroundStyle(accent.primary)
                }
            }
            .frame(minHeight: 44)
            .padding(.vertical, Spacing.sm)
            .padding(.horizontal, 12)
            .background(selected ? accent.tint(0.16) : .clear,
                        in: RoundedRectangle(cornerRadius: 12, style: .continuous))
            .contentShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
        }
        .buttonStyle(.plain)
        // Hinterlegung bis nahe an den Kartenrand
        .padding(.horizontal, -8)
        .padding(.vertical, 1)
        .accessibilityAddTraits(selected ? [.isButton, .isSelected] : .isButton)
    }
}

extension SettingsChoiceRow where Leading == EmptyView {
    init(title: String, subtitle: String? = nil, selected: Bool, action: @escaping () -> Void) {
        self.init(title: title, subtitle: subtitle, selected: selected, leading: { EmptyView() }, action: action)
    }
}

/// Suchfeld über langen Auswahllisten (Währung) — wie `ChoiceSearchField` (Android).
@MainActor
struct SettingsChoiceSearchField: View {
    @Binding var query: String
    let placeholder: String

    var body: some View {
        HStack(spacing: 8) {
            Image(systemName: "magnifyingglass")
                .foregroundStyle(AppColors.onSurfaceVariant)
                .accessibilityHidden(true)
            TextField(placeholder, text: $query)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .submitLabel(.search)
            if !query.isEmpty {
                Button { query = "" } label: {
                    Image(systemName: "xmark.circle.fill").foregroundStyle(AppColors.outline)
                }
                .buttonStyle(.plain)
                .accessibilityLabel(L("action_clear"))
            }
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 11)
        .background(AppColors.container, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
    }
}

/// Wie das System, Hell oder Dunkel — gilt für App und Widgets. Als Liste (`SettingsChoiceRow`).
@MainActor
struct SettingsThemePicker: View {
    let selection: Bool?
    let onSelect: (Bool?) -> Void

    private static let options: [(value: Bool?, key: String)] = [
        (nil, "theme_system"),
        (false, "theme_light"),
        (true, "theme_dark"),
    ]

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            ForEach(Array(Self.options.enumerated()), id: \.offset) { _, option in
                SettingsChoiceRow(title: L(option.key), selected: option.value == selection) {
                    if option.value != selection { onSelect(option.value) }
                }
            }
        }
        .padding(.vertical, 4)
        .sensoryFeedback(.selection, trigger: selection)
    }
}

/// Akzentfarben als Liste: Farbpunkt und Name, die gewählte hinterlegt mit Häkchen.
@MainActor
struct SettingsAccentPicker: View {
    let selection: AccentColor
    let onSelect: (AccentColor) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            ForEach(AccentColor.allCases) { accent in
                SettingsChoiceRow(title: L(accent.labelKey), selected: accent == selection) {
                    Circle().fill(accent.seedColor).frame(width: 22, height: 22)
                        .accessibilityHidden(true)
                } action: {
                    if accent != selection { onSelect(accent) }
                }
            }
        }
        .padding(.vertical, 4)
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

    /// Wie Android (`parsePercent`): Komma oder Punkt, 0 – 100, «%»/«٪» (auch «5 %») erlaubt;
    /// arabische/persische Ziffern gelten (`ThresholdParser.latinDigits`, auch für den
    /// vorbefüllten Wert aus `format`); Exponent oder Suffix («1e2», «nan») ungültig.
    static func parse(_ text: String) -> Double? {
        let cleaned = ThresholdParser.latinDigits(text)
            .replacingOccurrences(of: "%", with: "")
            .replacingOccurrences(of: "\u{066A}", with: "")
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .replacingOccurrences(of: ",", with: ".")
        // Ziffern mit höchstens einem Dezimalpunkt, mindestens eine Ziffer
        let digits = cleaned.filter { $0 != "." }
        guard !digits.isEmpty, digits.allSatisfy({ $0.isASCII && $0.isNumber }),
              cleaned.filter({ $0 == "." }).count <= 1,
              let v = Double(cleaned), v >= 0, v <= 100 else { return nil }
        return v
    }
}
