import SwiftUI

/// Abschnitt «Alarme» der Paarseite (`AlarmsScreen(embedded: true)`) — wie Android `PairAlarmsSection`: Überschrift,
/// Hinweise (Mitteilungen aus, Referenzkurs, DEX-Pool zu dünn, Höchstzahl), «Zuletzt geprüft 14:32» und die Karten.
/// Eine Spalte statt einer Liste (die Seite scrollt selbst); Löschen über das Kontextmenü bzw. die VoiceOver-Aktion der
/// Karte, mit Rückfrage.
extension AlarmsScreen {
    var embeddedSection: some View {
        let watch = data.watch(watchId)
        let alarms = data.alarms(for: watchId)
        let lowLiquidity = Self.lowLiquidity(watch)
        let referenceRate = Self.referenceRate(watch)
        let blocked = lowLiquidity || referenceRate
        return VStack(alignment: .leading, spacing: Spacing.sm) {
            Text(L("alarms_title"))
                .font(.title3.weight(.semibold))
                .accessibilityAddTraits(.isHeader)
            if !alarms.isEmpty {
                NotificationsOffBanner()
            }
            if referenceRate {
                ReferenceRateAlarmNote()
            } else if lowLiquidity {
                DexLowLiquidityNote()
            } else if !WatchlistLimits.canAddAlarm(alarms.count) {
                AlarmLimitNote()
            }
            if alarms.isEmpty {
                if !blocked {
                    Text(L("alarms_empty"))
                        .font(.subheadline)
                        .foregroundStyle(AppColors.onSurfaceVariant)
                }
            } else {
                // «Zuletzt geprüft 14:32» wie in «Alarme», Übersicht, Editor und Android
                AlarmLastCheckedLine()
                ForEach(alarms) { alarm in
                    AlarmCard(
                        alarm: alarm,
                        base: watch?.baseAsset ?? "",
                        quote: watch?.quoteAsset ?? "",
                        unchecked: checkStatus.unchecked(alarm.id),
                        status: AlarmCardStatus.of(
                            alarmEnabled: alarm.enabled,
                            notificationsAllowed: notificationsAllowed,
                            dexBlocksAlarms: lowLiquidity,
                            exchangeTime: watch?.exchangeTime,
                            lastUpdate: watch?.lastUpdate ?? 0,
                            now: now,
                            staleAfterMillis: data.staleAfterMillis,
                            unchecked: checkStatus.unchecked(alarm.id) != nil,
                            markFallback: watch.map { MarkFallbackStore.contains($0.id) } ?? false
                        ),
                        onToggle: { enabled in toggleAlarm(alarm.id, enabled: enabled) },
                        onEdit: { if !blocked { draft = AlarmDraft.from(alarm) } },
                        onDelete: { askDelete = alarm }
                    )
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .animation(reduceMotionAnimation, value: alarms)
    }

    /// Karten weich ein- und ausblenden, bei reduzierter Bewegung sofort.
    private var reduceMotionAnimation: Animation? {
        reduceMotion ? nil : .spring(duration: 0.35)
    }
}
