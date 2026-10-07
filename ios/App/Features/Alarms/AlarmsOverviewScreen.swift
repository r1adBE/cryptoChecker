import SwiftUI

/// Übersicht aller Alarme — wie `AlarmsOverviewScreen.kt`: Welche sind scharf,
/// auf welchem Paar? Ein Tipp auf ein Paar öffnet dessen Alarme zum Bearbeiten.
struct AlarmsOverviewScreen: View {
    @EnvironmentObject private var data: AppData
    @Environment(\.appAccent) private var accent

    init() {}

    /// Nach Paar gruppiert: Gruppen mit scharfen Alarmen zuerst, dann nach Name;
    /// innerhalb scharfe zuerst, dann nach Id.
    private var groups: [[AlarmWithWatch]] {
        Dictionary(grouping: data.alarmsWithWatch, by: \.watch.id)
            .values
            .map { group in
                group.sorted {
                    if $0.alarm.enabled != $1.alarm.enabled { return $0.alarm.enabled }
                    return $0.alarm.id < $1.alarm.id
                }
            }
            .sorted { a, b in
                let aActive = a.contains { $0.alarm.enabled }
                let bActive = b.contains { $0.alarm.enabled }
                if aActive != bActive { return aActive }
                let an = a.first?.watch.displayName.lowercased() ?? ""
                let bn = b.first?.watch.displayName.lowercased() ?? ""
                if an != bn { return an < bn }
                return (a.first?.watch.id ?? 0) < (b.first?.watch.id ?? 0)
            }
    }

    var body: some View {
        let groups = self.groups
        let active = groups.reduce(0) { sum, g in sum + g.filter(\.alarm.enabled).count }
        Group {
            if groups.isEmpty {
                ScrollView {
                    EmptyStateView(systemImage: "bell.slash", title: L("alarms_overview_empty"))
                        .containerRelativeFrame(.vertical, alignment: .center)
                }
            } else {
                ScrollView {
                    LazyVStack(spacing: 12) {
                        ForEach(groups, id: \.first?.watch.id) { group in
                            if let watch = group.first?.watch {
                                groupCard(watch: watch, items: group)
                            }
                        }
                    }
                    .padding(.horizontal, 16)
                    .padding(.top, 8)
                    .padding(.bottom, 24)
                    // iPad/Querformat: Inhalt höchstens 640 pt breit, mittig
                    .readableContentWidth()
                }
            }
        }
        .background(AppColors.background.ignoresSafeArea())
        .navigationTitle(L("alarms_overview_title"))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .principal) {
                VStack(spacing: 0) {
                    Text(L("alarms_overview_title")).font(.headline)
                    Text(L("alarms_overview_active", count: active))
                        .font(.caption.monospacedDigit())
                        .foregroundStyle(active > 0 ? accent.primary : AppColors.onSurfaceVariant)
                        .contentTransition(.numericText(value: Double(active)))
                }
                .accessibilityElement(children: .combine)
                .animation(.snappy, value: active)
            }
        }
    }

    private func groupCard(watch: Watch, items: [AlarmWithWatch]) -> some View {
        let anyActive = items.contains { $0.alarm.enabled }
        return VStack(spacing: 0) {
            NavigationLink {
                AlarmsScreen(watchId: watch.id)
            } label: {
                HStack(spacing: 12) {
                    CoinBadge(symbol: watch.baseAsset, size: 38)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(watch.displayName)
                            .font(.headline)
                            .foregroundStyle(AppColors.onSurface)
                            .lineLimit(1)
                        Text(watch.marketName)
                            .font(.footnote)
                            .foregroundStyle(AppColors.onSurfaceVariant)
                    }
                    Spacer(minLength: 8)
                    Text(PriceFormat.priceWithCurrency(watch.lastPrice, watch.quoteAsset))
                        .font(.subheadline.weight(.semibold).monospacedDigit())
                        .foregroundStyle(AppColors.onSurfaceVariant)
                        .lineLimit(1)
                        .minimumScaleFactor(0.7)
                    // Spiegelt sich in Rechts-nach-links-Sprachen automatisch
                    Image(systemName: "chevron.forward")
                        .scaledFont(size: 13, weight: .semibold, relativeTo: .footnote)
                        .foregroundStyle(AppColors.outline)
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 12)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)

            RowDivider().padding(.horizontal, 16)

            VStack(spacing: 0) {
                ForEach(items) { item in
                    let alarm = item.alarm
                    // Ganze Zeile schaltet
                    Toggle(isOn: Binding(
                        get: { alarm.enabled },
                        set: { enabled in
                            WatchlistHaptics.selection()
                            withAnimation(.snappy) { data.setAlarmEnabled(alarm.id, enabled) }
                        }
                    )) {
                        HStack(spacing: 10) {
                            Image(systemName: AlarmStyle.symbol(alarm.condition))
                                .scaledFont(size: 13, weight: .semibold, relativeTo: .footnote)
                                .foregroundStyle(alarm.enabled ? accent.primary : AppColors.outline)
                                .frame(width: 20)
                            VStack(alignment: .leading, spacing: 1) {
                                Text(AlarmStyle.title(alarm, quote: watch.quoteAsset))
                                    .font(.subheadline.monospacedDigit())
                                    .foregroundStyle(alarm.enabled ? AppColors.onSurface : AppColors.onSurfaceVariant)
                                // Alarm als Satz (zweite Zeile)
                                Text(AlarmTexts.sentence(alarm, base: watch.baseAsset, quote: watch.quoteAsset))
                                    .font(.caption)
                                    .foregroundStyle(AppColors.onSurfaceVariant)
                                    .lineLimit(2)
                                    .fixedSize(horizontal: false, vertical: true)
                            }
                        }
                    }
                    .tint(accent.primary)
                    .padding(.horizontal, 16)
                    .padding(.vertical, 8)
                }
            }
            .padding(.vertical, 4)
        }
        .background(AppColors.container, in: RoundedRectangle(cornerRadius: 20, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: 20, style: .continuous)
                .strokeBorder(anyActive ? accent.primary.opacity(0.35) : AppColors.outlineVariant.opacity(0.6), lineWidth: 1)
        )
    }
}
