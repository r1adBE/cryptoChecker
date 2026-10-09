import Foundation
import UserNotifications

/// Alle Mitteilungen der App — wie `AppNotifier.kt`.
///
/// iOS kennt keine dauerhafte Kurs-Mitteilung. Kurs-Mitteilungen erscheinen
/// deshalb als normale, stille Mitteilung, die die vorige desselben Paars ersetzt.
enum Notifier {
    static let categoryAlarm = "alarm"
    /// Kursalarm eines gehandelten Paars: mit der Aktion «Warum?».
    static let categoryAlarmWhy = "alarm_why"
    /// Aktion «Warum?»: öffnet die App mit «Warum bewegt sich das?» des Paars (`AppDelegate`).
    static let actionWhy = "why"
    static let userInfoWatchId = "watchId"

    /// Kategorien der Mitteilungen registrieren (beim Start der App). «Warum?» öffnet die App
    /// im Vordergrund; die Mitteilung selbst bleibt wie bisher (Tipp = zum Paar).
    static func registerCategories() {
        let why = UNNotificationAction(identifier: actionWhy, title: L("watch_action_why"), options: [.foreground])
        center.setNotificationCategories([
            UNNotificationCategory(identifier: categoryAlarm, actions: [], intentIdentifiers: [], options: []),
            UNNotificationCategory(identifier: categoryAlarmWhy, actions: [why], intentIdentifiers: [], options: []),
        ])
    }

    private static var center: UNUserNotificationCenter { .current() }

    static func requestPermission() async -> Bool {
        (try? await center.requestAuthorization(options: [.alert, .sound, .badge])) ?? false
    }

    static func isAuthorized() async -> Bool {
        let s = await center.notificationSettings()
        return s.authorizationStatus == .authorized || s.authorizationStatus == .provisional || s.authorizationStatus == .ephemeral
    }

    // MARK: Zahl am App-Symbol

    private static let badgeKey = "app_icon_badge_count"

    /// «Zahl am App-Symbol»: zählt diese Mitteilung (+1) und gibt die neue Zahl mit — wie
    /// WhatsApp. Zähler im App-Group-Speicher, weil auch die Hintergrund-Aktualisierung meldet.
    /// Einstellung aus: kein Kennzeichen.
    static func badge(_ content: UNMutableNotificationContent, _ settings: AppSettings) {
        guard settings.appIconBadge else { return }
        let defaults = SharedStorage.defaults
        let count = defaults.integer(forKey: badgeKey) + 1
        defaults.set(count, forKey: badgeKey)
        content.badge = NSNumber(value: count)
    }

    /// App geöffnet (oder Einstellung aus): Zahl am App-Symbol auf 0. Die Mitteilungen selbst
    /// bleiben in der Mitteilungszentrale.
    static func clearBadge() {
        SharedStorage.defaults.set(0, forKey: badgeKey)
        center.setBadgeCount(0) { _ in }
    }

    private static func post(id: String, content: UNMutableNotificationContent) {
        let request = UNNotificationRequest(identifier: id, content: content, trigger: nil)
        center.add(request) { _ in }
    }

    // MARK: Kurs

    static func priceId(_ watchId: Int64) -> String { "price-\(watchId)" }
    static func alarmId(_ alarmId: Int64) -> String { "alarm-\(alarmId)" }

    /// Die zweite Zeile bezieht sich auf die vorige Meldung, nicht auf die vorige Abfrage.
    static func showPrice(_ watch: Watch) {
        guard let price = watch.lastPrice else { return }
        let content = UNMutableNotificationContent()
        content.title = L("notification_price_title", watch.baseAsset, PriceFormat.price(price), watch.quoteAsset, watch.marketName)
        content.body = comparisonText(watch, price)
        content.subtitle = PriceFormat.time(watch.lastUpdate)
        content.sound = nil
        content.threadIdentifier = "prices"
        content.userInfo = [userInfoWatchId: NSNumber(value: watch.id)]
        if #available(iOS 15.0, *) { content.interruptionLevel = .passive }
        post(id: priceId(watch.id), content: content)
    }

