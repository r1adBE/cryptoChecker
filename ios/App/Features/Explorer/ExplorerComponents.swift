import SwiftUI
import UIKit

// MARK: Schritte

/// Hintergrundfläche eines Schritts (wie `StepCard` in Android) mit dünnem Rand; hervorgehoben
/// ist der Rand in der Themenfarbe.
struct ExplorerStepCard<Content: View>: View {
    var highlighted: Bool = false
    @ViewBuilder var content: () -> Content
    @Environment(\.appAccent) var accent

    private var outline: RoundedRectangle {
        RoundedRectangle(cornerRadius: Radius.large, style: .continuous)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 0, content: content)
            .padding(16)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(AppColors.container, in: outline)
            .overlay(
                outline.strokeBorder(
                    highlighted ? accent.tint(0.35) : AppColors.outlineVariant.opacity(0.35),
                    lineWidth: highlighted ? 1 : 0.5
                )
            )
    }
}

/// Kopfzeile eines Schritts: Kreis mit Nummer (erledigt: Häkchen). Aktiv oder erledigt leuchtet
/// der Kreis in der Themenfarbe, sonst bleibt alles gedämpft.
struct ExplorerStepHeader: View {
    let number: Int
    let title: String
    let active: Bool
    let done: Bool
    @Environment(\.appAccent) var accent
    @Environment(\.onAccent) var onAccent

    private var lit: Bool { active || done }

    var body: some View {
        HStack(spacing: Spacing.sm) {
            badge
                .frame(width: 24, height: 24)
                .animation(.spring(duration: 0.35), value: done)
            Text(title)
                .font(.headline)
                .foregroundStyle(lit ? AppColors.onSurface : AppColors.onSurfaceVariant)
        }
        .padding(.bottom, 12)
    }

    private var badge: some View {
        ZStack {
            Circle()
                .fill(lit ? AnyShapeStyle(accent.primary.gradient) : AnyShapeStyle(AppColors.containerHighest))
            if done {
                Image(systemName: "checkmark")
                    .scaledFont(size: 11, weight: .heavy, relativeTo: .caption)
                    .dynamicTypeSize(...DynamicTypeSize.xxxLarge)
                    .foregroundStyle(onAccent)
                    .transition(.scale.combined(with: .opacity))
            } else {
                Text(verbatim: LocaleNumbers.integer(number))
                    .scaledFont(size: 12, weight: .bold, design: .rounded, relativeTo: .caption)
                    .dynamicTypeSize(...DynamicTypeSize.xxxLarge)
                    .foregroundStyle(lit ? onAccent : AppColors.onSurfaceVariant)
            }
        }
    }
}

/// Kleiner erklärender Text unter einem Schritt.
struct ExplorerHint: View {
    let text: String

    var body: some View {
        Text(text)
            .font(.footnote)
            .foregroundStyle(AppColors.onSurfaceVariant)
            .fixedSize(horizontal: false, vertical: true)
            .padding(.top, 8)
    }
}

/// Fehlermeldung in roter Fläche, rechts «Erneut versuchen».
struct ExplorerErrorRow: View {
    let message: String
    let onRetry: () -> Void
    @Environment(\.appAccent) var accent

    var body: some View {
        HStack(alignment: .center, spacing: 8) {
            Image(systemName: "exclamationmark.triangle.fill")
                .foregroundStyle(AppColors.error)
            messageText
            Button(L("action_retry"), action: onRetry)
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(accent.primary)
                .buttonStyle(.borderless)
        }
        .padding(12)
        .background(AppColors.error.opacity(0.08),
                    in: RoundedRectangle(cornerRadius: Radius.field, style: .continuous))
        .padding(.top, Spacing.sm)
    }

    private var messageText: some View {
        Text(L("check_error_generic_prefix", message))
            .font(.footnote)
            .foregroundStyle(AppColors.error)
            .lineLimit(4)
            .frame(maxWidth: .infinity, alignment: .leading)
    }
}

/// Knopf gibt beim Drücken leicht nach (kleiner, etwas blasser).
struct ExplorerPressStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        let down = configuration.isPressed
        return configuration.label
            .scaleEffect(down ? 0.98 : 1)
            .opacity(down ? 0.85 : 1)
            .animation(.easeOut(duration: 0.15), value: down)
    }
}

// MARK: Register

/// Zwei Register über der Paar-Auswahl: «Paar wählen» | «Mehrere Paare auf einmal», gleich breit
/// und gleich hoch (umbricht der längere Text, wächst das andere mit). Gewählt: graue Fläche,
/// Schrift in der Themenfarbe wie bei den Gruppen-Chips – dieselbe Darstellung wie im Markt-Tab
/// (`RegisterTabs`). Gegenstück zu `ExplorerModeTabs` (Android).
struct ExplorerModeTabs: View {
    let bulk: Bool
    let onSelect: (Bool) -> Void

    var body: some View {
        RegisterTabs(titles: [L("explorer_step_pair"), L("explorer_bulk_title")],
                     selected: bulk ? 1 : 0) { onSelect($0 == 1) }
    }
}

/// Register in einer Reihe (`titles`, gewählt `selected`), alle gleich breit und so hoch wie das
/// höchste (lange Titel umbrechen). Gewählt: graue Fläche mit Schrift in der Themenfarbe wie bei den
/// Gruppen-Chips; VoiceOver hört Tasten mit Zustand «ausgewählt». Verwendet in «Paar hinzufügen» und
/// im Markt-Tab («Jetzt» | «Einordnung» | «Daten»). Gegenstück zu `RegisterTabs` (Android).
struct RegisterTabs: View {
    let titles: [String]
    let selected: Int
    let onSelect: (Int) -> Void
    @Environment(\.appAccent) private var accent

    var body: some View {
        HStack(spacing: 4) {
            ForEach(titles.indices, id: \.self) { position in
                tab(titles[position], at: position)
            }
        }
        // Alle Register strecken sich auf die Höhe des höchsten
        .fixedSize(horizontal: false, vertical: true)
        .padding(4)
        .background(AppColors.container, in: RoundedRectangle(cornerRadius: Radius.field, style: .continuous))
        .padding(.bottom, 12)
    }

    private func tab(_ title: String, at position: Int) -> some View {
        let on = position == selected
        return Button { onSelect(position) } label: {
            Text(title)
                .font(.subheadline.weight(on ? .semibold : .regular))
                .multilineTextAlignment(.center)
                .lineLimit(2)
                .foregroundStyle(on ? accent.onContainer : AppColors.onSurfaceVariant)
                .padding(.horizontal, 8)
                .padding(.vertical, 8)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .frame(minHeight: 44)
                .background(on ? accent.container : Color.clear,
                            in: RoundedRectangle(cornerRadius: Radius.chip, style: .continuous))
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(on ? [.isSelected] : [])
    }
}
