import Foundation
import UserNotifications

/// Alle Mitteilungen der App — wie `AppNotifier.kt`.
///
/// iOS kennt keine dauerhafte Kurs-Mitteilung. Kurs-Mitteilungen erscheinen
/// deshalb als normale, stille Mitteilung, die die vorige desselben Paars ersetzt.
enum Notifier {
    static let categoryAlarm = "alarm"
    /// Kursalarm eines gehandelten Paars: mit «Was bewegt sich mit?» und «Anpassen»; die übrigen Kategorien der
    /// Paar-Alarme (mit «1 h später») in `Notifier+AlarmActions.swift`.
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

    /// Kategorien der Mitteilungen registrieren (beim Start der App). «Was bewegt sich mit?» und «Anpassen» öffnen
    /// die App im Vordergrund, «1 h später» nicht; die Mitteilung selbst bleibt wie bisher (Tipp = zum Paar).
    static func registerCategories() {
        let exchange = UNNotificationAction(identifier: actionOpenExchange,
                                            title: L("notification_depeg_action_exchange"), options: [.foreground])
        center.setNotificationCategories(Set(pairAlarmCategories()).union([
            UNNotificationCategory(identifier: categoryAlarm, actions: [], intentIdentifiers: [], options: []),
            UNNotificationCategory(identifier: categoryDepeg, actions: [exchange], intentIdentifiers: [], options: []),
            UNNotificationCategory(identifier: categoryPortfolioLocked, actions: [], intentIdentifiers: [],
                                   hiddenPreviewsBodyPlaceholder: L("notification_portfolio_alarm_locked"),
                                   options: [.hiddenPreviewsShowTitle]),
        ]))
    }

    static var center: UNUserNotificationCenter { .current() }

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
    /// Zähler am App-Symbol als Datei im App-Group-Ordner: App, Hintergrund-Aufgabe und Widget zählen hoch — lesen,
    /// erhöhen und schreiben laufen abgestimmt (`SharedStorage.coordinatedWrite`), sonst ginge bei gleichzeitigen
    /// Mitteilungen eine verloren. Aus den Geräte-Backups ausgenommen.
    static var badgeURL: URL { SharedStorage.directory.appendingPathComponent("app_icon_badge.txt") }

    /// «Zahl am App-Symbol»: zählt diese Mitteilung (+1) und gibt die neue Zahl mit — wie
    /// WhatsApp. Zähler im App-Group-Ordner, weil auch die Hintergrund-Aktualisierung meldet.
    /// Einstellung aus: kein Kennzeichen.
    static func badge(_ content: UNMutableNotificationContent, _ settings: AppSettings) {
        guard settings.appIconBadge else { return }
        // Bis Build 74 stand der Zähler in den Defaults: ohne Datei dort weiterzählen
        let count = incrementBadge(at: badgeURL, fallback: SharedStorage.defaults.integer(forKey: badgeKey))
        content.badge = NSNumber(value: count)
    }

    /// Zähler in `url` um 1 erhöhen, als ein abgestimmter Schritt; fehlt die Datei (oder ist sie unlesbar), zählt er
    /// ab `fallback`. Gibt die neue Zahl zurück.
    static func incrementBadge(at url: URL, fallback: Int) -> Int {
        SharedStorage.coordinatedWrite(at: url) { () -> Int in
            let next = Swift.max(readBadge(at: url) ?? fallback, 0) + 1
            writeBadge(next, at: url)
            return next
        }
    }

    /// Zähler in `url` auf `value` setzen (abgestimmt).
    static func storeBadgeCount(_ value: Int, at url: URL) {
        SharedStorage.coordinatedWrite(at: url) { writeBadge(value, at: url) }
    }

    /// Gespeicherter Zähler; nil = keine oder unlesbare Datei.
    static func readBadge(at url: URL) -> Int? {
        guard let text = try? String(contentsOf: url, encoding: .utf8) else { return nil }
        return Int(text.trimmingCharacters(in: .whitespacesAndNewlines))
    }

    private static func writeBadge(_ value: Int, at url: URL) {
        guard (try? Data(String(value).utf8).write(to: url, options: .atomic)) != nil else { return }
        // Atomares Schreiben ersetzt die Datei: Merkmal jedes Mal neu setzen
        BackupExclusion.set(excluded: true, for: url)
    }

    /// App geöffnet (oder Einstellung aus): Zahl am App-Symbol auf 0. Die Mitteilungen selbst
    /// bleiben in der Mitteilungszentrale.
    static func clearBadge() {
        SharedStorage.defaults.set(0, forKey: badgeKey)
        storeBadgeCount(0, at: badgeURL)
        center.setBadgeCount(0) { _ in }
    }

