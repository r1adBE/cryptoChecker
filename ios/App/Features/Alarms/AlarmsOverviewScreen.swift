import SwiftUI

/// Übersicht aller Alarme — wie `AlarmsOverviewScreen.kt`: Welche sind scharf,
/// auf welchem Paar? Ein Tipp auf ein Paar öffnet dessen Alarme zum Bearbeiten.
struct AlarmsOverviewScreen: View {
    @EnvironmentObject private var data: AppData
    @Environment(\.appAccent) private var accent
    @ObservedObject private var lock = AppLock.shared
    /// Portfolio-Alarm, der gelöscht werden soll (Rückfrage).
    @State private var deletePortfolio: PortfolioAlarm?

    /// Alarme «Portfolio-Wert» — nur mit eingeschaltetem Portfolio (sonst prüft sie niemand).
    private var portfolioAlarms: [PortfolioAlarm] {
        data.settings.portfolioEnabled ? data.portfolioAlarms : []
    }

    /// Beträge der Portfolio-Alarme verbergen: «Beträge verbergen» oder Portfolio gesperrt.
    private var hidePortfolio: Bool { data.settings.hidePortfolioAmounts || lock.locked }

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
        let portfolio = portfolioAlarms
        let active = groups.reduce(0) { sum, g in sum + g.filter(\.alarm.enabled).count }
            + portfolio.filter(\.enabled).count
        Group {
            if groups.isEmpty && portfolio.isEmpty {
                ScrollView {
                    EmptyStateView(systemImage: "bell.slash", title: L("alarms_overview_empty"))
                        .containerRelativeFrame(.vertical, alignment: .center)
                }
            } else {
                ScrollView {
                    LazyVStack(spacing: 12) {
                        // Alarme «Portfolio-Wert» zuerst, als eigene Karte
                        if !portfolio.isEmpty {
                            portfolioCard(portfolio)
                        }
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
        .confirmationDialog(L("alarm_delete_title"), isPresented: Binding(
            get: { deletePortfolio != nil },
            set: { if !$0 { deletePortfolio = nil } }
        ), titleVisibility: .visible, presenting: deletePortfolio) { alarm in
            Button(L("action_delete"), role: .destructive) {
                withAnimation(.snappy) { data.deletePortfolioAlarm(alarm.id) }
                deletePortfolio = nil
            }
            Button(L("action_cancel"), role: .cancel) { deletePortfolio = nil }
        } message: { alarm in
            Text(PortfolioAlarmTexts.sentence(alarm, basis: data.settings.changeBasis, hidden: hidePortfolio))
        }
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
                    CoinBadge(symbol: watch.baseAsset, size: 38, logo: CoinLogos.allowed(forMarket: watch.marketKey))
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
                    SwitchRow(isOn: Binding(
                        get: { alarm.enabled },
                        set: { enabled in
                            WatchlistHaptics.selection()
                            withAnimation(.snappy) { data.setAlarmEnabled(alarm.id, enabled) }
                        }
                    ), verticalPadding: 8) {
                        HStack(spacing: Spacing.sm) {
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
                    .padding(.horizontal, 16)
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

    /// Karte «Portfolio»: je Alarm Satz und Schalter (ganze Zeile); Löschen über das Kontextmenü.
    private func portfolioCard(_ alarms: [PortfolioAlarm]) -> some View {
        let anyActive = alarms.contains(where: \.enabled)
        return VStack(spacing: 0) {
            HStack(spacing: 12) {
                Image(systemName: "chart.pie")
                    .scaledFont(size: 17, weight: .semibold, relativeTo: .headline)
                    .foregroundStyle(accent.primary)
                    .frame(width: 38, height: 38)
                    .background(accent.primary.opacity(0.14), in: Circle())
                    .accessibilityHidden(true)
                Text(L("portfolio_title"))
                    .font(.headline)
                    .foregroundStyle(AppColors.onSurface)
                    .accessibilityAddTraits(.isHeader)
                Spacer(minLength: 8)
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 12)

            RowDivider().padding(.horizontal, 16)

            VStack(spacing: 0) {
                ForEach(alarms) { alarm in
                    SwitchRow(isOn: Binding(
                        get: { alarm.enabled },
                        set: { enabled in
                            WatchlistHaptics.selection()
                            withAnimation(.snappy) { data.setPortfolioAlarmEnabled(alarm.id, enabled) }
                        }
                    ), verticalPadding: 8) {
                        VStack(alignment: .leading, spacing: 1) {
                            Text(PortfolioAlarmTexts.sentence(alarm, basis: data.settings.changeBasis, hidden: hidePortfolio))
                                .font(.subheadline.monospacedDigit())
                                .foregroundStyle(alarm.enabled ? AppColors.onSurface : AppColors.onSurfaceVariant)
                            Text(L(alarm.repeating ? "alarm_repeating" : "alarm_once"))
                                .font(.caption)
                                .foregroundStyle(AppColors.onSurfaceVariant)
                        }
                    }
                    .padding(.horizontal, 16)
                    .contextMenu {
                        Button(role: .destructive) {
                            deletePortfolio = alarm
                        } label: {
                            Label(L("action_delete"), systemImage: "trash")
                        }
                    }
                    .accessibilityAction(named: Text(L("action_delete"))) { deletePortfolio = alarm }
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
