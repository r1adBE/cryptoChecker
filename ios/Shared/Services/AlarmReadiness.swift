import Foundation

/// Seite «Damit Alarme ankommen» (Build 81): aus dem echten Zustand von System und App je Punkt eine Zeile mit
/// ✓ / ⚠ / Hinweis und dem passenden Knopf. Reine Logik — wie `AlarmReadiness.kt`; gemeinsame Fälle in
/// `Parity/alarm_delivery.json`. Liest nur (Einstellungen, Erlaubnisse, «Letzte Prüfungen»): an der Prüfung der
/// Alarme selbst ändert die Seite nichts.
enum AlarmReadiness {
    /// Letzte Prüfung im Hintergrund älter als das: «länger her».
    static let staleCheckMillis: Int64 = 6 * 60 * 60_000

    enum Platform: Sendable { case android, ios }

    /// Darf die App Mitteilungen zeigen?
    enum Access: String, Sendable {
        case granted
        case denied
        case notDetermined = "not_determined"
    }

    /// Wichtigkeit des Alarm-Kanals (nur Android); nil = noch nicht angelegt.
    enum Channel: String, Sendable { case high, low, off }

    enum Row: String, CaseIterable, Sendable {
        case notifications
        case channel
        case alertSound = "alert_sound"
        case timeSensitive = "time_sensitive"
        case exact
        case battery
        case background
        case backgroundRefresh = "background_refresh"
        case lowPower = "low_power"
        case dnd
        case quietHours = "quiet_hours"
        case lastCheck = "last_check"
        case swipeAway = "swipe_away"
        case liveActivity = "live_activity"
    }

    /// ✓, ⚠ oder neutraler Hinweis (nur zur Kenntnis).
    enum State: String, Sendable { case ok, warn, info }

    /// Welcher Text in der Zeile steht.
    enum Detail: String, Sendable {
        case allowed
        case on
        case off
        case notAsked = "not_asked"
        case channelHigh = "channel_high"
        case channelLow = "channel_low"
        case channelOff = "channel_off"
        case exactOn = "exact_on"
        case exactOff = "exact_off"
        case exactNoPermission = "exact_no_permission"
        case batteryUnrestricted = "battery_unrestricted"
        case batteryOptimized = "battery_optimized"
        case restricted
        case alertOff = "alert_off"
        case soundOff = "sound_off"
        case quietNow = "quiet_now"
        case quietSet = "quiet_set"
        case checkRecent = "check_recent"
        case checkOld = "check_old"
        case checkNever = "check_never"
        case note
    }

    /// Knopf rechts: Systemseite bzw. Seite der App; nur bei Zeilen, die nicht ✓ sind.
    enum Action: String, CaseIterable, Sendable {
        case none
        case notificationSettings = "notification_settings"
        case requestPermission = "request_permission"
        case channelSettings = "channel_settings"
        case exactPermission = "exact_permission"
        case updateSettings = "update_settings"
        case batterySettings = "battery_settings"
        case appSettings = "app_settings"
        case alarmSettings = "alarm_settings"
        case checkRuns = "check_runs"
    }

    struct Item: Equatable, Sendable {
        let row: Row
        let state: State
        let detail: Detail
        var action: Action = .none
    }

    /// Gelesener Zustand. Felder der anderen Plattform bleiben auf dem Standard und werden nicht ausgewertet.
    struct Input: Sendable {
        var platform: Platform
        var notifications: Access
        var now: Int64
        /// Minute des Tages in Ortszeit.
        var minuteOfDay: Int
        var quietEnabled = false
        var quietStart = QuietHours.defaultStart
        var quietEnd = QuietHours.defaultEnd
        /// Ende der letzten vom System geplanten Prüfung; nil = noch keine.
        var lastBackgroundCheck: Int64? = nil
        /// Gibt es eingeschaltete Alarme?
        var hasAlarms = false
        // ---- Android
        var channel: Channel? = nil
        var exactEnabled = false
        var exactPermitted = true
        var batteryUnrestricted = false
        var backgroundRestricted = false
        var systemDnd: Bool? = nil
        // ---- iOS
        var alertEnabled = true
        var soundEnabled = true
        /// Alarm-Signal mit Ton gewählt? Bei «Lautlos» ist ein ausgeschalteter Ton gewollt.
        var wantsSound = true
        /// «Dringliche Mitteilungen»; nil = nicht unterstützt (keine Zeile).
        var timeSensitive: Bool? = nil
        var backgroundRefresh = true
        var lowPower = false
    }

    /// Zeilen in fester Reihenfolge je Plattform.
    static func rows(_ input: Input) -> [Item] {
        switch input.platform {
        case .android:
            return [notifications(input), channel(input), exact(input), battery(input), background(input),
                    dnd(input), quietHours(input), lastCheck(input)].compactMap { $0 }
        case .ios:
            return [notifications(input), alertSound(input), timeSensitive(input), backgroundRefresh(input),
                    lowPower(input), quietHours(input), lastCheck(input),
                    Item(row: .swipeAway, state: .info, detail: .note),
                    Item(row: .liveActivity, state: .info, detail: .note)].compactMap { $0 }
        }
    }

    /// Mindestens eine Zeile mit ⚠?
    static func hasWarning(_ items: [Item]) -> Bool { items.contains { $0.state == .warn } }

    /// Alarm-Arten, die eine Prüfung im Hintergrund brauchen (`hasBackgroundAlarms`); Werte wie in den Einstellungen.
    struct AlarmKinds: Equatable {
        /// Mindestens ein eingeschalteter Paar-Alarm.
        var pairAlarm = false
        var portfolioEnabled = false
        /// Mindestens ein scharfer Portfolio-Alarm (zählt nur mit eingeschaltetem Portfolio).
        var portfolioAlarm = false
        var gasEthTenths = 0
        var gasBtc = 0
        var depeg = false
        var fearGreedBelow = 0
        var fearGreedAbove = 0
        /// Mindestens eine eigene Grenze einer Bitcoin-Kennzahl.
        var indicatorAlert = false
    }

