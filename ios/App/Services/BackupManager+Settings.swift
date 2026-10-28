import Foundation
import SwiftUI
import UniformTypeIdentifiers

// Einstellungen in der Sicherung (Teil von `BackupManager`): schreiben, prüfen und wieder einlesen.
// Eigene Datei, weil die Liste der Einstellungen lang ist.

extension BackupManager {
    // MARK: Einstellungen

    /// Einstellungen samt Spaltensortierung der Merkliste (`@AppStorage`, nicht in `AppSettings`);
    /// nil = eigene Reihenfolge — gleiches Feld wie Android.
    static func settingsJson(_ s: AppSettings) -> [String: Any] {
        var o = settingsToJson(s)
        o[ColumnSort.backupField] = ColumnSort.backupValue(UserDefaults.standard.string(forKey: ColumnSort.storageKey))
            .map { $0 as Any } ?? NSNull()
        return o
    }

    /// Spaltensortierung aus der Sicherung übernehmen (`checkSettings`): null/unbekannt = eigene Reihenfolge ("").
    static func restoreColumnSort(_ o: [String: Any]) {
        let value = o[ColumnSort.backupField] as? String
        UserDefaults.standard.set(ColumnSort.backupValue(value) ?? "", forKey: ColumnSort.storageKey)
    }

    private static func settingsToJson(_ s: AppSettings) -> [String: Any] {
        [
            // «Wie aktuell?» (wie Android)
            "updateMode": s.updateMode.rawValue,
            "backgroundIntervalMinutes": s.backgroundIntervalMinutes,
            "priceNotifications": s.priceNotifications,
            "ongoingNotifications": s.ongoingNotifications,
            "notificationChangePercent": s.notificationChangePercent,
            "ttsEnabled": s.ttsEnabled,
            "ttsAlarmsOnly": s.ttsAlarmsOnly,
            "ttsSpeechRate": s.ttsSpeechRate,
            "alarmCooldownMinutes": s.alarmCooldownMinutes,
            "includeRollingFutures": s.includeRollingFutures,
            "assetScope": s.assetScope.rawValue,
            "includeAlphaTokens": s.includeAlphaTokens,
            "accentColor": s.accentColor.rawValue,
            "darkMode": s.darkMode.map { $0 as Any } ?? NSNull(),
            "fearGreedBelow": s.fearGreedBelow,
            // Eigene Grenzen der Bitcoin-Kennzahlen (Art → gespeicherte Ganzzahl), wie Android
            "indicatorAlerts": s.indicatorAlerts.filter { $0.value > 0 },
            "fearGreedAbove": s.fearGreedAbove,
            "gasAlertEthTenths": s.gasAlertEthTenths,
            "gasAlertBtc": s.gasAlertBtc,
            // USDT-Depeg-Alarm
            "depegAlarm": s.depegAlarm,
            "depegThresholdTenths": s.depegThresholdTenths,
            "activityAlerts": s.activityAlerts,
            "activitySensitivity": s.activitySensitivity.rawValue,
            "macroNotifications": s.macroNotifications,
            "portfolioEnabled": s.portfolioEnabled,
            "portfolioCurrency": s.portfolioCurrency,
            "showConverted": s.showConverted,
            "priceColorScheme": s.priceColorScheme.rawValue,
            "watchlistSparkline": s.watchlistSparkline,
            "watchlistNames": s.watchlistNames,
            "appIconBadge": s.appIconBadge,
            "watchlistActivityCard": s.watchlistActivityCard,
            "coinLogos": s.coinLogos,
            "widgetCoinLogos": s.widgetCoinLogos,
            "portfolioCoinLogos": s.portfolioCoinLogos,
            "changeBasis": s.changeBasis.rawValue,
            "highContrast": s.highContrast,
            "priceColorsInverted": s.priceColorsInverted,
            "alarmSignal": s.alarmSignal.rawValue,
            "quietHoursEnabled": s.quietHoursEnabled,
            "quietHoursStart": s.quietHoursStart,
            "quietHoursEnd": s.quietHoursEnd,
            "appLock": s.appLock,
            "hidePortfolioAmounts": s.hidePortfolioAmounts,
            "portfolioSystemBackup": s.portfolioSystemBackup,
            // Einstandsmethode «FIFO» | «AVERAGE» (wie Android)
            "costMethod": s.costMethod.rawValue,
            // Steuer-Vorlage «NEUTRAL» | «DE» | … (wie Android)
            "taxTemplate": s.taxTemplate.rawValue,
            // Eigener Bitcoin-Server für Adressabfragen; null = öffentliche Knoten (wie Android)
            "addressServerBtc": s.addressServerBtc.map { $0 as Any } ?? NSNull(),
        ]
    }

