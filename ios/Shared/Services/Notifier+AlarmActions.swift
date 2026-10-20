import Foundation
import UIKit
import UserNotifications

// Knöpfe und Bild der Paar-Alarm-Mitteilung (Teil von `Notifier`): «1 h später», «Anpassen» und das Coin-Logo als
// Vorschaubild — wie Android `AlarmNotificationActions.kt`. Die Antworten verarbeitet `AppDelegate`.
extension Notifier {
    /// Paar-Alarm eines nicht gehandelten Paars: nur «Anpassen».
    static let categoryAlarmPair = "alarm_pair"
    /// Paar-Alarm mit «1 h später» (`AlarmSnooze.offeredFor`), Paar nicht gehandelt: «1 h später» und «Anpassen».
    static let categoryAlarmSnooze = "alarm_snooze"
    /// Paar-Alarm mit «1 h später» eines gehandelten Paars: «Was bewegt sich mit?», «1 h später» und «Anpassen».
    static let categoryAlarmWhySnooze = "alarm_why_snooze"

    /// «1 h später»: vermerkt die Pause (`AlarmSnooze`), ohne die App zu öffnen.
    static let actionSnooze = "snooze"
    /// «Anpassen»: öffnet den Editor dieses Alarms.
    static let actionEdit = "edit"
    static let userInfoAlarmId = "alarmId"

    /// Kategorie einer Paar-Alarm-Mitteilung (rein, testbar): «Was bewegt sich mit?» nur für gehandelte Paare,
    /// «1 h später» nur, wo die Pause wirkt (`snooze` = `AlarmSnooze.offeredFor`: wiederholende Prozent- und
    /// Bewegungsalarme sowie Kursmarken). Wie Android.
    static func pairAlarmCategory(traded: Bool, snooze: Bool) -> String {
        switch (traded, snooze) {
        case (true, true): return categoryAlarmWhySnooze
        case (true, false): return categoryAlarmWhy
        case (false, true): return categoryAlarmSnooze
        case (false, false): return categoryAlarmPair
        }
    }

    /// Die vier Kategorien der Paar-Alarme (für `registerCategories`).
    static func pairAlarmCategories() -> [UNNotificationCategory] {
        let why = UNNotificationAction(identifier: actionWhy, title: L("watch_action_why_short"), options: [.foreground])
        // Ohne .foreground: iOS weckt die App kurz im Hintergrund, die Mitteilung verschwindet
        let snooze = UNNotificationAction(identifier: actionSnooze, title: L("notification_alarm_action_snooze"), options: [])
        let edit = UNNotificationAction(identifier: actionEdit, title: L("notification_alarm_action_edit"),
                                        options: [.foreground])
        func category(_ id: String, _ actions: [UNNotificationAction]) -> UNNotificationCategory {
            UNNotificationCategory(identifier: id, actions: actions, intentIdentifiers: [], options: [])
        }
        return [
            category(categoryAlarmPair, [edit]),
            category(categoryAlarmWhy, [why, edit]),
            category(categoryAlarmSnooze, [snooze, edit]),
            category(categoryAlarmWhySnooze, [why, snooze, edit]),
        ]
    }

    /// Coin-Logo als Anhang (Vorschaubild) — nur aus Speicher, Datei oder mitgeliefertem Icon (`CoinLogoStore.stored`),
    /// nie aus dem Netz; DEX-Pools nie (fremde Tokens dürfen «BTC» heissen). iOS verschiebt die Datei eines Anhangs in
    /// seinen eigenen Speicher, darum eine eigene Kopie im temporären Ordner. nil = ohne Bild.
    static func coinLogoAttachment(_ watch: Watch) -> UNNotificationAttachment? {
        guard CoinLogos.allowed(forMarket: watch.marketKey),
              let image = CoinLogoStore.stored(CoinLogoStore.logoKey(for: watch)),
              let data = image.pngData() else { return nil }
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent("alarm-logo-\(UUID().uuidString).png")
        guard (try? data.write(to: url, options: .atomic)) != nil else { return nil }
        return try? UNNotificationAttachment(identifier: "logo", url: url, options: nil)
    }
}
