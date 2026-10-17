import Foundation
import UserNotifications

/// Alle Mitteilungen der App — wie `AppNotifier.kt`.
///
/// iOS kennt keine dauerhafte Kurs-Mitteilung. Kurs-Mitteilungen erscheinen
/// deshalb als normale, stille Mitteilung, die die vorige desselben Paars ersetzt.
enum Notifier {
    static let categoryAlarm = "alarm"
    /// Kursalarm eines gehandelten Paars: mit der Aktion «Was bewegt sich mit?».
    static let categoryAlarmWhy = "alarm_why"
    /// Portfolio-Alarm mit Portfolio-Sperre: neutraler Text auch bei verborgener Vorschau.
    static let categoryPortfolioLocked = "portfolio_locked"
    /// Aktion «Was bewegt sich mit?»: öffnet die App mit dem Blatt des Paars (`AppDelegate`).
    static let actionWhy = "why"
    static let userInfoWatchId = "watchId"
    /// USDT-Depeg-Alarm: mit der Aktion «Börse öffnen» (öffnet `userInfoExchangeUrl`, `AppDelegate`).
    static let categoryDepeg = "depeg"
    static let actionOpenExchange = "open_exchange"
    static let userInfoExchangeUrl = "exchangeUrl"

    /// Kategorien der Mitteilungen registrieren (beim Start der App). «Was bewegt sich mit?» öffnet die App
    /// im Vordergrund; die Mitteilung selbst bleibt wie bisher (Tipp = zum Paar).
    static func registerCategories() {
        let why = UNNotificationAction(identifier: actionWhy, title: L("watch_action_why_short"), options: [.foreground])
        let exchange = UNNotificationAction(identifier: actionOpenExchange,
                                            title: L("notification_depeg_action_exchange"), options: [.foreground])
        center.setNotificationCategories([
            UNNotificationCategory(identifier: categoryAlarm, actions: [], intentIdentifiers: [], options: []),
            UNNotificationCategory(identifier: categoryAlarmWhy, actions: [why], intentIdentifiers: [], options: []),
            UNNotificationCategory(identifier: categoryDepeg, actions: [exchange], intentIdentifiers: [], options: []),
            UNNotificationCategory(identifier: categoryPortfolioLocked, actions: [], intentIdentifiers: [],
                                   hiddenPreviewsBodyPlaceholder: L("notification_portfolio_alarm_locked"),
                                   options: [.hiddenPreviewsShowTitle]),
        ])
    }

    private static var center: UNUserNotificationCenter { .current() }

    static func requestPermission() async -> Bool {
        (try? await center.requestAuthorization(options: [.alert, .sound, .badge])) ?? false
    }

    /// Mitteilungen abgelehnt bzw. in den Einstellungen aus? «Noch nicht gefragt» zählt nicht — dann
    /// läuft die Abfrage gerade (Bestätigung nach dem Speichern eines Alarms: « · Mitteilungen aus»).
    static func isDenied() async -> Bool {
        await center.notificationSettings().authorizationStatus == .denied
    }

    /// Kann eine Mitteilung sichtbar ankommen? Für alle Prüfungen vor dem Auslösen (Alarme, Marktphase …):
    /// erlaubt UND mindestens ein sichtbarer Ort (Banner, Sperrbildschirm oder Mitteilungszentrale).
    static func isAuthorized() async -> Bool {
        let s = await center.notificationSettings()
        return isDeliverable(status: s.authorizationStatus, alert: s.alertSetting,
                             lockScreen: s.lockScreenSetting, notificationCenter: s.notificationCenterSetting)
    }