    /// Jedes Feld, das `settingsJson` schreibt, mit seinem Typ (wie Android `BackupSettingsCodec.FIELDS`).
    static let settingsFields: BackupFields.Fields = [
        ("updateMode", .string), ("backgroundIntervalMinutes", .int), ("priceNotifications", .bool),
        ("ongoingNotifications", .bool), ("notificationChangePercent", .double), ("ttsEnabled", .bool),
        ("ttsAlarmsOnly", .bool), ("ttsSpeechRate", .double), ("alarmCooldownMinutes", .int),
        ("includeRollingFutures", .bool), ("assetScope", .string), ("includeAlphaTokens", .bool),
        ("accentColor", .string), ("darkMode", .boolOrNull), ("fearGreedBelow", .int), ("indicatorAlerts", .intMap),
        ("fearGreedAbove", .int), ("gasAlertEthTenths", .int), ("gasAlertBtc", .int), ("depegAlarm", .bool),
        ("depegThresholdTenths", .int), ("activityAlerts", .bool), ("activitySensitivity", .string),
        ("macroNotifications", .bool), ("portfolioEnabled", .bool), ("portfolioCurrency", .string),
        ("showConverted", .bool), ("priceColorScheme", .string), ("watchlistSparkline", .bool),
        ("watchlistNames", .bool), ("appIconBadge", .bool), ("watchlistActivityCard", .bool), ("coinLogos", .bool),
        ("widgetCoinLogos", .bool), ("portfolioCoinLogos", .bool), ("changeBasis", .string), ("highContrast", .bool),
        ("priceColorsInverted", .bool), ("alarmSignal", .string), ("quietHoursEnabled", .bool),
        ("quietHoursStart", .int), ("quietHoursEnd", .int), ("appLock", .bool), ("hidePortfolioAmounts", .bool),
        ("portfolioSystemBackup", .bool), ("costMethod", .string), ("taxTemplate", .string),
        (ColumnSort.backupField, .stringOrNull), ("addressServerBtc", .stringOrNull),
    ]

    /// Jedes Feld aus `settingsFields` vorhanden und vom richtigen Typ (`BackupFields.check`); sonst `Failure.invalid` —
    /// die Sicherung ist unlesbar. Unbekannte Felder werden übergangen. Wie Android `checkSettings`.
    static func checkSettings(_ o: [String: Any]) throws {
        try BackupFields.check(o, settingsFields, "settings.")
    }

    // Lesen nach `checkSettings`: Feld vorhanden und vom richtigen Typ, sonst `Failure.invalid`

    private static func flag(_ o: [String: Any], _ key: String) throws -> Bool {
        guard let v = o[key], BackupFields.isFlag(v), let n = v as? NSNumber else { throw Failure.invalid("settings." + key) }
        return n.boolValue
    }

    /// Ausserhalb von Int gesättigt; die Grenzen setzt der Aufrufer.
    private static func whole(_ o: [String: Any], _ key: String) throws -> Int {
        guard let v = o[key], BackupFields.isWhole(v), let d = BackupFields.finiteNumber(v) else { throw Failure.invalid("settings." + key) }
        if d >= Double(Int.max) { return Int.max }
        if d <= Double(Int.min) { return Int.min }
        return Int(d)
    }

    private static func real(_ o: [String: Any], _ key: String) throws -> Double {
        guard let v = o[key], let d = BackupFields.finiteNumber(v) else { throw Failure.invalid("settings." + key) }
        return d
    }

    private static func text(_ o: [String: Any], _ key: String) throws -> String {
        guard let v = o[key] as? String else { throw Failure.invalid("settings." + key) }
        return v
    }


