import SwiftUI

/// Aktionen im Aktionsblatt: Hauptkacheln, Live-Aktivität, Sortieren. Wie `WatchSheetActions.kt`.
extension WatchActionsSheet {
    // MARK: Hauptaktionen

    /// Alarm, «Warum?» (mit ⚡ bei Signalen) und Favorit als drei gleich grosse Kacheln.
    func primaryActions(_ watch: Watch, alarmCount: Int) -> some View {
        HStack(spacing: 8) {
            WatchActionTile(
                systemImage: alarmCount > 0 ? "alarm.fill" : "alarm",
                title: alarmCount > 0 ? L("watchlist_alarms_count", count: alarmCount) : L("watch_action_alarm")
            ) {
                WatchlistHaptics.selection()
                onOpenAlarms(watch.id)
                dismiss()
            }
            // Nicht mehr gehandelt: kein «Warum?» (es gäbe nur alte Daten)
            if !watch.isNotTraded {
                WatchActionTile(
                    systemImage: "lightbulb",
                    title: L("watch_action_why"),
                    badge: hasActivity
                ) {
                    WatchlistHaptics.selection()
                    onWhy(watch.id)
                    dismiss()
                }
            }
            WatchActionTile(
                systemImage: watch.favorite ? "star.fill" : "star",
                title: L("watch_action_favorite"),
                selected: watch.favorite
            ) {
                WatchlistHaptics.impact()
                data.toggleFavorite(watch)
            }
        }
        // Gleich hoch, auch wenn ein Text umbricht
        .fixedSize(horizontal: false, vertical: true)
    }

    // MARK: Live-Aktivität

