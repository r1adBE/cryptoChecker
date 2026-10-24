import Foundation
import UserNotifications

/// Abschluss-Rückruf einer Mitteilungs-Delegate-Methode, der in einen MainActor-Task wandert. iOS darf ihn von jedem
/// Thread aus aufrufen; das SDK kennzeichnet ihn aber nicht als `@Sendable`. Er wird genau einmal aufgerufen.
struct NotificationCompletion: @unchecked Sendable { // @unchecked: kein Zustand, nur der Rückruf (beliebiger Thread)
    let call: () -> Void
}

/// Was `didReceive` aus der Antwort braucht — sofort ausgelesen, damit nur Sendable-Werte in den MainActor-Task gehen.
struct NotificationTap: Sendable {
    let action: String
    let watchId: Int64?
    let alarmId: Int64?
    let exchangeUrl: URL?
    let openTarget: String?

    init(_ response: UNNotificationResponse) {
        let userInfo = response.notification.request.content.userInfo
        action = response.actionIdentifier
        watchId = (userInfo[Notifier.userInfoWatchId] as? NSNumber)?.int64Value
        alarmId = (userInfo[Notifier.userInfoAlarmId] as? NSNumber)?.int64Value
        exchangeUrl = (userInfo[Notifier.userInfoExchangeUrl] as? String).flatMap { URL(string: $0) }
        openTarget = userInfo["open"] as? String
    }
}
