import SwiftUI

// MARK: Karte

/// Ein Alarm: Bedingung, Wiederholung, zuletzt ausgelöst, Schalter.
struct AlarmCard: View {
    let alarm: Alarm
    /// Basis-Symbol des Paars für den Satz («… wenn BTC über …»); leer = kein Satz.
    var base: String = ""
    let quote: String
    /// Zuletzt nicht geprüft (kein Devisenkurs/keine Kerzen); nil = geprüft. Liefert den Text zu `.unchecked`.
    var unchecked: AlarmCheckStatus.Unchecked? = nil
    /// Statuszeile (`AlarmCardStatus`); nil = keine.
    var status: AlarmCardStatus.Kind? = nil
    let onToggle: (Bool) -> Void
    let onEdit: () -> Void
    let onDelete: () -> Void
    @Environment(\.appAccent) private var accent

    var body: some View {
        HStack(spacing: 0) {
            // Nur der Inhalt öffnet den Editor; der Schalter daneben schaltet nur (eigene Tippfläche)
            content
                .padding(.leading, 12)
                .padding(.trailing, Spacing.sm)
                .padding(.vertical, 12)
                .contentShape(Rectangle())
                .onTapGesture(perform: onEdit)
                // VoiceOver: ein Element mit allen Angaben (inkl. Ton, Ansage, Wiederholung)
                .accessibilityElement(children: .ignore)
                .accessibilityLabel(a11yLabel)
                .accessibilityAddTraits(.isButton)
                .accessibilityIdentifier(UiTestTags.alarmCard)
                .accessibilityAction(named: Text(L("action_delete")), onDelete)
                // «Mitteilungen aus»: Einstellungen öffnen wie beim Antippen der Statuszeile
                .accessibilityActions {
                    if status == .notificationsOff && !invalidThreshold {
                        Button(L("alarms_notifications_turn_on")) { NotificationsOffBanner.openSettings() }
                    }
                }

            // Beschriftung nur für VoiceOver: welcher Alarm geschaltet wird. «Schwelle ungültig»
            // lässt sich nicht einschalten (erst im Editor anpassen), wie Android
            SwitchRow(isOn: Binding(get: { alarm.enabled }, set: { onToggle($0) }),
                      enabled: BackupManager.toggleAllowed(alarm.condition, alarm.threshold, enable: !alarm.enabled),
                      verticalPadding: 0, labelHidden: true) {
                Text(AlarmStyle.title(alarm, quote: quote))
            }
            .padding(.trailing, Spacing.md)
        }
        .background(AppColors.container, in: RoundedRectangle(cornerRadius: Radius.large, style: .continuous))
        .contentShape(RoundedRectangle(cornerRadius: Radius.large, style: .continuous))
        .contextMenu {
            Button(action: onEdit) { Label(L("action_edit"), systemImage: "pencil") }
            Button(role: .destructive, action: onDelete) { Label(L("action_delete"), systemImage: "trash") }
        }
        .animation(.easeInOut(duration: 0.2), value: alarm.enabled)
    }

