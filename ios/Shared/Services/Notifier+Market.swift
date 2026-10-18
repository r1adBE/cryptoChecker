import Foundation
import UserNotifications

// Markt-Mitteilungen (Teil von `Notifier`): ungewöhnliche Aktivität, Fear & Greed, Indikatoren, Gas und Depeg — wie
// `AppNotifierMarket.kt` (Android).

extension Notifier {
    // MARK: Ungewöhnliche Aktivität

    static func activityId(_ watchId: Int64) -> String { "activity-\(watchId)" }

    /// «SOL/USDT · Binance» mit dem wichtigsten Signal als Text; eine Meldung je Paar.
    /// Selbst eingeschaltet (Standard aus) — deshalb hörbar, aber nicht zeitkritisch.
    /// - Returns: true, wenn die Mitteilung angenommen wurde (`ActivityMonitor` merkt sie sonst nicht als gemeldet).
    @discardableResult
    static func showActivity(_ watch: Watch, _ signal: ActivitySignal) async -> Bool {
        let content = UNMutableNotificationContent()
        content.title = L("notification_activity_title", watch.displayName, watch.marketName)
        content.body = ActivityTexts.signal(signal)
        content.sound = .default
        content.threadIdentifier = "activity"
        content.userInfo = [userInfoWatchId: NSNumber(value: watch.id)]
        content.interruptionLevel = .active
        let settings = SharedStorage.loadSettings()
        applyQuietHours(content, settings)
        badge(content, settings)
        return await postChecked(id: activityId(watch.id), content: content)
    }

    static func cancelActivity(_ watchId: Int64) {
        center.removeDeliveredNotifications(withIdentifiers: [activityId(watchId)])
        center.removePendingNotificationRequests(withIdentifiers: [activityId(watchId)])
    }

    // MARK: Fear & Greed

    /// Fear & Greed hat die Grenze unter- bzw. überschritten. In der Nachtruhe lautlos — wie Aktivität und Gas.
    /// - Returns: true, wenn die Mitteilung angenommen wurde.
    @discardableResult
    static func showFearGreed(value: Int, below: Int? = nil, above: Int? = nil) async -> Bool {
        let content = UNMutableNotificationContent()
        content.title = L("notification_fng_title", value)
        if let below {
            content.body = L("notification_fng_below", below)
        } else {
            content.body = L("notification_fng_above", above ?? 0)
        }
        content.sound = .default
        content.threadIdentifier = "market"
        let settings = SharedStorage.loadSettings()
        applyQuietHours(content, settings)
        badge(content, settings)
        return await postChecked(id: "fng", content: content)
    }

    /// Eigene Grenze einer Bitcoin-Kennzahl erreicht: Titel «Mayer Multiple: 2.43×», Text «Über deiner Grenze 2.40× –
    /// Messwert, keine Empfehlung.» Eine Mitteilung je Art — wie Android.
    static func showIndicatorAlert(_ kind: IndicatorAlertKind, name: String, value: String, threshold: String) async -> Bool {
        let content = UNMutableNotificationContent()
        content.title = L("notification_indicator_title", name, value)
        content.body = L(kind.above ? "notification_indicator_above" : "notification_indicator_below", threshold)
        content.sound = .default
        content.threadIdentifier = "market"
        let settings = SharedStorage.loadSettings()
        applyQuietHours(content, settings)
        badge(content, settings)
        return await postChecked(id: "indicator_" + kind.rawValue.lowercased(), content: content)
    }

    // MARK: Gas

    /// Netzwerkgebühr ist unter die eingestellte Grenze gefallen (#167).
    /// - Returns: true, wenn die Mitteilung angenommen wurde (`GasAlertCheck` hält den Alarm sonst scharf).
    @discardableResult
    static func showGas(id: String, title: String, body: String) async -> Bool {
        let content = UNMutableNotificationContent()
        content.title = title
        content.body = body
        content.sound = .default
        content.threadIdentifier = "market"
        let settings = SharedStorage.loadSettings()
        applyQuietHours(content, settings)
        badge(content, settings)
        return await postChecked(id: id, content: content)
    }

    // MARK: USDT-Depeg

    static let depegId = "depeg"

    /// USDT weicht um die Schwelle von 1 USD ab — wie `AppNotifier.showDepeg`: Ton der Kursalarme
    /// («Alarm-Signal», zeitkritisch; in der Nachtruhe löst der Alarm gar nicht aus, lautlos nur als Rückfall
    /// an der Grenze). Aktion «Börse öffnen» öffnet die Seite USDC/USDT der Hauptbörse — kein Handel in der App.
    /// - Returns: true, wenn die Mitteilung angenommen wurde (`DepegAlarmCheck` hält den Alarm sonst scharf).
    static func showDepeg(title: String, body: String, exchangeUrl: String, settings: AppSettings) async -> Bool {
        let content = UNMutableNotificationContent()
        content.title = title
        content.body = body
        content.sound = settings.alarmSignal.notificationSound(tone: settings.alarmSound)
        content.categoryIdentifier = categoryDepeg
        content.threadIdentifier = "alarms"
        content.userInfo = [userInfoExchangeUrl: exchangeUrl]
        content.interruptionLevel = .timeSensitive
        applyQuietHours(content, settings)
        badge(content, settings)
        return await postChecked(id: depegId, content: content)
    }
}
