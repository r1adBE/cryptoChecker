import SwiftUI

// Karten über den Paaren der Merkliste (Teil von `WatchlistScreen`, gezeigt in WatchlistList.swift): «Ersten Alarm
// setzen» und der Hinweis zu den Gesten.

extension WatchlistScreen {
    /// Karte «Ersten Alarm setzen» (`FirstAlarmCard`): ganze Karte antippbar (Alarme des ersten Paars, ohne
    /// Alarm gleich der Editor), ✕ schliesst sie für immer; der Titel nennt das Paar — wie Android `FirstAlarmCardView`.
    func firstAlarmCard(first: Watch) -> some View {
        HStack(alignment: .center, spacing: Spacing.sm) {
            Button {
                alarmsFor = first.id
            } label: {
                HStack(alignment: .top, spacing: Spacing.sm) {
                    Image(systemName: "bell.badge")
                        .scaledFont(size: 15, weight: .semibold, relativeTo: .subheadline)
                        .foregroundStyle(accent.primary)
                        .padding(.top, 1)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(L("watchlist_first_alarm_title_pair", first.displayName))
                            .font(.subheadline.weight(.semibold))
                            .foregroundStyle(accent.primary)
                        // iOS-eigene Fassung: bei geschlossener App entscheidet iOS, wann geprüft wird (wie `alarm_timing_hint_ios`)
                        Text(L("watchlist_first_alarm_text_ios"))
                            .font(.footnote)
                            .foregroundStyle(AppColors.onSurface)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityElement(children: .combine)
            .accessibilityAddTraits(.isButton)
            Button {
                withAnimation { data.settings.firstAlarmCardDismissed = true }
            } label: {
                Image(systemName: "xmark")
                    .scaledFont(size: 11, weight: .bold, relativeTo: .caption)
                    .dynamicTypeSize(...DynamicTypeSize.xxxLarge)
                    .foregroundStyle(AppColors.onSurfaceVariant)
                    .frame(width: 26, height: 26)
                    .background(AppColors.containerHigh, in: Circle())
                    // Sichtbar klein, Tippfläche 44 × 44 pt (Apple-Richtlinie)
                    .frame(width: 44, height: 44)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.borderless)
            .padding(.vertical, -9)
            .accessibilityLabel(L("action_close"))
        }
        .padding(.leading, Spacing.md)
        .padding(.trailing, Spacing.sm)
        .padding(.vertical, Spacing.md)
        .background(accent.tint(0.12), in: RoundedRectangle(cornerRadius: Radius.card, style: .continuous))
    }

    /// Kurzer Hinweis zu den Gesten mit Schliessen-Knopf. Zuerst eine Zeile, was ein Tipp bringt («Was bewegt
    /// sich mit?», sonst kaum zu entdecken), darunter die Gesten — wie Android `GestureHint`.
    var gestureHint: some View {
        HStack(alignment: .top, spacing: Spacing.sm) {
            Image(systemName: "hand.tap")
                .scaledFont(size: 15, weight: .semibold, relativeTo: .subheadline)
                .foregroundStyle(accent.primary)
                .padding(.top, 1)
            VStack(alignment: .leading, spacing: 2) {
                Text(L("watch_hint_tap_why"))
                    .font(.footnote.weight(.semibold))
                Text(L("watch_gesture_hint_short"))
                    .font(.footnote)
            }
            .foregroundStyle(AppColors.onSurface)
            .frame(maxWidth: .infinity, alignment: .leading)
            Button {
                withAnimation { data.settings.gestureHintSeen = true }
            } label: {
                Image(systemName: "xmark")
                    .scaledFont(size: 11, weight: .bold, relativeTo: .caption)
                    .dynamicTypeSize(...DynamicTypeSize.xxxLarge)
                    .foregroundStyle(AppColors.onSurfaceVariant)
                    .frame(width: 26, height: 26)
                    .background(AppColors.containerHigh, in: Circle())
                    // Sichtbar klein, Tippfläche 44 × 44 pt (Apple-Richtlinie)
                    .frame(width: 44, height: 44)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.borderless)
            // Die grössere Tippfläche ragt in den Rand des Hinweises, statt ihn höher zu machen
            .padding(.vertical, -9)
            .accessibilityLabel(L("action_close"))
        }
        .padding(.leading, Spacing.md)
        .padding(.trailing, Spacing.sm)
        .padding(.vertical, Spacing.md)
        .background(accent.tint(0.08), in: RoundedRectangle(cornerRadius: Radius.card, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: Radius.card, style: .continuous)
                .strokeBorder(accent.tint(0.2), lineWidth: 1)
        )
    }
}