    /// `Input.hasAlarms`: Gibt es etwas, das nur eine Prüfung im Hintergrund meldet? Paar- und Portfolio-Alarme, Gas,
    /// USDT-Depeg, Angst-&-Gier- und Kennzahlen-Meldungen — nicht nur Paar-Alarme, sonst wäre eine ausbleibende Prüfung
    /// bei den anderen bloss ein Hinweis. Wie Android.
    static func hasBackgroundAlarms(_ k: AlarmKinds) -> Bool {
        k.pairAlarm || (k.portfolioEnabled && k.portfolioAlarm) || k.gasEthTenths > 0 || k.gasBtc > 0 || k.depeg
            || k.fearGreedBelow > 0 || k.fearGreedAbove > 0 || k.indicatorAlert
    }

    private static func notifications(_ input: Input) -> Item? {
        switch input.notifications {
        case .granted: return Item(row: .notifications, state: .ok, detail: .allowed)
        case .denied: return Item(row: .notifications, state: .warn, detail: .off, action: .notificationSettings)
        case .notDetermined: return Item(row: .notifications, state: .warn, detail: .notAsked, action: .requestPermission)
        }
    }

    private static func channel(_ input: Input) -> Item? {
        switch input.channel {
        case nil, .high: return Item(row: .channel, state: .ok, detail: .channelHigh)
        case .low: return Item(row: .channel, state: .warn, detail: .channelLow, action: .channelSettings)
        case .off: return Item(row: .channel, state: .warn, detail: .channelOff, action: .channelSettings)
        }
    }

    private static func exact(_ input: Input) -> Item? {
        if input.exactEnabled && input.exactPermitted { return Item(row: .exact, state: .ok, detail: .exactOn) }
        if input.exactEnabled {
            return Item(row: .exact, state: .warn, detail: .exactNoPermission, action: .exactPermission)
        }
        return Item(row: .exact, state: .info, detail: .exactOff, action: .updateSettings)
    }

    private static func battery(_ input: Input) -> Item? {
        input.batteryUnrestricted
            ? Item(row: .battery, state: .ok, detail: .batteryUnrestricted)
            : Item(row: .battery, state: .info, detail: .batteryOptimized, action: .batterySettings)
    }

    private static func background(_ input: Input) -> Item? {
        input.backgroundRestricted
            ? Item(row: .background, state: .warn, detail: .restricted, action: .appSettings)
            : Item(row: .background, state: .ok, detail: .allowed)
    }

    private static func dnd(_ input: Input) -> Item? {
        guard let on = input.systemDnd else { return nil }
        return on ? Item(row: .dnd, state: .warn, detail: .on) : Item(row: .dnd, state: .ok, detail: .off)
    }

    private static func quietHours(_ input: Input) -> Item? {
        let configured = input.quietEnabled && input.quietStart != input.quietEnd &&
            QuietHours.isValidMinute(input.quietStart) && QuietHours.isValidMinute(input.quietEnd)
        if !configured { return Item(row: .quietHours, state: .ok, detail: .off) }
        // Dieselbe Regel wie die Prüfung: in der Nachtruhe warten fällige Alarme
        if QuietHours.holdsAlarms(enabled: true, start: input.quietStart, end: input.quietEnd,
                                  minuteOfDay: input.minuteOfDay) {
            return Item(row: .quietHours, state: .warn, detail: .quietNow, action: .alarmSettings)
        }
        return Item(row: .quietHours, state: .info, detail: .quietSet, action: .alarmSettings)
    }

    private static func lastCheck(_ input: Input) -> Item? {
        let missing: State = input.hasAlarms ? .warn : .info
        guard let at = input.lastBackgroundCheck, at > 0 else {
            return Item(row: .lastCheck, state: missing, detail: .checkNever, action: .checkRuns)
        }
        // Uhr zurückgestellt (Zeit in der Zukunft): als eben geprüft werten
        if input.now - at <= staleCheckMillis { return Item(row: .lastCheck, state: .ok, detail: .checkRecent) }
        return Item(row: .lastCheck, state: missing, detail: .checkOld, action: .checkRuns)
    }

    private static func alertSound(_ input: Input) -> Item? {
        guard input.notifications == .granted else { return nil }
        if !input.alertEnabled {
            return Item(row: .alertSound, state: .warn, detail: .alertOff, action: .notificationSettings)
        }
        if !input.soundEnabled && input.wantsSound {
            return Item(row: .alertSound, state: .warn, detail: .soundOff, action: .notificationSettings)
        }
        return Item(row: .alertSound, state: .ok, detail: .on)
    }

    private static func timeSensitive(_ input: Input) -> Item? {
        guard input.notifications == .granted, let on = input.timeSensitive else { return nil }
        return on ? Item(row: .timeSensitive, state: .ok, detail: .on)
            : Item(row: .timeSensitive, state: .info, detail: .off, action: .notificationSettings)
    }

    private static func backgroundRefresh(_ input: Input) -> Item? {
        input.backgroundRefresh
            ? Item(row: .backgroundRefresh, state: .ok, detail: .on)
            : Item(row: .backgroundRefresh, state: .warn, detail: .off, action: .appSettings)
    }

    private static func lowPower(_ input: Input) -> Item? {
        input.lowPower ? Item(row: .lowPower, state: .warn, detail: .on) : Item(row: .lowPower, state: .ok, detail: .off)
    }
}
