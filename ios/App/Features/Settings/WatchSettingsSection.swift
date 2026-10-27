import SwiftUI

/// «Widgets» › Uhr (Build 155): Coin-Liste der Apple-Watch-App vom iPhone aus wählen — «Wie iPhone» (Favoriten ★) oder
/// eine eigene Liste mit bis zu `WatchCoins.max` Coins. Abgleich: `PhoneWatchLink`. Wie Android `WatchSettingsCard`.
@MainActor
struct WatchSettingsSection: View {
    @ObservedObject private var link = PhoneWatchLink.shared
    @Environment(\.appAccent) private var accent

    var body: some View {
        let s = link.settings
        VStack(alignment: .leading, spacing: 8) {
            Text(L("settings_watch"))
                .sectionTitleStyle()
                .accessibilityAddTraits(.isHeader)
            Text(L("ios_settings_watch_hint", WatchCoins.max))
                .font(.footnote)
                .foregroundStyle(AppColors.onSurfaceVariant)
                .fixedSize(horizontal: false, vertical: true)
            ChoiceChips(options: [WatchSettings.Mode.phone, .own], selection: s.mode,
                        label: { L($0 == .phone ? "settings_watch_mode_phone" : "settings_watch_mode_own") },
                        onSelect: { link.setMode($0) })
            // Coins, die die Uhr gerade zeigt
            Text(verbatim: s.shown.isEmpty ? L("settings_watch_none") : s.shown.joined(separator: " · "))
                .font(.footnote)
                .foregroundStyle(AppColors.onSurfaceVariant)
            if s.mode == .own {
                Text(L("settings_watch_own_title", s.own.count, WatchCoins.max)).font(.body)
                FlowLayout(spacing: 8) {
                    ForEach(WatchCoins.top, id: \.self) { coin in
                        coinChip(coin, selected: s.own.contains(coin), full: s.own.count >= WatchCoins.max)
                    }
                }
            }
        }
        .settingsAnchor("widgets.watch")
    }

    private func coinChip(_ coin: String, selected: Bool, full: Bool) -> some View {
        Button { link.toggleOwn(coin) } label: {
            Text(verbatim: coin)
                .font(.subheadline.weight(selected ? .semibold : .regular))
                .padding(.horizontal, Spacing.md)
                .padding(.vertical, 8)
                .foregroundStyle(selected ? accent.onContainer : AppColors.onSurface)
                .background(selected ? accent.container : AppColors.containerHigh, in: Capsule())
                .overlay(Capsule().strokeBorder(selected ? accent.primary.opacity(0.6) : .clear, lineWidth: 1))
                .frame(minHeight: 44)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(!selected && full)
        .accessibilityAddTraits(selected ? .isSelected : [])
    }
}
