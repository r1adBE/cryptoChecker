import Foundation

/// Schranken für Alarme mit Schwelle in einer anderen Währung (Kurs × Faktor, «Fiat-Alarme») und Hinweise an der
/// ≈ Umrechnung — reine Logik, gleich wie `FiatAlarmGuard.kt` (Android).
///
/// - Devisenkurs zu alt: Die EZB veröffentlicht nur an Bankarbeitstagen (TARGET2). Gezählt wird das Alter des Kurses
///   (Veröffentlichung, nicht Abruf) ohne Samstage, Sonntage und TARGET-Feiertage; über `fxMaxAgeMillis` prüft ein
///   Fiat-Alarm in diesem Durchlauf nicht (bleibt scharf), wie bei einem veralteten Kurs.
/// - USDT/USD unsicher: Liegt nur der Wert über Binance USDC/USDT vor (`DepegAlarm.Reading.viaUsdc`) und weicht
///   USDC/USDT um mehr als `usdcMaxDeviation` von 1 ab, ist offen, ob USDT oder USDC abweicht — Fiat-Alarme prüfen
///   nicht, die Anzeige rechnet mit dem Wert weiter und zeigt «Umrechnung unsicher».
/// - Ohne Messung gilt USDT = 1 (`UsdtState.assumed`): Vermerk «USDT = 1 angenommen» an Zeile bzw. Alarmtext.
enum FiatAlarmGuard {
    private static let hourMillis: Int64 = 60 * 60_000
    private static let dayMillis: Int64 = 24 * hourMillis

    /// Höchstalter des Devisenkurses in Bankarbeitszeit (ohne Wochenende und TARGET-Feiertage): 36 h.
    static let fxMaxAgeMillis: Int64 = 36 * 60 * 60_000

    /// USDC/USDT weiter als 1 % von 1 entfernt: USDT/USD allein über USDC ist unsicher.
    static let usdcMaxDeviation = 0.01

    /// Ab so vielen Kalendertagen wird nicht mehr Tag für Tag gezählt (sicher zu alt).
    private static let maxCountedDays: Int64 = 60

    /// Uhrzeit (UTC), zu der ein Tageskurs mit Datum gilt: EZB-Veröffentlichung um etwa 16:00 MEZ.
    static let ecbPublishHourUtc: Int64 = 15