    private static func comparisonText(_ watch: Watch, _ price: Double) -> String {
        guard let reference = watch.notifiedPrice, reference > 0 else { return L("notification_price_first") }
        let change = (price - reference) / reference * 100
        let refText = PriceFormat.priceWithCurrency(reference, watch.quoteAsset)
        if let age = PriceFormat.age(since: watch.notifiedAt) {
            return L("notification_price_change", PriceFormat.changePercentDetailed(change), refText, age)
        }
        return L("notification_price_change_no_age", PriceFormat.changePercentDetailed(change), refText)
    }

    static func cancelPrice(_ watchId: Int64) {
        center.removeDeliveredNotifications(withIdentifiers: [priceId(watchId)])
    }

    /// Wie `cancelPrice`, für viele Paare in EINEM Aufruf (Aktualisierung mit hunderten Paaren).
    static func cancelPrices(_ watchIds: [Int64]) {
        guard !watchIds.isEmpty else { return }
        center.removeDeliveredNotifications(withIdentifiers: watchIds.map(priceId))
    }

    // MARK: Alarm

    /// `volumeRatio` nur beim Volumen-Spike: Volumen der letzten Stunde im Vergleich zum Schnitt.
    /// `text` = fertiger Meldungstext (z. B. «Nahe am Hoch»); nil = Standardtext.
    static func showAlarm(_ watch: Watch, _ alarm: Alarm, price: Double, volumeRatio: Double? = nil, text: String? = nil) {
        let content = UNMutableNotificationContent()
        content.title = L("notification_alarm_title", watch.displayName, watch.marketName)
        if let text {
            content.body = text
        } else if let volumeRatio {
            // «Volumen 4.2× normal in der letzten Stunde»
            content.body = L("notification_volume_spike_text", AlarmTexts.factor(volumeRatio))
        } else {
            content.body = L("notification_alarm_text", AlarmTexts.describe(alarm), PriceFormat.priceWithCurrency(price, watch.quoteAsset))
        }
        let settings = SharedStorage.loadSettings()
        // «Alarm-Signal»: «Lautlos» = nur Mitteilung (zeitkritisch, ohne Ton); Vibration nach Töne & Haptik
        content.sound = settings.alarmSignal.notificationSound(alarmSound: alarm.sound, tone: settings.alarmSound)
        // «Warum?» nur für gehandelte Paare (sonst gibt es nichts zu erklären)
        content.categoryIdentifier = watch.isNotTraded ? categoryAlarm : categoryAlarmWhy
        content.threadIdentifier = "alarms"
        content.userInfo = [userInfoWatchId: NSNumber(value: watch.id)]
        if #available(iOS 15.0, *) { content.interruptionLevel = .timeSensitive }
        applyQuietHours(content, settings)
        badge(content, settings)
        post(id: alarmId(alarm.id), content: content)
    }

    // MARK: Portfolio-Wert

    static func portfolioAlarmId(_ alarmId: Int64) -> String { "portfolio-alarm-\(alarmId)" }

    /// Alarm «Portfolio-Wert» — wie `AppNotifier.showPortfolioAlarm`: gleicher Ton wie Kursalarme
    /// («Alarm-Signal»), Nachtruhe lautlos; Tipp öffnet den Portfolio-Tab. «Beträge verbergen»
    /// gilt auch hier; mit Portfolio-Sperre nur der Titel (Inhalt erst nach dem Entsperren des Geräts).
    static func showPortfolioAlarm(_ alarm: PortfolioAlarm, measured: Double, settings: AppSettings) {
        // Mit Portfolio-Sperre auch ohne «Beträge verbergen» keine Beträge (Sperrbildschirm)
        let hidden = settings.hidePortfolioAmounts || settings.appLock
        let content = UNMutableNotificationContent()
        content.title = L("notification_portfolio_alarm_title")
        content.body = L("notification_portfolio_alarm_text",
                         PortfolioAlarmTexts.sentence(alarm, basis: settings.changeBasis.storage, hidden: hidden),
                         PortfolioAlarmTexts.measured(alarm, measured: measured, hidden: hidden))
        content.sound = settings.alarmSignal.notificationSound(tone: settings.alarmSound)
        content.categoryIdentifier = categoryAlarm
        content.threadIdentifier = "alarms"
        content.userInfo = ["open": "portfolio"]
        if #available(iOS 15.0, *) { content.interruptionLevel = .timeSensitive }
        applyQuietHours(content, settings)
        badge(content, settings)
        post(id: portfolioAlarmId(alarm.id), content: content)
    }