    /// Reine Regel zu `isAuthorized`: Nur Ton (alles Sichtbare aus) zählt nicht als zugestellt — sonst
    /// wäre ein einmaliger Alarm verbraucht, ohne dass ihn jemand sieht.
    static func isDeliverable(status: UNAuthorizationStatus, alert: UNNotificationSetting,
                              lockScreen: UNNotificationSetting, notificationCenter: UNNotificationSetting) -> Bool {
        guard status == .authorized || status == .provisional || status == .ephemeral else { return false }
        return alert == .enabled || lockScreen == .enabled || notificationCenter == .enabled
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

    /// Wie `post`, wartet aber auf das System: true, wenn die Mitteilung angenommen wurde.
    private static func postChecked(id: String, content: UNMutableNotificationContent) async -> Bool {
        let request = UNNotificationRequest(identifier: id, content: content, trigger: nil)
        do {
            try await center.add(request)
            return true
        } catch {
            return false
        }
    }

    /// Alarm-Mitteilung prüfen: so oft, im Abstand von `verifyStepNanos` (zusammen höchstens rund 1 s).
    private static let verifyAttempts = 10
    private static let verifyStepNanos: UInt64 = 100_000_000

    /// Wie `postChecked`, true aber erst, wenn die Mitteilung unter den zugestellten steht — wie Android
    /// `awaitActive`. Für Alarme: unbestätigt bleiben sie scharf (`AlarmDelivery`).
    private static func postConfirmed(id: String, content: UNMutableNotificationContent) async -> Bool {
        // Eine Sekunde Spielraum: `date` der Mitteilung setzt das System, nicht dieser Prozess
        let postedAt = Date().addingTimeInterval(-1)
        guard await postChecked(id: id, content: content) else { return false }
        return await awaitDelivered(id, since: postedAt)
    }

    /// Steht die Mitteilung `id` (zugestellt ab `since`) unter den zugestellten? Das System führt eine eben
    /// angenommene evtl. erst kurz danach — darum einige Male kurz nachsehen. Das Datum zählt, weil eine
    /// ältere Mitteilung desselben Alarms noch in der Mitteilungszentrale stehen kann. Ist die
    /// Mitteilungszentrale für die App aus, listet iOS dort nichts: dann gilt die Annahme durch das System.
    private static func awaitDelivered(_ id: String, since: Date) async -> Bool {
        guard await center.notificationSettings().notificationCenterSetting == .enabled else { return true }
        for attempt in 0..<verifyAttempts {
            let delivered = await center.deliveredNotifications()
            if delivered.contains(where: { $0.request.identifier == id && $0.date >= since }) { return true }
            if attempt < verifyAttempts - 1 { try? await Task.sleep(nanoseconds: verifyStepNanos) }
        }
        return false
    }

    // MARK: Kurs

    static func priceId(_ watchId: Int64) -> String { "price-\(watchId)" }
    static func alarmId(_ alarmId: Int64) -> String { "alarm-\(alarmId)" }

    /// Kurs-Mitteilung nach einer bewussten Handlung (Paar-Mitteilung ein, «Alle an»): einziger Weg dafür —
    /// nur mit Kurs-Mitteilungen an (`PriceNotificationRule.showNow`), wie Android `AppNotifier.showPriceNow`.
    /// - Returns: true = gezeigt.
    @discardableResult
    static func showPriceNow(_ watch: Watch, settings: AppSettings) -> Bool {
        let show = PriceNotificationRule.showNow(masterOn: settings.priceNotifications, pairOn: watch.notificationEnabled,
                                                 disabled: watch.disabled, notTraded: watch.isNotTraded,
                                                 price: watch.lastPrice)
        if show { showPrice(watch) }
        return show
    }

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
    /// Auch aus der Widget-Erweiterung: Dort liegen die Alarmtöne nicht im Bundle, iOS spielt dann den
    /// Standardton (`UNNotificationSound(named:)` ohne Datei).
    /// - Returns: true erst, wenn die Mitteilung zugestellt ist (`postConfirmed`) — sonst bleibt der Alarm scharf.
    /// `currency`: Währung von `price`, wenn nicht die Quote-Währung des Paars (Schwelle umgerechnet).
    static func showAlarm(_ watch: Watch, _ alarm: Alarm, price: Double, volumeRatio: Double? = nil,
                          text: String? = nil, currency: String? = nil) async -> Bool {
        let content = UNMutableNotificationContent()
        content.title = L("notification_alarm_title", watch.displayName, watch.marketName)
        if let text {
            content.body = text
        } else if let volumeRatio {
            // «Volumen 4.2× normal in der letzten Stunde»
            content.body = L("notification_volume_spike_text", AlarmTexts.factor(volumeRatio))
        } else {
            content.body = L("notification_alarm_text", AlarmTexts.describe(alarm),
                             PriceFormat.priceWithCurrency(price, currency ?? watch.quoteAsset))
        }
        let settings = SharedStorage.loadSettings()
        // «Alarm-Signal»: «Lautlos» = nur Mitteilung (zeitkritisch, ohne Ton); Vibration nach Töne & Haptik
        content.sound = settings.alarmSignal.notificationSound(alarmSound: alarm.sound, tone: settings.alarmSound)
        // «Was bewegt sich mit?» nur für gehandelte Paare (sonst gibt es nichts zu erklären)
        content.categoryIdentifier = watch.isNotTraded ? categoryAlarm : categoryAlarmWhy
        content.threadIdentifier = "alarms"
        content.userInfo = [userInfoWatchId: NSNumber(value: watch.id)]
        if #available(iOS 15.0, *) { content.interruptionLevel = .timeSensitive }
        applyQuietHours(content, settings)
        badge(content, settings)
        return await postConfirmed(id: alarmId(alarm.id), content: content)
    }

    // MARK: Portfolio-Wert

    static func portfolioAlarmId(_ alarmId: Int64) -> String { "portfolio-alarm-\(alarmId)" }

    /// Alarm «Portfolio-Wert» — wie `AppNotifier.showPortfolioAlarm`: gleicher Ton wie Kursalarme
    /// («Alarm-Signal»; in der Nachtruhe löst der Alarm gar nicht aus, lautlos nur als Rückfall an
    /// der Grenze); Tipp öffnet den Portfolio-Tab. «Beträge verbergen» gilt auch hier.
    /// Mit Portfolio-Sperre wie die öffentliche Fassung in Android (VISIBILITY_PRIVATE, nur Titel): iOS kann
    /// den Text nicht je nach Sperrzustand verbergen, darum nur Titel und ein neutraler Text — weder Beträge
    /// noch Prozent; Einzelheiten erst in der App (`PortfolioAlarmNotice.body`).
    /// - Returns: true erst, wenn die Mitteilung zugestellt ist (`postConfirmed`) — sonst bleibt der Alarm scharf.
    static func showPortfolioAlarm(_ alarm: PortfolioAlarm, measured: Double, settings: AppSettings) async -> Bool {
        let content = UNMutableNotificationContent()
        content.title = L("notification_portfolio_alarm_title")
        content.body = PortfolioAlarmNotice.body(alarm, measured: measured, settings: settings)
        content.sound = settings.alarmSignal.notificationSound(tone: settings.alarmSound)
        content.categoryIdentifier = settings.appLock ? categoryPortfolioLocked : categoryAlarm
        content.threadIdentifier = "alarms"
        content.userInfo = ["open": "portfolio"]
        if #available(iOS 15.0, *) { content.interruptionLevel = .timeSensitive }
        applyQuietHours(content, settings)
        badge(content, settings)
        return await postConfirmed(id: portfolioAlarmId(alarm.id), content: content)
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
    /// - Returns: true, wenn die Mitteilung angenommen wurde (`ActivityMonitor` merkt sie sonst nicht als gemeldet).
    @discardableResult
    static func showActivity(_ watch: Watch, _ signal: ActivitySignal) async -> Bool {
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
        if #available(iOS 15.0, *) { content.interruptionLevel = .timeSensitive }
        applyQuietHours(content, settings)
        badge(content, settings)
        return await postChecked(id: depegId, content: content)
    }

    // MARK: Nachtruhe

    /// Während der Nachtruhe lautlos zustellen: kein Ton, nur in der Mitteilungszentrale
    /// (`.passive`) — die Mitteilung selbst kommt trotzdem. Geprüft in der Ortszeit des
    /// Geräts im Moment des Zustellens. Paar- und Portfolio-Alarme lösen in der Nachtruhe gar
    /// nicht aus (`QuietHours.holdsAlarms`); für sie greift das nur an der Grenze als Rückfall.
    static func applyQuietHours(_ content: UNMutableNotificationContent, _ settings: AppSettings, date: Date = Date()) {
        guard QuietHours.isQuietNow(settings, date: date) else { return }
        content.sound = nil
        if #available(iOS 15.0, *) { content.interruptionLevel = .passive }
    }
}

/// Text eines Portfolio-Alarms (rein, testbar): mit Portfolio-Sperre neutral (wie Android öffentlich: nichts
/// ausser dem Titel), sonst Satz und Messwert — mit «Beträge verbergen» ohne Beträge.
enum PortfolioAlarmNotice {
    static func body(_ alarm: PortfolioAlarm, measured: Double, settings: AppSettings) -> String {
        if settings.appLock { return L("notification_portfolio_alarm_locked") }
        let hidden = settings.hidePortfolioAmounts
        return L("notification_portfolio_alarm_text",
                 PortfolioAlarmTexts.sentence(alarm, basis: settings.changeBasis.storage, hidden: hidden),
                 PortfolioAlarmTexts.measured(alarm, measured: measured, hidden: hidden))
    }
}
