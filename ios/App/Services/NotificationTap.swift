import Foundation
import os
import UserNotifications

/// Abschluss-Rückruf einer Mitteilungs-Delegate-Methode, der in einen MainActor-Task wandert. iOS darf ihn von jedem
/// Thread aus aufrufen; das SDK kennzeichnet ihn aber nicht als `@Sendable`. Darum liegt er in einer Sperre und wird
/// nur unter ihr aufgerufen — so ist die Hülle vom Compiler geprüft `Sendable`. Genau einmal: danach ist er weg.
final class NotificationCompletion: Sendable {
    private let handler: OSAllocatedUnfairLock<(() -> Void)?>

    init(call: @escaping () -> Void) {
        handler = OSAllocatedUnfairLock(uncheckedState: call)
    }

    func call() {
        handler.withLock { pending in
            pending?()
            pending = nil
        }
    }
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
