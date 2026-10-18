import Foundation

/// Bestätigung nach dem Speichern eines Alarms (Banner unten): «Alarm gespeichert: BTC Kurs über
/// 70’000 USDT · einmalig», bei ausgeschalteten Mitteilungen dazu « · Mitteilungen aus» (mit Aktion
/// «Einstellungen») — wie `AlarmSavedText.kt` (Android).
enum AlarmSavedText {
    static let separator = " · "

    /// Teil hinter «Alarm gespeichert: »: Bedingung, Häufigkeit (erster Buchstabe klein, mitten im
    /// Satz: «Einmalig» → «einmalig») und ggf. der Hinweis auf ausgeschaltete Mitteilungen (nil = erlaubt).
    static func detail(condition: String, frequency: String, notificationsOff: String?,
                       locale: Locale = .current) -> String {
        let trimmed = frequency.trimmingCharacters(in: .whitespaces)
        let lowered = trimmed.prefix(1).lowercased(with: locale) + trimmed.dropFirst()
        return [condition.trimmingCharacters(in: .whitespaces), lowered,
                notificationsOff?.trimmingCharacters(in: .whitespaces) ?? ""]
            .filter { !$0.isEmpty }
            .joined(separator: separator)
    }

    /// Bedingung eines Paar-Alarms: Basis-Asset vor dem Titel der Alarmkarte («BTC» + «Kurs über 70’000 USDT»).
    static func pairCondition(symbol: String, title: String) -> String {
        [symbol.trimmingCharacters(in: .whitespaces), title.trimmingCharacters(in: .whitespaces)]
            .filter { !$0.isEmpty }
            .joined(separator: " ")
    }

    /// Ganzer Text mit den Texten der App («Einmalig»/«Wiederholend», «Mitteilungen aus»).
    static func text(condition: String, repeating: Bool, notificationsOff: Bool) -> String {
        L("alarm_saved_banner", detail(condition: condition, frequency: L(repeating ? "alarm_repeating" : "alarm_once"),
                                       notificationsOff: notificationsOff ? L("alarm_saved_notifications_off") : nil))
    }
}
