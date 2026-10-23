import Foundation

// Grenzen, Wahlmöglichkeiten und Standards (Teil von `AppSettings`): Intervalle kürzen, frühere Standards für
// bestehende Installationen, Währung und Kursfarben nach Region — wie `AppSettings.kt` (Android).

extension AppSettings {
    static let minBackgroundIntervalMinutes = 15
    static let minLiveIntervalSeconds = 15
    /// Obergrenzen (weit über den Wahlmöglichkeiten): Werte aus Sicherungen und gespeicherten Einstellungen
    /// werden darauf gekürzt — riesige Werte liessen sonst Zeitrechnungen überlaufen (wie Android).
    static let maxBackgroundIntervalMinutes = 1_440
    static let maxLiveIntervalSeconds = 3_600
    static let maxAlarmCooldownMinutes = 1_440

    static func clampBackgroundInterval(_ minutes: Int) -> Int {
        min(max(minutes, minBackgroundIntervalMinutes), maxBackgroundIntervalMinutes)
    }
    static func clampLiveInterval(_ seconds: Int) -> Int {
        min(max(seconds, minLiveIntervalSeconds), maxLiveIntervalSeconds)
    }
    static func clampAlarmCooldown(_ minutes: Int) -> Int {
        min(max(minutes, 0), maxAlarmCooldownMinutes)
    }
    static let backgroundIntervalChoices = [15, 30, 60]
    static let alarmCooldownChoices = [0, 5, 15, 60]
    static let fearGreedBelowChoices = [0, 10, 20, 25]
    static let fearGreedAboveChoices = [0, 75, 80, 90]
    /// Gas-Alarm Ethereum in Zehntel-gwei: aus, 0.5, 1, 2, 5.
    static let gasEthChoices = [0, 5, 10, 20, 50]
    /// Gas-Alarm Bitcoin in sat/vB.
    static let gasBtcChoices = [0, 1, 2, 5, 10]
    static let notificationChangeChoices: [Double] = [0, 3, 5, 7]

    /// Bisherige Standards der für neue Installationen geänderten Schalter (ältere Installationen) — wie Android.
    static let legacyWatchlistNames = false
    static let legacyShowConverted = false
    static let legacyIncludeAlphaTokens = true
    /// Bisheriger Standard (ältere Installationen) des Portfolio-Tabs; auch für Sicherungen ohne das Feld.
    static let legacyPortfolioEnabled = true

    /// Geänderter Standard, wenn der Wert fehlt: bestehende Installation → bisheriger Standard, neue →
    /// neuer Standard — wie Android `defaultForMigration`. iOS: gespeicherte Einstellungen zu lesen
    /// heisst bestehende Installation (eine neue hat noch keine und nimmt `AppSettings()`).
    static func defaultForMigration(existingInstall: Bool, legacy: Bool, current: Bool) -> Bool {
        existingInstall ? legacy : current
    }

    /// Portfolio nach Standardwerten einschalten? Ja, wenn die Einstellungen fehlten oder unlesbar waren
    /// (`resetDefaults`, Standardwerte einer neuen Installation), das Portfolio aus ist und trotzdem
    /// Portfolio-Daten da sind (Transaktionen, Adressen, Portfolio-Alarme) — dann ist es eine bestehende
    /// Installation, sonst blieben Tab und Portfolio-Alarme stumm. Wie Android `enablePortfolioForData`.
    static func enablePortfolioForData(resetDefaults: Bool, portfolioEnabled: Bool, hasPortfolioData: Bool) -> Bool {
        resetDefaults && !portfolioEnabled && hasPortfolioData
    }
}

extension AppSettings {
    /// Standard-Umrechnungswährung: die Währung der Geräte-Region (Schweiz → CHF,
    /// Deutschland → EUR, USA → USD …), sofern dafür ein Devisenkurs verfügbar ist,
    /// sonst USD. Gilt nur, bis jemand selbst eine Währung wählt — wie `defaultCurrency()` in Android.
    static func defaultCurrency(_ locale: Locale = .current) -> String {
        guard let code = locale.currency?.identifier.uppercased(),
              FxRateSource.currencies.contains(code) else { return "USD" }
        return code
    }

    /// Regionen, in denen Rot «steigend» bedeutet — wie Android (`Locale.getDefault().country`).
    static let invertedPriceColorRegions: Set<String> = ["CN", "TW", "HK", "MO", "JP", "KR"]

    /// Standard für «Farben tauschen»: an in CN, TW, HK, MO, JP und KR.
    static func defaultPriceColorsInverted(_ locale: Locale = .current) -> Bool {
        guard let region = locale.region?.identifier.uppercased() else { return false }
        return invertedPriceColorRegions.contains(region)
    }
}
