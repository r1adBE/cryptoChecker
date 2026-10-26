import SwiftUI

// Kleine Teile des Alarm-Bildschirms (`AlarmsScreen`): Ansage einer Alarm-Karte, Beispiel im leeren Zustand und
// Hinweise statt «Neuer Alarm» (DEX-Liquidität, Höchstzahl Alarme). Eigene Datei, damit der Bildschirm kurz bleibt.

/// Satz, «Einmalig»/«Wiederholend», zuletzt ausgelöst, «Ton an», «Ansage an».
enum AlarmA11y {
    static func description(_ alarm: Alarm, title: String, sentence: String?) -> String {
        var parts = [title]
        if let sentence, !sentence.isEmpty { parts.append(sentence) }
        parts.append(L(alarm.repeating ? "alarm_repeating" : "alarm_once"))
        if alarm.lastTriggeredAt > 0 {
            parts.append(L("alarm_last_triggered", MomentText.moment(alarm.lastTriggeredAt)))
        }
        if alarm.sound { parts.append(L("alarm_a11y_sound_on")) }
        if alarm.speak { parts.append(L("alarm_a11y_speak_on")) }
        return parts.joined(separator: ". ")
    }
}

/// Beispielzeile unter dem leeren Alarm-Text (Übersicht und je Paar, L-18) im Stil der Alarm-Sätze:
/// «Zum Beispiel: Sag mir Bescheid, wenn BTC über 90’000 steigt.» — Zahl im Format der Sprache.
enum AlarmsEmptyExample {
    static var text: String { L("alarms_empty_example", LocaleNumbers.integer(90_000, grouping: true)) }
}

/// Hinweis für DEX-Pools mit zu wenig Liquidität: kein Alarm, bestehende Alarme ruhen (`DexLiquidity`). Wie Android.
struct DexLowLiquidityNote: View {
    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(L("dex_low_liquidity_no_alarm"))
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(AppColors.error)
            Text(L("dex_low_liquidity_alarm_hint"))
                .font(.footnote)
                .foregroundStyle(AppColors.onSurfaceVariant)
                .fixedSize(horizontal: false, vertical: true)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .accessibilityElement(children: .combine)
    }
}

/// Quelle ohne Alarme (EZB-Referenzkurse, `Market.allowsAlarms`): statt «Neuer Alarm» dieser Hinweis. Wie Android.
struct ReferenceRateAlarmNote: View {
    var body: some View {
        Text(L("alarms_reference_rate_hint"))
            .font(.subheadline)
            .foregroundStyle(AppColors.onSurfaceVariant)
            .fixedSize(horizontal: false, vertical: true)
            .frame(maxWidth: .infinity, alignment: .leading)
    }
}

/// «Ein Paar kann höchstens 10 Alarme haben.» (Zahl im Format der Sprache, `WatchlistLimits`) — wie Android
/// `alarmLimitText`.
enum AlarmLimitText {
    static var text: String { L("alarms_limit_reached", LocaleNumbers.integer(WatchlistLimits.maxAlarmsPerPair)) }
}

/// Paar hat schon die Höchstzahl Alarme: statt «Neuer Alarm» dieser Hinweis über der Liste. Wie Android `AlarmLimitNote`.
struct AlarmLimitNote: View {
    var body: some View {
        Text(AlarmLimitText.text)
            .font(.subheadline)
            .foregroundStyle(AppColors.onSurfaceVariant)
            .fixedSize(horizontal: false, vertical: true)
            .frame(maxWidth: .infinity, alignment: .leading)
    }
}