    /// Probe-Alarm aus den Einstellungen: gleicher Weg wie ein Kursalarm (Ton der
    /// Einstellung, zeitkritisch, Kategorie und Gruppe «alarm») — aber bewusst ohne
    /// Nachtruhe, weil es ein Test ist. Die Ansage macht der Aufrufer (App).
    static let testAlarmId = "alarm-test"

    static func showTestAlarm(settings: AppSettings) {
        let content = UNMutableNotificationContent()
        content.title = L("alarm_test_title")
        content.body = L("alarm_test_text")
        content.sound = settings.alarmSignal.notificationSound(tone: settings.alarmSound)
        content.categoryIdentifier = categoryAlarm
        content.threadIdentifier = "alarms"
        if #available(iOS 15.0, *) { content.interruptionLevel = .timeSensitive }
        post(id: testAlarmId, content: content)
    }

    // MARK: Ungewöhnliche Aktivität

    static func activityId(_ watchId: Int64) -> String { "activity-\(watchId)" }

    /// «SOL/USDT · Binance» mit dem wichtigsten Signal als Text; eine Meldung je Paar.
    /// Selbst eingeschaltet (Standard aus) — deshalb hörbar, aber nicht zeitkritisch.
    static func showActivity(_ watch: Watch, _ signal: ActivitySignal) {
        let content = UNMutableNotificationContent()
        content.title = L("notification_activity_title", watch.displayName, watch.marketName)
        content.body = ActivityTexts.signal(signal)
        content.sound = .default
        content.threadIdentifier = "activity"
        content.userInfo = [userInfoWatchId: NSNumber(value: watch.id)]
        if #available(iOS 15.0, *) { content.interruptionLevel = .active }
        let settings = SharedStorage.loadSettings()
        applyQuietHours(content, settings)
        badge(content, settings)
        post(id: activityId(watch.id), content: content)
    }

    static func cancelActivity(_ watchId: Int64) {
        center.removeDeliveredNotifications(withIdentifiers: [activityId(watchId)])
        center.removePendingNotificationRequests(withIdentifiers: [activityId(watchId)])
    }

    // MARK: Marktphase & Fear & Greed

    /// Die Bitcoin-Marktphase hat gewechselt (Schlüssel der Phasen-Texte, z. B. "zone_bull").
    static func showZoneChange(fromKey: String, toKey: String) {
        let to = L(toKey)
        let content = UNMutableNotificationContent()
        content.title = L("notification_zone_title", to)
        content.body = L("notification_zone_text", L(fromKey), to)
        content.sound = .default
        content.threadIdentifier = "market"
        badge(content, SharedStorage.loadSettings())
        post(id: "zone", content: content)
    }

    static func showFearGreed(value: Int, below: Int? = nil, above: Int? = nil) {
        let content = UNMutableNotificationContent()
        content.title = L("notification_fng_title", value)
        if let below {
            content.body = L("notification_fng_below", below)
        } else {
            content.body = L("notification_fng_above", above ?? 0)
        }
        content.sound = .default
        content.threadIdentifier = "market"
        badge(content, SharedStorage.loadSettings())
        post(id: "fng", content: content)
    }

    // MARK: Gas

    /// Netzwerkgebühr ist unter die eingestellte Grenze gefallen (#167).
    static func showGas(id: String, title: String, body: String) {
        let content = UNMutableNotificationContent()
        content.title = title
        content.body = body
        content.sound = .default
        content.threadIdentifier = "market"
        let settings = SharedStorage.loadSettings()
        applyQuietHours(content, settings)
        badge(content, settings)
        post(id: id, content: content)
    }

    // MARK: Nachtruhe

    /// Während der Nachtruhe lautlos zustellen: kein Ton, nur in der Mitteilungszentrale
    /// (`.passive`) — die Mitteilung selbst kommt trotzdem. Geprüft in der Ortszeit des
    /// Geräts im Moment des Zustellens.
    static func applyQuietHours(_ content: UNMutableNotificationContent, _ settings: AppSettings, date: Date = Date()) {
        guard QuietHours.isQuietNow(settings, date: date) else { return }
        content.sound = nil
        if #available(iOS 15.0, *) { content.interruptionLevel = .passive }
    }
}
