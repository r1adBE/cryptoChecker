import SwiftUI

/// Karte der Einstellungen (Hauptseite und Unterseiten) — gleiche Fläche wie `SectionCard`, ohne
/// Abstand darunter. Mit `title` steht die Überschrift (Strich in der Themenfarbe, für VoiceOver
/// eine Überschrift) oben in der Karte, wie bei den Karten im Markt-Tab «Jetzt» — wie
/// `SettingsSection`/`GroupCard(title)` (Android).
@MainActor
struct SettingsCard<Content: View>: View {
    var title: String? = nil
    @ViewBuilder var content: () -> Content

    init(title: String? = nil, @ViewBuilder content: @escaping () -> Content) {
        self.title = title
        self.content = content
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            if let title {
                Text(title)
                    .sectionTitleStyle()
                    .accessibilityAddTraits(.isHeader)
                    .accessibilityIdentifier(UiTestTags.settingsSectionTitle)
                    .padding(.top, 8)
                    .padding(.bottom, 4)
            }
            content()
        }
        .appCard(padding: .list)
    }
}

/// Masse der Zeilen der Hauptseite (Karten statt Liste): so hoch wie bisher die Listenzeilen. Innenabstand oben und
/// unten `Spacing.sm` wie Android (`SettingsNavRow`: vertical 8 dp) — eine Zeile mit Wert ist so auf beiden gleich hoch.
enum SettingsRowMetrics {
    static let minHeight: CGFloat = 52
}

/// Symbol links in einer Zeile der Hauptseite (Unterseiten ohne): SF Symbol, eigenes Bild aus den
/// Assets (wie die Tabs) oder die Kerzen der Charts.
enum SettingsRowIcon {
    case system(String)
    case asset(String)
    case candles
}

/// Zeichnet ein `SettingsRowIcon` in 22 pt; Farbe gibt die Zeile vor (Textfarbe, bei «App zurücksetzen» Rot).
/// Rein schmückend, für VoiceOver ausgeblendet — wie `SettingsLeadingIcon` (Android).
@MainActor
struct SettingsLeadingIcon: View {
    let icon: SettingsRowIcon

    var body: some View {
        symbol
            .frame(width: 22, height: 22)
            .accessibilityHidden(true)
    }

    @ViewBuilder
    private var symbol: some View {
        switch icon {
        case .system(let name):
            Image(systemName: name).font(.system(size: 18, weight: .regular))
        case .asset(let name):
            Image(name).resizable().scaledToFit()
        case .candles:
            CandlesIcon()
        }
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
            .padding(.bottom, Spacing.sm)
    }
}

/// Titel oben, darunter klein (.footnote) und grau der aktuelle Wert — beide mit der ganzen Breite
/// bis zum Pfeil, damit lange Beschriftungen Platz haben. Wie die `Column` in `SettingsNavRow` (Android).
@MainActor
struct SettingsRowTitle<Value: View>: View {
    let title: String
    let showsValue: Bool
    @ViewBuilder let value: () -> Value

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(title)
                .font(.body)
                .foregroundStyle(AppColors.onSurface)
            if showsValue {
                valueLine
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private var valueLine: some View {
        value()
            .font(.footnote)
            .foregroundStyle(AppColors.onSurfaceVariant)
            .lineLimit(2)
            .truncationMode(.tail)
    }
}

/// Zeile der Hauptseite, die zu einer Unterseite führt: Titel oben, darunter klein und grau der
/// Wert (höchstens zwei Zeilen), rechts der Pfeil. Mindestens so hoch wie bisher die Listenzeilen,
/// über die ganze Breite tippbar. Für VoiceOver «Titel, Wert, Taste».
/// `icon`: Symbol links einfarbig in der Textfarbe (weiss im dunklen, schwarz im hellen Design);
/// nil = ohne Symbol.
/// Die Wertzeile erscheint nur, wenn `valueDescription` nicht leer ist.
@MainActor
struct SettingsNavRow<Value: View, Destination: View>: View {
    let title: String
    let icon: SettingsRowIcon?
    let valueDescription: String?
    let value: () -> Value
    let destination: () -> Destination

    init(
        title: String,
        icon: SettingsRowIcon? = nil,
        valueDescription: String?,
        @ViewBuilder value: @escaping () -> Value,
        @ViewBuilder destination: @escaping () -> Destination
    ) {
        self.title = title
        self.icon = icon
        self.valueDescription = valueDescription
        self.value = value
        self.destination = destination
    }

    private var showsValue: Bool {
        !(valueDescription ?? "").isEmpty
    }

    var body: some View {
        NavigationLink {
            destination()
        } label: {
            HStack(spacing: 12) {
                // 12 pt vom Symbol bis zum Titel (wie Android `SettingsLeadingIcon`)
                if let icon {
                    SettingsLeadingIcon(icon: icon)
                        .foregroundStyle(AppColors.onSurface)
                }
                SettingsRowTitle(title: title, showsValue: showsValue, value: value)
                // Spiegelt sich in Rechts-nach-links-Sprachen automatisch
                Image(systemName: "chevron.forward")
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(AppColors.outline)
                    .accessibilityHidden(true)
            }
            .padding(.vertical, Spacing.sm)
            .frame(maxWidth: .infinity, minHeight: SettingsRowMetrics.minHeight, alignment: .leading)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(title)
        .accessibilityValue(valueDescription ?? "")
        .accessibilityAddTraits(.isButton)
    }
}

extension SettingsNavRow where Value == Text {
    /// Wert als Text (oder keiner).
    init(title: String, icon: SettingsRowIcon? = nil, value: String?, @ViewBuilder destination: @escaping () -> Destination) {
        self.init(title: title, icon: icon, valueDescription: value, value: { Text(value ?? "") }, destination: destination)
    }
}

/// Zeile mit Aktion statt Unterseite (Link nach aussen, Blatt): Titel, optional Wert klein darunter, Symbol rechts.
@MainActor
struct SettingsButtonRow: View {
    let title: String
    var value: String? = nil
    var trailingIcon: String = "chevron.forward"
    var hint: String? = nil
    /// Symbol links einfarbig in der Textfarbe; nil = ohne Symbol.
    var icon: SettingsRowIcon? = nil
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 12) {
                // 12 pt vom Symbol bis zum Titel (wie Android `SettingsLeadingIcon`)
                if let icon {
                    SettingsLeadingIcon(icon: icon)
                        .foregroundStyle(AppColors.onSurface)
                }
                // Titel oben, Wert klein und grau darunter (wie `SettingsNavRow`)
                SettingsRowTitle(title: title, showsValue: !(value ?? "").isEmpty) {
                    Text(value ?? "")
                }
                // Spiegelt sich in Rechts-nach-links-Sprachen automatisch
                Image(systemName: trailingIcon)
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(AppColors.outline)
                    .accessibilityHidden(true)
            }
            .padding(.vertical, Spacing.sm)
            .frame(maxWidth: .infinity, minHeight: SettingsRowMetrics.minHeight, alignment: .leading)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(title)
        .accessibilityValue(value ?? "")
        .accessibilityHint(hint ?? "")
        .accessibilityAddTraits(.isButton)
    }
}