    private static func post(id: String, content: UNMutableNotificationContent) {
        let request = UNNotificationRequest(identifier: id, content: content, trigger: nil)
        center.add(request) { _ in }
    }

    /// Wie `post`, wartet aber auf das System: true, wenn die Mitteilung angenommen wurde.
    static func postChecked(id: String, content: UNMutableNotificationContent) async -> Bool {
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

    /// Gezeigte Mitteilung eines Paar-Alarms entfernen (Alarm liess sich nach dem Melden nicht speichern).
    static func cancelAlarm(_ id: Int64) {
        center.removeDeliveredNotifications(withIdentifiers: [alarmId(id)])
    }

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
        content.interruptionLevel = .passive
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
    /// Auch aus der Widget-Erweiterung: Den gewählten Alarmton findet iOS dort in `Library/Sounds` der App Group (die
    /// App kopiert ihn beim Start dorthin, `AlarmSounds.installSharedSounds`); fehlt er (App seit dem Update nie
    /// gestartet), spielt iOS den Standardton.
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
        // «Was bewegt sich mit?» nur für gehandelte Paare (sonst gibt es nichts zu erklären), «1 h später» nur, wo die
        // Pause wirkt (wiederholende Prozent- und Bewegungsalarme), «Anpassen» immer
        content.categoryIdentifier = pairAlarmCategory(
            traded: !watch.isNotTraded, snooze: AlarmSnooze.offeredFor(alarm.condition, repeating: alarm.repeating))
        content.threadIdentifier = "alarms"
        content.userInfo = [userInfoWatchId: NSNumber(value: watch.id), userInfoAlarmId: NSNumber(value: alarm.id)]
        // Coin-Logo als Vorschaubild, nur wenn es schon auf dem Gerät liegt (nie aus dem Netz)
        if settings.coinLogos, let logo = coinLogoAttachment(watch) { content.attachments = [logo] }
        content.interruptionLevel = .timeSensitive
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
    /// `usdtAssumed`: Alarm in anderer Währung mit USDT = 1 umgerechnet (`PortfolioAlarmLogic.usdtAssumed`) — Vermerk.
    static func showPortfolioAlarm(_ alarm: PortfolioAlarm, measured: Double, settings: AppSettings,
                                   usdtAssumed: Bool = false) async -> Bool {
        let content = UNMutableNotificationContent()
        content.title = L("notification_portfolio_alarm_title")
        content.body = PortfolioAlarmNotice.body(alarm, measured: measured, settings: settings, usdtAssumed: usdtAssumed)
        content.sound = settings.alarmSignal.notificationSound(tone: settings.alarmSound)
        content.categoryIdentifier = settings.appLock ? categoryPortfolioLocked : categoryAlarm
        content.threadIdentifier = "alarms"
        content.userInfo = ["open": "portfolio"]
        content.interruptionLevel = .timeSensitive
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
        content.interruptionLevel = .timeSensitive
        post(id: testAlarmId, content: content)
    }

    // MARK: Nachtruhe

    /// Während der Nachtruhe lautlos zustellen: kein Ton, nur in der Mitteilungszentrale
    /// (`.passive`) — die Mitteilung selbst kommt trotzdem. Geprüft in der Ortszeit des
    /// Geräts im Moment des Zustellens. Paar- und Portfolio-Alarme lösen in der Nachtruhe gar
    /// nicht aus (`QuietHours.holdsAlarms`); für sie greift das nur an der Grenze als Rückfall.
    static func applyQuietHours(_ content: UNMutableNotificationContent, _ settings: AppSettings, date: Date = Date()) {
        guard QuietHours.isQuietNow(settings, date: date) else { return }
        content.sound = nil
        content.interruptionLevel = .passive
    }
}

/// Text eines Portfolio-Alarms (rein, testbar): mit Portfolio-Sperre neutral (wie Android öffentlich: nichts
/// ausser dem Titel), sonst Satz und Messwert — mit «Beträge verbergen» ohne Beträge.
enum PortfolioAlarmNotice {
    static func body(_ alarm: PortfolioAlarm, measured: Double, settings: AppSettings, usdtAssumed: Bool = false) -> String {
        if settings.appLock { return L("notification_portfolio_alarm_locked") }
        let hidden = settings.hidePortfolioAmounts
        let text = L("notification_portfolio_alarm_text",
                     PortfolioAlarmTexts.sentence(alarm, basis: settings.changeBasis.storage, hidden: hidden),
                     PortfolioAlarmTexts.measured(alarm, measured: measured, hidden: hidden))
        // Alarm in anderer Währung, umgerechnet mit USDT = 1: Vermerk dahinter (wie Android)
        return AlarmTexts.join(text, [usdtAssumed ? L("portfolio_usdt_assumed") : nil])
    }
}
