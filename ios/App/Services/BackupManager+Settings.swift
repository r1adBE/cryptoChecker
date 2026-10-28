import Foundation
import SwiftUI
import UniformTypeIdentifiers

// Einstellungen in der Sicherung (Teil von `BackupManager`): schreiben und tolerant wieder einlesen.
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

    /// Spaltensortierung aus der Sicherung übernehmen: fehlt das Feld, bleibt sie stehen; null/unbekannt = eigene
    /// Reihenfolge ("").
    static func restoreColumnSort(_ o: [String: Any]?) {
        guard let o, o[ColumnSort.backupField] != nil else { return }
        let value = isNull(o, ColumnSort.backupField) ? nil : string(o, ColumnSort.backupField)
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

    /// Übernimmt nur vorhandene Schlüssel, mit denselben Grenzen wie die Android-Setter.
    /// `trusted` = Sicherung mit Passwort (bzw. eigener Stand); sonst nur Schutz-Einstellungen in Richtung «mehr Schutz».
    static func applySettings(_ o: [String: Any], onto current: AppSettings, trusted: Bool = false) -> AppSettings {
        var s = current
        // «Wie aktuell?»; fehlt oder unbekannt: Einstellung bleibt (wie Android)
        if let mode = UpdateMode.from(name: string(o, "updateMode")) { s.updateMode = mode }
        // Unter- und Obergrenze (riesige Werte liessen Zeitrechnungen überlaufen), wie Android
        if let v = int(o, "backgroundIntervalMinutes") { s.backgroundIntervalMinutes = AppSettings.clampBackgroundInterval(v) }
        if let v = bool(o, "priceNotifications") { s.priceNotifications = v }
        if let v = bool(o, "ongoingNotifications") { s.ongoingNotifications = v }
        if let v = double(o, "notificationChangePercent") { s.notificationChangePercent = min(max(v, 0), 100) }
        if let v = bool(o, "ttsEnabled") { s.ttsEnabled = v }
        if let v = bool(o, "ttsAlarmsOnly") { s.ttsAlarmsOnly = v }
        if let v = double(o, "ttsSpeechRate") { s.ttsSpeechRate = min(max(v, 0.5), 2.0) }
        if let v = int(o, "alarmCooldownMinutes") { s.alarmCooldownMinutes = AppSettings.clampAlarmCooldown(v) }
        if let v = bool(o, "includeRollingFutures") { s.includeRollingFutures = v }
        // «Token & TradFi»; fehlt oder unbekannt: Einstellung bleibt (wie Android)
        if let v = AssetScope.from(name: string(o, "assetScope")) { s.assetScope = v }
        if let v = bool(o, "includeAlphaTokens") { s.includeAlphaTokens = v }
        if o["accentColor"] != nil { s.accentColor = AccentColor(rawValue: string(o, "accentColor") ?? "") ?? .default }
        if o["darkMode"] != nil { s.darkMode = isNull(o, "darkMode") ? nil : bool(o, "darkMode") }
        if let v = int(o, "fearGreedBelow") { s.fearGreedBelow = min(max(v, 0), 100) }
        // Fehlt das Feld, bleiben die Grenzen stehen; nur gültige Werte je bekannter Art
        if let alerts = o["indicatorAlerts"] as? [String: Any] {
            var out: [String: Int] = [:]
            for kind in IndicatorAlertKind.allCases {
                if let v = int(alerts, kind.rawValue), v > 0, kind.choices.contains(v) { out[kind.rawValue] = v }
            }
            s.indicatorAlerts = out
        }
        if let v = int(o, "fearGreedAbove") { s.fearGreedAbove = min(max(v, 0), 100) }
        if let v = int(o, "gasAlertEthTenths") { s.gasAlertEthTenths = min(max(v, 0), 10_000) }
        if let v = int(o, "gasAlertBtc") { s.gasAlertBtc = min(max(v, 0), 10_000) }
        // Fehlen die Felder, bleibt die Einstellung stehen (wie Android)
        if let v = bool(o, "depegAlarm") { s.depegAlarm = v }
        if let v = int(o, "depegThresholdTenths") { s.depegThresholdTenths = DepegAlarm.threshold(v) }
        if let v = bool(o, "activityAlerts") { s.activityAlerts = v }
        // Fehlt der Schlüssel oder ist er unbekannt: «Normal» (wie Android)
        s.activitySensitivity = ActivitySensitivity.from(name: isNull(o, "activitySensitivity") ? nil : string(o, "activitySensitivity"))
        if let v = bool(o, "macroNotifications") { s.macroNotifications = v }
        if let v = bool(o, "portfolioEnabled") { s.portfolioEnabled = v }
        // Dreistelliger Währungscode, sonst bleibt die bisherige Währung (wie `setPortfolioCurrency`)
        if !isNull(o, "portfolioCurrency"), let v = string(o, "portfolioCurrency") {
            let code = v.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
            if FxRateSource.isCurrencyCode(code) { s.portfolioCurrency = code }
        }
        if let v = bool(o, "showConverted") { s.showConverted = v }
        if !isNull(o, "priceColorScheme") {
            s.priceColorScheme = PriceColorScheme(rawValue: string(o, "priceColorScheme") ?? "") ?? .default
        }
        if let v = bool(o, "watchlistSparkline") { s.watchlistSparkline = v }
        if let v = bool(o, "watchlistNames") { s.watchlistNames = v }
        if let v = bool(o, "appIconBadge") { s.appIconBadge = v }
        if let v = bool(o, "watchlistActivityCard") { s.watchlistActivityCard = v }
        if let v = bool(o, "coinLogos") { s.coinLogos = v }
        if let v = bool(o, "widgetCoinLogos") { s.widgetCoinLogos = v }
        if let v = bool(o, "portfolioCoinLogos") { s.portfolioCoinLogos = v }
        // %-Basis (wie Android): fehlt der Schlüssel, bleibt der Wert; unbekannt → «Letzte 24 Std.»
        if o["changeBasis"] != nil {
            s.changeBasis = ChangeBasis.from(name: isNull(o, "changeBasis") ? nil : string(o, "changeBasis"))
        }
        if let v = bool(o, "highContrast") { s.highContrast = v }
        // Fehlt der Schlüssel, bleibt der bisherige Wert
        if let v = bool(o, "priceColorsInverted") { s.priceColorsInverted = v }
        // Alarm-Signal (wie Android): fehlt der Schlüssel, bleibt der Wert; unbekannt → «System»;
        // Android-Werte («Nur Vibration» …) → nächster iOS-Wert
        if o["alarmSignal"] != nil {
            s.alarmSignal = AlarmSignal.from(name: isNull(o, "alarmSignal") ? nil : string(o, "alarmSignal")).ios
        }
        // Nachtruhe: nur vorhandene Schlüssel, Zeiten nur 0…1439
        if let v = bool(o, "quietHoursEnabled") { s.quietHoursEnabled = v }
        if let v = minute(o, "quietHoursStart") { s.quietHoursStart = v }
        if let v = minute(o, "quietHoursEnd") { s.quietHoursEnd = v }
        // Ohne Passwort (nicht `trusted`) nur in Richtung «mehr Schutz» (`BackupRestorePolicy`, wie Android)
        if let v = BackupRestorePolicy.hideAmounts(wanted: bool(o, "hidePortfolioAmounts"),
                                                   current: current.hidePortfolioAmounts, trusted: trusted) {
            s.hidePortfolioAmounts = v
        }
        // «Portfolio in Systemsicherung»: fehlt der Schlüssel, bleibt die Einstellung stehen
        if let v = BackupRestorePolicy.systemBackup(wanted: bool(o, "portfolioSystemBackup"),
                                                    current: current.portfolioSystemBackup, trusted: trusted) {
            s.portfolioSystemBackup = v
        }
        // Einstandsmethode: fehlt der Schlüssel (oder unbekannter Wert) → FIFO (wie Android)
        s.costMethod = CostMethod.fromName(string(o, "costMethod"))
        // Steuer-Vorlage: fehlt der Schlüssel, bleibt sie stehen; unbekannt → Neutral (wie Android)
        if let v = string(o, "taxTemplate") { s.taxTemplate = TaxTemplate.fromName(v) }
        // Eigener Bitcoin-Server: fehlt/ungültig bleibt er; ohne Passwort nie zurück auf öffentliche Knoten (wie Android)
        s.addressServerBtc = AddressServer.restored(present: o["addressServerBtc"] != nil, value: string(o, "addressServerBtc"),
                                                    current: current.addressServerBtc, trusted: trusted)
        // Portfolio-Sperre: nicht hier, sondern in `restore` (`BackupRestorePolicy.appLock`)
        return s
    }
}