    private static var utc: Calendar {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "UTC")!
        return calendar
    }

    /// Zeitpunkt eines Tageskurses mit Datum `isoDate` («2026-10-16»): an dem Tag um `ecbPublishHourUtc` UTC.
    static func rateTime(ofIsoDate isoDate: String?) -> Int64? {
        let parts = (isoDate ?? "").trimmingCharacters(in: .whitespaces).split(separator: "-")
        guard parts.count == 3, let y = Int(parts[0]), let m = Int(parts[1]), let d = Int(parts[2]),
              parts[0].count == 4, (1...12).contains(m), (1...31).contains(d),
              let date = utc.date(from: DateComponents(year: y, month: m, day: d)),
              utc.component(.day, from: date) == d
        else { return nil }
        return Int64((date.timeIntervalSince1970 * 1000).rounded()) + ecbPublishHourUtc * hourMillis
    }

    /// Bankarbeitstag der EZB (TARGET2) für den UTC-Tag `epochDay`: nicht Samstag/Sonntag, nicht 1.1., Karfreitag,
    /// Ostermontag, 1.5., 25./26.12.
    static func isBusinessDay(epochDay: Int64) -> Bool {
        // 1970-01-01 war ein Donnerstag: 0 = Montag … 6 = Sonntag
        let weekday = ((epochDay + 3) % 7 + 7) % 7
        if weekday >= 5 { return false }
        let date = Date(timeIntervalSince1970: TimeInterval(epochDay * dayMillis) / 1000)
        let c = utc.dateComponents([.year, .month, .day], from: date)
        guard let year = c.year, let month = c.month, let day = c.day else { return true }
        if (month == 1 && day == 1) || (month == 5 && day == 1) || (month == 12 && (day == 25 || day == 26)) {
            return false
        }
        let easter = easterSundayEpochDay(year)
        return epochDay != easter - 2 && epochDay != easter + 1
    }

    /// Ostersonntag als UTC-Tag seit 1970 (gregorianisch, anonymer Algorithmus nach Meeus/Jones/Butcher).
    static func easterSundayEpochDay(_ year: Int) -> Int64 {
        let a = year % 19, b = year / 100, c = year % 100
        let d = b / 4, e = b % 4, f = (b + 8) / 25, g = (b - f + 1) / 3
        let h = (19 * a + b - d - g + 15) % 30
        let i = c / 4, k = c % 4
        let l = (32 + 2 * e + 2 * i - h - k) % 7
        let m = (a + 11 * h + 22 * l) / 451
        let month = (h + l - 7 * m + 114) / 31
        let day = (h + l - 7 * m + 114) % 31 + 1
        let date = utc.date(from: DateComponents(year: year, month: month, day: day))!
        return Int64((date.timeIntervalSince1970 / 86_400).rounded(.down))
    }

    /// Zeit zwischen `rateTime` und `now`, nur an Bankarbeitstagen (UTC) gezählt; 0, wenn `now` davor liegt.
    static func businessAgeMillis(rateTime: Int64, now: Int64) -> Int64 {
        guard now > rateTime else { return 0 }
        let first = floorDiv(rateTime, dayMillis)
        let last = floorDiv(now, dayMillis)
        if last - first > maxCountedDays { return now - rateTime }
        var age: Int64 = 0
        for day in first...last where isBusinessDay(epochDay: day) {
            let start = max(rateTime, day * dayMillis)
            let end = min(now, (day + 1) * dayMillis)
            if end > start { age += end - start }
        }
        return age
    }

    private static func floorDiv(_ a: Int64, _ b: Int64) -> Int64 {
        let q = a / b
        return (a % b != 0 && (a < 0) != (b < 0)) ? q - 1 : q
    }

    /// Devisenkurs mit Zeitpunkt `rateTime` für einen Fiat-Alarm zu alt? nil = kein Devisenkurs beteiligt (nie zu alt).
    static func fxTooOld(rateTime: Int64?, now: Int64) -> Bool {
        guard let rateTime else { return false }
        return businessAgeMillis(rateTime: rateTime, now: now) > fxMaxAgeMillis
    }

    /// Hängt der Faktor `from` → `to` an USDT/USD? Ja, wenn die Quote USDT, ein anderer Stablecoin (Marktkurs in
    /// USDT) oder Krypto (über USDT bewertet) ist, oder das Ziel USDT — wie `CurrencyConversion.quoteToUsd` rechnet.
    static func involvesUsdt(from: String, to: String, fiat: [String] = FxRateSource.currencies) -> Bool {
        if CurrencyConversion.sameCurrency(from, to) { return false }
        let source = CurrencyConversion.normalize(from)
        if CurrencyConversion.normalize(to) == "USDT" { return true }
        switch CurrencyConversion.classify(source, fiat: fiat) {
        case .usd: return source != "USD"
        case .crypto: return true
        case .fiat, .fiatUnsupported, .invalid: return false
        }
    }

    /// USDT/USD messen, weil eine Fiat-Umrechnung daran hängt (`DepegAlarm.shouldMeasure`)? Ja mit eingeschalteter
    /// ≈ Umrechnung (`showConverted`) in `displayCurrency` auf einer der Quotes `watchQuotes`, oder mit einem Alarm in
    /// anderer Währung (`alarmConversions`: Quote des Paars → Alarmwährung), dessen Faktor an USDT hängt. Wie Android.
    static func needsUsdtMeasurement(showConverted: Bool, displayCurrency: String, watchQuotes: [String],
                                     alarmConversions: [(quote: String, currency: String)],
                                     fiat: [String] = FxRateSource.currencies) -> Bool {
        (showConverted && watchQuotes.contains { involvesUsdt(from: $0, to: displayCurrency, fiat: fiat) })
            || alarmConversions.contains { involvesUsdt(from: $0.quote, to: $0.currency, fiat: fiat) }
    }

    /// Wie USDT/USD in einen Faktor eingeht (`usdtState`) — wie Android `UsdtState`.
    enum UsdtState: String, Equatable {
        /// USDT nicht beteiligt.
        case NOT_INVOLVED
        /// Frische Messung (direkt oder über USDC ohne grössere Abweichung).
        case MEASURED
        /// Keine frische Messung: USDT = 1 angenommen (Vermerk).
        case ASSUMED
        /// Nur über USDC/USDT gemessen und USDC/USDT mehr als 1 % neben 1: «Umrechnung unsicher».
        case UNSURE
    }

    /// Stand von USDT/USD für einen Faktor, an dem USDT `involved` ist; `reading` = letzte Messung (Depeg-Prüfung).
    static func usdtState(involved: Bool, reading: DepegAlarm.Reading?, now: Int64) -> UsdtState {
        guard involved else { return .NOT_INVOLVED }
        guard let live = DepegAlarm.liveUsdtUsd(reading, now: now) else { return .ASSUMED }
        // Gemessen wurde USDT/USD = 1 / (USDC/USDT); USDC/USDT also = 1 / live
        let unsure = reading?.viaUsdc == true && abs(1 / live - 1) > usdcMaxDeviation
        return unsure ? .UNSURE : .MEASURED
    }

    /// Warum ein Fiat-Alarm in diesem Durchlauf nicht prüft.
    enum Skip: String, Equatable {
        case FX_STALE
        case UNSURE
    }

    /// Auslassen? Zuerst der zu alte Devisenkurs, dann die unsichere USDT-Umrechnung; nil = prüfen.
    static func skip(fxRateTime: Int64?, usdt: UsdtState, now: Int64) -> Skip? {
        if fxTooOld(rateTime: fxRateTime, now: now) { return .FX_STALE }
        if usdt == .UNSURE { return .UNSURE }
        return nil
    }
}
