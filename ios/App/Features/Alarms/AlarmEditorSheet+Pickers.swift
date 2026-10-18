import SwiftUI

// Auswahl-Chips im Alarm-Blatt: Faktor des Volumen-Spikes, Zeitraum «Nahe am Hoch/Tief» und Zeitfenster.
// Eigene Datei neben `AlarmAdvancedOptions.swift` («Mehr Bedingungen» und «Erweitert»).

extension AlarmEditorSheet {
    /// Volumen-Spike: Faktor als Chips statt Eingabefeld, darunter der Hinweis zur Quelle.
    var volumeFactorPicker: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(L("alarm_volume_factor_label"))
                .font(.subheadline.weight(.medium))
                .foregroundStyle(AppColors.onSurfaceVariant)
            HStack(spacing: 8) {
                ForEach(AlarmDraft.volumeFactors, id: \.self) { factor in
                    factorChip(factor)
                }
            }
            Text(L("alarm_volume_hint"))
                .font(.footnote)
                .foregroundStyle(AppColors.onSurfaceVariant)
                .fixedSize(horizontal: false, vertical: true)
        }
    }

    private func factorChip(_ factor: Double) -> some View {
        let selected = draft.threshold == factor
        return Button {
            WatchlistHaptics.selection()
            draft.thresholdText = AlarmDraft.formatFactor(factor)
        } label: {
            Text("×" + AlarmDraft.formatFactor(factor))
                .font(.subheadline.weight(selected ? .semibold : .regular).monospacedDigit())
                .lineLimit(1)
                .foregroundStyle(selected ? accent.onContainer : AppColors.onSurface)
                .frame(maxWidth: .infinity)
                .padding(.vertical, Spacing.md)
                .background(selected ? accent.container : AppColors.container, in: Capsule())
                .overlay(Capsule().strokeBorder(selected ? accent.primary.opacity(0.6) : .clear, lineWidth: 1))
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(selected ? .isSelected : [])
    }

    /// «Nahe am Hoch/Tief»: Zeitraum als Chips, darunter der Hinweis zu neuen Hochs/Tiefs.
    var nearWindowPicker: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(L("alarm_near_window_label"))
                .font(.subheadline.weight(.medium))
                .foregroundStyle(AppColors.onSurfaceVariant)
            HStack(spacing: 8) {
                ForEach(NearExtreme.windows, id: \.self) { days in
                    nearWindowChip(days)
                }
            }
            Text(L("alarm_near_hint"))
                .font(.footnote)
                .foregroundStyle(AppColors.onSurfaceVariant)
                .fixedSize(horizontal: false, vertical: true)
        }
    }

    private func nearWindowChip(_ days: Int) -> some View {
        let selected = NearExtreme.windowDays(draft.windowHours) == days
        let weight: Font.Weight = selected ? Font.Weight.semibold : Font.Weight.regular
        return Button {
            WatchlistHaptics.selection()
            draft.windowHours = days
        } label: {
            Text(AlarmTexts.windowLabel(days))
                .font(.subheadline.weight(weight).monospacedDigit())
                .lineLimit(1)
                .minimumScaleFactor(0.8)
                .foregroundStyle(selected ? accent.onContainer : AppColors.onSurface)
                .frame(maxWidth: .infinity)
                .padding(.vertical, Spacing.md)
                .background(selected ? accent.container : AppColors.container, in: Capsule())
                .overlay(Capsule().strokeBorder(selected ? accent.primary.opacity(0.6) : .clear, lineWidth: 1))
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(selected ? .isSelected : [])
    }

    func windowChip(_ hours: Int) -> some View {
        let selected = draft.windowHours == hours
        return Button {
            WatchlistHaptics.selection()
            draft.windowHours = hours
        } label: {
            Text(L("alarm_window_hours", count: hours))
                .font(.subheadline.weight(selected ? .semibold : .regular).monospacedDigit())
                .lineLimit(1)
                .minimumScaleFactor(0.8)
                .foregroundStyle(selected ? accent.onContainer : AppColors.onSurface)
                .frame(maxWidth: .infinity)
                .padding(.vertical, Spacing.md)
                .background(selected ? accent.container : AppColors.container, in: Capsule())
                .overlay(Capsule().strokeBorder(selected ? accent.primary.opacity(0.6) : .clear, lineWidth: 1))
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(selected ? .isSelected : [])
    }
}