    /// Symbol, Titel, Satz, Wiederholung und die kleinen Symbole für Ton/Ansage/Wiederholung.
    private var content: some View {
        HStack(spacing: 12) {
            Image(systemName: AlarmStyle.symbol(alarm.condition))
                .scaledFont(size: 16, weight: .semibold, relativeTo: .callout)
                .dynamicTypeSize(...DynamicTypeSize.xxxLarge)
                .foregroundStyle(alarm.enabled ? accent.primary : AppColors.outline)
                .frame(width: 40, height: 40)
                .background(
                    (alarm.enabled ? accent.tint(0.14) : AppColors.containerHigh),
                    in: RoundedRectangle(cornerRadius: 12, style: .continuous)
                )

            VStack(alignment: .leading, spacing: 3) {
                Text(AlarmStyle.title(alarm, quote: quote))
                    .font(.headline.monospacedDigit())
                    .foregroundStyle(alarm.enabled ? AppColors.onSurface : AppColors.onSurfaceVariant)
                    .fixedSize(horizontal: false, vertical: true)
                // Alarm als Satz
                if !base.isEmpty {
                    Text(AlarmTexts.sentence(alarm, base: base, quote: quote))
                        .font(.footnote)
                        .foregroundStyle(AppColors.onSurfaceVariant)
                        .lineLimit(2)
                        .fixedSize(horizontal: false, vertical: true)
                }
                // Statuszeile, bis der Grund wegfällt (vorgelesen über die Beschreibung der Karte). «Mitteilungen
                // aus» öffnet beim Antippen die Mitteilungs-Einstellungen (VoiceOver: Aktion auf der Karte).
                if let statusLine {
                    if status == .notificationsOff && !invalidThreshold {
                        Button { NotificationsOffBanner.openSettings() } label: { statusLineView(statusLine) }
                            .buttonStyle(.plain)
                    } else {
                        statusLineView(statusLine)
                    }
                }
                Text(subtitle)
                    .font(.footnote.monospacedDigit())
                    .foregroundStyle(AppColors.onSurfaceVariant)
                HStack(spacing: 8) {
                    if alarm.sound { optionIcon("speaker.wave.2.fill") }
                    if alarm.speak { optionIcon("waveform") }
                    if alarm.repeating { optionIcon("repeat") }
                }
                .padding(.top, 1)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }

    private var a11yLabel: String {
        let sentence = base.isEmpty ? nil : AlarmTexts.sentence(alarm, base: base, quote: quote)
        let text = AlarmA11y.description(alarm, title: AlarmStyle.title(alarm, quote: quote), sentence: sentence)
        return statusLine.map { text + ". " + $0.text } ?? text
    }

    private func statusLineView(_ line: (text: String, symbol: String)) -> some View {
        HStack(alignment: .firstTextBaseline, spacing: 4) {
            Image(systemName: line.symbol)
                .font(.caption)
            Text(line.text)
        }
        .font(.footnote)
        .foregroundStyle(invalidThreshold ? AppColors.error : AppColors.onSurfaceVariant)
        .fixedSize(horizontal: false, vertical: true)
    }

    /// «fällt um ≥ 100 %» löst nie aus — vor allen anderen Gründen, auch ausgeschaltet.
    private var invalidThreshold: Bool { BackupManager.exceedsFall(alarm.condition, alarm.threshold) }

    /// Text und Symbol der Statuszeile; sonst nur bei eingeschalteten Alarmen (das stellt `AlarmCardStatus` sicher).
    private var statusLine: (text: String, symbol: String)? {
        if invalidThreshold { return (L("alarm_threshold_invalid_fix"), "exclamationmark.triangle") }
        switch status {
        case nil: return nil
        case .notificationsOff: return (L("alarm_status_notifications_off"), "bell.slash")
        case .lowLiquidity: return (L("alarm_status_low_liquidity"), "exclamationmark.triangle")
        case .waitingForPrice: return (L("alarm_status_waiting_price"), "arrow.clockwise")
        case .unchecked:
            guard let unchecked else { return nil }
            switch unchecked.reason {
            case .noRate: return (L("alarm_unchecked_no_rate"), "exclamationmark.circle")
            case .noCandles: return (L("alarm_unchecked_no_candles"), "exclamationmark.circle")
            // Funding/Open Interest ohne Werte: vorhandener Text «Keine Futures-Daten …» (wie Android)
            case .noData: return (L("futures_no_data"), "exclamationmark.circle")
            }
        }
    }

    private var subtitle: String {
        var s = L(alarm.repeating ? "alarm_repeating" : "alarm_once")
        if alarm.lastTriggeredAt > 0 {
            // Heute nur die Uhrzeit, sonst mit Datum («zuletzt 12.10.26, 14:02») — wie Android
            s += " · " + L("alarm_last_triggered", MomentText.moment(alarm.lastTriggeredAt))
        }
        return s
    }

    private func optionIcon(_ name: String) -> some View {
        Image(systemName: name)
            .scaledFont(size: 10, weight: .semibold, relativeTo: .caption2)
            .foregroundStyle(AppColors.outline)
    }
}

/// Vorgelesene Beschreibung eines Paar-Alarms — wie `alarmA11yDescription` (Android): Titel,