    /// Übernimmt die Einstellungen der Sicherung (`checkSettings`, sonst `Failure.invalid`), mit denselben Grenzen wie
    /// die Android-Setter. `trusted` = Sicherung mit Passwort (bzw. eigener Stand); sonst nur Schutz-Einstellungen in
    /// Richtung «mehr Schutz».
    static func applySettings(_ o: [String: Any], onto current: AppSettings, trusted: Bool = false) throws -> AppSettings {
        try checkSettings(o)
        var s = current
        // «Wie aktuell?»; unbekannt: Einstellung bleibt (wie Android)
        if let mode = UpdateMode.from(name: try text(o, "updateMode")) { s.updateMode = mode }
        // Unter- und Obergrenze (riesige Werte liessen Zeitrechnungen überlaufen), wie Android
        s.backgroundIntervalMinutes = AppSettings.clampBackgroundInterval(try whole(o, "backgroundIntervalMinutes"))
        s.priceNotifications = try flag(o, "priceNotifications")
        s.ongoingNotifications = try flag(o, "ongoingNotifications")
        s.notificationChangePercent = min(max(try real(o, "notificationChangePercent"), 0), 100)
        s.ttsEnabled = try flag(o, "ttsEnabled")
        s.ttsAlarmsOnly = try flag(o, "ttsAlarmsOnly")
        s.ttsSpeechRate = min(max(try real(o, "ttsSpeechRate"), 0.5), 2.0)
        s.alarmCooldownMinutes = AppSettings.clampAlarmCooldown(try whole(o, "alarmCooldownMinutes"))
        s.includeRollingFutures = try flag(o, "includeRollingFutures")
        // «Token & TradFi»; unbekannt: Einstellung bleibt (wie Android)
        if let v = AssetScope.from(name: try text(o, "assetScope")) { s.assetScope = v }
        s.includeAlphaTokens = try flag(o, "includeAlphaTokens")
        s.accentColor = AccentColor(rawValue: try text(o, "accentColor")) ?? .default
        // null = «Wie das System»
        s.darkMode = try o["darkMode"] is NSNull ? nil : flag(o, "darkMode")
        s.fearGreedBelow = min(max(try whole(o, "fearGreedBelow"), 0), 100)
        // Nur eingeschaltete Grenzen stehen in der Datei; nur gültige Werte je bekannter Art
        let alerts = o["indicatorAlerts"] as? [String: Any] ?? [:]
        var out: [String: Int] = [:]
        for kind in IndicatorAlertKind.allCases where alerts[kind.rawValue] != nil {
            let v = try whole(alerts, kind.rawValue)
            if v > 0, kind.choices.contains(v) { out[kind.rawValue] = v }
        }
        s.indicatorAlerts = out
        s.fearGreedAbove = min(max(try whole(o, "fearGreedAbove"), 0), 100)
        s.gasAlertEthTenths = min(max(try whole(o, "gasAlertEthTenths"), 0), 10_000)
        s.gasAlertBtc = min(max(try whole(o, "gasAlertBtc"), 0), 10_000)
        s.depegAlarm = try flag(o, "depegAlarm")
        s.depegThresholdTenths = DepegAlarm.threshold(try whole(o, "depegThresholdTenths"))
        s.activityAlerts = try flag(o, "activityAlerts")
        // Unbekannt: «Normal» (wie Android)
        s.activitySensitivity = ActivitySensitivity.from(name: try text(o, "activitySensitivity"))
        s.macroNotifications = try flag(o, "macroNotifications")
        s.portfolioEnabled = try flag(o, "portfolioEnabled")
        // Dreistelliger Währungscode, sonst bleibt die bisherige Währung (wie `setPortfolioCurrency`)
        let code = try text(o, "portfolioCurrency").trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
        if FxRateSource.isCurrencyCode(code) { s.portfolioCurrency = code }
        s.showConverted = try flag(o, "showConverted")
        s.priceColorScheme = PriceColorScheme(rawValue: try text(o, "priceColorScheme")) ?? .default
        s.watchlistSparkline = try flag(o, "watchlistSparkline")
        s.watchlistNames = try flag(o, "watchlistNames")
        s.appIconBadge = try flag(o, "appIconBadge")
        s.watchlistActivityCard = try flag(o, "watchlistActivityCard")
        s.coinLogos = try flag(o, "coinLogos")
        s.widgetCoinLogos = try flag(o, "widgetCoinLogos")
        s.portfolioCoinLogos = try flag(o, "portfolioCoinLogos")
        // %-Basis (wie Android): unbekannt → «Letzte 24 Std.»
        s.changeBasis = ChangeBasis.from(name: try text(o, "changeBasis"))
        s.highContrast = try flag(o, "highContrast")
        s.priceColorsInverted = try flag(o, "priceColorsInverted")
        // Alarm-Signal (wie Android): unbekannt → «System»; Android-Werte («Nur Vibration» …) → nächster iOS-Wert
        s.alarmSignal = AlarmSignal.from(name: try text(o, "alarmSignal")).ios
        // Nachtruhe: Zeiten nur übernehmen, wenn gültig (0…1439)
        s.quietHoursEnabled = try flag(o, "quietHoursEnabled")
        let start = try whole(o, "quietHoursStart"), end = try whole(o, "quietHoursEnd")
        if QuietHours.isValidMinute(start) { s.quietHoursStart = start }
        if QuietHours.isValidMinute(end) { s.quietHoursEnd = end }
        // Ohne Passwort (nicht `trusted`) nur in Richtung «mehr Schutz» (`BackupRestorePolicy`, wie Android)
        if let v = BackupRestorePolicy.hideAmounts(wanted: try flag(o, "hidePortfolioAmounts"),
                                                   current: current.hidePortfolioAmounts, trusted: trusted) {
            s.hidePortfolioAmounts = v
        }
        if let v = BackupRestorePolicy.systemBackup(wanted: try flag(o, "portfolioSystemBackup"),
                                                    current: current.portfolioSystemBackup, trusted: trusted) {
            s.portfolioSystemBackup = v
        }
        // Einstandsmethode: unbekannter Wert → FIFO (wie Android)
        s.costMethod = CostMethod.fromName(try text(o, "costMethod"))
        // Steuer-Vorlage: unbekannt → Neutral (wie Android)
        s.taxTemplate = TaxTemplate.fromName(try text(o, "taxTemplate"))
        // Eigener Bitcoin-Server: ungültig bleibt er; ohne Passwort nie zurück auf öffentliche Knoten (wie Android)
        s.addressServerBtc = AddressServer.restored(value: try textOrNull(o, "addressServerBtc"),
                                                    current: current.addressServerBtc, trusted: trusted)
        // Portfolio-Sperre: nicht hier, sondern in `restore` (`BackupRestorePolicy.appLock`)
        return s
    }
}