    /// «Auf dem Sperrbildschirm zeigen» / «Vom Sperrbildschirm entfernen» (ein Paar gleichzeitig).
    func liveActivityCard(_ watch: Watch) -> some View {
        VStack(alignment: .leading, spacing: Spacing.xs) {
            Button {
                WatchlistHaptics.selection()
                toggleLiveActivity(watch)
            } label: {
                HStack(spacing: 12) {
                    Image(systemName: liveActivityOn ? "lock.rectangle.on.rectangle.fill" : "lock.rectangle.on.rectangle")
                        .font(.body.weight(.medium))
                        .foregroundStyle(accent.primary)
                        .frame(width: 26)
                    Text(L(liveActivityOn ? "live_activity_stop" : "live_activity_start"))
                        .font(.body.weight(.medium))
                        .foregroundStyle(AppColors.onSurface)
                        .fixedSize(horizontal: false, vertical: true)
                    Spacer(minLength: 8)
                    if liveActivityOn {
                        Image(systemName: "checkmark.circle.fill")
                            .foregroundStyle(accent.primary)
                            .accessibilityHidden(true)
                    }
                }
                .padding(.vertical, 12)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            Text(L(liveActivityDisabled ? "live_activity_disabled" : "live_activity_hint"))
                .font(.footnote)
                .foregroundStyle(liveActivityDisabled ? AppColors.error : AppColors.onSurfaceVariant)
                .fixedSize(horizontal: false, vertical: true)
                .padding(.bottom, 12)
        }
        .padding(.horizontal, 16)
        .background(AppColors.container, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
        .task(id: watch.id) {
            liveActivityOn = LiveActivityController.isRunning(watchId: watch.id)
        }
    }

    private func toggleLiveActivity(_ watch: Watch) {
        let id = watch.id
        Task { @MainActor in
            if LiveActivityController.isRunning(watchId: id) {
                await LiveActivityController.stop(watchId: id)
                liveActivityOn = false
                liveActivityDisabled = false
                return
            }
            switch await LiveActivityController.start(watch) {
            case .started:
                liveActivityOn = true
                liveActivityDisabled = false
            case .disabled:
                liveActivityOn = false
                liveActivityDisabled = true
            case .failed:
                liveActivityOn = false
            }
        }
    }

    // MARK: Sortieren

    func moveRow(_ watch: Watch) -> some View {
        // Favoriten bleiben oben: Grenzen innerhalb der eigenen Abteilung. In der
        // gefilterten Ansicht nur unter den sichtbaren Paaren der Gruppe; gehört das
        // Paar (nach einem Gruppenwechsel) nicht mehr dazu, gilt die ganze Liste.
        let selected = data.selectedWatchlistGroup
        let filter = selected != nil && watch.groupName == selected ? selected : nil
        let section = data.watches.filter {
            $0.favorite == watch.favorite && (filter == nil || $0.groupName == filter)
        }
        let index = section.firstIndex { $0.id == watch.id } ?? 0
        let atTop = index == 0
        let atBottom = index >= section.count - 1
        return HStack(spacing: 8) {
            moveButton("arrow.up.to.line", "sort_move_top", disabled: atTop) { data.move(watch, .top, group: filter) }
            moveButton("arrow.up", "sort_move_up", disabled: atTop) { data.move(watch, .up, group: filter) }
            moveButton("arrow.down", "sort_move_down", disabled: atBottom) { data.move(watch, .down, group: filter) }
            moveButton("arrow.down.to.line", "sort_move_bottom", disabled: atBottom) { data.move(watch, .bottom, group: filter) }
        }
    }

    private func moveButton(_ symbol: String, _ key: String, disabled: Bool, action: @escaping () -> Void) -> some View {
        Button {
            WatchlistHaptics.selection()
            withAnimation(.spring(duration: 0.35)) { action() }
        } label: {
            VStack(spacing: Spacing.xs) {
                Image(systemName: symbol).scaledFont(size: 16, weight: .semibold, relativeTo: .callout)
                Text(L(key))
                    .font(.caption2)
                    .lineLimit(2)
                    .multilineTextAlignment(.center)
                    .minimumScaleFactor(0.8)
            }
            .foregroundStyle(disabled ? AppColors.outline : accent.primary)
            .frame(maxWidth: .infinity, minHeight: 64)
            .padding(.horizontal, 4)
            .background(AppColors.container, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(disabled)
        .accessibilityLabel(L(key))
    }
}

/// Grosse getönte Kachel im Aktionsblatt (drei nebeneinander): Symbol über kurzem Text.
/// `badge` = kleines ⚡ (Signale); `selected` = eingeschaltet (Favorit), für VoiceOver «ausgewählt».
private struct WatchActionTile: View {
    let systemImage: String
    let title: String
    var badge = false
    var selected = false
    let action: () -> Void
    @Environment(\.appAccent) var accent

    var body: some View {
        Button(action: action) {
            VStack(spacing: Spacing.xs) {
                Image(systemName: systemImage)
                    .scaledFont(size: 19, weight: .semibold, relativeTo: .body)
                    .dynamicTypeSize(...DynamicTypeSize.xxxLarge)
                    .frame(height: 24)
                    .overlay(alignment: .topTrailing) {
                        if badge {
                            Image(systemName: "bolt.fill")
                                .scaledFont(size: 10, weight: .bold, relativeTo: .caption2)
                                .dynamicTypeSize(...DynamicTypeSize.xxxLarge)
                                .foregroundStyle(AppColors.warning)
                                .offset(x: 10, y: -4)
                        }
                    }
                Text(title)
                    .font(.subheadline.weight(.semibold))
                    .multilineTextAlignment(.center)
                    .lineLimit(2)
                    .minimumScaleFactor(0.8)
            }
            .foregroundStyle(accent.onContainer)
            .padding(.horizontal, Spacing.xs)
            .padding(.vertical, 12)
            .frame(maxWidth: .infinity, minHeight: 76, maxHeight: .infinity)
            .background(accent.container.opacity(selected ? 1 : 0.7), in: RoundedRectangle(cornerRadius: 16, style: .continuous))
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(badge ? title + ", " + L("activity_indicator") : title)
        .accessibilityAddTraits(selected ? AccessibilityTraits([.isButton, .isSelected]) : AccessibilityTraits.isButton)
    }
}
