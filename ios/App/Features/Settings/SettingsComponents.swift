import SwiftUI

/// Karte der Unterseiten der Einstellungen — gleiche Fläche wie `SectionCard`, ohne Abstand darunter.
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
            .padding(.bottom, Spacing.sm)
    }
}

/// Zeile der Hauptseite, die zu einer Unterseite führt: Titel links, Wert grau rechts
/// (einzeilig, gekürzt); den Pfeil setzt die Liste. Für VoiceOver «Titel, Wert, Taste».
@MainActor
struct SettingsNavRow<Value: View, Destination: View>: View {
    let title: String
    let valueDescription: String?
    let value: () -> Value
    let destination: () -> Destination

    init(
        title: String,
        valueDescription: String?,
        @ViewBuilder value: @escaping () -> Value,
        @ViewBuilder destination: @escaping () -> Destination
    ) {
        self.title = title
        self.valueDescription = valueDescription
        self.value = value
        self.destination = destination
    }

    var body: some View {
        NavigationLink {
            destination()
        } label: {
            HStack(spacing: 12) {
                Text(title)
                    .font(.body)
                    .foregroundStyle(AppColors.onSurface)
                Spacer(minLength: 8)
                value()
                    .font(.body)
                    .foregroundStyle(AppColors.onSurfaceVariant)
                    .lineLimit(1)
                    .truncationMode(.tail)
            }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(title)
        .accessibilityValue(valueDescription ?? "")
        .accessibilityAddTraits(.isButton)
    }
}

extension SettingsNavRow where Value == Text {
    /// Wert als Text (oder keiner).
    init(title: String, value: String?, @ViewBuilder destination: @escaping () -> Destination) {
        self.init(title: title, valueDescription: value, value: { Text(value ?? "") }, destination: destination)
    }
}

/// Zeile mit Aktion statt Unterseite (Link nach aussen, Blatt): Titel, optional Wert, Symbol rechts.
@MainActor
struct SettingsButtonRow: View {
    let title: String
    var value: String? = nil
    var trailingIcon: String = "chevron.forward"
    var hint: String? = nil
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 12) {
                Text(title)
                    .font(.body)
                    .foregroundStyle(AppColors.onSurface)
                Spacer(minLength: 8)
                if let value, !value.isEmpty {
                    Text(value)
                        .font(.body)
                        .foregroundStyle(AppColors.onSurfaceVariant)
                        .lineLimit(1)
                }
                // Spiegelt sich in Rechts-nach-links-Sprachen automatisch
                Image(systemName: trailingIcon)
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(AppColors.outline)
                    .accessibilityHidden(true)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
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
